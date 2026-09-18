package com.sailpoint.intellij.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Chooses what to peek at: an object type (`account` or an entitlement type) and how many objects. */
class PeekDialog(project: Project, sourceName: String, types: List<String>) : DialogWrapper(project) {
    private val typeBox = ComboBox((listOf("account") + types).distinct().toTypedArray()).apply { isEditable = true }
    private val count = JBIntSpinner(25, 1, 250)

    val objectType: String get() = (typeBox.editor.item ?: typeBox.selectedItem)?.toString()?.trim().orEmpty()
    val maxCount: Int get() = count.number

    init {
        title = "Peek Resource Objects of $sourceName"
        setOKButtonText("Peek")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Object type:") {
            cell(typeBox).align(AlignX.FILL)
                .validationOnApply { if (objectType.isEmpty()) error("Choose or type an object type") else null }
                .comment("Reads objects straight from the system through the connector, without aggregating them.")
        }
        row("At most:") { cell(count); label("objects") }
    }
}
