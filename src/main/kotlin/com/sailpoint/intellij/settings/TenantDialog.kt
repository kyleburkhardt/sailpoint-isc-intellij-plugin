package com.sailpoint.intellij.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.IscConnection
import java.awt.Component
import java.util.UUID
import javax.swing.JComponent

/**
 * Adds or edits one tenant. [secret] is the currently stored secret, or null if unknown;
 * after OK, [result] holds the edited tenant and [resultSecret] the entered secret.
 */
class TenantDialog(parent: Component, private val existing: IscTenant?, secret: String?) : DialogWrapper(parent, true) {
    private val nameField = JBTextField(existing?.name.orEmpty())
    private val tenantField = JBTextField(existing?.tenant.orEmpty())
    private val clientIdField = JBTextField(existing?.clientId.orEmpty())
    private val secretField = JBPasswordField().apply { text = secret.orEmpty() }

    lateinit var result: IscTenant
        private set
    lateinit var resultSecret: String
        private set

    init {
        title = if (existing == null) "Add SailPoint Tenant" else "Edit SailPoint Tenant"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Display name:") {
            cell(nameField).align(AlignX.FILL).focused()
                .comment("Shown in the SailPoint tool window, e.g. <code>Acme Sandbox</code>")
                .validationOnApply { if (it.text.isBlank()) error("Name is required") else null }
        }
        row("Tenant:") {
            cell(tenantField).align(AlignX.FILL)
                .comment("Tenant name (<code>acme</code>) or API URL (<code>https://acme.api.identitynow.com</code>)")
                .validationOnApply { if (it.text.isBlank()) error("Tenant is required") else null }
        }
        row("PAT client ID:") {
            cell(clientIdField).align(AlignX.FILL)
                .validationOnApply { if (it.text.isBlank()) error("Client ID is required") else null }
        }
        row("PAT client secret:") {
            cell(secretField).align(AlignX.FILL).comment("Stored in the IDE password safe.")
                .validationOnApply { if (it.password.isEmpty()) error("Client secret is required") else null }
        }
        row {
            button("Test Connection") { testConnection() }
        }
    }.withPreferredWidth(480)

    override fun doOKAction() {
        result = IscTenant(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = nameField.text.trim(),
            tenant = tenantField.text.trim(),
            clientId = clientIdField.text.trim(),
        )
        resultSecret = String(secretField.password)
        super.doOKAction()
    }

    private fun testConnection() {
        val connection = IscConnection(
            IscConnection.normalizeBaseUrl(tenantField.text),
            clientIdField.text.trim(),
            String(secretField.password),
        )
        if (connection.baseUrl.isEmpty() || connection.clientId.isEmpty() || connection.clientSecret.isEmpty()) {
            Messages.showWarningDialog(contentPanel, "Fill in the tenant, client ID and client secret first.", "SailPoint ISC")
            return
        }
        var error: String? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { error = runCatching { service<IscClient>().testConnection(connection) }.exceptionOrNull()?.message },
            "Connecting to ${connection.baseUrl}",
            true,
            null,
            contentPanel,
        )
        if (error == null) {
            Messages.showInfoMessage(contentPanel, "Connected to ${connection.baseUrl}.", "SailPoint ISC")
        } else {
            Messages.showErrorDialog(contentPanel, error, "SailPoint ISC")
        }
    }
}
