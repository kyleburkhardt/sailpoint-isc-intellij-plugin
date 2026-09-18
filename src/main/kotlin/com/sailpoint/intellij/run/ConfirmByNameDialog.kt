package com.sailpoint.intellij.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Confirmation for destructive actions on a source: OK only unlocks once [expected] (the source's name) has been
 * typed exactly. [details] lists anything worth knowing first, e.g. what still uses the source.
 */
class ConfirmByNameDialog(
    project: Project,
    title: String,
    private val warning: String,
    private val details: List<String>,
    private val expected: String,
    okText: String,
) : DialogWrapper(project) {
    private val typed = JBTextField()

    init {
        this.title = title
        setOKButtonText(okText)
        isOKActionEnabled = false
        typed.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                isOKActionEnabled = typed.text.trim() == expected
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { text(warning, maxLineLength = 70) }
        if (details.isNotEmpty()) {
            row {
                text(details.joinToString("<br>") { "• ${StringUtil.escapeXmlEntities(it)}" }, maxLineLength = 80)
            }
        }
        row { text("Type <b>${StringUtil.escapeXmlEntities(expected)}</b> to confirm:") }
        row { cell(typed).align(AlignX.FILL).focused() }
    }
}
