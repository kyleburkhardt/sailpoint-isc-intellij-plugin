package com.sailpoint.intellij.run

import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.text.StringUtil
import com.sailpoint.intellij.api.IscApiException
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.IscItem
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.api.string
import com.sailpoint.intellij.editor.IscEditorService
import java.nio.file.Path

/** Runs things on a source in ISC: aggregations, connector checks, peeking at raw objects, attribute sync. */
@Service(Service.Level.PROJECT)
class SourceRunner(private val project: Project) {
    private val client get() = service<IscClient>()

    /** [reprocessAll] turns off optimization, so unchanged accounts are processed too; [file] aggregates a CSV. */
    fun aggregateAccounts(source: IscItem, reprocessAll: Boolean = false, file: Path? = null) {
        val fields = if (reprocessAll) mapOf("disableOptimization" to "true") else emptyMap()
        val what = "Account aggregation" + if (file != null) " from ${file.fileName}" else ""
        startTask(source, what, "/load-accounts", fields, file)
    }

    fun aggregateEntitlements(source: IscItem, file: Path? = null) =
        startTask(source, "Entitlement aggregation" + if (file != null) " from ${file.fileName}" else "", "/load-entitlements", file = file)

    fun processUncorrelatedAccounts(source: IscItem) = startTask(source, "Processing uncorrelated accounts", "/load-uncorrelated-accounts")

    fun testConnection(source: IscItem) = connectorCheck(source, "Connection test", "check-connection")

    fun testConfiguration(source: IscItem) = connectorCheck(source, "Configuration test", "test-configuration")

    fun pingCluster(source: IscItem) = connectorCheck(source, "Cluster ping", "ping-cluster")

    /** Asks which object type and how many, then shows what the connector reads, straight from the system. */
    fun peekResourceObjects(source: IscItem) {
        val types = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { client.list(source.tenantId, ResourceKind.SOURCE_SCHEMAS, source).map { it.name } },
                "Reading Object Types of ${source.name}", true, project,
            )
        } catch (e: Exception) {
            // The dialog still works; it just can't suggest the source's entitlement types.
            emptyList()
        }
        val dialog = PeekDialog(project, source.name, types)
        if (!dialog.showAndGet()) return
        val body = JsonObject().apply {
            addProperty("objectType", dialog.objectType)
            addProperty("maxCount", dialog.maxCount)
        }
        background("Peeking at ${dialog.objectType} objects of ${source.name}") {
            val result = client.post(source.tenantId, "${sourcePath(source)}/connector/peek-resource-objects", body).asJsonObject
            onEdt {
                project.service<IscEditorService>().showJson("${source.name} ${dialog.objectType} objects", result)
                val count = result.get("objectCount")?.takeIf { it.isJsonPrimitive }?.asInt ?: result.getAsJsonArray("resourceObjects")?.size()
                Notifier.notify(project, "Read ${count ?: "the"} ${dialog.objectType} objects from '${source.name}'${elapsed(result)}.", NotificationType.INFORMATION)
            }
        }
    }

    /** Starts attribute sync (an experimental API); ISC gives no way to follow it, so this reports that it started. */
    fun synchronizeAttributes(source: IscItem) {
        background("Starting attribute sync of ${source.name}") {
            val job = client.postWithoutBody(source.tenantId, "${sourcePath(source)}/synchronize-attributes", experimental = true).asJsonObject
            onEdt {
                val status = job.string("status")?.let { " ($it)" }.orEmpty()
                Notifier.notify(project, "Attribute sync of '${source.name}' started$status.", NotificationType.INFORMATION)
            }
        }
    }

    /**
     * Deletes [source] after listing what still uses it (identity profiles, transforms, apps…) and having its name
     * typed to confirm. ISC deletes in the background, so this follows the task and reloads Sources when it's done.
     */
    fun deleteSource(source: IscItem) {
        background("Checking what uses ${source.name}") {
            val usage = try {
                describeConnections(client.get(source.tenantId, "${sourcePath(source)}/connections").asJsonObject)
            } catch (e: IscApiException) {
                listOf("Couldn't check what uses this source: ${e.message}")
            }
            onEdt {
                val warning = "Deleting <b>${source.name.escaped()}</b> removes it from ISC along with its accounts and " +
                    "entitlements. This can't be undone." +
                    if (usage.isEmpty()) "<br><br>Nothing else in ISC uses this source." else "<br><br>These use this source:"
                if (!ConfirmByNameDialog(project, "Delete Source", warning, usage, source.name, "Delete Source").showAndGet()) return@onEdt
                background("Deleting ${source.name}") {
                    val response = client.delete(source.tenantId, sourcePath(source)).asJsonObject
                    val taskId = response.string("id")?.takeIf { response.string("type") == "TASK_RESULT" }
                    onEdt {
                        val editors = project.service<IscEditorService>()
                        editors.closeEditorsOf(source)
                        if (taskId == null) {
                            Notifier.notify(project, "Source '${source.name}' deleted.", NotificationType.INFORMATION)
                            editors.announce(source.tenantId, ResourceKind.SOURCES, null)
                        } else {
                            TaskTracker.track(project, source.tenantId, "Deleting source '${source.name}'", taskId) {
                                editors.announce(source.tenantId, ResourceKind.SOURCES, null)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Removes every account from [source] in ISC, once its name has been typed to confirm, and follows the task. */
    fun removeAllAccounts(source: IscItem) {
        val warning = "This removes <b>all accounts</b> of <b>${source.name.escaped()}</b> from ISC, along with the access " +
            "identities get through them. They come back only through the next aggregation."
        if (!ConfirmByNameDialog(project, "Remove All Accounts", warning, emptyList(), source.name, "Remove All Accounts").showAndGet()) return
        background("Starting account removal from ${source.name}") {
            val response = client.postWithoutBody(source.tenantId, "${sourcePath(source)}/remove-accounts").asJsonObject
            val taskId = response.string("id")
            onEdt {
                if (taskId == null) {
                    Notifier.notify(project, "Removing all accounts of '${source.name}' started.", NotificationType.INFORMATION)
                } else {
                    TaskTracker.track(project, source.tenantId, "Removing all accounts of '${source.name}'", taskId)
                }
            }
        }
    }

    /** Uploads a file the connector needs, such as a JDBC driver, and shows the updated source if it's open. */
    fun uploadConnectorFile(source: IscItem, file: Path) {
        background("Uploading ${file.fileName} to ${source.name}") {
            val updated = client.postMultipart(source.tenantId, "${sourcePath(source)}/upload-connector-file", file = file).asJsonObject
            onEdt {
                project.service<IscEditorService>().refreshIfOpen(source, updated)
                Notifier.notify(project, "Uploaded ${file.fileName} to '${source.name}'.", NotificationType.INFORMATION)
            }
        }
    }

    /** One line per thing that uses a source, from its `/connections`, grouped by what kind of thing it is. */
    private fun describeConnections(connections: JsonObject): List<String> {
        val labels = linkedMapOf(
            "identityProfiles" to "Identity profile",
            "credentialProfiles" to "Credential profile",
            "sourceAttributes" to "Source attribute",
            "mappingProfiles" to "Mapping profile",
            "dependentCustomTransforms" to "Transform",
            "dependentApps" to "App",
        )
        return labels.flatMap { (field, label) ->
            connections.getAsJsonArray(field)?.mapNotNull { element ->
                val name = when {
                    element.isJsonPrimitive -> element.asString
                    element.isJsonObject -> element.asJsonObject.let { it.string("name") ?: it.string("id") }
                    else -> null
                }
                name?.let { "$label: $it" }
            }.orEmpty()
        }
    }

    private fun String.escaped() = StringUtil.escapeXmlEntities(this)

    /** Starts a task in ISC and follows it until it finishes. */
    private fun startTask(source: IscItem, what: String, action: String, fields: Map<String, String> = emptyMap(), file: Path? = null) {
        background("Starting ${what.replaceFirstChar { it.lowercase() }} of ${source.name}") {
            val response = client.postMultipart(source.tenantId, sourcePath(source) + action, fields, file).asJsonObject
            val taskId = response.get("task")?.takeIf { it.isJsonObject }?.asJsonObject?.string("id") ?: response.string("id")
            onEdt {
                if (taskId == null) {
                    Notifier.notify(project, "$what of '${source.name}' started.", NotificationType.INFORMATION)
                } else {
                    TaskTracker.track(project, source.tenantId, "$what of '${source.name}'", taskId)
                }
            }
        }
    }

    /** Runs a quick connector check and reports success or failure, with the full details a click away. */
    private fun connectorCheck(source: IscItem, what: String, check: String) {
        background("$what of ${source.name}") {
            val result = client.postWithoutBody(source.tenantId, "${sourcePath(source)}/connector/$check").asJsonObject
            onEdt {
                val succeeded = result.string("status") == "SUCCESS"
                val content = "$what of '${source.name}' ${if (succeeded) "succeeded" else "failed"}${elapsed(result)}."
                Notifier.notify(
                    project, content, if (succeeded) NotificationType.INFORMATION else NotificationType.ERROR, "Show details",
                ) { project.service<IscEditorService>().showJson("${source.name} ${what.lowercase()}", result) }
            }
        }
    }

    private fun sourcePath(source: IscItem) = service<IscClient>().objectPath(ResourceKind.SOURCES, null, source.id)

    private fun elapsed(result: JsonObject): String =
        result.get("elapsedMillis")?.takeIf { it.isJsonPrimitive }?.asLong?.let { " in %.1f s".format(it / 1000.0) }.orEmpty()

    private fun background(title: String, work: () -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) = work()

            override fun onThrowable(error: Throwable) {
                Notifier.notify(project, "$title failed: ${error.message ?: error}", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, project.disposed)
    }
}
