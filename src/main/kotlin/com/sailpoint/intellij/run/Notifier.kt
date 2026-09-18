package com.sailpoint.intellij.run

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Balloons in the plugin's notification group, optionally with one follow-up link. */
object Notifier {
    fun notify(project: Project, content: String, type: NotificationType, linkText: String? = null, onLink: (() -> Unit)? = null) {
        NotificationGroupManager.getInstance().getNotificationGroup("SailPoint ISC")
            .createNotification(content, type)
            .apply { if (linkText != null && onLink != null) addAction(NotificationAction.createSimpleExpiring(linkText, onLink)) }
            .notify(project)
    }
}
