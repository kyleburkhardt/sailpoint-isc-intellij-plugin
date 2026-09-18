package com.sailpoint.intellij.explorer

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
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
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.string
import com.sailpoint.intellij.editor.IscEditorService
import com.sailpoint.intellij.settings.IscSettings
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * First step of New Source: pick the connector. Loading every connector is slow, so the dialog asks ISC only
 * for the ones whose name contains what's typed, a moment after typing stops.
 */
class NewSourceDialog(private val project: Project, private val tenantId: String) : DialogWrapper(project) {
    private val model = CollectionListModel<JsonObject>()
    private val list = JBList(model)
    private val search = SearchTextField(false)
    private val status = JBLabel("Searching…")
    private val searches = Alarm(Alarm.ThreadToUse.POOLED_THREAD, disposable)
    /** The search in flight, cancelled when a newer one starts. */
    @Volatile
    private var running: EmptyProgressIndicator? = null
    /** Bumped for every search, so late results of an older one are ignored. */
    private var generation = 0

    init {
        title = "New Source: Choose Connector"
        setOKButtonText("Next")
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ConnectorRenderer()
        list.emptyText.text = "Searching…"
        list.addListSelectionListener { isOKActionEnabled = list.selectedValue != null }
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
        isOKActionEnabled = false
        init()
        scheduleSearch(0)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        preferredSize = JBUI.size(560, 460)
        add(search, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun doValidate(): ValidationInfo? = if (list.selectedValue == null) ValidationInfo("Choose a connector", list) else null

    /** Searches for what's typed after [delayMs], replacing any search still waiting or running. */
    private fun scheduleSearch(delayMs: Int) {
        searches.cancelAllRequests()
        running?.cancel()
        val text = search.text.trim()
        val searchGeneration = ++generation
        status.text = "Searching…"
        searches.addRequest({ search(text, searchGeneration) }, delayMs)
    }

    /** Runs on a pooled thread. */
    private fun search(text: String, searchGeneration: Int) {
        val indicator = EmptyProgressIndicator().also { running = it }
        try {
            val found = ProgressManager.getInstance().runProcess(
                Computable { service<IscClient>().searchConnectors(tenantId, text, LIMIT) }, indicator,
            )
            onEdt { if (searchGeneration == generation) show(found, text) }
        } catch (e: ProcessCanceledException) {
            // A newer search replaced this one, or the dialog closed.
        } catch (e: Exception) {
            val tenantName = service<IscSettings>().findTenant(tenantId)?.name ?: "tenant"
            onEdt {
                if (searchGeneration == generation) {
                    model.removeAll()
                    status.text = "Couldn't search connectors in $tenantName: ${e.message ?: e}"
                    list.emptyText.text = "Search failed"
                }
            }
        }
    }

    /** Shows search results, keeping the selection if it's still among them. */
    private fun show(found: List<JsonObject>, text: String) {
        val selected = list.selectedValue
        model.replaceAll(found)
        selected?.let { list.setSelectedValue(it, true) }
        status.text = when {
            found.size >= LIMIT -> "Showing the first $LIMIT; type more of the name to narrow it down"
            text.isEmpty() -> "${found.size} connectors"
            else -> "${found.size} matching \"$text\""
        }
        list.emptyText.text = if (text.isEmpty()) "No connectors" else "No connector names contain \"$text\""
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }

    override fun dispose() {
        running?.cancel()
        super.dispose()
    }

    fun showAndChoose() {
        if (showAndGet()) list.selectedValue?.let { project.service<IscEditorService>().showConnector(tenantId, it) }
    }

    private companion object {
        const val LIMIT = 50
        const val SEARCH_DELAY_MS = 300

        /** The connector's one-line description, when it has one. */
        fun JsonObject.description(): String? =
            get("connectorMetadata")?.takeIf { it.isJsonObject }?.asJsonObject?.string("shortDesc")
    }

    private class ConnectorRenderer : ColoredListCellRenderer<JsonObject>() {
        override fun customizeCellRenderer(list: JList<out JsonObject>, value: JsonObject?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            append(value.string("name") ?: value.string("scriptName") ?: "(unnamed)")
            value.string("type")?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            value.description()?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        }
    }
}
