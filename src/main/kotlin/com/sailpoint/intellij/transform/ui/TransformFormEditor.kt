package com.sailpoint.intellij.transform.ui

import com.google.gson.JsonParser
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.editor.IscVirtualFile
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The form half of a transform's editor. The document stays the source of truth — the form reads it on every outside
 * change and writes it back on every edit — so push, reload and the JSON view all keep working unchanged.
 */
class TransformFormEditor(private val project: Project, private val file: IscVirtualFile) : UserDataHolderBase(), FileEditor {

    private val document = FileDocumentManager.getInstance().getDocument(file)
    private val form = TransformFormPanel(nameEditable = file.remoteId == null) { writeBack() }
    private val message = JBLabel().apply { border = JBUI.Borders.empty(12) }
    private val root = JPanel(BorderLayout())
    private val reload = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** True while the form is writing the document, so its own edit doesn't come back as an outside change. */
    private var writing = false

    init {
        load()
        document?.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (writing) return
                    // The JSON is half-typed between keystrokes, so settle before reading it.
                    reload.cancelAllRequests()
                    reload.addRequest({ load() }, RELOAD_DELAY_MS)
                }
            },
            this,
        )
    }

    private fun load() {
        val text = document?.text ?: file.content.toString()
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (json == null || !json.isJsonObject) {
            show(message.also { it.text = "This transform isn't valid JSON yet, so the form can't show it. The JSON view has the details." })
            return
        }
        form.setModel(json.asJsonObject)
        show(form.component)
    }

    private fun show(component: JComponent) {
        if (root.componentCount == 1 && root.getComponent(0) === component) return
        root.removeAll()
        root.add(component, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    private fun writeBack() {
        val document = document ?: return
        val text = IscClient.gson.toJson(form.model)
        if (text == document.text) return
        writing = true
        try {
            WriteCommandAction.runWriteCommandAction(project, "Edit Transform", null, { document.setText(text) })
        } finally {
            writing = false
        }
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = form.component

    override fun getName(): String = "Transform"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Unit

    private companion object {
        const val RELOAD_DELAY_MS = 250
    }
}
