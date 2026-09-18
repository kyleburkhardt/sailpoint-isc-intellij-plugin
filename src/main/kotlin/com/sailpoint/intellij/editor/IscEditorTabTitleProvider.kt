package com.sailpoint.intellij.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.sailpoint.intellij.settings.IscSettings

/** Adds the tenant name to ISC editor tabs when more than one tenant is configured. */
class IscEditorTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        if (file !is IscVirtualFile || service<IscSettings>().tenants.size < 2) return null
        return "${file.name} [${file.tenant?.name ?: "removed tenant"}]"
    }
}
