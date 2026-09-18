package com.sailpoint.intellij.api

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.net.JdkProxyProvider
import com.sailpoint.intellij.settings.IscSettings
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Thin blocking client for the ISC REST API. Call from a background thread only.
 */
@Service(Service.Level.APP)
class IscClient {
    private val http: HttpClient = HttpClient.newBuilder()
        .proxy(JdkProxyProvider.getInstance().proxySelector)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private data class CachedToken(val connection: IscConnection, val value: String, val expiresAt: Instant)

    /** Tokens per tenant ID. Keyed by ID but checked against the connection so edited credentials take effect. */
    private val tokens = ConcurrentHashMap<String, CachedToken>()

    /** Org time zones per base URL; they rarely change, so they're read once per session. */
    private val timeZones = ConcurrentHashMap<String, String>()

    /** [experimental] adds the `X-SailPoint-Experimental` header that experimental endpoints require. */
    fun get(tenantId: String, path: String, experimental: Boolean = false): JsonElement =
        send(tenantId, "GET", path, null, experimental = experimental)

    fun put(tenantId: String, path: String, body: JsonElement, experimental: Boolean = false): JsonElement =
        send(tenantId, "PUT", path, body, experimental = experimental)

    fun post(tenantId: String, path: String, body: JsonElement, experimental: Boolean = false): JsonElement =
        send(tenantId, "POST", path, body, experimental = experimental)

    /** A POST that takes no request body, e.g. an action like testing a connection. */
    fun postWithoutBody(tenantId: String, path: String, experimental: Boolean = false): JsonElement =
        send(tenantId, "POST", path, null, experimental = experimental)

    fun delete(tenantId: String, path: String, experimental: Boolean = false): JsonElement =
        send(tenantId, "DELETE", path, null, experimental = experimental)

    /** Sends a JSON Patch document. */
    fun patch(tenantId: String, path: String, operations: JsonArray, experimental: Boolean = false): JsonElement =
        send(tenantId, "PATCH", path, operations, "application/json-patch+json", experimental)

    /**
     * Lists every object of [kind] in a tenant, or under [parent] for child kinds,
     * following `offset` pagination up to [maxItems].
     */
    fun list(tenantId: String, kind: ResourceKind, parent: IscItem? = null, maxItems: Int = 10_000): List<IscItem> {
        val path = collectionPath(kind, parent?.id)
        // Source schemas and schedules come back in one unpaginated response.
        if (kind == ResourceKind.SOURCE_SCHEMAS || kind == ResourceKind.SOURCE_SCHEDULES) {
            return get(tenantId, path, kind.experimental).asJsonArray
                .map { kind.toItem(tenantId, it.asJsonObject, parent) }
                .sortedBy { it.name.lowercase() }
        }
        val pageSize = kind.pageSize
        val seen = LinkedHashMap<String, IscItem>()
        var offset = 0
        while (seen.size < maxItems) {
            ProgressManager.checkCanceled()
            val page = when (kind) {
                ResourceKind.IDENTITIES -> post(
                    tenantId,
                    "/search/v1?limit=$pageSize&offset=$offset",
                    JsonObject().apply {
                        add("indices", JsonArray().apply { add("identities") })
                        add("query", JsonObject().apply { addProperty("query", "*") })
                        add("sort", JsonArray().apply { add("name"); add("id") })
                    },
                )
                else -> get(tenantId, "$path?limit=$pageSize&offset=$offset", kind.experimental)
            }.asJsonArray
            var added = 0
            for (element in page) {
                val item = kind.toItem(tenantId, element.asJsonObject, parent)
                if (seen.putIfAbsent(item.id, item) == null) added++
            }
            // Stop on a short page, or if the endpoint ignored offset and repeated itself.
            if (page.size() < pageSize || added == 0) break
            offset += pageSize
        }
        return seen.values.sortedBy { it.name.lowercase() }
    }

    fun fetch(tenantId: String, kind: ResourceKind, parentId: String?, id: String): JsonObject =
        get(tenantId, objectPath(kind, parentId, id), kind.experimental).asJsonObject

    /** Path of one existing object; [id] is ignored for singleton kinds. */
    fun objectPath(kind: ResourceKind, parentId: String?, id: String): String =
        if (kind.singleton) collectionPath(kind, parentId) else "${collectionPath(kind, parentId)}/${encode(id)}"

    /** Collection path of [kind], with [parentId] filled in for child kinds. */
    fun collectionPath(kind: ResourceKind, parentId: String?): String = kind.collectionPath(parentId?.let(::encode))

    /** The tenant's time zone (e.g. `America/Toronto`), which scheduled tasks such as aggregations run in. */
    fun timeZone(tenantId: String): String {
        val baseUrl = service<IscSettings>().connection(tenantId).baseUrl
        timeZones[baseUrl]?.let { return it }
        val zone = get(tenantId, "/org-config/v1").asJsonObject.string("timeZone")
            ?: throw IscApiException(0, "The org config has no time zone")
        return zone.also { timeZones[baseUrl] = it }
    }

    /** Released connectors whose name contains [text] (the first ones when it's blank), at most [limit], by name. */
    fun searchConnectors(tenantId: String, text: String, limit: Int): List<JsonObject> {
        // Filter values are quoted, so drop quotes from what was typed rather than escape them.
        val filter = text.replace("\"", "").trim().takeIf { it.isNotEmpty() }
            ?.let { "&filters=" + encode("name co \"$it\"") }.orEmpty()
        return get(tenantId, "/connectors/v1?limit=$limit$filter").asJsonArray
            .map { it.asJsonObject }
            .sortedBy { it.string("name")?.lowercase() }
    }

    /** A connector's source configuration, which ISC returns as XML. */
    fun connectorSourceConfig(tenantId: String, scriptName: String): String =
        sendText(tenantId, "GET", "/connectors/v1/${encode(scriptName)}/source-config", null, accept = "application/xml")

    /** Machine account subtypes defined on a source (experimental API). */
    fun sourceSubtypes(tenantId: String, sourceId: String): List<JsonObject> =
        get(tenantId, "/source-subtypes/v1?limit=250&filters=${encode("source.id eq \"$sourceId\"")}", experimental = true)
            .asJsonArray.map { it.asJsonObject }

    /** Verifies the credentials by requesting a token. Throws on failure. */
    fun testConnection(connection: IscConnection) {
        requestToken(connection)
    }

    private fun send(
        tenantId: String, method: String, path: String, body: JsonElement?,
        contentType: String = "application/json", experimental: Boolean = false,
    ): JsonElement {
        val text = sendText(tenantId, method, path, body, contentType, experimental)
        return if (text.isBlank()) JsonObject() else JsonParser.parseString(text)
    }

    /** Sends a request and returns the response body as text, e.g. for endpoints that answer in XML. */
    private fun sendText(
        tenantId: String, method: String, path: String, body: JsonElement?,
        contentType: String = "application/json", experimental: Boolean = false, accept: String = "application/json",
    ): String {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) }
        val request = request(tenantId, method, path, publisher, contentType, experimental, accept)
        return checked(tenantId, request, execute(request)).body()
    }

    /**
     * Posts a `multipart/form-data` form of text [fields] and, optionally, a [file] in the `file` field. ISC's
     * aggregation and upload endpoints take their input this way.
     */
    fun postMultipart(tenantId: String, path: String, fields: Map<String, String> = emptyMap(), file: Path? = null): JsonElement {
        val boundary = "----SailPointIntellij" + UUID.randomUUID().toString().replace("-", "")
        fun quoted(value: String) = "\"" + value.replace("\"", "%22").replace("\r", "").replace("\n", "") + "\""
        val parts = mutableListOf<ByteArray>()
        for ((name, value) in fields) {
            parts += "--$boundary\r\nContent-Disposition: form-data; name=${quoted(name)}\r\n\r\n$value\r\n".toByteArray()
        }
        if (file != null) {
            parts += ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=${quoted(file.fileName.toString())}\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
            parts += Files.readAllBytes(file)
            parts += "\r\n".toByteArray()
        }
        parts += "--$boundary--\r\n".toByteArray()
        val request = request(
            tenantId, "POST", path, HttpRequest.BodyPublishers.ofByteArrays(parts), "multipart/form-data; boundary=$boundary",
        )
        val text = checked(tenantId, request, execute(request)).body()
        return if (text.isBlank()) JsonObject() else JsonParser.parseString(text)
    }

    /** The status of a background task in ISC, such as an aggregation. */
    fun taskStatus(tenantId: String, taskId: String): JsonObject = get(tenantId, "/task-status/v1/${encode(taskId)}").asJsonObject

    private fun request(
        tenantId: String, method: String, path: String, body: HttpRequest.BodyPublisher?,
        contentType: String = "application/json", experimental: Boolean = false, accept: String = "application/json",
    ): HttpRequest {
        val connection = service<IscSettings>().connection(tenantId)
        return HttpRequest.newBuilder(URI.create(connection.baseUrl + path))
            // Uploads such as aggregation CSVs can be large, so requests with a body get longer.
            .timeout(Duration.ofSeconds(if (body != null) 300 else 60))
            .header("Authorization", "Bearer ${accessToken(tenantId, connection)}")
            .header("Accept", accept)
            .apply { if (experimental) header("X-SailPoint-Experimental", "true") }
            .apply {
                if (body != null) header("Content-Type", contentType)
                method(method, body ?: HttpRequest.BodyPublishers.noBody())
            }
            .build()
    }

    /** Returns [response] if it succeeded, otherwise throws with ISC's error message. */
    private fun checked(tenantId: String, request: HttpRequest, response: HttpResponse<String>): HttpResponse<String> {
        if (response.statusCode() == 401) tokens.remove(tenantId)
        if (response.statusCode() !in 200..299) {
            val path = request.uri().rawPath + (request.uri().rawQuery?.let { "?$it" } ?: "")
            throw IscApiException(
                response.statusCode(), "${request.method()} $path failed (${response.statusCode()}): ${errorMessage(response.body())}",
            )
        }
        return response
    }

    private fun accessToken(tenantId: String, connection: IscConnection): String {
        tokens[tenantId]?.takeIf { it.connection == connection && Instant.now().isBefore(it.expiresAt) }?.let { return it.value }
        return requestToken(connection).also { tokens[tenantId] = it }.value
    }

    private fun requestToken(connection: IscConnection): CachedToken {
        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to connection.clientId,
            "client_secret" to connection.clientSecret,
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val request = HttpRequest.newBuilder(URI.create("${connection.baseUrl}/oauth/token"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = execute(request)
        if (response.statusCode() !in 200..299) {
            throw IscApiException(response.statusCode(), "Authentication failed (${response.statusCode()}): ${errorMessage(response.body())}")
        }
        val json = JsonParser.parseString(response.body()).asJsonObject
        val expiresIn = json.get("expires_in")?.asLong ?: 600
        // Refresh a minute early so long-running requests don't race expiry.
        return CachedToken(connection, json.get("access_token").asString, Instant.now().plusSeconds(expiresIn - 60))
    }

    private fun execute(request: HttpRequest): HttpResponse<String> {
        val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        try {
            return await(future, request)
        } catch (e: Throwable) {
            future.cancel(true)
            throw e
        }
    }

    /** Waits for [future], polling so the surrounding progress indicator can cancel it. */
    private fun await(future: CompletableFuture<HttpResponse<String>>, request: HttpRequest): HttpResponse<String> {
        while (true) {
            try {
                ProgressManager.checkCanceled()
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                continue
            } catch (e: ExecutionException) {
                throw IscApiException(0, "${request.method()} ${request.uri()} failed: ${e.cause?.message ?: e.message}")
            }
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        val json = JsonParser.parseString(body).asJsonObject
        json.getAsJsonArray("messages")?.firstOrNull()?.asJsonObject?.string("text")
            ?: json.string("error_description")
            ?: json.string("detailCode")
            ?: body
    }.getOrDefault(body).ifBlank { "no response body" }

    companion object {
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()!!


        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
