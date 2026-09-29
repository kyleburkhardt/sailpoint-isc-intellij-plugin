package com.sailpoint.intellij.transform.ui

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
import com.sailpoint.intellij.transform.readsImplicitInput
import com.sailpoint.intellij.transform.withImplicitInput

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
    ) : IscTestOutcome

    data class Failed(override val setup: IscTestSetup, val message: String, val sent: String) : IscTestOutcome
}

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
 * as it would on a refresh, and nothing is saved. The transform is sent as it stands in the editor, unpushed edits and
 * all.
 */
object IscTest {
    private val KEY = Key.create<IscTestState>("sailpoint.transform.iscTest")

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
        val definition = JsonObject().apply {
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
            override fun run(indicator: ProgressIndicator) {
                val outcome = try {
                    val response = service<IscClient>().identityPreview(file.tenantId, setup.identityId, setup.attribute, definition)
                    done(setup, response, sent)
                } catch (e: Exception) {
                    IscTestOutcome.Failed(setup, e.message ?: e.toString(), sent)
                }
                ApplicationManager.getApplication().invokeLater({ state.outcome = outcome }, ModalityState.any())
            }
        }.queue()
    }

    /** The transform as it is in the editor now, or null when it isn't valid JSON. */
    fun current(file: IscVirtualFile): JsonObject? {
        val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return null
        return runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun done(setup: IscTestSetup, response: JsonObject, sent: String): IscTestOutcome {
        val attributes = response.getAsJsonArray("previewAttributes")?.map { it.asJsonObject }.orEmpty()
        val preview = attributes.firstOrNull { it.string("name") == setup.attribute }
            ?: return IscTestOutcome.Failed(setup, "ISC's preview didn't include '${setup.attribute}'.", sent)
        val errors = preview.getAsJsonArray("errorMessages")
            ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.string("text") }
            .orEmpty()
        return IscTestOutcome.Done(setup, preview.nullableString("value"), preview.nullableString("previousValue"), errors, sent)
    }

    private fun JsonObject.nullableString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
}
