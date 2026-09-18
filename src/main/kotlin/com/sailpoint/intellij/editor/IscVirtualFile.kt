package com.sailpoint.intellij.editor

import com.google.gson.JsonObject
import com.intellij.json.JsonFileType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.IscItem
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.api.string
import com.sailpoint.intellij.settings.IscSettings
import com.sailpoint.intellij.settings.IscTenant

/**
 * An in-memory editor for one ISC object.
 *
 * Transforms and read-only objects are shown as JSON. Connector rules are shown as their
 * BeanShell script only; the rest of the rule is kept in [remote] and sent back on push.
 */
class IscVirtualFile(
    val tenantId: String,
    val kind: ResourceKind,
    /** The object this one belongs to, for kinds with a [ResourceKind.parent]. */
    val owner: IscItem?,
    /** Null for an object (transform, schedule) that hasn't been created in ISC yet. */
    var remoteId: String?,
    /** The object as last received from (or sent to) ISC. */
    var remote: JsonObject,
) : LightVirtualFile(fileName(kind, owner, remote), fileType(kind), textFor(kind, remote)) {

    /** Editor text as of the last load or push, used to detect unpushed edits. */
    var syncedText: String = textFor(kind, remote)

    val objectName: String get() = kind.toItem(tenantId, remote, owner).name

    val tenant: IscTenant? get() = service<IscSettings>().findTenant(tenantId)

    init {
        isWritable = kind.editable
    }

    fun textFor(json: JsonObject): String = textFor(kind, json)

    companion object {
        fun textFor(kind: ResourceKind, json: JsonObject): String = when (kind) {
            ResourceKind.CONNECTOR_RULES -> json.getAsJsonObject("sourceCode")?.string("script").orEmpty()
            else -> IscClient.gson.toJson(json)
        }

        private fun fileType(kind: ResourceKind): FileType =
            if (kind == ResourceKind.CONNECTOR_RULES) PlainTextFileType.INSTANCE else JsonFileType.INSTANCE

        private fun fileName(kind: ResourceKind, owner: IscItem?, json: JsonObject): String {
            // Child objects like schemas share names across parents ("account"), so prefix the owner's name.
            val objectName = if (kind.singleton) kind.qualifiedName else kind.toItem("", json, owner).name
            val base = listOfNotNull(owner?.name, objectName)
                .joinToString(" - ")
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
            return if (kind == ResourceKind.CONNECTOR_RULES) "$base.bsh" else "$base.json"
        }
    }
}
