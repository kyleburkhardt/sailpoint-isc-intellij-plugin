package com.sailpoint.intellij.transform.ui

import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.sailpoint.intellij.transform.EvalContext
import com.sailpoint.intellij.transform.NeedKind
import com.sailpoint.intellij.transform.NeededInput
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * The values a preview runs against: the attribute flowing in, and one field for every tenant value the transform
 * reads. The fields follow what the transform references, so there's nothing to set up by hand.
 */
class TestInputPanel(private val onChange: () -> Unit) {

    private val values = LinkedHashMap<NeededInput, String>()
    private var incoming = ""
    private var needs = emptyList<NeededInput>()
    private var readsInput = true

    private val container = JPanel(BorderLayout())

    val component: JComponent = container

    init {
        rebuild()
    }

    /**
     * Shows a field for each of [needed], and the input value only when the transform [reads it][readsInput], keeping
     * anything already typed.
     */
    fun update(needed: List<NeededInput>, readsInput: Boolean) {
        if (needed == needs && readsInput == this.readsInput) return
        needs = needed
        this.readsInput = readsInput
        values.keys.retainAll(needed.toSet())
        rebuild()
    }

    fun context(): EvalContext = EvalContext(
        input = incoming.ifEmpty { null },
        identityAttributes = filled(NeedKind.IDENTITY_ATTRIBUTE).associate { (need, value) -> need.name to value },
        accountAttributes = filled(NeedKind.ACCOUNT_ATTRIBUTE)
            .groupBy { (need, _) -> need.qualifier.orEmpty() }
            .mapValues { (_, entries) -> entries.associate { (need, value) -> need.name to value } },
        referenceAttributes = filled(NeedKind.REFERENCE_IDENTITY_ATTRIBUTE)
            .groupBy { (need, _) -> need.qualifier.orEmpty() }
            .mapValues { (_, entries) -> entries.associate { (need, value) -> need.name to value } },
    )

    private fun filled(kind: NeedKind): List<Pair<NeededInput, String>> =
        values.entries.filter { it.key.kind == kind && it.value.isNotEmpty() }.map { it.key to it.value }

    private fun rebuild() {
        container.removeAll()
        container.add(form(), BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
    }

    private fun form(): DialogPanel = panel {
        if (readsInput) {
            row("Input:") {
                cell(field(incoming) { incoming = it }).align(AlignX.FILL)
                    .applyToComponent { toolTipText = "The attribute value ISC passes into the transform." }
            }
        }
        // Only the operations that read tenant data ask for anything, and only for what they actually reference.
        needs.filter { it.kind.asksForAValue }.forEach { need ->
            row {
                // Above the field, so a long source name doesn't squeeze it.
                cell(field(values[need].orEmpty()) { values[need] = it }).align(AlignX.FILL)
                    .label("${need.shortLabel}:", LabelPosition.TOP)
                    .applyToComponent { toolTipText = need.label }
            }
        }
        needs.filterNot { it.kind.asksForAValue }.forEach { need ->
            row { comment(need.prompt) }
        }
    }.apply { border = JBUI.Borders.empty(4, 8, 0, 8) }

    /** A field label short enough to sit beside its field: the attribute, and where it comes from if not the identity. */
    private val NeededInput.shortLabel: String
        get() = when (kind) {
            NeedKind.ACCOUNT_ATTRIBUTE -> qualifier?.let { "$it › $name" } ?: name
            NeedKind.REFERENCE_IDENTITY_ATTRIBUTE -> qualifier?.let { "$it › $name" } ?: name
            else -> name
        }

    private fun field(initial: String, onEdit: (String) -> Unit) = JBTextField(initial).apply {
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                onEdit(text)
                onChange()
            }
        })
    }

    private val NeedKind.asksForAValue: Boolean
        get() = this == NeedKind.IDENTITY_ATTRIBUTE || this == NeedKind.ACCOUNT_ATTRIBUTE || this == NeedKind.REFERENCE_IDENTITY_ATTRIBUTE
}
