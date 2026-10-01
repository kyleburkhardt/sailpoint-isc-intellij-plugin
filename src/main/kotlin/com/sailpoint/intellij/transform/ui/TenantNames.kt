package com.sailpoint.intellij.transform.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.api.string
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The names a transform refers to, for its dropdowns: sources, a source's account attributes, identity attributes.
 * Everything loads in the background and answers on the UI thread; a failed load answers with nothing, so the
 * dropdowns fall back to plain typing.
 */
interface TenantNames {
    fun sources(onLoaded: (List<String>) -> Unit)
    fun accountAttributes(source: String, onLoaded: (List<String>) -> Unit)
    fun identityAttributes(onLoaded: (List<String>) -> Unit)

    /** The saved transform named [name], for the preview to run where a `reference` points; null when there isn't one. */
    fun transform(name: String, onLoaded: (JsonObject?) -> Unit) = onLoaded(null)

    /** Drops what was loaded for transform [name], so the next [transform] reads it from the tenant again. */
    fun reloadTransform(name: String) = Unit

    /** Calls [listener] with a transform's name whenever what [transform] gives for it changes. Returns how to stop. */
    fun onTransformChanged(listener: (String) -> Unit): () -> Unit = {}
}

/** [TenantNames] read from a tenant, kept for the session so every transform opened on it shares one load. */
class IscTenantNames private constructor(private val tenantId: String) : TenantNames {

    private val client get() = service<IscClient>()

    /** Source name to ID. */
    private val sourceIds: CompletableFuture<Map<String, String>> by lazy {
        load { client.list(tenantId, ResourceKind.SOURCES).associate { it.name to it.id } }
    }

    private val identity: CompletableFuture<List<String>> by lazy {
        load {
            client.get(tenantId, "/identity-attributes/v1").asJsonArray
                .mapNotNull { it.asJsonObject.string("name") }
                .sortedBy { it.lowercase() }
        }
    }

    private val accounts = ConcurrentHashMap<String, CompletableFuture<List<String>>>()

    private val transforms = ConcurrentHashMap<String, CompletableFuture<JsonObject?>>()
    private val transformListeners = CopyOnWriteArrayList<(String) -> Unit>()

    override fun sources(onLoaded: (List<String>) -> Unit) = sourceIds.answer(onLoaded) { it.keys.sortedBy(String::lowercase) }

    override fun identityAttributes(onLoaded: (List<String>) -> Unit) = identity.answer(onLoaded) { it }

    override fun accountAttributes(source: String, onLoaded: (List<String>) -> Unit) {
        accounts.computeIfAbsent(source) {
            sourceIds.thenApply { ids ->
                val id = ids[source] ?: return@thenApply emptyList()
                val schemas = client.get(tenantId, "/sources/v1/$id/schemas").asJsonArray.map { it.asJsonObject }
                val account = schemas.firstOrNull { it.string("name") == "account" } ?: schemas.firstOrNull()
                account?.getAsJsonArray("attributes").orEmptyNames()
            }.exceptionally { error ->
                LOG.info("Couldn't load the account attributes of $source", error)
                emptyList()
            }
        }.answer(onLoaded) { it }
    }

    override fun transform(name: String, onLoaded: (JsonObject?) -> Unit) {
        val loading = transforms.computeIfAbsent(name) {
            CompletableFuture.supplyAsync(
                { client.transformByName(tenantId, name) },
                { ApplicationManager.getApplication().executeOnPooledThread(it) },
            )
        }
        // A failed load isn't kept, so the next preview asks again.
        loading.whenComplete { _, error -> if (error != null) transforms.remove(name, loading) }
        loading.whenComplete { value, error ->
            if (error != null) LOG.info("Couldn't load the transform '$name'", error)
            ApplicationManager.getApplication().invokeLater({ onLoaded(value) }, ModalityState.any())
        }
    }

    override fun reloadTransform(name: String) {
        transforms.remove(name)
        changed(name)
    }

    /** Takes [transform] as it was just saved to the tenant, so previews that reference it use it straight away. */
    fun transformSaved(transform: JsonObject) {
        val name = transform.string("name") ?: return
        transforms[name] = CompletableFuture.completedFuture(transform)
        changed(name)
    }

    override fun onTransformChanged(listener: (String) -> Unit): () -> Unit {
        transformListeners += listener
        return { transformListeners -= listener }
    }

    private fun changed(name: String) {
        ApplicationManager.getApplication().invokeLater({ transformListeners.forEach { it(name) } }, ModalityState.any())
    }

    private fun com.google.gson.JsonArray?.orEmptyNames(): List<String> =
        this?.mapNotNull { it.asJsonObject.string("name") }?.sortedBy { it.lowercase() }.orEmpty()

    private fun <T : Any> load(fetch: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(
        { fetch() },
        { ApplicationManager.getApplication().executeOnPooledThread(it) },
    )

    private fun <T, R> CompletableFuture<T>.answer(onLoaded: (R) -> Unit, pick: (T) -> R) {
        whenComplete { value, error ->
            if (error != null) LOG.info("Couldn't load names from the tenant for the transform editor", error)
            val result = runCatching { value?.let(pick) }.getOrNull() ?: return@whenComplete
            ApplicationManager.getApplication().invokeLater({ onLoaded(result) }, ModalityState.any())
        }
    }

    companion object {
        private val LOG = logger<IscTenantNames>()
        private val byTenant = ConcurrentHashMap<String, IscTenantNames>()

        fun of(tenantId: String): IscTenantNames = byTenant.computeIfAbsent(tenantId, ::IscTenantNames)
    }
}
