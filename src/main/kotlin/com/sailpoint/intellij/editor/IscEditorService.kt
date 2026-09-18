package com.sailpoint.intellij.editor

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotifications
import com.sailpoint.intellij.api.IscApiException
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.IscItem
import com.sailpoint.intellij.api.ResourceKind
import com.sailpoint.intellij.api.string
import com.sailpoint.intellij.schedule.EditScheduleDialog
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class IscEditorService(private val project: Project) {
    private data class Key(val tenantId: String, val kind: ResourceKind, val parentId: String?, val id: String)

    private val openFiles = ConcurrentHashMap<Key, IscVirtualFile>()

    /** Opens [item] in an editor, reusing an already-open tab for the same object. */
    fun open(item: IscItem) {
        val key = Key(item.tenantId, item.kind, item.parent?.id, item.id)
        openFiles[key]?.takeIf { FileEditorManager.getInstance(project).isFileOpen(it) }?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
            return
        }
        background("Loading ${item.kind.singularName} ${item.name}") {
            val json = try {
                service<IscClient>().fetch(item.tenantId, item.kind, item.parent?.id, item.id)
            } catch (e: IscApiException) {
                if (e.status != 404 || !item.kind.singleton) throw e
                onEdt { openUnconfigured(item) }
                return@background
            }
            onEdt {
                val file = IscVirtualFile(item.tenantId, item.kind, item.parent, item.id, json)
                openFiles[key] = file
                FileEditorManager.getInstance(project).openFile(file, true)
            }
        }
    }

    /**
     * Opens a starter for a one-per-parent config ISC has none of yet; pushing it creates the config. Configs ISC only
     * accepts patches for can't be created that way, so for those this just says so.
     */
    private fun openUnconfigured(item: IscItem) {
        val owner = item.parent?.let { " on ${it.kind.singularName} '${it.name}'" }.orEmpty()
        if (item.kind.patchUpdated) {
            Messages.showInfoMessage(
                project, "ISC has no ${item.kind.singularName}$owner yet, and it can only be changed, not created, from here.",
                item.kind.displayName,
            )
            return
        }
        val file = IscVirtualFile(item.tenantId, item.kind, item.parent, null, starter(item.kind))
        openFiles[Key(item.tenantId, item.kind, item.parent?.id, item.id)] = file
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** The smallest valid config of a one-per-parent kind, as a starting point. */
    private fun starter(kind: ResourceKind): JsonObject = JsonObject().apply {
        when (kind) {
            ResourceKind.SOURCE_CORRELATION -> add("attributeAssignments", JsonArray())
            ResourceKind.SOURCE_ATTRIBUTE_SYNC -> add("attributes", JsonArray())
            ResourceKind.SOURCE_NATIVE_CHANGE_DETECTION -> {
                addProperty("enabled", false)
                add("operations", JsonArray())
                addProperty("allEntitlements", false)
                addProperty("allNonEntitlementAttributes", false)
            }
            else -> Unit
        }
    }

    fun newTransform(tenantId: String, name: String, type: String) {
        val json = JsonObject().apply {
            addProperty("name", name)
            addProperty("type", type)
            add("attributes", JsonObject())
        }
        FileEditorManager.getInstance(project).openFile(IscVirtualFile(tenantId, ResourceKind.TRANSFORMS, null, null, json), true)
    }

    /** Opens an unsaved schedule of [type] for [source]; pushing it creates the schedule. */
    fun newSchedule(source: IscItem, type: String, cronExpression: String) {
        val json = JsonObject().apply {
            addProperty("type", type)
            addProperty("cronExpression", cronExpression)
        }
        FileEditorManager.getInstance(project).openFile(IscVirtualFile(source.tenantId, ResourceKind.SOURCE_SCHEDULES, source, null, json), true)
    }

    /**
     * Starts a provisioning policy [policy] for [source]. With [useDefaultFields] ISC fills in the connector's default
     * fields on creation, so the policy is created right away and then opened; otherwise it opens unsaved.
     */
    fun newProvisioningPolicy(source: IscItem, policy: JsonObject, useDefaultFields: Boolean) {
        val kind = ResourceKind.SOURCE_PROVISIONING_POLICIES
        if (!useDefaultFields) {
            policy.add("fields", JsonArray())
            FileEditorManager.getInstance(project).openFile(IscVirtualFile(source.tenantId, kind, source, null, policy), true)
            return
        }
        val name = kind.toItem(source.tenantId, policy, source).name
        background("Creating provisioning policy $name on ${source.name}") {
            val client = service<IscClient>()
            val path = client.collectionPath(kind, source.id) + "?useDefaultFields=true"
            val json = client.post(source.tenantId, path, policy, kind.experimental).asJsonObject
            onEdt {
                val id = kind.toItem(source.tenantId, json, source).id
                val file = IscVirtualFile(source.tenantId, kind, source, id, json)
                openFiles[Key(source.tenantId, kind, source.id, id)] = file
                FileEditorManager.getInstance(project).openFile(file, true)
                notify("Provisioning policy '${file.objectName}' created on ${source.name}.", NotificationType.INFORMATION)
                announce(source.tenantId, kind, source.id)
            }
        }
    }

    /** Edits the `cronExpression` of an open schedule in the schedule builder; the change is pushed like any other edit. */
    fun editSchedule(file: IscVirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val json = runCatching { JsonParser.parseString(document.text).asJsonObject }.getOrNull() ?: run {
            Messages.showErrorDialog(project, "Fix the schedule's JSON before editing it here.", "Edit Schedule")
            return
        }
        val dialog = EditScheduleDialog(project, file.tenantId, file.objectName, json.string("cronExpression").orEmpty())
        if (!dialog.showAndGet() || dialog.cronExpression == json.string("cronExpression")) return
        json.addProperty("cronExpression", dialog.cronExpression)
        WriteCommandAction.runWriteCommandAction(project, "Edit Schedule", null, { document.setText(IscClient.gson.toJson(json)) })
    }

    /**
     * Deletes [item] in ISC and closes its editor if it's open. For a one-per-parent config this removes the
     * configuration, and the entry stays where it is.
     */
    fun delete(item: IscItem) {
        val what = item.kind.singularName.replaceFirstChar { it.uppercase() }
        background(if (item.kind.singleton) "Removing ${item.kind.singularName}" else "Deleting ${item.kind.singularName} ${item.name}") {
            val client = service<IscClient>()
            client.delete(item.tenantId, client.objectPath(item.kind, item.parent?.id, item.id), item.kind.experimental)
            onEdt {
                openFiles.remove(Key(item.tenantId, item.kind, item.parent?.id, item.id))
                    ?.let { FileEditorManager.getInstance(project).closeFile(it) }
                val owner = item.parent?.let { " from ${it.kind.singularName} '${it.name}'" }.orEmpty()
                notify(if (item.kind.singleton) "$what removed$owner." else "$what '${item.name}' deleted$owner.", NotificationType.INFORMATION)
                announce(item.tenantId, item.kind, item.parent?.id)
            }
        }
    }

    /** Shows a connector's details and source configuration, which a guided New Source will be built from. */
    fun showConnector(tenantId: String, connector: JsonObject) {
        val name = connector.string("name") ?: connector.string("scriptName") ?: "connector"
        val scriptName = connector.string("scriptName") ?: run {
            showReadOnly("$name connector.json", IscClient.gson.toJson(connector))
            return
        }
        background("Loading source config of $name") {
            val xml = service<IscClient>().connectorSourceConfig(tenantId, scriptName)
            onEdt {
                showReadOnly("$name connector.json", IscClient.gson.toJson(connector))
                showReadOnly("$name source-config.xml", xml)
            }
        }
    }

    /** Opens [json] in a read-only JSON tab named [title], for responses that are looked at rather than edited. */
    fun showJson(title: String, json: JsonElement) = showReadOnly("$title.json", IscClient.gson.toJson(json))

    /** Opens [text] in a read-only tab; the extension of [fileName] picks the highlighting. */
    private fun showReadOnly(fileName: String, text: String) {
        val safeName = fileName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val file = LightVirtualFile(safeName, FileTypeManager.getInstance().getFileTypeByFileName(safeName), text)
        file.isWritable = false
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun hasLocalChanges(file: IscVirtualFile): Boolean = documentText(file) != file.syncedText

    /** Sends the editor contents to ISC, creating the object if it has no ID yet. */
    fun push(file: IscVirtualFile) {
        if (!file.kind.editable) return
        val text = documentText(file)
        val body = try {
            requestBody(file, text)
        } catch (e: IllegalStateException) {
            Messages.showErrorDialog(project, e.message, "Cannot Push to SailPoint ISC")
            return
        }
        val tenant = file.tenant ?: run {
            Messages.showErrorDialog(project, "The tenant this ${file.kind.singularName} came from was removed from settings.", "Cannot Push to SailPoint ISC")
            return
        }
        val creating = file.remoteId == null
        background("${if (creating) "Creating" else "Pushing"} ${file.kind.singularName} ${file.objectName} in ${tenant.name}") {
            val client = service<IscClient>()
            val id = file.remoteId
            val response = when {
                // A one-per-parent config is created by writing it at its own path, like an update.
                id == null && !file.kind.singleton ->
                    client.post(file.tenantId, client.collectionPath(file.kind, file.owner?.id), body, file.kind.experimental)
                body is JsonArray ->
                    client.patch(file.tenantId, client.objectPath(file.kind, file.owner?.id, id.orEmpty()), body, file.kind.experimental)
                else -> client.put(file.tenantId, client.objectPath(file.kind, file.owner?.id, id.orEmpty()), body, file.kind.experimental)
            }
            onEdt {
                val json = response.asJsonObject
                file.remote = json
                // Keep an existing ID: singletons like correlation config are keyed by their owner, not the ID ISC returns.
                file.remoteId = file.remoteId ?: if (file.kind.singleton) "" else file.kind.toItem(file.tenantId, json).id.ifEmpty { null }
                file.remoteId?.let { openFiles[Key(file.tenantId, file.kind, file.owner?.id, it)] = file }
                if (creating) announce(file.tenantId, file.kind, file.owner?.id)
                // ISC normalizes what it stores; show that, unless the user kept typing meanwhile.
                replaceText(file, text, file.textFor(json))
                notify("${file.kind.singularName.replaceFirstChar { it.uppercase() }} '${file.objectName}' saved to ${tenant.name}.", NotificationType.INFORMATION)
            }
        }
    }

    fun reload(file: IscVirtualFile) {
        val id = file.remoteId ?: return
        if (hasLocalChanges(file) && Messages.showYesNoDialog(
                project, "Discard local changes to '${file.objectName}' and reload it from ISC?", "Reload from SailPoint ISC", null,
            ) != Messages.YES
        ) return
        val text = documentText(file)
        background("Reloading ${file.kind.singularName} ${file.objectName}") {
            val json = service<IscClient>().fetch(file.tenantId, file.kind, file.owner?.id, id)
            onEdt {
                file.remote = json
                replaceText(file, text, file.textFor(json))
            }
        }
    }

    private fun requestBody(file: IscVirtualFile, text: String): JsonElement = when (file.kind) {
        ResourceKind.CONNECTOR_RULES -> file.remote.deepCopy().apply {
            listOf("id", "created", "modified").forEach(::remove)
            val sourceCode = getAsJsonObject("sourceCode") ?: JsonObject().also { add("sourceCode", it) }
            sourceCode.addProperty("script", text)
        }
        else -> {
            check(file.kind.editable) { "${file.kind.displayName} are read-only." }
            val json = try {
                JsonParser.parseString(text)
            } catch (e: JsonSyntaxException) {
                error("The ${file.kind.singularName} is not valid JSON: ${e.cause?.message ?: e.message}")
            }
            check(json.isJsonObject) { "A ${file.kind.singularName} must be a JSON object." }
            when {
                file.kind == ResourceKind.TRANSFORMS -> json.asJsonObject.deepCopy().apply {
                    remove("id")
                    remove("internal")
                }
                file.kind.patchUpdated && file.remoteId != null ->
                    jsonPatch(file.remote, json.asJsonObject, file.kind.immutableFields)
                // Everything else is replaced with the full object, including immutable fields ISC checks are unchanged.
                else -> json.asJsonObject
            }
        }
    }

    /** Top-level JSON Patch operations turning [old] into [new]. */
    private fun jsonPatch(old: JsonObject, new: JsonObject, immutable: Set<String>): JsonArray {
        fun op(op: String, key: String, value: JsonElement? = null) = JsonObject().apply {
            addProperty("op", op)
            addProperty("path", "/" + key.replace("~", "~0").replace("/", "~1"))
            value?.let { add("value", it) }
        }
        for (key in immutable) {
            check(old.get(key) == new.get(key)) { "The $key of an existing object can't be changed." }
        }
        return JsonArray().apply {
            for ((key, value) in new.entrySet()) {
                if (key !in immutable && old.get(key) != value) add(op(if (old.has(key)) "replace" else "add", key, value))
            }
            for (key in old.keySet()) {
                if (key !in immutable && !new.has(key)) add(op("remove", key))
            }
            check(!isEmpty) { "There are no changes to push." }
        }
    }

    private fun documentText(file: IscVirtualFile): String =
        FileDocumentManager.getInstance().getDocument(file)?.text ?: file.content.toString()

    private fun replaceText(file: IscVirtualFile, expectedCurrent: String, newText: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document != null && document.text == expectedCurrent && newText != expectedCurrent) {
            val wasReadOnly = !document.isWritable
            document.setReadOnly(false)
            WriteCommandAction.runWriteCommandAction(project, "Update from SailPoint ISC", null, { document.setText(newText) })
            document.setReadOnly(wasReadOnly)
        }
        file.syncedText = newText
        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    private fun announce(tenantId: String, kind: ResourceKind, parentId: String?) {
        project.messageBus.syncPublisher(IscChangeListener.TOPIC).changed(tenantId, kind, parentId)
    }

    private fun background(title: String, work: (ProgressIndicator) -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) = work(indicator)

            override fun onThrowable(error: Throwable) {
                notify(error.message ?: error.toString(), NotificationType.ERROR)
            }
        }.queue()
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, project.disposed)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("SailPoint ISC")
            .createNotification(content, type)
            .notify(project)
    }
}
