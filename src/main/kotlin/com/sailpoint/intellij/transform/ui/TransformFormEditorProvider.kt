package com.sailpoint.intellij.transform.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.editor.IscVirtualFile

/**
 * Opens a transform on its form, with the JSON one click away on the editor's own toolbar. Both halves edit the same
 * document, so whichever you use, Push to ISC sends the same thing.
 */
class TransformFormEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is IscVirtualFile && file.kind == ResourceKind.TRANSFORMS

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return TextEditorWithPreview(
            text,
            TransformFormEditor(project, file as IscVirtualFile),
            "SailPointTransform",
            TextEditorWithPreview.Layout.SHOW_PREVIEW,
        )
    }

    override fun getEditorTypeId(): String = "sailpoint-transform"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
