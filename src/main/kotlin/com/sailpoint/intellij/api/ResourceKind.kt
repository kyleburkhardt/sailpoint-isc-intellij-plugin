package com.sailpoint.intellij.api

import com.google.gson.JsonObject

/**
 * The ISC object types the explorer knows how to list and open.
 *
 * Paths use the per-service `/{service}/v1` APIs; v3, beta and the yearly versions (v2024…) are being retired.
 */
enum class ResourceKind(
    val displayName: String,
    val singularName: String,
    /**
     * Collection path; `GET {path}/{id}` fetches one object. `{parentId}` stands for the owning object's ID.
     * For a [singleton] kind this is the path of the object itself.
     */
    private val path: String,
    val editable: Boolean,
    /** The kind whose objects own these, listed underneath each of them; null for top-level kinds. */
    val parent: ResourceKind? = null,
    /** Exactly one per parent, shown as a single object instead of a category. */
    val singleton: Boolean = false,
    /** Largest `limit` the collection accepts. */
    val pageSize: Int = 250,
    /** Requires the `X-SailPoint-Experimental` header on every call. */
    val experimental: Boolean = false,
    /** Updated with a JSON Patch of what changed rather than a full replace. */
    val patchUpdated: Boolean = false,
    /** Fields ISC won't let a patch change. */
    val immutableFields: Set<String> = emptySet(),
    /** Name of the folder these are grouped under below their parent; null to sit directly under it. */
    val group: String? = null,
    /** Can be deleted from the explorer; for a [singleton] that removes its configuration but keeps the entry. */
    val deletable: Boolean = false,
    /** Reached only through a folder that loads it, so it gets no node of its own under its parent. */
    val hidden: Boolean = false,
) {
    SOURCES("Sources", "source", "/sources/v1", editable = true),
    TRANSFORMS("Transforms", "transform", "/transforms/v1", editable = true, deletable = true),
    CONNECTOR_RULES("Connector Rules", "connector rule", "/connector-rules/v1", editable = true, pageSize = 50, deletable = true),
    IDENTITIES("Identities", "identity", "/search/v1/identities", editable = false),
    /** Shown as the account schema under Accounts and as each entitlement type's schema under Entitlements. */
    SOURCE_SCHEMAS("Schemas", "schema", "/sources/v1/{parentId}/schemas", editable = true, parent = SOURCES, hidden = true),
    SOURCE_CORRELATION(
        "Correlation", "correlation config", "/sources/v1/{parentId}/correlation-config",
        editable = true, parent = SOURCES, singleton = true, group = ACCOUNTS,
    ),
    /** The schedule's `type` doubles as its ID; shown in the Accounts and Entitlements folders. */
    SOURCE_SCHEDULES(
        "Schedules", "schedule", "/sources/v1/{parentId}/schedules",
        editable = true, parent = SOURCES, patchUpdated = true, immutableFields = setOf("type"), hidden = true, deletable = true,
    ),
    /** Experimental v2 API: policies have real IDs, so a source can have several per usage type (v1 allows one). */
    SOURCE_PROVISIONING_POLICIES(
        "Provisioning Policies", "provisioning policy", "/sources/v2/{parentId}/provisioning-policies",
        editable = true, parent = SOURCES, experimental = true, deletable = true, group = ACCOUNTS,
    ),
    SOURCE_ACCOUNT_DELETE_APPROVAL(
        "Deletion Approval", "account deletion approval config", "/sources/v1/{parentId}/approval-config/account-delete",
        editable = true, parent = SOURCES, singleton = true, patchUpdated = true, group = ACCOUNTS,
    ),
    /** Experimental API, and a full replace on save. */
    SOURCE_ATTRIBUTE_SYNC(
        "Attribute Sync", "attribute sync config", "/sources/v1/{parentId}/attribute-sync-config",
        editable = true, parent = SOURCES, singleton = true, experimental = true, group = ACCOUNTS,
    ),
    SOURCE_NATIVE_CHANGE_DETECTION(
        "Native Change Detection", "native change detection config", "/sources/v1/{parentId}/native-change-detection-config",
        editable = true, parent = SOURCES, singleton = true, group = ACCOUNTS, deletable = true,
    ),
    SOURCE_MACHINE_ACCOUNT_DELETE_APPROVAL(
        "Deletion Approval", "machine account deletion approval config",
        "/sources/v1/{parentId}/approval-config/machine-account-delete",
        editable = true, parent = SOURCES, singleton = true, patchUpdated = true, group = MACHINE_ACCOUNTS,
    );

    /** Kinds with a node of their own underneath each object of this kind, in tree order. */
    val children: List<ResourceKind> get() = entries.filter { it.parent == this && !it.hidden }

    /** Folders under each object of this kind, in tree order. */
    val childFolders: List<String> get() = when (this) {
        SOURCES -> listOf(ACCOUNTS, MACHINE_ACCOUNTS, ENTITLEMENTS)
        else -> emptyList()
    }

    /** Display name with its folder, e.g. `Machine Accounts Deletion Approval`, for names that repeat across folders. */
    val qualifiedName: String get() = listOfNotNull(group, displayName).joinToString(" ")

    fun collectionPath(parentId: String?): String {
        if (parent == null) return path
        requireNotNull(parentId) { "A $singularName needs the ID of its ${parent.singularName}" }
        return path.replace("{parentId}", parentId)
    }

    fun toItem(tenantId: String, json: JsonObject, parent: IscItem? = null): IscItem = IscItem(
        tenantId = tenantId,
        kind = this,
        // A singleton belongs to its parent rather than having an ID of its own, and is named after its kind.
        id = when {
            singleton -> ""
            this == SOURCE_SCHEDULES -> json.string("type") ?: ""
            else -> json.string("id") ?: ""
        },
        name = when {
            singleton -> displayName
            this == SOURCE_SCHEDULES -> json.string("type")?.let(::humanize) ?: "(unnamed)"
            this == SOURCE_PROVISIONING_POLICIES ->
                json.string("name") ?: json.string("usageType")?.let(::humanize) ?: "(unnamed)"
            else -> json.string("name") ?: json.string("displayName") ?: "(unnamed)"
        },
        detail = when (this) {
            SOURCES, TRANSFORMS, CONNECTOR_RULES -> json.string("type")
            IDENTITIES -> json.string("email")
            SOURCE_SCHEMAS -> json.string("nativeObjectType")
            SOURCE_SCHEDULES -> json.string("cronExpression")
            SOURCE_PROVISIONING_POLICIES -> json.string("usageType")
            SOURCE_ACCOUNT_DELETE_APPROVAL, SOURCE_MACHINE_ACCOUNT_DELETE_APPROVAL ->
                if (json.get("approvalRequired")?.asBoolean == true) "approval required" else null
            SOURCE_ATTRIBUTE_SYNC -> json.getAsJsonArray("attributes")?.size()?.let { "$it attributes" }
            SOURCE_NATIVE_CHANGE_DETECTION -> if (json.get("enabled")?.asBoolean == true) "enabled" else "disabled"
            SOURCE_CORRELATION -> null
        },
        parent = parent,
    )
}

data class IscItem(
    val tenantId: String,
    val kind: ResourceKind,
    val id: String,
    val name: String,
    val detail: String?,
    /** The object this one belongs to, for kinds with a [ResourceKind.parent]. */
    val parent: IscItem? = null,
)

/** Folders that group a source's children in the explorer. */
const val ACCOUNTS = "Accounts"
const val MACHINE_ACCOUNTS = "Machine Accounts"
const val ENTITLEMENTS = "Entitlements"

/** The schema every source has for its accounts; the others are entitlement types. */
const val ACCOUNT_SCHEMA = "account"

/** The usage type of a provisioning policy for machine accounts; the rest cover human accounts. */
const val CREATE_MACHINE_ACCOUNT = "CREATE_MACHINE_ACCOUNT"

/** Schedule types, which double as schedule IDs. */
const val ACCOUNT_AGGREGATION = "ACCOUNT_AGGREGATION"
const val GROUP_AGGREGATION = "GROUP_AGGREGATION"

/** `ACCOUNT_AGGREGATION` -> `Account Aggregation`. */
private fun humanize(constant: String): String =
    constant.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

internal fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
