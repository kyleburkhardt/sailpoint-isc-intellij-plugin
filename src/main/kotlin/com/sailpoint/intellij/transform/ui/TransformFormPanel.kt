package com.sailpoint.intellij.transform.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
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
import com.sailpoint.intellij.transform.readsImplicitInput
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableModel

/**
 * A transform as the steps it runs, top to bottom, with the value after each one down the right. Clicking a step opens
 * its settings underneath; everything else stays shut. The JSON behind it never appears.
 */
class TransformFormPanel(private val nameEditable: Boolean, private val onModelChanged: () -> Unit) {

    var model: JsonObject = JsonObject()
        private set

    private val inputs = TestInputPanel { refresh() }
    private val rows = JPanel(VerticalLayout(0))
    private val updaters = mutableListOf<(Trace) -> Unit>()

    /** The one step whose settings are open, by its path; only one is open at a time. */
    private var opened: String? = null

    val component: JComponent = JBScrollPane(rows).apply { border = JBUI.Borders.empty() }

    fun setModel(transform: JsonObject) {
        model = transform
        opened = null
        rebuild()
    }

    private fun rebuild() {
        updaters.clear()
        rows.removeAll()

        rows.add(heading())
        rows.add(inputs.component)
        rows.add(divider("Steps"))
        chain().forEachIndexed { index, (node, path) -> step(node, path, index + 1) }
        rows.add(addStep())
        rows.add(divider("Result"))
        rows.add(resultRow())

        rows.revalidate()
        rows.repaint()
        refresh()
    }

    private fun refresh() {
        inputs.update(neededInputs(model), readsImplicitInput(model))
        val trace = evaluate(model, inputs.context())
        updaters.forEach { it(trace) }
    }

    private fun changed(structural: Boolean = false) {
        onModelChanged()
        if (structural) rebuild() else refresh()
    }

    /**
     * The steps in the order they run. A transform is written inside out — each one's `input` is the one before it —
     * so the chain is collected from the outside in and then turned around.
     */
    private fun chain(): List<Pair<JsonObject, String>> {
        val steps = mutableListOf<Pair<JsonObject, String>>()
        var node: JsonObject? = model
        var path = ""
        while (node != null) {
            steps += node to path
            val input = node.attributes().get("input")
            node = input?.takeIf { it.isTransform() }?.asJsonObject
            path = TransformEvaluator.join(path, "input")
        }
        return steps.reversed()
    }

    // ---------------------------------------------------------------- rows

    /** The transform's own name and nightly refresh, above everything else. */
    private fun heading(): JComponent = panel {
        row("Name:") {
            if (nameEditable) {
                cell(textBox(model.string("name").orEmpty()) { model.addProperty("name", it); changed() }).align(AlignX.FILL)
            } else {
                // ISC won't rename a transform that already exists, so this is shown rather than offered.
                label(model.string("name").orEmpty())
            }
            cell(
                checkBox("Re-evaluate nightly", model.flag("requiresPeriodicRefresh")) {
                    model.addProperty("requiresPeriodicRefresh", it)
                    changed()
                },
            )
        }
    }.apply { border = JBUI.Borders.empty(8, 8, 0, 8) }

    private fun divider(title: String): JComponent =
        TitledSeparator(title).apply { border = JBUI.Borders.empty(8, 8, 0, 8) }

    /** One step of the chain: its number, what it does, and what it produced. */
    private fun step(node: JsonObject, path: String, number: Int) {
        operation(node, path, indent = 0, lead = "$number", chained = true)
    }

    /**
     * A row for one transform, wherever it sits. [chained] marks a step of the top-level chain, whose `input` is the
     * step before it rather than something to show here.
     */
    private fun operation(node: JsonObject, path: String, indent: Int, lead: String, chained: Boolean, remove: (() -> Unit)? = null) {
        val op = TransformCatalog[node.string("type")]
        val attributes = node.attributes()
        val open = opened == path

        rows.add(
            row(
                indent = indent,
                lead = lead,
                title = op?.label ?: node.string("type").orEmpty().ifEmpty { "Nothing yet" },
                detail = summary(op, attributes),
                path = path,
                open = open,
                onClick = { opened = if (open) null else path; rebuild() },
            ),
        )
        if (open) rows.add(settings(node, attributes, op, path, chained, remove))
        branches(attributes, op, path, indent + 1, chained)
    }

    /** The values feeding an operation: its list entries, its other inputs, and any Velocity variables. */
    private fun branches(attributes: JsonObject, op: OpDef?, path: String, indent: Int, chained: Boolean) {
        op?.attributes?.forEach { attr ->
            when {
                attr.name == "input" && chained -> Unit
                attr.kind == AttrKind.VALUE -> slot(attributes, attr.name, attr.label, path, indent, removable = false)
                attr.kind == AttrKind.VALUE_LIST || attr.kind == AttrKind.STRING_LIST -> {
                    val list = attributes.list(attr.name)
                    list.forEachIndexed { index, _ ->
                        item(list, index, attr, path, indent)
                    }
                }
                else -> Unit
            }
        }
        attributes.keySet().filter { it != "input" && (op?.isVariable(it) ?: false) }.forEach { name ->
            slot(attributes, name, "\$$name", path, indent, removable = true)
        }
    }

    /** An attribute that holds either a plain value, edited in place, or a whole transform of its own. */
    private fun slot(attributes: JsonObject, name: String, label: String, path: String, indent: Int, removable: Boolean) {
        val element = attributes.get(name)
        val childPath = TransformEvaluator.join(path, name)
        if (element.isTransform()) {
            operation(element.asJsonObject, childPath, indent, "$label:", chained = false) {
                attributes.addProperty(name, "")
                changed(structural = true)
            }
            return
        }
        rows.add(
            literal(
                indent = indent,
                label = "$label:",
                value = element?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                onEdit = { attributes.addProperty(name, it); changed() },
                onTransform = { attributes.add(name, staticOf(it)); changed(structural = true) },
                onRemove = if (removable) ({ attributes.remove(name); changed(structural = true) }) else null,
            ),
        )
    }

    /** One entry of a list attribute. */
    private fun item(list: JsonArray, index: Int, attr: AttrDef, path: String, indent: Int) {
        val element = list.get(index)
        val childPath = TransformEvaluator.join(path, "${attr.name}[$index]")
        val label = "${attr.label.trimEnd('s')} ${index + 1}:"
        if (element.isTransform()) {
            operation(element.asJsonObject, childPath, indent, label, chained = false) {
                list.set(index, JsonPrimitive(""))
                changed(structural = true)
            }
            return
        }
        rows.add(
            literal(
                indent = indent,
                label = label,
                value = element.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                onEdit = { list.set(index, JsonPrimitive(it)); changed() },
                onTransform = if (attr.kind == AttrKind.VALUE_LIST) ({ list.set(index, staticOf(it)); changed(structural = true) }) else null,
                onRemove = { list.remove(index); changed(structural = true) },
            ),
        )
    }

    private fun addStep(): JComponent = panel {
        row {
            link("Add a step") { _ -> appendStep() }
            comment("Runs on whatever the steps above produced.")
        }
    }.apply { border = JBUI.Borders.empty(4, indentOf(0) + JBUI.scale(20), 4, 8) }

    private fun resultRow(): JComponent {
        val value = JBLabel()
        updaters += { trace -> show(value, trace.result) }
        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(0, indentOf(0) + JBUI.scale(20), 12, 8)
            addToCenter(value)
        }
    }

    // ------------------------------------------------------------- widgets

    /** A clickable row: what it is on the left, what it produced on the right. */
    private fun row(indent: Int, lead: String, title: String, detail: String, path: String, open: Boolean, onClick: () -> Unit): JComponent {
        val value = JBLabel()
        updaters += { trace -> show(value, trace.at(path)?.result) }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight))
            add(JBLabel(lead).apply { foreground = UIUtil.getContextHelpForeground() })
            add(JBLabel(title))
            if (detail.isNotEmpty()) add(JBLabel(detail).apply { foreground = UIUtil.getContextHelpForeground() })
        }
        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(3, indentOf(indent), 3, 8)
            addToCenter(left)
            addToRight(value)
            isOpaque = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onClick()

                    override fun mouseEntered(e: MouseEvent) {
                        background = hover
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        background = UIUtil.getPanelBackground()
                        repaint()
                    }
                },
            )
            background = UIUtil.getPanelBackground()
        }
    }

    /** A plain value, edited where it sits, with the value it contributes on the right. */
    private fun literal(
        indent: Int,
        label: String,
        value: String,
        onEdit: (String) -> Unit,
        onTransform: ((String) -> Unit)?,
        onRemove: (() -> Unit)?,
    ): JComponent {
        val field = textBox(value, onEdit)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            onTransform?.let { add(linkLabel("Use a transform") { it(field.text) }) }
            onRemove?.let { add(linkLabel("Remove") { it() }) }
        }
        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(3, indentOf(indent), 3, 8)
            addToLeft(JBLabel(label).apply { border = JBUI.Borders.emptyRight(6) })
            addToCenter(field)
            addToRight(buttons)
        }
    }

    /** Everything about one step that isn't on its row: the operation itself and its plainer settings. */
    private fun settings(node: JsonObject, attributes: JsonObject, op: OpDef?, path: String, chained: Boolean, remove: (() -> Unit)?): JComponent =
        panel {
            row("Operation:") {
                cell(typeCombo(node, op))
                op?.let { browserLink("What it does", it.docsUrl) }
            }
            op?.attributes?.forEach { attr ->
                when (attr.kind) {
                    AttrKind.VALUE -> Unit
                    AttrKind.VALUE_LIST, AttrKind.STRING_LIST -> row {
                        link("Add ${attr.label.trimEnd('s').lowercase()}") { _ ->
                            attributes.list(attr.name).add(JsonPrimitive(""))
                            changed(structural = true)
                        }
                    }
                    else -> setting(attributes, attr)
                }
            }
            // Variables are only offered where a template could use them, or where the transform already has some.
            val takesVariables = op?.type == "static" || op?.type == "conditional" ||
                attributes.keySet().any { it != "input" && op?.isVariable(it) == true }
            val canRemove = remove != null || (chained && (path.isNotEmpty() || attributes.get("input").isTransform()))
            if (takesVariables || canRemove) {
                row {
                    if (takesVariables) {
                        link("Add variable") { _ ->
                            val name = Messages.showInputDialog(
                                this@TransformFormPanel.component, "Name, as a template refers to it without the \$:", "Add Variable", null,
                            )?.trim()?.takeIf { it.isNotEmpty() } ?: return@link
                            attributes.addProperty(name, "")
                            changed(structural = true)
                        }
                    }
                    if (remove != null) link("Use a plain value") { _ -> remove() }
                    else if (canRemove) link("Remove this step") { _ -> removeStep(path) }
                }
            }
        }.apply {
            border = JBUI.Borders.empty(4, indentOf(1) + JBUI.scale(20), 8, 8)
        }

    private fun Panel.setting(attributes: JsonObject, attr: AttrDef) {
        val label = attr.label + if (attr.required) " *" else ""
        when (attr.kind) {
            AttrKind.BOOLEAN -> row {
                cell(checkBox(label, attributes.flag(attr.name)) { attributes.addProperty(attr.name, it); changed() })
                    .comment(attr.help)
            }

            AttrKind.ENUM -> row("$label:") {
                cell(
                    ComboBox(attr.options.toTypedArray()).apply {
                        selectedItem = attributes.string(attr.name)
                        addActionListener { attributes.addProperty(attr.name, selectedItem as? String); changed() }
                    },
                ).comment(attr.help)
            }

            AttrKind.INTEGER -> row("$label:") {
                cell(
                    textBox(attributes.string(attr.name).orEmpty()) { typed ->
                        typed.trim().toIntOrNull()?.let { attributes.addProperty(attr.name, it) }
                            ?: attributes.addProperty(attr.name, typed)
                        changed()
                    },
                ).columns(8).comment(attr.help)
            }

            AttrKind.TABLE -> row("$label:") {
                cell(table(attributes, attr.name)).align(AlignX.FILL).comment(attr.help)
            }

            else -> row("$label:") {
                cell(textBox(attributes.string(attr.name).orEmpty()) { attributes.addProperty(attr.name, it); changed() })
                    .align(AlignX.FILL)
                    .comment(attr.help)
            }
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

    /** A key/value table, for the lookup and replace-all operations. */
    private fun table(attributes: JsonObject, name: String): JComponent {
        val json = attributes.child(name)
        val model = object : DefaultTableModel(arrayOf<Any>("Key", "Value"), 0) {
            override fun isCellEditable(row: Int, column: Int) = true
        }
        json.entrySet().forEach { (key, value) ->
            model.addRow(arrayOf<Any>(key, value.takeIf { it.isJsonPrimitive }?.asString.orEmpty()))
        }
        val table = JBTable(model).apply { preferredScrollableViewportSize = JBUI.size(360, 110) }
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

    // --------------------------------------------------------------- model

    /** Adds a step at the end of the chain, running on what the steps before it produced. */
    private fun appendStep() {
        val previous = JsonObject().apply {
            add("type", model.get("type"))
            add("attributes", model.attributes())
        }
        model.addProperty("type", "upper")
        model.add("attributes", JsonObject().apply { add("input", previous) })
        opened = ""
        changed(structural = true)
    }

    /** Takes a step out of the chain, joining what fed it to what followed it. */
    private fun removeStep(path: String) {
        val step = nodeAt(path) ?: return
        val inner = step.attributes().get("input")?.takeIf { it.isTransform() }?.asJsonObject
        when {
            // Something feeds this step, so that takes its place.
            inner != null -> {
                step.add("type", inner.get("type"))
                step.add("attributes", inner.attributes())
            }
            // The first step of the chain: the one after it now runs on the incoming value itself.
            path.isNotEmpty() -> nodeAt(path.substringBeforeLast('.', ""))?.attributes()?.remove("input")
            // The only step there is; a transform has to do something.
            else -> return
        }
        opened = null
        changed(structural = true)
    }

    /** The transform at [path], which is only ever a chain of `input` attributes. */
    private fun nodeAt(path: String): JsonObject? {
        var node: JsonObject? = model
        if (path.isNotEmpty()) {
            for (part in path.split('.')) {
                node = node?.attributes()?.get(part)?.takeIf { it.isTransform() }?.asJsonObject
            }
        }
        return node
    }

    /** Switches an operation, keeping the attributes the new one also has and starting the rest off empty. */
    private fun retype(node: JsonObject, op: OpDef) {
        val old = node.attributes()
        val kept = TransformCatalog.starterAttributes(op.type)
        old.entrySet().filter { (name, _) -> op.attribute(name) != null || name == "input" }
            .forEach { (name, value) -> kept.add(name, value) }
        node.addProperty("type", op.type)
        node.add("attributes", kept)
    }

    // -------------------------------------------------------------- pieces

    /** What a step does, in a few words, so a shut step still says something. */
    private fun summary(op: OpDef?, attributes: JsonObject): String {
        op ?: return ""
        val named = op.attributes.count { it.name != "input" } > 1
        val parts = op.attributes.filter { it.name != "input" }.mapNotNull { attr ->
            val element = attributes.get(attr.name)?.takeUnless { it.isJsonNull } ?: return@mapNotNull null
            val shown = when (attr.kind) {
                // These are rows of their own, so they're not repeated here.
                AttrKind.VALUE, AttrKind.VALUE_LIST -> null
                AttrKind.STRING_LIST -> element.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { "$it" }
                AttrKind.TABLE -> element.takeIf { it.isJsonObject }?.asJsonObject?.size()?.let { "$it entries" }
                AttrKind.BOOLEAN -> if (attributes.flag(attr.name)) "" else null
                else -> element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    ?.let { if (it.isString) it.asString.takeIf(String::isNotEmpty)?.let { text -> "\"$text\"" } else it.asString }
            } ?: return@mapNotNull null
            if (named) "${attr.label.lowercase()} $shown".trim() else shown
        }
        val text = parts.joinToString("  ")
        return if (text.length > SUMMARY_LENGTH) text.take(SUMMARY_LENGTH - 1) + "…" else text
    }

    private fun show(label: JBLabel, result: EvalResult?) {
        when (result) {
            null -> label.text = ""
            is EvalResult.Value -> {
                label.text = result.text?.let { "\"$it\"" } ?: "nothing"
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

    private fun linkLabel(text: String, onClick: () -> Unit) = ActionLink(text) { onClick() }

    private fun checkBox(text: String, selected: Boolean, onToggle: (Boolean) -> Unit) = JBCheckBox(text, selected).apply {
        addActionListener { onToggle(isSelected) }
    }

    private fun textBox(initial: String, onEdit: (String) -> Unit) = JBTextField(initial).apply {
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onEdit(text)
        })
    }

    private fun indentOf(level: Int): Int = JBUI.scale(8 + level * 18)

    private fun JsonObject.attributes(): JsonObject = child("attributes")

    /** The object at [name], replacing anything that isn't one so the form always has something to edit. */
    private fun JsonObject.child(name: String): JsonObject =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { add(name, it) }

    private fun JsonObject.list(name: String): JsonArray =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { add(name, it) }

    private fun JsonObject.flag(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean == true

    private fun JsonElement?.isTransform(): Boolean = this != null && isJsonObject && asJsonObject.has("type")

    private fun staticOf(value: String): JsonObject = JsonObject().apply {
        addProperty("type", "static")
        add("attributes", JsonObject().apply { addProperty("value", value) })
    }

    private val hover: JBColor get() = JBColor.namedColor("Table.stripeColor", JBColor(0xF5F5F5, 0x3C3F41))

    private companion object {
        const val SUMMARY_LENGTH = 60
    }
}
