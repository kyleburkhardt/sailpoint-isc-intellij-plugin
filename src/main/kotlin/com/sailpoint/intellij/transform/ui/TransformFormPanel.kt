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
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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
import com.sailpoint.intellij.transform.readsImplicitInput
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicComboPopup
import javax.swing.table.DefaultTableModel

/**
 * A transform as the steps it runs, top to bottom, with the value after each one down the right. Clicking a step opens
 * its settings underneath; everything else stays shut. The JSON behind it never appears.
 */
class TransformFormPanel(
    private val nameEditable: Boolean,
    /** Where the dropdowns get source and attribute names; without it they're plain text. */
    private val tenant: TenantNames? = null,
    private val onModelChanged: () -> Unit,
) {

    var model: JsonObject = JsonObject()
        private set

    private val inputs = TestInputPanel { refresh() }
    private val rows = object : JPanel(VerticalLayout(0)), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    private val updaters = mutableListOf<(Trace) -> Unit>()

    /** Running the transform in ISC, when the form belongs to a tenant; without it there's no ISC line. */
    var iscTest: IscTestActions? = null

    private var iscOutcome: IscTestOutcome? = null
    private val iscRow = JPanel(VerticalLayout(0)).apply { isOpaque = false }

    /** The one step whose settings are open, by its path; only one is open at a time. */
    private var opened: String? = null

    val component: JComponent = JBScrollPane(rows, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER).apply {
        border = JBUI.Borders.empty()
    }

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
        if (iscTest != null) {
            rows.add(iscRow)
            updaters += { trace -> fillIscRow(trace) }
        }

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
        }
        row {
            cell(
                checkBox("Re-evaluate nightly", model.flag("requiresPeriodicRefresh")) {
                    model.addProperty("requiresPeriodicRefresh", it)
                    changed()
                },
            )
        }
    }.apply { border = JBUI.Borders.empty(6, 8, 0, 8) }

    private fun divider(title: String): JComponent =
        TitledSeparator(title).apply { border = JBUI.Borders.empty(4, 8, 0, 8) }

    /** One step of the chain: its number, what it does, and what it produced. */
    private fun step(node: JsonObject, path: String, number: Int) {
        val earlier = node.attributes().get("input").isTransform()
        val later = path.isNotEmpty()
        val arrows = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(iconButton("Move up", AllIcons.Actions.MoveUp) { moveStep(path, up = true) }.apply { isEnabled = earlier })
            add(iconButton("Move down", AllIcons.Actions.MoveDown) { moveStep(path, up = false) }.apply { isEnabled = later })
        }
        // Holds the arrows' space while they're hidden, so the row doesn't shift when they appear.
        val moves = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = arrows.preferredSize
            add(arrows, BorderLayout.CENTER)
        }
        operation(node, path, indent = 0, lead = "$number", chained = true, moves = moves.takeIf { earlier || later })
    }

    /**
     * A row for one transform, wherever it sits. [chained] marks a step of the top-level chain, whose `input` is the
     * step before it rather than something to show here.
     */
    private fun operation(
        node: JsonObject,
        path: String,
        indent: Int,
        lead: String,
        chained: Boolean,
        moves: JComponent? = null,
        remove: (() -> Unit)? = null,
    ) {
        val op = TransformCatalog[node.string("type")]
        val attributes = node.attributes()
        val open = opened == path

        val onClick = { opened = if (open) null else path; rebuild() }
        val tenantStep = TENANT_STEPS[node.string("type")]
        rows.add(
            if (tenantStep != null) {
                tenantRow(node, attributes, tenantStep, indent, lead, path, open, moves, onClick)
            } else {
                row(
                    indent = indent,
                    lead = lead,
                    title = op?.label ?: node.string("type").orEmpty().ifEmpty { "Nothing yet" },
                    detail = summary(op, attributes),
                    path = path,
                    open = open,
                    moves = moves,
                    onClick = onClick,
                )
            },
        )
        if (open) rows.add(settings(node, attributes, op, path, chained, remove))
        branches(attributes, op, path, indent + 1, chained)
    }

    /** The values feeding an operation: its list entries, its other inputs, and any Velocity variables. */
    private fun branches(attributes: JsonObject, op: OpDef?, path: String, indent: Int, chained: Boolean) {
        val (input, others) = op?.attributes.orEmpty().partition { it.name == "input" }
        fun show(attr: AttrDef) = when {
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
        // What flows in, then the variables, then the template or values that use them — so a static's value is last.
        input.forEach(::show)
        val variables = attributes.keySet().filter { it != "input" && (op?.isVariable(it) ?: false) }
        variables.forEach { name -> slot(attributes, name, "\$$name", path, indent, removable = true) }
        // Variables are only offered where a template could use them, or where the transform already has some.
        if (op?.type == "static" || op?.type == "conditional" || variables.isNotEmpty()) rows.add(addVariable(attributes, indent))
        others.forEach(::show)
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
        if (element.isTransform()) {
            operation(element.asJsonObject, childPath, indent, "", chained = false) {
                list.set(index, JsonPrimitive(""))
                changed(structural = true)
            }
            return
        }
        rows.add(
            literal(
                indent = indent,
                label = null,
                value = element.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                onEdit = { list.set(index, JsonPrimitive(it)); changed() },
                onTransform = if (attr.kind == AttrKind.VALUE_LIST) ({ list.set(index, staticOf(it)); changed(structural = true) }) else null,
                onRemove = { list.remove(index); changed(structural = true) },
            ),
        )
    }

    /** Adds a variable below the others, above the template that uses them. */
    private fun addVariable(attributes: JsonObject, indent: Int): JComponent = panel {
        row {
            link("Add variable") { _ ->
                val name = Messages.showInputDialog(
                    this@TransformFormPanel.component, "Name, as a template refers to it without the \$:", "Add Variable", null,
                )?.trim()?.takeIf { it.isNotEmpty() } ?: return@link
                attributes.addProperty(name, "")
                // The JSON reads the same way as the form: variables first, the template after them.
                attributes.remove("value")?.let { attributes.add("value", it) }
                changed(structural = true)
            }
        }
    }.apply { border = JBUI.Borders.empty(2, indentOf(indent) + ARROW_WIDTH, 2, 8) }

    private fun addStep(): JComponent = panel {
        row {
            link("Add step") { _ -> appendStep() }
        }
    }.apply { border = JBUI.Borders.empty(4, indentOf(0) + JBUI.scale(20), 4, 8) }

    /** Shows how the last test in ISC went, under the preview's own result. */
    fun showIscOutcome(outcome: IscTestOutcome?) {
        iscOutcome = outcome
        refresh()
    }

    /**
     * ISC's answer for the transform, next to the preview's: the identity it ran on, its value and any errors, whether
     * it agrees with the preview, and whether the transform has changed since.
     */
    private fun fillIscRow(trace: Trace) {
        val actions = iscTest ?: return
        iscRow.removeAll()
        val outcome = iscOutcome
        val lines = mutableListOf<Pair<String, java.awt.Color>>()
        when (outcome) {
            null -> Unit
            is IscTestOutcome.Running -> lines += "ISC · ${outcome.setup.identityName}: running…" to UIUtil.getContextHelpForeground()
            is IscTestOutcome.Failed -> lines += "ISC · ${outcome.setup.identityName}: ${outcome.message}" to JBColor.RED
            is IscTestOutcome.Done -> {
                lines += "ISC · ${outcome.setup.identityName}: ${outcome.value?.let { "\"$it\"" } ?: "nothing"}" to UIUtil.getLabelForeground()
                outcome.errors.forEach { lines += it to JBColor.RED }
                outcome.previousValue?.let { lines += "Now in ${outcome.setup.attribute}: \"$it\"" to UIUtil.getContextHelpForeground() }
                val local = trace.result
                if (local is EvalResult.Value && local.text != outcome.value) {
                    lines += "The preview above differs from ISC." to UIUtil.getContextHelpForeground()
                }
            }
        }
        val sent = (outcome as? IscTestOutcome.Done)?.sent ?: (outcome as? IscTestOutcome.Failed)?.sent
        if (sent != null && sent != actions.current()) {
            lines += "Run before your latest edits." to UIUtil.getContextHelpForeground()
        }
        lines.forEach { (text, color) ->
            iscRow.add(
                JBTextArea(text).apply {
                    isEditable = false
                    isOpaque = false
                    lineWrap = true
                    border = JBUI.Borders.empty()
                    font = UIUtil.getLabelFont()
                    foreground = color
                },
            )
        }
        iscRow.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
                isOpaque = false
                if (outcome != null && outcome !is IscTestOutcome.Running) add(ActionLink("Run again") { actions.run() })
                add(ActionLink(if (outcome == null) "Test in ISC…" else "Change…") { actions.setUp() })
            },
        )
        iscRow.border = JBUI.Borders.empty(0, indentOf(0) + ARROW_WIDTH, 12, 8)
        iscRow.revalidate()
        iscRow.repaint()
    }

    /** The final result in full: wrapped rather than cut short, and selectable so it can be copied. */
    private fun resultRow(): JComponent {
        val value = JBTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = false
            border = JBUI.Borders.empty()
            font = UIUtil.getLabelFont()
        }
        updaters += { trace ->
            val result = trace.result
            value.text = when (result) {
                is EvalResult.Value -> result.text?.let { "\"$it\"" } ?: "nothing"
                is EvalResult.Needs -> result.need.prompt
                is EvalResult.Failure -> result.message
            }
            value.foreground = when {
                result is EvalResult.Failure -> JBColor.RED
                result is EvalResult.Value && result.text != null -> UIUtil.getLabelForeground()
                else -> UIUtil.getContextHelpForeground()
            }
        }
        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(0, indentOf(0) + ARROW_WIDTH, if (iscTest != null) 6 else 12, 8)
            addToCenter(value)
        }
    }

    // ------------------------------------------------------------- widgets

    /** A clickable row: what it is on the left, what it produced on the right. */
    private fun row(
        indent: Int,
        lead: String,
        title: String,
        detail: String,
        path: String,
        open: Boolean,
        moves: JComponent?,
        onClick: () -> Unit,
    ): JComponent {
        val value = JBLabel().apply { border = JBUI.Borders.emptyLeft(8) }
        updaters += { trace -> show(value, trace.at(path)?.result) }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight))
            if (lead.isNotEmpty()) add(JBLabel(lead).apply { foreground = UIUtil.getContextHelpForeground() })
            add(JBLabel(title))
            moves?.let(::add)
        }
        val summary = JBLabel(detail).apply {
            foreground = UIUtil.getContextHelpForeground()
            toolTipText = detail.takeIf { it.isNotEmpty() }
            minimumSize = JBUI.emptySize()
        }
        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(2, indentOf(indent), 2, 8)
            addToLeft(left)
            addToCenter(summary)
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
            moves?.let { revealOnHover(this, it, open) }
        }
    }

    /**
     * A step that reads from the tenant: its names as dropdowns on the row, and the test value to use for it where
     * other rows show their result.
     */
    private fun tenantRow(
        node: JsonObject,
        attributes: JsonObject,
        step: TenantStep,
        indent: Int,
        lead: String,
        path: String,
        open: Boolean,
        moves: JComponent?,
        onClick: () -> Unit,
    ): JComponent {
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight))
            if (lead.isNotEmpty()) add(JBLabel(lead).apply { foreground = UIUtil.getContextHelpForeground() })
            add(JBLabel(step.title).apply { toolTipText = TransformCatalog[node.string("type")]?.label })
            moves?.let(::add)
        }
        val names = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }
        when (node.string("type")) {
            "accountAttribute" -> {
                // Whichever way the transform names its source is the one edited; only display names are offered.
                val key = SOURCE_KEYS.firstOrNull { attributes.has(it) } ?: "sourceName"
                val attribute = nameCombo(attributes.string("attributeName").orEmpty(), "attribute", ATTRIBUTE_SCALE) {
                    attributes.addProperty("attributeName", it)
                    changed()
                }
                val source = nameCombo(attributes.string(key).orEmpty(), "source", SOURCE_SCALE) { typed ->
                    attributes.addProperty(key, typed)
                    changed()
                    if (key == "sourceName") {
                        tenant?.accountAttributes(typed) { offered ->
                            // Only for the source still chosen when the names arrive, and only once it's a known one.
                            if (attributes.string(key) != typed) return@accountAttributes
                            attribute.offer(offered)
                            if (offered.isNotEmpty() && attributes.string("attributeName").orEmpty() !in offered) attribute.clear()
                        }
                    }
                }
                if (key == "sourceName") {
                    tenant?.sources { source.offer(it) }
                    attributes.string(key)?.let { name -> tenant?.accountAttributes(name) { attribute.offer(it) } }
                }
                names.add(source)
                names.add(attribute)
            }
            "identityAttribute" -> names.add(
                nameCombo(attributes.string("name").orEmpty(), "attribute", ATTRIBUTE_SCALE) { attributes.addProperty("name", it); changed() }
                    .also { combo -> tenant?.identityAttributes { combo.offer(it) } },
            )
            "getReferenceIdentityAttribute" -> {
                names.add(
                    nameCombo(attributes.string("uid").orEmpty(), "identity") { attributes.addProperty("uid", it); changed() }
                        .also { it.offer(listOf("manager")) },
                )
                names.add(
                    nameCombo(attributes.string("attributeName").orEmpty(), "attribute", ATTRIBUTE_SCALE) { attributes.addProperty("attributeName", it); changed() }
                        .also { combo -> tenant?.identityAttributes { combo.offer(it) } },
                )
            }
        }

        val test = JBTextField().apply {
            emptyText.text = "test value"
        }
        var syncing = false
        test.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (syncing) return
                neededInputs(node).firstOrNull()?.let { inputs.set(it, test.text) }
            }
        })
        updaters += { trace ->
            // The same value can be read in two places, so a field shows what was typed in the other.
            val typed = neededInputs(node).firstOrNull()?.let(inputs::value).orEmpty()
            if (!test.hasFocus() && test.text != typed) {
                syncing = true
                test.text = typed
                syncing = false
            }
            val failure = trace.at(path)?.result as? EvalResult.Failure
            test.putClientProperty("JComponent.outline", failure?.let { "error" })
            test.toolTipText = failure?.message ?: "The value to preview with. ISC reads the real one from the tenant."
            test.repaint()
        }

        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(2, indentOf(indent), 2, 8)
            // The title sits level with the dropdowns rather than at the top of the row.
            addToLeft(JPanel(GridBagLayout()).apply { isOpaque = false; add(left) })
            addToCenter(
                BorderLayoutPanel().apply {
                    isOpaque = false
                    addToLeft(names)
                    addToCenter(BorderLayoutPanel().apply { isOpaque = false; border = JBUI.Borders.emptyLeft(6); addToCenter(test) })
                },
            )
            isOpaque = true
            background = UIUtil.getPanelBackground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
            })
            moves?.let { revealOnHover(this, it, open) }
        }
    }

    /** An editable dropdown of names; typing a name that isn't offered is fine, the list is only a help. */
    /** [scale] widens a dropdown beyond what its names need, for the ones that matter most on the row. */
    private fun nameCombo(initial: String, placeholder: String, scale: Float = 1f, onEdit: (String) -> Unit): ComboBox<String> =
        ComboBox<String>().apply {
            isEditable = true
            putClientProperty(WIDTH_SCALE, scale)
            val field = editor.editorComponent as? JTextField
            field?.text = initial
            (field as? JBTextField)?.emptyText?.text = placeholder
            toolTipText = initial.ifEmpty { null }
            fitNames(listOf(initial))
            addPopupMenuListener(object : PopupMenuListener {
                // The dropdown itself is narrow; its list is as wide as the names in it.
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                    val popup = ui.getAccessibleChild(this@apply, 0) as? BasicComboPopup ?: return
                    val scroller = UIUtil.findComponentOfType(popup, JScrollPane::class.java) ?: return
                    val width = maxOf(this@apply.width, popup.list.preferredSize.width + JBUI.scale(24))
                    scroller.preferredSize = Dimension(width, scroller.preferredSize.height)
                    scroller.maximumSize = scroller.preferredSize
                }

                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = Unit
                override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
            })
            field?.document?.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (getClientProperty(OFFERING) == true) return
                    toolTipText = field.text.ifEmpty { null }
                    onEdit(field.text)
                }
            })
        }

    /** Empties a dropdown, which clears the attribute it edits. */
    private fun ComboBox<String>.clear() {
        (editor.editorComponent as? JTextField)?.text = ""
    }

    /** Replaces the names a dropdown offers, keeping what's typed in it. */
    private fun ComboBox<String>.offer(names: List<String>) {
        val field = editor.editorComponent as? JTextField ?: return
        val typed = field.text
        putClientProperty(OFFERING, true)
        try {
            model = DefaultComboBoxModel(names.toTypedArray())
            field.text = typed
        } finally {
            putClientProperty(OFFERING, null)
        }
        fitNames(names + typed)
    }

    /**
     * Makes a dropdown wide enough for most of [names] — the longest few are left to the tooltip and the wider list,
     * so one very long name doesn't widen every row.
     */
    private fun ComboBox<String>.fitNames(names: List<String>) {
        val field = editor.editorComponent as? JTextField ?: return
        val metrics = field.getFontMetrics(field.font)
        val widths = names.filter { it.isNotEmpty() }.map { metrics.stringWidth(it) }.sorted()
        val text = if (widths.isEmpty()) 0 else widths[((widths.size - 1) * FIT_SHARE).toInt()]
        // The arrow button and the field's own insets come on top of the text.
        val chrome = JBUI.scale(ARROW_AND_INSETS)
        val scale = getClientProperty(WIDTH_SCALE) as? Float ?: 1f
        val width = ((text + chrome).coerceIn(JBUI.scale(MIN_NAME_WIDTH), JBUI.scale(MAX_NAME_WIDTH)) * scale).toInt()
        val size = Dimension(width, preferredSize.height)
        preferredSize = size
        minimumSize = size
        parent?.revalidate()
    }

    /**
     * Shows a step's move arrows only while the pointer is over its row, or while the step is open. [moves] keeps its
     * size either way; only the arrows inside it come and go.
     */
    private fun revealOnHover(row: JComponent, moves: JComponent, open: Boolean) {
        val arrows = moves.getComponent(0)
        arrows.isVisible = open
        if (open) return
        val tracker = object : MouseAdapter() {
            // Moving onto a child leaves the row, so check where the pointer really is.
            override fun mouseEntered(e: MouseEvent) = update()
            override fun mouseExited(e: MouseEvent) = update()

            fun update() {
                arrows.isVisible = row.getMousePosition(true) != null
            }
        }
        // Children that handle the mouse themselves (fields, dropdowns, tooltips) don't pass their exits on, so each is told.
        fun listen(component: java.awt.Component) {
            component.addMouseListener(tracker)
            (component as? java.awt.Container)?.components?.forEach(::listen)
        }
        listen(row)
    }

    /** A plain value, edited where it sits, with the value it contributes on the right. */
    private fun literal(
        indent: Int,
        label: String?,
        value: String,
        onEdit: (String) -> Unit,
        onTransform: ((String) -> Unit)?,
        onRemove: (() -> Unit)?,
    ): JComponent {
        val field = textBox(value, onEdit)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            onTransform?.let { add(iconButton("Use a transform", AllIcons.Nodes.Function) { it(field.text) }) }
            onRemove?.let { add(iconButton("Remove", AllIcons.Actions.Close) { it() }) }
        }
        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(2, indentOf(indent) + ARROW_WIDTH, 2, 8)
            label?.let { addToLeft(JBLabel(it).apply { border = JBUI.Borders.emptyRight(6) }) }
            addToCenter(field)
            addToRight(buttons)
        }
    }

    /** Everything about one step that isn't on its row: the operation itself and its plainer settings. */
    private fun settings(node: JsonObject, attributes: JsonObject, op: OpDef?, path: String, chained: Boolean, remove: (() -> Unit)?): JComponent =
        panel {
            row {
                cell(typeCombo(node, op))
                op?.let { browserLink("Docs", it.docsUrl).applyToComponent { toolTipText = it.summary } }
            }
            val onRow = TENANT_STEPS[node.string("type")]?.onRow.orEmpty()
            op?.attributes?.filterNot { it.name in onRow }?.forEach { attr ->
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
            val canRemove = remove != null || (chained && (path.isNotEmpty() || attributes.get("input").isTransform()))
            if (canRemove) {
                row {
                    if (remove != null) link("Plain value") { _ -> remove() }
                    else if (canRemove) link("Remove step") { _ -> removeStep(path) }
                }
            }
        }.apply {
            border = JBUI.Borders.empty(2, indentOf(1) + JBUI.scale(8), 6, 8)
        }

    private fun Panel.setting(attributes: JsonObject, attr: AttrDef) {
        val label = attr.label + if (attr.required) " *" else ""
        when (attr.kind) {
            AttrKind.BOOLEAN -> row {
                cell(checkBox(label, attributes.flag(attr.name)) { attributes.addProperty(attr.name, it); changed() })
                help(attr)
            }

            AttrKind.ENUM -> row("$label:") {
                cell(
                    ComboBox(attr.options.toTypedArray()).apply {
                        selectedItem = attributes.string(attr.name)
                        addActionListener { attributes.addProperty(attr.name, selectedItem as? String); changed() }
                    },
                )
                help(attr)
            }

            AttrKind.INTEGER -> row("$label:") {
                cell(
                    textBox(attributes.string(attr.name).orEmpty()) { typed ->
                        typed.trim().toIntOrNull()?.let { attributes.addProperty(attr.name, it) }
                            ?: attributes.addProperty(attr.name, typed)
                        changed()
                    },
                ).columns(6)
                help(attr)
            }

            AttrKind.TABLE -> row("$label:") {
                cell(table(attributes, attr.name)).align(AlignX.FILL).resizableColumn()
                help(attr)
            }

            else -> row("$label:") {
                cell(textBox(attributes.string(attr.name).orEmpty()) { attributes.addProperty(attr.name, it); changed() })
                    .align(AlignX.FILL)
                    .resizableColumn()
                help(attr)
            }
        }
    }

    private fun Row.help(attr: AttrDef) {
        if (attr.help.isNotEmpty()) contextHelp(attr.help)
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
        val table = JBTable(model).apply { preferredScrollableViewportSize = JBUI.size(220, 100) }
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

    /**
     * Swaps a step with the one before it ([up]) or after it. Each keeps its place in the chain — its `input` link —
     * and takes the other's operation and settings, so an explicit input at the start stays at the start.
     */
    internal fun moveStep(path: String, up: Boolean) {
        val otherPath = if (up) TransformEvaluator.join(path, "input") else path.substringBeforeLast('.', "")
        val step = nodeAt(path) ?: return
        val other = nodeAt(otherPath)?.takeIf { it !== step } ?: return
        val stepAttributes = step.attributes()
        val otherAttributes = other.attributes()
        val stepInput = stepAttributes.remove("input")
        val otherInput = otherAttributes.remove("input")
        val type = step.get("type")
        step.add("type", other.get("type"))
        other.add("type", type)
        step.add("attributes", otherAttributes.apply { stepInput?.let { add("input", it) } })
        other.add("attributes", stepAttributes.apply { otherInput?.let { add("input", it) } })
        if (opened == path) opened = otherPath
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
        label.icon = null
        when (result) {
            null -> {
                label.text = ""
                label.toolTipText = null
            }
            is EvalResult.Value -> {
                label.text = result.text?.let { "\"${clip(it)}\"" } ?: "nothing"
                label.toolTipText = result.text?.takeIf { it.length > VALUE_LENGTH }
                label.foreground = if (result.text == null) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
            }
            is EvalResult.Needs -> {
                label.text = "needs a value"
                label.toolTipText = result.need.prompt
                label.foreground = UIUtil.getContextHelpForeground()
            }
            is EvalResult.Failure -> {
                label.text = result.brief ?: clip(result.message)
                label.icon = AllIcons.General.Error
                label.toolTipText = result.message
                label.foreground = JBColor.RED
            }
        }
    }

    private fun clip(text: String): String = if (text.length > VALUE_LENGTH) text.take(VALUE_LENGTH - 1) + "…" else text

    private fun iconButton(tooltip: String, icon: Icon, onClick: () -> Unit) = InplaceButton(tooltip, icon) { onClick() }

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

    /** A step that reads from the tenant: its short title, and the attributes its row edits. */
    private class TenantStep(val title: String, val onRow: Set<String>)

    private companion object {
        val SOURCE_KEYS = listOf("sourceName", "applicationName", "applicationId")
        const val OFFERING = "sailpoint.transform.offering"
        const val WIDTH_SCALE = "sailpoint.transform.widthScale"
        const val SOURCE_SCALE = 1.25f
        const val ATTRIBUTE_SCALE = 2f
        const val MIN_NAME_WIDTH = 90
        const val MAX_NAME_WIDTH = 220
        const val ARROW_AND_INSETS = 36

        /** The share of names a dropdown is sized to show in full. */
        const val FIT_SHARE = 0.8
        val TENANT_STEPS = mapOf(
            "accountAttribute" to TenantStep("Account", SOURCE_KEYS.toSet() + "attributeName"),
            "identityAttribute" to TenantStep("Identity", setOf("name")),
            "getReferenceIdentityAttribute" to TenantStep("Reference", setOf("uid", "attributeName")),
        )

        const val SUMMARY_LENGTH = 40
        const val VALUE_LENGTH = 36
        val ARROW_WIDTH: Int get() = AllIcons.General.ArrowRight.iconWidth + JBUI.scale(6)
    }
}

/**
 * What the form's ISC line can do: run the test again as it was set up, or set it up afresh. [current] is the
 * transform as a test would send it now, to tell when the last result is out of date.
 */
class IscTestActions(val run: () -> Unit, val setUp: () -> Unit, val current: () -> String?)
