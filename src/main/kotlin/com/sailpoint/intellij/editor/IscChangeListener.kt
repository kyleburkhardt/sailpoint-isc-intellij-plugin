package com.sailpoint.intellij.editor

import com.intellij.util.messages.Topic
import com.sailpoint.intellij.api.ResourceKind

/**
 * Announces that objects of a kind were created or deleted in ISC, so views listing them can reload.
 * [parentId] is the owning object's ID for child kinds, e.g. the source of a schedule.
 */
fun interface IscChangeListener {
    fun changed(tenantId: String, kind: ResourceKind, parentId: String?)

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(IscChangeListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
