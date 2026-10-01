package com.sailpoint.intellij.transform.ui

import com.google.gson.Gson
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.sailpoint.intellij.editor.IscVirtualFile
import com.sailpoint.intellij.transform.NeedKind
import com.sailpoint.intellij.transform.NeededInput

/** The test values typed for a transform: the input flowing in, and each tenant value, some known to be empty in ISC. */
data class TestValues(
    val input: String = "",
    val values: Map<NeededInput, String> = emptyMap(),
    val absent: Set<NeededInput> = emptySet(),
)

/**
 * What each transform was last tested with, kept between sessions: its test values and its Test in ISC setup. It's
 * stored in the workspace file, so it stays on this machine and out of version control.
 */
@Service(Service.Level.PROJECT)
@State(name = "SailPointTransformTests", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TransformTestMemory : SimplePersistentStateComponent<TransformTestMemory.MemoryState>(MemoryState()) {

    class MemoryState : BaseState() {
        /** Tenant and transform to what was remembered for it, as JSON. */
        var entries by map<String, String>()
    }

    fun values(file: IscVirtualFile): TestValues? = entry(file)?.let { entry ->
        val values = entry.values.orEmpty().mapNotNull { it.need()?.let { need -> need to it } }
        TestValues(
            input = entry.input.orEmpty(),
            values = values.associate { (need, saved) -> need to saved.value.orEmpty() },
            absent = values.filter { (_, saved) -> saved.absent == true }.mapTo(HashSet()) { (need, _) -> need },
        )
    }

    fun setup(file: IscVirtualFile): IscTestSetup? = entry(file)?.setup?.takeIf { !it.identityId.isNullOrEmpty() }

    fun remember(file: IscVirtualFile, values: TestValues) = update(file) { entry ->
        entry.copy(
            input = values.input,
            values = values.values.map { (need, value) ->
                SavedValue(need.kind.name, need.name, need.qualifier, value, need in values.absent)
            },
        )
    }

    fun remember(file: IscVirtualFile, setup: IscTestSetup) = update(file) { it.copy(setup = setup) }

    private fun entry(file: IscVirtualFile): Entry? =
        state.entries[key(file)]?.let { runCatching { GSON.fromJson(it, Entry::class.java) }.getOrNull() }

    private fun update(file: IscVirtualFile, change: (Entry) -> Entry) {
        state.entries[key(file)] = GSON.toJson(change(entry(file) ?: Entry()))
        state.intIncrementModificationCount()
    }

    /** One transform's memory as it's stored. Fields are nullable because Gson fills them from whatever was saved. */
    private data class Entry(
        val input: String? = null,
        val values: List<SavedValue>? = null,
        val setup: IscTestSetup? = null,
    )

    private data class SavedValue(
        val kind: String?,
        val name: String?,
        val qualifier: String?,
        val value: String?,
        val absent: Boolean?,
    ) {
        fun need(): NeededInput? {
            val kind = NeedKind.entries.firstOrNull { it.name == kind } ?: return null
            return NeededInput(kind, name ?: return null, qualifier)
        }
    }

    private companion object {
        val GSON = Gson()

        /** A transform by its tenant and ID, or its name before it has an ID. */
        fun key(file: IscVirtualFile): String = "${file.tenantId}/${file.remoteId ?: file.objectName}"
    }
}
