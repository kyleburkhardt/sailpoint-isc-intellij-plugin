package com.sailpoint.intellij.transform.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.sailpoint.intellij.api.IscClient
import com.sailpoint.intellij.api.string
import com.sailpoint.intellij.editor.IscVirtualFile
import com.sailpoint.intellij.transform.NeedKind
import com.sailpoint.intellij.transform.NeededInput
import com.sailpoint.intellij.transform.neededInputs
import com.sailpoint.intellij.transform.readsImplicitInput
import com.sailpoint.intellij.transform.withImplicitInput
import java.util.UUID

/** What a test in ISC runs against: an identity, the attribute to work out, and where the input comes from. */
data class IscTestSetup(
    val identityId: String,
    val identityName: String,
    /** The identity attribute the transform is computed as. Nothing is saved, so it only decides the "previous" value. */
    val attribute: String,
    /** The source and account attribute standing in for the value an identity profile would pass in. */
    val inputSource: String? = null,
    val inputAttribute: String? = null,
) {
    val hasInput: Boolean get() = !inputSource.isNullOrBlank() && !inputAttribute.isNullOrBlank()
}

/** How the last test in ISC went. [sent] is the transform as it was when the test ran, to tell when it's out of date. */
sealed interface IscTestOutcome {
    val setup: IscTestSetup

    data class Running(override val setup: IscTestSetup) : IscTestOutcome

    data class Done(
        override val setup: IscTestSetup,
        val value: String?,
        val previousValue: String?,
        val errors: List<String>,
        val sent: String,
        val sample: IscSample? = null,
    ) : IscTestOutcome

    data class Failed(
        override val setup: IscTestSetup,
        val message: String,
        val sent: String,
        val sample: IscSample? = null,
    ) : IscTestOutcome
}

/**
 * The identity's real values for what the local preview reads, so it can run on the same data as ISC. A value of
 * null in [values] means ISC has none; a need that isn't a key couldn't be looked up (see [notes]).
 */
data class IscSample(
    val input: String?,
    val values: Map<NeededInput, String?>,
    val notes: List<String>,
)

/** The test in ISC for one open transform: how it's set up, how it last went, and who's watching. */
class IscTestState {
    var setup: IscTestSetup? = null
    var outcome: IscTestOutcome? = null
        set(value) {
            field = value
            listeners.toList().forEach { it(value) }
        }

    private val listeners = mutableListOf<(IscTestOutcome?) -> Unit>()

    fun listen(listener: (IscTestOutcome?) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}

/**
 * Runs a transform in ISC against a real identity, through the identity profile preview: ISC works the transform out
 * as it would on a refresh. The preview only runs saved transforms, so the transform as it stands in the editor,
 * unpushed edits and all, is saved as a scratch copy for the test and deleted after it.
 */
object IscTest {
    private val KEY = Key.create<IscTestState>("sailpoint.transform.iscTest")

    /** Names the transforms saved just for a test, so any left behind are easy to spot. */
    private const val SCRATCH_PREFIX = "zz-plugin-test-"

    fun state(file: IscVirtualFile): IscTestState =
        file.getUserData(KEY) ?: IscTestState().also { file.putUserData(KEY, it) }

    /** Asks what to test against, then runs it. */
    fun setUpAndRun(project: Project, file: IscVirtualFile) {
        val transform = current(file) ?: return
        val state = state(file)
        val setup = IscTestDialog(project, file.tenantId, state.setup, needsInput = readsImplicitInput(transform))
            .showAndChoose() ?: return
        state.setup = setup
        run(project, file)
    }

    /** Runs again with the last setup, or asks for one when there isn't one or it no longer fits the transform. */
    fun run(project: Project, file: IscVirtualFile) {
        val transform = current(file) ?: return
        val state = state(file)
        val setup = state.setup
        val needsInput = readsImplicitInput(transform)
        if (setup == null || (needsInput && !setup.hasInput)) return setUpAndRun(project, file)

        val sent = transform.toString()
        val scratchName = "$SCRATCH_PREFIX${UUID.randomUUID().toString().take(8)}"
        val definition = JsonObject().apply {
            addProperty("name", scratchName)
            add("type", transform.get("type"))
            add("attributes", transform.get("attributes")?.takeIf { it.isJsonObject }?.deepCopy() ?: JsonObject())
        }.let { definition ->
            if (!needsInput) definition
            else withImplicitInput(
                definition,
                JsonObject().apply {
                    addProperty("type", "accountAttribute")
                    add("attributes", JsonObject().apply {
                        addProperty("sourceName", setup.inputSource)
                        addProperty("attributeName", setup.inputAttribute)
                    })
                },
            )
        }

        state.outcome = IscTestOutcome.Running(setup)
        object : Task.Backgroundable(project, "Testing transform on ${setup.identityName}", true) {
            private var leftBehind: String? = null

            override fun run(indicator: ProgressIndicator) {
                val client = service<IscClient>()
                val sample = try {
                    // Loaded here rather than taken from the form, so a reference the form hasn't loaded yet still counts.
                    val referenced = HashMap<String, JsonObject?>()
                    val resolve = { name: String -> referenced.getOrPut(name) { client.transformByName(file.tenantId, name) } }
                    sample(client, file.tenantId, setup, neededInputs(transform, resolve), needsInput)
                } catch (e: Exception) {
                    IscSample(null, emptyMap(), listOf("Couldn't read ${setup.identityName}'s values: ${e.message ?: e}"))
                }
                var outcome = try {
                    val created = client.post(file.tenantId, "/transforms/v1", definition).asJsonObject
                    try {
                        done(setup, client.identityPreview(file.tenantId, setup.identityId, setup.attribute, scratchName), sent)
                    } finally {
                        val id = created.string("id")
                        val left = if (id == null) "ISC didn't return its ID" else runCatching {
                            client.delete(file.tenantId, "/transforms/v1/$id")
                        }.exceptionOrNull()?.let { it.message ?: it.toString() }
                        if (left != null) leftBehind = "The scratch transform '$scratchName' wasn't deleted ($left). Delete it in ISC."
                    }
                } catch (e: Exception) {
                    IscTestOutcome.Failed(setup, e.message ?: e.toString(), sent)
                }
                leftBehind?.let { note ->
                    outcome = when (val o = outcome) {
                        is IscTestOutcome.Done -> o.copy(errors = o.errors + note)
                        is IscTestOutcome.Failed -> o.copy(message = o.message + "\n" + note)
                        else -> o
                    }
                }
                outcome = when (val o = outcome) {
                    is IscTestOutcome.Done -> o.copy(sample = sample)
                    is IscTestOutcome.Failed -> o.copy(sample = sample)
                    else -> o
                }
                ApplicationManager.getApplication().invokeLater({ state.outcome = outcome }, ModalityState.any())
            }
        }.queue()
    }

    /**
     * Reads what the transform reads, for the identity it's tested on: its identity attributes, its accounts'
     * attributes, the attributes of the identities it references, and the input attribute.
     */
    private fun sample(
        client: IscClient, tenantId: String, setup: IscTestSetup, needs: List<NeededInput>, needsInput: Boolean,
    ): IscSample {
        val values = LinkedHashMap<NeededInput, String?>()
        val notes = mutableListOf<String>()
        val identity by lazy { client.identity(tenantId, setup.identityId) }
        val accounts by lazy { client.accountsOf(tenantId, setup.identityId) }

        fun account(source: String): JsonObject? {
            val matching = accounts.filter { account ->
                source == account.string("sourceName") || source == account.string("sourceId") ||
                    source.removeSuffix(" [source]") == account.string("sourceName")
            }
            if (matching.isEmpty()) notes += "${setup.identityName} has no account on $source."
            if (matching.size > 1) notes += "${setup.identityName} has ${matching.size} accounts on $source; the first is used."
            return matching.firstOrNull()
        }

        val referenced = HashMap<String, JsonObject?>()
        fun reference(uid: String): JsonObject? = referenced.getOrPut(uid) {
            val found = if (uid == "manager") {
                identity.objectAt("managerRef")?.string("id")?.let { client.identity(tenantId, it) }
            } else {
                client.identityByAlias(tenantId, uid)
            }
            if (found == null) {
                notes += if (uid == "manager") "${setup.identityName} has no manager." else "No identity has the uid '$uid'."
            }
            found
        }

        needs.forEach { need ->
            when (need.kind) {
                NeedKind.IDENTITY_ATTRIBUTE -> values[need] = identity.attribute(need.name)
                NeedKind.ACCOUNT_ATTRIBUTE -> need.qualifier?.let(::account)?.let { values[need] = it.attribute(need.name) }
                NeedKind.REFERENCE_IDENTITY_ATTRIBUTE ->
                    need.qualifier?.let(::reference)?.let { values[need] = it.attribute(need.name) }
                else -> Unit
            }
        }
        val input = if (needsInput) setup.inputSource?.let(::account)?.attribute(setup.inputAttribute.orEmpty()) else null
        return IscSample(input, values, notes.distinct())
    }

    /** An attribute from an identity's or account's `attributes`, as the text a transform would see. */
    private fun JsonObject.attribute(name: String): String? =
        objectAt("attributes")?.get(name)?.takeUnless { it.isJsonNull }
            ?.let { if (it.isJsonPrimitive) it.asString else it.toString() }

    private fun JsonObject.objectAt(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    /** The transform as it is in the editor now, or null when it isn't valid JSON. */
    fun current(file: IscVirtualFile): JsonObject? {
        val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return null
        return runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun done(setup: IscTestSetup, response: JsonObject, sent: String): IscTestOutcome {
        val attributes = response.array("previewAttributes").filter { it.isJsonObject }.map { it.asJsonObject }
        val preview = attributes.firstOrNull { it.string("name") == setup.attribute }
            ?: return IscTestOutcome.Failed(setup, "ISC's preview didn't include '${setup.attribute}'.", sent)
        val errors = preview.array("errorMessages")
            .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.string("text") }
        return IscTestOutcome.Done(setup, preview.nullableString("value"), preview.nullableString("previousValue"), errors, sent)
    }

    /** The array under [name]; empty when it's missing or null, which ISC sends for e.g. no errors. */
    private fun JsonObject.array(name: String): List<JsonElement> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    private fun JsonObject.nullableString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
}
