package com.sailpoint.intellij.explorer

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.sailpoint.intellij.editor.IscEditorService
import com.sailpoint.intellij.settings.IscSettings
import com.sailpoint.intellij.settings.IscTenant
import javax.swing.JComponent

class NewTransformDialog(private val project: Project, preselectedTenantId: String?) : DialogWrapper(project) {
    private val tenants = service<IscSettings>().tenants
    private var tenant: IscTenant? = tenants.find { it.id == preselectedTenantId } ?: tenants.firstOrNull()
    private var name = ""
    private var type: String? = "static"

    init {
        title = "New SailPoint Transform"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Tenant:") {
            comboBox(tenants, textListCellRenderer { it?.name }).bindItem(::tenant).align(AlignX.FILL)
        }
        row("Name:") {
            textField().bindText(::name).align(AlignX.FILL).focused()
                .validationOnApply { if (it.text.isBlank()) error("Name is required") else null }
        }
        row("Type:") {
            comboBox(TRANSFORM_TYPES).bindItem(::type)
        }
    }

    fun showAndCreate() {
        if (showAndGet()) {
            val tenantId = tenant?.id ?: return
            project.service<IscEditorService>().newTransform(tenantId, name.trim(), type ?: "static")
        }
    }

    companion object {
        val TRANSFORM_TYPES = listOf(
            "accountAttribute", "base64Decode", "base64Encode", "concat", "conditional", "dateCompare", "dateFormat",
            "dateMath", "decomposeDiacriticalMarks", "displayName", "e164phone", "firstValid", "getEndOfString",
            "getReferenceIdentityAttribute", "identityAttribute", "indexOf", "iso3166", "lastIndexOf", "leftPad",
            "lookup", "lower", "normalizeNames", "randomAlphaNumeric", "randomNumeric", "reference", "replace",
            "replaceAll", "rfc5646", "rightPad", "rule", "split", "static", "substring", "trim", "upper",
            "usernameGenerator", "uuid",
        )
    }
}
