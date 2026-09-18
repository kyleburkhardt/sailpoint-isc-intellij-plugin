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
