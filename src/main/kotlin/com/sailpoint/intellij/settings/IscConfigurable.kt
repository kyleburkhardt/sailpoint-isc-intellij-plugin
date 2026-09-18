package com.sailpoint.intellij.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList

class IscConfigurable : Configurable {
    private val settings = service<IscSettings>()
    private val model = CollectionListModel<IscTenant>()
    private val list = JBList(model)

    /** Secrets entered in this session, applied on OK/Apply. */
    private val pendingSecrets = mutableMapOf<String, String>()

    override fun getDisplayName() = "SailPoint ISC"

    override fun createComponent(): JComponent {
        list.emptyText.text = "No tenants configured"
        list.cellRenderer = object : ColoredListCellRenderer<IscTenant>() {
            override fun customizeCellRenderer(list: JList<out IscTenant>, value: IscTenant, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.name)
                append("  ${value.baseUrl}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                editSelected()
                return true
            }
        }.installOn(list)

        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val dialog = TenantDialog(list, null, null)
                if (dialog.showAndGet()) {
                    model.add(dialog.result)
                    pendingSecrets[dialog.result.id] = dialog.resultSecret
                    list.selectedIndex = model.size - 1
                }
            }
            .setEditAction { editSelected() }
            .setRemoveAction { list.selectedValue?.let { pendingSecrets.remove(it.id); model.remove(it) } }
            .setMoveUpAction { move(-1) }
            .setMoveDownAction { move(1) }
            .createPanel()

        return panel {
            row {
                label("Identity Security Cloud tenants. Each tenant authenticates with its own Personal Access Token.")
            }
            row {
                cell(decorated).align(Align.FILL)
            }.resizableRow()
        }
    }

    private fun editSelected() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val tenant = model.getElementAt(index)
        val dialog = TenantDialog(list, tenant, pendingSecrets[tenant.id] ?: settings.loadSecret(tenant.id))
        if (dialog.showAndGet()) {
            model.setElementAt(dialog.result, index)
            pendingSecrets[tenant.id] = dialog.resultSecret
        }
    }

    private fun move(delta: Int) {
        val index = list.selectedIndex
        val target = index + delta
        if (index < 0 || target !in 0 until model.size) return
        model.exchangeRows(index, target)
        list.selectedIndex = target
    }

    override fun isModified(): Boolean = model.items != settings.tenants || pendingSecrets.isNotEmpty()

    override fun apply() {
        pendingSecrets.forEach { (id, secret) -> settings.saveSecret(id, secret) }
        pendingSecrets.clear()
        settings.setTenants(model.items)
    }

    override fun reset() {
        pendingSecrets.clear()
        model.replaceAll(settings.tenants)
    }
}
