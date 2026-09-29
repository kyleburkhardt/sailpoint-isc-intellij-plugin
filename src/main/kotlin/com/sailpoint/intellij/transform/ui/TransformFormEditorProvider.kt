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
import com.intellij.ui.JBSplitter
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.editor.IscVirtualFile
import javax.swing.JComponent

/**
 * Opens a transform with its form and JSON side by side; the editor's own toolbar switches to either one alone. Both halves edit the same
 * document, so whichever you use, Push to ISC sends the same thing.
 */
class TransformFormEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is IscVirtualFile && file.kind == ResourceKind.TRANSFORMS

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return FormLeftEditor(text, TransformFormEditor(project, file as IscVirtualFile))
    }

    /** Opens on the split view with the form on the left and the JSON on the right, the reverse of the platform's order. */
    private class FormLeftEditor(text: TextEditor, form: FileEditor) : TextEditorWithPreview(
        text,
        form,
        "SailPointTransform",
        Layout.SHOW_EDITOR_AND_PREVIEW,
    ) {
        override fun createSplitter(): JBSplitter = object : JBSplitter(false, 0.5f, 0.15f, 0.85f) {
            override fun setFirstComponent(component: JComponent?) = super.setSecondComponent(component)
            override fun setSecondComponent(component: JComponent?) = super.setFirstComponent(component)
        }
    }

    override fun getEditorTypeId(): String = "sailpoint-transform"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
