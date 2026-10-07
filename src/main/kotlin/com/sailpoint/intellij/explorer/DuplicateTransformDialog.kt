package com.sailpoint.intellij.explorer

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.sailpoint.intellij.api.IscItem
import com.sailpoint.intellij.editor.IscEditorService
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Asks for the name of a copy of [transform]; it must differ from every transform in [existing], the tenant's
 * current transform names. Then creates the copy.
 */
class DuplicateTransformDialog(private val project: Project, private val transform: IscItem, private val existing: Set<String>) :
    DialogWrapper(project) {
    private var name = "${transform.name} - Copy"
    private lateinit var nameField: JTextField

    init {
        title = "Duplicate Transform '${transform.name}'"
        setOKButtonText("Duplicate")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("New name:") {
            nameField = textField().bindText(::name).align(AlignX.FILL).focused()
                .comment("The copy is created in ISC straight away, the same as '${transform.name}' as ISC has it now, apart from its name.")
                .validationOnApply {
                    val typed = it.text.trim()
                    when {
                        typed.isEmpty() -> error("Name is required")
                        existing.any { e -> e.equals(typed, ignoreCase = true) } -> error("A transform named '$typed' already exists")
                        else -> null
                    }
                }
                .component
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField.also { it.selectAll() }

    fun showAndDuplicate() {
        if (showAndGet()) project.service<IscEditorService>().duplicateTransform(transform, name.trim())
    }
}
