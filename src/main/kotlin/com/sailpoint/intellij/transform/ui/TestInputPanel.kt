package com.sailpoint.intellij.transform.ui

import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.sailpoint.intellij.transform.EvalContext
import com.sailpoint.intellij.transform.NeedKind
import com.sailpoint.intellij.transform.NeededInput
import java.awt.BorderLayout
import kotlin.random.Random
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * The values a preview runs against: the attribute flowing in, and the tenant values the transform reads. A tenant value
 * is typed on the step that reads it; one with no such step (read inside a referenced transform, or by Display Name)
 * has its field in this panel.
 */
class TestInputPanel(private val onChange: () -> Unit) {

    private val values = LinkedHashMap<NeededInput, String>()

    /** Values read from ISC that were empty there, so the preview treats them as nothing rather than untyped. */
    private val absent = HashSet<NeededInput>()
    private var incoming = ""
    private var needs = emptyList<NeededInput>()
    private var readsInput = true
    private var onSteps = emptySet<NeededInput>()
    private val seed = Random.nextInt()

    private val container = JPanel(BorderLayout())

    val component: JComponent = container

    init {
        rebuild()
    }

    /**
     * Tracks [needed], showing a field for each one that isn't typed on a step ([onSteps]), and the input value only
     * when the transform [reads it][readsInput], keeping anything already typed.
     */
    fun update(needed: List<NeededInput>, readsInput: Boolean, onSteps: Set<NeededInput>) {
        if (needed == needs && readsInput == this.readsInput && onSteps == this.onSteps) return
        needs = needed
        this.readsInput = readsInput
        this.onSteps = onSteps
        values.keys.retainAll(needed.toSet())
        absent.retainAll(needed.toSet())
        rebuild()
    }

    /** The test value typed for [need], on its step's row. */
    fun value(need: NeededInput): String = values[need].orEmpty()

    fun set(need: NeededInput, value: String) {
        values[need] = value
        absent -= need
        onChange()
    }

    /** Whether [need] was read from ISC and had no value there. */
    fun isAbsent(need: NeededInput): Boolean = need in absent && values[need].isNullOrEmpty()

    /** Takes the values read from a real identity, replacing what was typed for each of them. */
    fun fill(sample: IscSample) {
        sample.values.forEach { (need, value) ->
            values[need] = value.orEmpty()
            if (value.isNullOrEmpty()) absent += need else absent -= need
        }
        if (readsInput) incoming = sample.input.orEmpty()
        rebuild()
        onChange()
    }

    fun context(): EvalContext = EvalContext(
        input = incoming.ifEmpty { null },
        // The same seed every run, so a random value holds still while you type instead of changing on every key.
        random = Random(seed),
        identityAttributes = filled(NeedKind.IDENTITY_ATTRIBUTE).associate { (need, value) -> need.name to value },
        accountAttributes = filled(NeedKind.ACCOUNT_ATTRIBUTE)
            .groupBy { (need, _) -> need.qualifier.orEmpty() }
            .mapValues { (_, entries) -> entries.associate { (need, value) -> need.name to value } },
        referenceAttributes = filled(NeedKind.REFERENCE_IDENTITY_ATTRIBUTE)
            .groupBy { (need, _) -> need.qualifier.orEmpty() }
            .mapValues { (_, entries) -> entries.associate { (need, value) -> need.name to value } },
        absent = absent.filterTo(HashSet()) { values[it].isNullOrEmpty() },
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
        val elsewhere = needs.filter { it.kind.asksForAValue && it !in onSteps }
        if (elsewhere.isNotEmpty()) {
            row { comment("Other values the transform reads:") }
            elsewhere.forEach { need ->
                row("${need.label}:") {
                    cell(field(values[need].orEmpty()) { values[need] = it; absent -= need }).align(AlignX.FILL)
                        .applyToComponent {
                            emptyText.text = if (need in absent) "empty in ISC" else "test value"
                            toolTipText = "The value to preview with. ISC reads the real one from the tenant."
                        }
                }
            }
        }
        // Other tenant values are typed on their own steps; only what can't be typed anywhere is mentioned here.
        needs.filterNot { it.kind.asksForAValue }.forEach { need ->
            row { comment(need.prompt) }
        }
    }.apply { border = JBUI.Borders.empty(4, 8, 0, 8) }

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
