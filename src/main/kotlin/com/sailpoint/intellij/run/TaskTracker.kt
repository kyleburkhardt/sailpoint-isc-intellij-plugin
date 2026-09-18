package com.sailpoint.intellij.run

import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.string

/**
 * Follows a background task in ISC (an aggregation, say) by polling `/task-status/v1/{id}`, showing its progress
 * and then a notification with the outcome. Cancelling the progress stops following it, not the task in ISC.
 */
object TaskTracker {
    private const val POLL_MS = 3_000L

    fun track(project: Project, tenantId: String, title: String, taskId: String) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val client = service<IscClient>()
                while (true) {
                    val status = client.taskStatus(tenantId, taskId)
                    status.get("percentComplete")?.takeIf { it.isJsonPrimitive }?.asDouble?.let {
                        indicator.isIndeterminate = false
                        indicator.fraction = it / 100
                    }
                    status.string("progress")?.let { indicator.text2 = it }
                    if (status.string("completed") != null || status.string("completionStatus") != null) {
                        report(project, title, status)
                        return
                    }
                    waitCancellable(indicator, POLL_MS)
                }
            }

            override fun onThrowable(error: Throwable) {
                Notifier.notify(project, "$title: couldn't follow the task: ${error.message ?: error}", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun waitCancellable(indicator: ProgressIndicator, millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            indicator.checkCanceled()
            Thread.sleep(100)
        }
    }

    /** Notifies the outcome: the status, the counts the task returns, and its warnings and errors. */
    private fun report(project: Project, title: String, status: JsonObject) {
        val outcome = status.string("completionStatus") ?: "finished"
        val attributes = status.get("attributes")?.takeIf { it.isJsonObject }?.asJsonObject
        val counts = status.getAsJsonArray("returns")?.mapNotNull { element ->
            val detail = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val value = detail.string("attributeName")?.let { attributes?.get(it) }?.takeIf { it.isJsonPrimitive }?.asString
            value?.let { "${detail.string("name") ?: detail.string("attributeName")}: $it" }
        }.orEmpty()
        val problems = status.getAsJsonArray("messages")?.mapNotNull { element ->
            val message = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val type = message.string("type")?.takeIf { it == "WARN" || it == "ERROR" } ?: return@mapNotNull null
            val text = message.get("localizedText")?.takeIf { it.isJsonObject }?.asJsonObject?.string("message") ?: message.string("key")
            text?.let { "$type: $it" }
        }.orEmpty()
        val type = when (outcome) {
            "SUCCESS" -> NotificationType.INFORMATION
            "WARNING" -> NotificationType.WARNING
            else -> NotificationType.ERROR
        }
        val details = (counts + problems.take(MAX_PROBLEMS)).joinToString("<br>") +
            if (problems.size > MAX_PROBLEMS) "<br>…and ${problems.size - MAX_PROBLEMS} more" else ""
        Notifier.notify(project, "$title: $outcome" + if (details.isEmpty()) "" else "<br>$details", type)
    }

    private const val MAX_PROBLEMS = 5
}
