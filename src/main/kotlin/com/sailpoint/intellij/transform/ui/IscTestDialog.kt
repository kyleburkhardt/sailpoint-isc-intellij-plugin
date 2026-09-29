package com.sailpoint.intellij.transform.ui

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Computable
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.string
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Sets up a test in ISC: the identity to run on, the attribute to work the transform out as, and — when the transform
 * reads the value an identity profile would pass in — the source and account attribute that stands in for it.
 */
class IscTestDialog(
    project: Project,
    private val tenantId: String,
    private val previous: IscTestSetup?,
    private val needsInput: Boolean,
) : DialogWrapper(project) {

    private val model = CollectionListModel<JsonObject>()
    private val list = JBList(model)
    private val search = SearchTextField(false)
    private val status = JBLabel("Searching…")
    private val searches = Alarm(Alarm.ThreadToUse.POOLED_THREAD, disposable)
    private val names = IscTenantNames.of(tenantId)

    private val attribute = combo(previous?.attribute ?: DEFAULT_ATTRIBUTE)
    private val source = combo(previous?.inputSource.orEmpty())
    private val sourceAttribute = combo(previous?.inputAttribute.orEmpty())

    @Volatile
    private var running: EmptyProgressIndicator? = null
    private var generation = 0

    init {
        title = "Test in ISC"
        setOKButtonText("Run")
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = IdentityRenderer()
        list.emptyText.text = "Searching…"
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (list.selectedValue == null) return false
                doOKAction()
                return true
            }
        }.installOn(list)
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch(SEARCH_DELAY_MS)
        })
        // The identity tested last stays chosen until another is picked.
        previous?.let { model.add(identity(it.identityId, it.identityName)); list.selectedIndex = 0 }

        names.identityAttributes { attribute.offer(it) }
        names.sources { source.offer(it) }
        previous?.inputSource?.let { chosen -> names.accountAttributes(chosen) { sourceAttribute.offer(it) } }
        source.textField().document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val chosen = source.textField().text
                names.accountAttributes(chosen) { if (source.textField().text == chosen) sourceAttribute.offer(it) }
            }
        })

        init()
        scheduleSearch(if (previous == null) 0 else SEARCH_DELAY_MS)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        preferredSize = JBUI.size(520, 440)
        add(search, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        add(
            panel {
                row { cell(status) }
                row("Compute as:") {
                    cell(attribute).align(AlignX.FILL)
                        .comment("An identity attribute. Nothing is saved; its current value is shown for comparison.")
                }
                if (needsInput) {
                    row("Input from:") {
                        cell(source).align(AlignX.FILL).resizableColumn()
                        cell(sourceAttribute).align(AlignX.FILL).resizableColumn()
                    }.rowComment("This transform works on the value an identity profile passes in; this account attribute stands in for it.")
                }
            },
            BorderLayout.SOUTH,
        )
    }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun doValidate(): ValidationInfo? = when {
        list.selectedValue == null -> ValidationInfo("Choose an identity", list)
        attribute.textField().text.isBlank() -> ValidationInfo("Name an identity attribute", attribute)
        needsInput && source.textField().text.isBlank() -> ValidationInfo("Choose the source of the input", source)
        needsInput && sourceAttribute.textField().text.isBlank() -> ValidationInfo("Choose the account attribute", sourceAttribute)
        else -> null
    }

    /** The setup chosen, or null when cancelled. */
    fun showAndChoose(): IscTestSetup? {
        if (!showAndGet()) return null
        val chosen = list.selectedValue ?: return null
        return IscTestSetup(
            identityId = chosen.string("id") ?: return null,
            identityName = chosen.label(),
            attribute = attribute.textField().text.trim(),
            inputSource = source.textField().text.trim().takeIf { needsInput },
            inputAttribute = sourceAttribute.textField().text.trim().takeIf { needsInput },
        )
    }

    private fun scheduleSearch(delayMs: Int) {
        searches.cancelAllRequests()
        running?.cancel()
        val text = search.text.trim()
        val searchGeneration = ++generation
        status.text = "Searching…"
        searches.addRequest({ search(text, searchGeneration) }, delayMs)
    }

    private fun search(text: String, searchGeneration: Int) {
        val indicator = EmptyProgressIndicator().also { running = it }
        try {
            val found = ProgressManager.getInstance().runProcess(
                Computable { service<IscClient>().searchIdentities(tenantId, text, LIMIT) }, indicator,
            )
            onEdt { if (searchGeneration == generation) show(found, text) }
        } catch (e: ProcessCanceledException) {
            // A newer search replaced this one, or the dialog closed.
        } catch (e: Exception) {
            onEdt {
                if (searchGeneration == generation) {
                    status.text = "Couldn't search identities: ${e.message ?: e}"
                    list.emptyText.text = "Search failed"
                }
            }
        }
    }

    private fun show(found: List<JsonObject>, text: String) {
        val selectedId = list.selectedValue?.string("id")
        model.replaceAll(found)
        found.indexOfFirst { it.string("id") == selectedId }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        status.text = when {
            found.size >= LIMIT -> "Showing the first $LIMIT; type more of the name to narrow it down"
            text.isEmpty() -> "${found.size} identities"
            else -> "${found.size} matching \"$text\""
        }
        list.emptyText.text = if (text.isEmpty()) "No identities" else "No identities start with \"$text\""
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }

    override fun dispose() {
        running?.cancel()
        super.dispose()
    }

    private fun combo(initial: String): ComboBox<String> = ComboBox<String>().apply {
        isEditable = true
        textField().text = initial
    }

    private fun ComboBox<String>.textField(): JTextField = editor.editorComponent as JTextField

    private fun ComboBox<String>.offer(items: List<String>) {
        val typed = textField().text
        model = DefaultComboBoxModel(items.toTypedArray())
        textField().text = typed
    }

    private companion object {
        const val LIMIT = 50
        const val SEARCH_DELAY_MS = 300
        const val DEFAULT_ATTRIBUTE = "displayName"

        fun identity(id: String, name: String) = JsonObject().apply {
            addProperty("id", id)
            addProperty("displayName", name)
        }

        fun JsonObject.label(): String = string("displayName") ?: string("name") ?: string("id").orEmpty()
    }

    private class IdentityRenderer : ColoredListCellRenderer<JsonObject>() {
        override fun customizeCellRenderer(list: JList<out JsonObject>, value: JsonObject?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            append(value.label())
            value.string("name")?.takeIf { it != value.label() }?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            value.string("email")?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        }
    }
}
