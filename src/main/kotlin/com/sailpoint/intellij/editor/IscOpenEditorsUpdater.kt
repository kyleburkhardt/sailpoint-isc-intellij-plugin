package com.sailpoint.intellij.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import com.sailpoint.intellij.settings.IscSettingsListener

/** Refreshes banners and tab titles of open ISC editors when tenants are renamed, added or removed. */
class IscOpenEditorsUpdater(private val project: Project) : IscSettingsListener {
    override fun tenantsChanged() {
        val manager = FileEditorManager.getInstance(project)
        for (file in manager.openFiles.filterIsInstance<IscVirtualFile>()) {
            EditorNotifications.getInstance(project).updateNotifications(file)
            FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
        }
    }
}
