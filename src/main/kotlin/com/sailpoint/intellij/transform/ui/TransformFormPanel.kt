package com.sailpoint.intellij.transform.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import com.sailpoint.intellij.api.string
import com.sailpoint.intellij.transform.AttrDef
import com.sailpoint.intellij.transform.AttrKind
import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.OpDef
import com.sailpoint.intellij.transform.Trace
import com.sailpoint.intellij.transform.TransformCatalog
import com.sailpoint.intellij.transform.TransformEvaluator
import com.sailpoint.intellij.transform.evaluate
import com.sailpoint.intellij.transform.neededInputs
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableModel

/**
 * Edits a transform as a stack of cards — one per operation, nested where the transform is — and shows what each one
 * produces for the test input. The JSON behind it is never on screen; it stays the thing that's saved.
 */
class TransformFormPanel(private val nameEditable: Boolean, private val onModelChanged: () -> Unit) {

    var model: JsonObject = JsonObject()
        private set

    private val inputs = TestInputPanel { refresh() }
    private val cards = JPanel(VerticalLayout(JBUI.scale(8))).apply { border = JBUI.Borders.empty(8) }
    private val outcome = JBLabel()
    private val updaters = mutableListOf<(Trace) -> Unit>()

    val component: JComponent = BorderLayoutPanel().apply {
        addToTop(inputs.component)
        addToCenter(JBScrollPane(cards).apply { border = JBUI.Borders.empty() })
        addToBottom(
            panel {
                separator()
                row("Result:") { cell(outcome) }
            }.apply { border = JBUI.Borders.empty(0, 8, 8, 8) },
        )
    }

    /** Replaces what's being edited, after a load, a reload, or an edit made in the JSON. */
    fun setModel(transform: JsonObject) {
        model = transform
        rebuild()
    }

    private fun rebuild() {
        updaters.clear()
        cards.removeAll()
        cards.add(card(model, ""))
        cards.revalidate()
        cards.repaint()
        refresh()
    }

    /** Re-runs the preview and updates every card's result, leaving the form as it is. */
    private fun refresh() {
        inputs.update(neededInputs(model))
        val trace = evaluate(model, inputs.context())
        updaters.forEach { it(trace) }
        show(outcome, trace.result)
    }

    /** A structural change rebuilds the cards; a typed character only re-runs the preview. */
    private fun changed(structural: Boolean = false) {
        onModelChanged()
        if (structural) rebuild() else refresh()
    }

    /** One operation: what it is, its attributes, and what it produced. [revert] turns it back into a plain value. */
    private fun card(node: JsonObject, path: String, revert: (() -> Unit)? = null): JComponent {
        val op = TransformCatalog[node.string("type")]
        val attributes = node.child("attributes")
        val result = JBLabel()
        updaters += { trace -> show(result, trace.at(path)?.result) }

        return panel {
            row {
                cell(typeCombo(node, op))
                op?.let { browserLink("Documentation", it.docsUrl) }
                revert?.let { link("Use a plain value instead") { _ -> it() }.align(AlignX.RIGHT) }
            }
            op?.summary?.takeIf { it.isNotEmpty() }?.let { row { comment(it) } }
            if (path.isEmpty()) identification()
            op?.attributes?.forEach { attribute(attributes, it, path) }
            variables(attributes, op, path)
            row { cell(result) }
        }.apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(8))
        }
    }

    /** The transform's own name and refresh flag, which only the top-level transform has. */
    private fun Panel.identification() {
        row("Name:") {
            if (nameEditable) {
                cell(textBox(model.string("name").orEmpty()) { model.addProperty("name", it); changed() }).align(AlignX.FILL)
            } else {
                // ISC won't rename a transform that already exists, so this is shown rather than offered.
                label(model.string("name").orEmpty())
            }
        }
        row {
            cell(
                checkBox("Re-evaluate nightly during identity refresh", model.flag("requiresPeriodicRefresh")) {
                    model.addProperty("requiresPeriodicRefresh", it)
                    changed()
                },
            )
        }
    }

    private fun typeCombo(node: JsonObject, current: OpDef?): ComboBox<OpDef> =
        ComboBox(TransformCatalog.ops.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { it.label }
            selectedItem = current
            addActionListener {
                val chosen = selectedItem as? OpDef ?: return@addActionListener
                if (chosen.type != node.string("type")) {
                    retype(node, chosen)
                    changed(structural = true)
                }
            }
        }

    /** Switches an operation, keeping the attributes the new one also has and starting the rest off empty. */
    private fun retype(node: JsonObject, op: OpDef) {
        val old = node.child("attributes")
        val kept = TransformCatalog.starterAttributes(op.type)
        old.entrySet().filter { (name, _) -> op.attribute(name) != null || name == "input" }
            .forEach { (name, value) -> kept.add(name, value) }
        node.addProperty("type", op.type)
        node.add("attributes", kept)
    }

    private fun Panel.attribute(attributes: JsonObject, attr: AttrDef, path: String) {
        val label = attr.label + if (attr.required) " *" else ""
        when (attr.kind) {
            AttrKind.BOOLEAN -> row {
                cell(
                    checkBox(label, attributes.flag(attr.name)) {
                        attributes.addProperty(attr.name, it)
                        changed()
                    },
                )
            }.help(attr)

            AttrKind.ENUM -> row("$label:") {
                cell(
                    ComboBox(attr.options.toTypedArray()).apply {
                        selectedItem = attributes.string(attr.name)
                        addActionListener { attributes.addProperty(attr.name, selectedItem as? String); changed() }
                    },
                )
            }.help(attr)

            AttrKind.INTEGER -> row("$label:") {
                cell(textBox(attributes.string(attr.name).orEmpty()) { typed ->
                    typed.trim().toIntOrNull()?.let { attributes.addProperty(attr.name, it) }
                        ?: attributes.addProperty(attr.name, typed)
                    changed()
                }).columns(8)
            }.help(attr)

            AttrKind.TEXT -> row("$label:") {
                cell(textBox(attributes.string(attr.name).orEmpty()) { attributes.addProperty(attr.name, it); changed() })
                    .align(AlignX.FILL)
            }.help(attr)

            AttrKind.TABLE -> {
                row("$label:") { }.help(attr)
                row { cell(table(attributes, attr.name)).align(AlignX.FILL) }
            }

            AttrKind.VALUE -> value(attributes, attr.name, "$label:", path, attr)

            AttrKind.VALUE_LIST, AttrKind.STRING_LIST -> {
                val array = attributes.list(attr.name)
                row("$label:") { }.help(attr)
                array.forEachIndexed { index, element ->
                    entry(array, index, element, path, "${attr.name}[$index]", nestable = attr.kind == AttrKind.VALUE_LIST)
                }
                row {
                    link("Add value") { _ ->
                        array.add(JsonPrimitive(""))
                        changed(structural = true)
                    }
                }
            }
        }
    }

    /** An attribute that holds either a literal or a whole nested transform. */
    private fun Panel.value(attributes: JsonObject, name: String, label: String, path: String, attr: AttrDef?) {
        val element = attributes.get(name)
        if (element.isTransform()) {
            row(label) { }
            row {
                cell(
                    card(element.asJsonObject, TransformEvaluator.join(path, name)) {
                        attributes.addProperty(name, "")
                        changed(structural = true)
                    },
                ).align(AlignX.FILL)
            }
            return
        }
        row(label) {
            val current = element?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            cell(textBox(current) { attributes.addProperty(name, it); changed() }).align(AlignX.FILL)
            link("Use a transform") { _ ->
                attributes.add(name, staticOf(current))
                changed(structural = true)
            }
        }.apply { attr?.let { help(it) } }
    }

    /** One entry of a list attribute. */
    private fun Panel.entry(array: JsonArray, index: Int, element: JsonElement, path: String, step: String, nestable: Boolean) {
        val childPath = TransformEvaluator.join(path, step)
        if (element.isTransform()) {
            row {
                cell(
                    card(element.asJsonObject, childPath) {
                        array.set(index, JsonPrimitive(""))
                        changed(structural = true)
                    },
                ).align(AlignX.FILL)
                link("Remove") { _ ->
                    array.remove(index)
                    changed(structural = true)
                }
            }
            return
        }
        row {
            val current = element.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            cell(textBox(current) { array.set(index, JsonPrimitive(it)); changed() }).align(AlignX.FILL)
            if (nestable) {
                link("Use a transform") { _ ->
                    array.set(index, staticOf(current))
                    changed(structural = true)
                }
            }
            link("Remove") { _ ->
                array.remove(index)
                changed(structural = true)
            }
        }
    }

    /**
     * The extra attributes a Velocity template refers to as `$name`. They're ordinary values or transforms, so they
     * get the same editing as everything else.
     */
    private fun Panel.variables(attributes: JsonObject, op: OpDef?, path: String) {
        val names = attributes.keySet().filter { it != "input" && (op?.isVariable(it) ?: false) }
        collapsibleGroup("Variables") {
            names.forEach { name ->
                value(attributes, name, "\$$name:", path, null)
                row { link("Remove \$$name") { _ -> attributes.remove(name); changed(structural = true) } }
            }
            row {
                link("Add variable") { _ ->
                    val name = Messages.showInputDialog(
                        component, "Name, as the template refers to it without the \$:", "Add Variable", null,
                    )?.trim()?.takeIf { it.isNotEmpty() } ?: return@link
                    attributes.addProperty(name, "")
                    changed(structural = true)
                }
                comment("Referred to as \$name in a template.")
            }
        }.apply { expanded = names.isNotEmpty() }
    }

    /** A key/value table, for the lookup and replace-all operations. */
    private fun table(attributes: JsonObject, name: String): JComponent {
        val json = attributes.child(name)
        val model = object : DefaultTableModel(arrayOf<Any>("Key", "Value"), 0) {
            override fun isCellEditable(row: Int, column: Int) = true
        }
        json.entrySet().forEach { (key, value) ->
            model.addRow(arrayOf<Any>(key, value.takeIf { it.isJsonPrimitive }?.asString.orEmpty()))
        }
        val table = JBTable(model).apply { preferredScrollableViewportSize = JBUI.size(400, 120) }
        model.addTableModelListener {
            json.keySet().toList().forEach(json::remove)
            repeat(model.rowCount) { row ->
                val key = model.getValueAt(row, 0)?.toString().orEmpty()
                if (key.isNotEmpty()) json.addProperty(key, model.getValueAt(row, 1)?.toString().orEmpty())
            }
            changed()
        }
        return ToolbarDecorator.createDecorator(table)
            .setAddAction { model.addRow(arrayOf<Any>("", "")) }
            .setRemoveAction { table.selectedRow.takeIf { it >= 0 }?.let(model::removeRow) }
            .createPanel()
    }

    private fun checkBox(text: String, selected: Boolean, onToggle: (Boolean) -> Unit) = JBCheckBox(text, selected).apply {
        addActionListener { onToggle(isSelected) }
    }

    /** The object at [name], replacing anything that isn't one so the form always has something to edit. */
    private fun JsonObject.child(name: String): JsonObject =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { add(name, it) }

    private fun JsonObject.list(name: String): JsonArray =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { add(name, it) }

    private fun JsonObject.flag(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean == true

    private fun textBox(initial: String, onEdit: (String) -> Unit) = JBTextField(initial).apply {
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onEdit(text)
        })
    }

    private fun show(label: JBLabel, result: EvalResult?) {
        when (result) {
            null -> {
                label.text = ""
            }
            is EvalResult.Value -> {
                label.text = result.text?.let { "→ \"$it\"" } ?: "→ nothing"
                label.foreground = if (result.text == null) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
            }
            is EvalResult.Needs -> {
                label.text = result.need.prompt
                label.foreground = UIUtil.getContextHelpForeground()
            }
            is EvalResult.Failure -> {
                label.text = result.message
                label.foreground = JBColor.RED
            }
        }
    }

    private fun JsonElement?.isTransform(): Boolean = this != null && isJsonObject && asJsonObject.has("type")

    private fun staticOf(value: String): JsonObject = JsonObject().apply {
        addProperty("type", "static")
        add("attributes", JsonObject().apply { addProperty("value", value) })
    }
}

/** Hangs the attribute's description under its field, where the JSON schema would have shown it in completion. */
private fun Row.help(attr: AttrDef): Row = also { if (attr.help.isNotEmpty()) rowComment(attr.help) }
