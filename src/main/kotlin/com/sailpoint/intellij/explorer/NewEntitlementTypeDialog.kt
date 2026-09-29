package com.sailpoint.intellij.explorer

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.sailpoint.intellij.api.ACCOUNT_SCHEMA
import com.sailpoint.intellij.api.IscItem
import com.sailpoint.intellij.editor.IscEditorService
import javax.swing.JComponent

/**
 * A new entitlement type on [source], i.e. a schema besides the account one. Asks for the essentials, then creates
 * it and opens it so the rest of its attributes can be added. [existing] are the source's current schema names.
 */
class NewEntitlementTypeDialog(private val project: Project, private val source: IscItem, private val existing: Set<String>) : DialogWrapper(project) {
    private var name = ""
    private var nativeObjectType = ""
    private var identityAttribute = ""
    private var displayAttribute = ""

    init {
        title = "New Entitlement Type for ${source.name}"
        setOKButtonText("Create")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            textField().bindText(::name).align(AlignX.FILL).focused()
                .comment("How ISC refers to the type, e.g. <code>group</code> or <code>role</code>.")
                .validationOnApply {
                    val typed = it.text.trim()
                    when {
                        typed.isEmpty() -> error("Name is required")
                        typed.equals(ACCOUNT_SCHEMA, ignoreCase = true) -> error("'$ACCOUNT_SCHEMA' is the account schema")
                        existing.any { e -> e.equals(typed, ignoreCase = true) } -> error("'${source.name}' already has '$typed'")
                        else -> null
                    }
                }
        }
        row("Native object type:") {
            textField().bindText(::nativeObjectType).align(AlignX.FILL)
                .comment("The object's type in the source system, e.g. <code>group</code>. Defaults to the name.")
        }
        row("Identity attribute:") {
            textField().bindText(::identityAttribute).align(AlignX.FILL)
                .comment("The attribute that uniquely identifies each object, e.g. <code>id</code> or <code>distinguishedName</code>.")
                .validationOnApply { if (it.text.isBlank()) error("Identity attribute is required") else null }
        }
        row("Display attribute:") {
            textField().bindText(::displayAttribute).align(AlignX.FILL)
                .comment("The attribute shown as each object's name. Defaults to the identity attribute.")
        }
    }

    fun showAndCreate() {
        if (!showAndGet()) return
        val identity = identityAttribute.trim()
        val display = displayAttribute.trim().ifEmpty { identity }
        val schema = JsonObject().apply {
            addProperty("name", name.trim())
            addProperty("nativeObjectType", nativeObjectType.trim().ifEmpty { name.trim() })
            addProperty("identityAttribute", identity)
            addProperty("displayAttribute", display)
            // Start with the attributes named above; more can be added in the editor that opens next.
            add("attributes", JsonArray().apply { listOf(identity, display).distinct().forEach { add(stringAttribute(it)) } })
        }
        project.service<IscEditorService>().createSchema(source, schema)
    }

    private fun stringAttribute(attribute: String) = JsonObject().apply {
        addProperty("name", attribute)
        addProperty("type", "STRING")
        addProperty("isMulti", false)
        addProperty("isEntitlement", false)
        addProperty("isGroup", false)
    }
}
