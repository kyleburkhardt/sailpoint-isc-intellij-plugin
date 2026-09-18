package com.sailpoint.intellij.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.sailpoint.intellij.api.ResourceKind
import java.util.function.Function
import javax.swing.JComponent

/** Banner above ISC editors showing where the object lives, with Push / Reload links. */
class IscEditorNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file !is IscVirtualFile) return null
        return Function { editor ->
            val service = project.service<IscEditorService>()
            val tenant = file.tenant
            val where = tenant?.let {
                val location = "${it.name} (${it.baseUrl.removePrefix("https://")})"
                file.owner?.let { owner -> "${owner.kind.singularName} '${owner.name}' in $location" } ?: location
            }
            EditorNotificationPanel(editor, if (tenant == null) EditorNotificationPanel.Status.Warning else EditorNotificationPanel.Status.Info).apply {
                text = when {
                    where == null -> "The tenant this ${file.kind.singularName} came from is no longer configured"
                    file.remoteId == null -> "New ${file.kind.singularName}, not yet created in $where"
                    !file.kind.editable -> "Read-only ${file.kind.singularName} '${file.objectName}' from $where"
                    file.kind == ResourceKind.CONNECTOR_RULES -> "Script of connector rule '${file.objectName}' from $where"
                    else -> "${file.kind.singularName.replaceFirstChar { it.uppercase() }} '${file.objectName}' from $where"
                }
                if (tenant != null && file.kind == ResourceKind.SOURCE_SCHEDULES) {
                    createActionLabel("Edit Schedule…") { service.editSchedule(file) }
                }
                if (tenant != null && file.kind.editable) {
                    createActionLabel(if (file.remoteId == null) "Create in ISC" else "Push to ISC", PushToIscAction.ID)
                }
                if (tenant != null && file.remoteId != null) {
                    createActionLabel("Reload") { service.reload(file) }
                }
            }
        }
    }
}
