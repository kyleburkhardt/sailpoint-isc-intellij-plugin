package com.sailpoint.intellij.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

class PushToIscAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) as? IscVirtualFile
        e.presentation.isEnabledAndVisible = e.project != null && file != null && file.kind.editable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) as? IscVirtualFile ?: return
        project.service<IscEditorService>().push(file)
    }

    companion object {
        const val ID = "SailPoint.PushToIsc"
    }
}
