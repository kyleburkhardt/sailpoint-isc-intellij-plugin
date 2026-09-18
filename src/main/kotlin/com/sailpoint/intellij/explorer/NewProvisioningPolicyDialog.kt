package com.sailpoint.intellij.explorer

import com.google.gson.JsonObject
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selectedValueMatches
import com.sailpoint.intellij.api.CREATE_MACHINE_ACCOUNT
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.IscItem
import com.sailpoint.intellij.api.string
import com.sailpoint.intellij.editor.IscEditorService
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * Collects the basics of a new provisioning policy for [source]. A [machineAccount] policy covers a machine
 * account subtype; the others cover human accounts.
 */
class NewProvisioningPolicyDialog(
    private val project: Project,
    private val source: IscItem,
    private val machineAccount: Boolean,
) : DialogWrapper(project) {
    private val usageType = ComboBox(usageTypes(machineAccount).toTypedArray())
    private val subtypes = DefaultComboBoxModel<JsonObject>()
    private var subtypesLoaded = false
    private var name = ""
    private var description = ""
    private var useDefaultFields = true

    init {
        title = if (machineAccount) "New Machine Account Provisioning Policy for ${source.name}" else "New Provisioning Policy for ${source.name}"
        usageType.addItemListener { if (it.stateChange == ItemEvent.SELECTED && isMachineAccount()) loadSubtypes() }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Usage type:") {
            cell(usageType).align(AlignX.FILL)
        }
        row("Machine subtype:") {
            comboBox(subtypes, textListCellRenderer { it?.string("displayName") ?: it?.string("technicalName") })
                .align(AlignX.FILL)
                .validationOnApply { if (isMachineAccount() && it.selectedItem == null) error("A machine account policy needs a subtype") else null }
        }.visibleIf(usageType.selectedValueMatches { it == CREATE_MACHINE_ACCOUNT })
        row("Name:") {
            textField().bindText(::name).align(AlignX.FILL).focused()
                .validationOnApply { if (it.text.isBlank()) error("Name is required") else null }
        }
        row("Description:") {
            textField().bindText(::description).align(AlignX.FILL)
        }
        row {
            checkBox("Start from the connector's default fields").bindSelected(::useDefaultFields)
                .comment("ISC fills in the fields when it creates the policy, so the policy is created right away. Otherwise it opens unsaved with no fields.")
        }
    }

    private fun isMachineAccount() = usageType.selectedItem == CREATE_MACHINE_ACCOUNT

    private fun loadSubtypes() {
        if (subtypesLoaded) return
        try {
            val loaded = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { service<IscClient>().sourceSubtypes(source.tenantId, source.id) },
                "Loading Machine Subtypes of ${source.name}", true, project,
            )
            subtypes.addAll(loaded.sortedBy { it.string("displayName")?.lowercase() })
            subtypesLoaded = true
            if (loaded.isEmpty()) {
                Messages.showWarningDialog(project, "'${source.name}' has no machine account subtypes.", "New Provisioning Policy")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "Cannot Load Machine Subtypes")
        }
    }

    fun showAndCreate() {
        if (!showAndGet()) return
        val type = usageType.selectedItem as String
        val policy = JsonObject().apply {
            addProperty("name", name.trim())
            description.trim().takeIf { it.isNotEmpty() }?.let { addProperty("description", it) }
            addProperty("usageType", type)
            if (type == CREATE_MACHINE_ACCOUNT) (subtypes.selectedItem as? JsonObject)?.string("id")?.let { addProperty("subtypeId", it) }
        }
        project.service<IscEditorService>().newProvisioningPolicy(source, policy, useDefaultFields)
    }

    companion object {
        private val USAGE_TYPES = listOf(
            "CREATE", "UPDATE", "ENABLE", "DISABLE", "DELETE", "ASSIGN", "UNASSIGN", "CREATE_GROUP", "UPDATE_GROUP",
            "DELETE_GROUP", "EDIT_GROUP", "REGISTER", "CREATE_IDENTITY", "UPDATE_IDENTITY", "UNLOCK", "CHANGE_PASSWORD",
            CREATE_MACHINE_ACCOUNT,
        )

        private fun usageTypes(machineAccount: Boolean) =
            if (machineAccount) listOf(CREATE_MACHINE_ACCOUNT) else USAGE_TYPES - CREATE_MACHINE_ACCOUNT
    }
}
