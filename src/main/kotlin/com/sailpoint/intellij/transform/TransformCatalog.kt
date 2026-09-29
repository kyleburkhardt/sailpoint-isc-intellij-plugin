package com.sailpoint.intellij.transform

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sailpoint.intellij.api.string

/** How an attribute is edited, worked out from its shape in the schema. */
enum class AttrKind {
    /** A literal string, or a nested transform in its place. */
    VALUE,

    /** A list whose entries are each a literal or a nested transform. */
    VALUE_LIST,

    /** A list of plain strings. */
    STRING_LIST,

    /** A key/value table. */
    TABLE,
    TEXT,
    INTEGER,
    BOOLEAN,
    ENUM,
}

data class AttrDef(
    val name: String,
    val label: String,
    val help: String,
    val kind: AttrKind,
    val required: Boolean,
    val options: List<String> = emptyList(),
)

data class OpDef(
    val type: String,
    val label: String,
    val summary: String,
    private val docsSlug: String,
    val attributes: List<AttrDef>,
) {
    val docsUrl: String get() = "https://developer.sailpoint.com/docs/extensibility/transforms/operations/$docsSlug"

    fun attribute(name: String): AttrDef? = attributes.find { it.name == name }

    /** True when [name] isn't one of the operation's own attributes, so it's a Velocity variable. */
    fun isVariable(name: String): Boolean = attribute(name) == null
}

/**
 * The transform operations, read from `transform.schema.json` — the same file that drives JSON completion, so an
 * operation only has to be described once. Display names and summaries, which JSON Schema has no place for, live in
 * [DISPLAY] here.
 */
object TransformCatalog {

    /** Every operation, ordered by display name. */
    val ops: List<OpDef> by lazy { parse().sortedBy { it.label } }

    private val byType: Map<String, OpDef> by lazy { ops.associateBy { it.type } }

    operator fun get(type: String?): OpDef? = type?.let { byType[it] }

    /** The type names in schema order, for anything that just needs the list. */
    val types: List<String> get() = ops.map { it.type }.sorted()

    /** An `attributes` object holding the operation's required attributes, empty and ready to fill in. */
    fun starterAttributes(type: String): JsonObject {
        val json = JsonObject()
        this[type]?.attributes?.filter { it.required }?.forEach { attr ->
            when (attr.kind) {
                AttrKind.VALUE, AttrKind.TEXT -> json.addProperty(attr.name, "")
                AttrKind.ENUM -> json.addProperty(attr.name, attr.options.firstOrNull().orEmpty())
                AttrKind.INTEGER -> json.addProperty(attr.name, 0)
                AttrKind.BOOLEAN -> json.addProperty(attr.name, false)
                AttrKind.VALUE_LIST, AttrKind.STRING_LIST -> json.add(attr.name, JsonArray())
                AttrKind.TABLE -> json.add(attr.name, JsonObject().apply { addProperty("default", "") })
            }
        }
        return json
    }

    private fun parse(): List<OpDef> {
        val schema = JsonParser.parseReader(
            requireNotNull(javaClass.getResourceAsStream(SCHEMA)) { "$SCHEMA is missing from the plugin" }.reader(),
        ).asJsonObject
        val transform = schema.getAsJsonObject("definitions").getAsJsonObject("transform")
        return transform.getAsJsonArray("allOf").mapNotNull { block ->
            val obj = block.asJsonObject
            val type = obj.getAsJsonObject("if")?.getAsJsonObject("properties")?.getAsJsonObject("type")?.string("const")
                ?: return@mapNotNull null
            val attributes = obj.getAsJsonObject("then")
                ?.getAsJsonObject("properties")
                ?.getAsJsonObject("attributes")
            val (label, summary, slug) = DISPLAY[type] ?: Triple(type, "", "")
            OpDef(type, label, summary, slug, attributes?.let(::attrDefs).orEmpty())
        }
    }

    private fun attrDefs(attributes: JsonObject): List<AttrDef> {
        val required = attributes.getAsJsonArray("required")?.map { it.asString }.orEmpty()
        return attributes.getAsJsonObject("properties")?.entrySet()?.map { (name, element) ->
            val schema = element.asJsonObject
            AttrDef(
                name = name,
                label = labelFor(name),
                help = schema.string("description").orEmpty(),
                kind = kindOf(schema),
                required = name in required,
                options = schema.getAsJsonArray("enum")?.map { it.asString }.orEmpty(),
            )
        }.orEmpty()
    }

    private fun kindOf(schema: JsonObject): AttrKind = when {
        schema.has("enum") -> AttrKind.ENUM
        // "a literal value or a nested transform" is written as anyOf [string, $ref transform].
        schema.has("anyOf") -> AttrKind.VALUE
        schema.string("type") == "array" -> {
            val items = schema.getAsJsonObject("items")
            if (items?.has("anyOf") == true) AttrKind.VALUE_LIST else AttrKind.STRING_LIST
        }
        schema.string("type") == "object" -> AttrKind.TABLE
        schema.string("type") == "integer" || schema.string("type") == "number" -> AttrKind.INTEGER
        schema.string("type") == "boolean" -> AttrKind.BOOLEAN
        else -> AttrKind.TEXT
    }

    /** `accountSortDescending` -> `Account sort descending`. */
    private fun labelFor(name: String): String = name
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .lowercase()
        .replaceFirstChar(Char::uppercase)

    private const val SCHEMA = "/schemas/transform.schema.json"

    /** Display name, one-line summary and documentation page for each operation. */
    private val DISPLAY: Map<String, Triple<String, String, String>> = mapOf(
        "accountAttribute" to Triple("Account Attribute", "Read an account attribute from one of the identity's sources.", "account-attribute"),
        "base64Decode" to Triple("Base64 Decode", "Decode base64-encoded text.", "base64-decode"),
        "base64Encode" to Triple("Base64 Encode", "Encode text as base64.", "base64-encode"),
        "concat" to Triple("Concatenation", "Join several values into one string.", "concatenation"),
        "conditional" to Triple("Conditional", "Return one of two values depending on a comparison.", "conditional"),
        "dateCompare" to Triple("Date Compare", "Compare two dates and return one of two values.", "date-compare"),
        "dateFormat" to Triple("Date Format", "Convert a date from one format to another.", "date-format"),
        "dateMath" to Triple("Date Math", "Add to, subtract from or round a date.", "date-math"),
        "decomposeDiacriticalMarks" to Triple("Decompose Diacritical Marks", "Replace accented characters with plain ones.", "decompose-diacritical-marks"),
        "displayName" to Triple("Display Name", "Build a display name from preferred or given names.", "display-name"),
        "e164phone" to Triple("E.164 Phone", "Normalize a phone number to E.164 format.", "e164-phone"),
        "firstValid" to Triple("First Valid", "Return the first value that isn't null.", "first-valid"),
        "getEndOfString" to Triple("Get End of String", "Take the last N characters of a string.", "get-end-of-string"),
        "getReferenceIdentityAttribute" to Triple("Get Reference Identity Attribute", "Read an identity attribute from another identity, such as a manager.", "get-reference-identity-attribute"),
        "identityAttribute" to Triple("Identity Attribute", "Read one of the identity's own attributes.", "identity-attribute"),
        "indexOf" to Triple("Index Of", "Find where a substring first appears.", "index-of"),
        "iso3166" to Triple("ISO 3166", "Convert a country name or code to an ISO 3166 code.", "iso-3166"),
        "join" to Triple("Join", "Join several values with a separator.", "join"),
        "lastIndexOf" to Triple("Last Index Of", "Find where a substring last appears.", "last-index-of"),
        "leftPad" to Triple("Left Pad", "Pad a string on the left to a given length.", "left-pad"),
        "lookup" to Triple("Lookup", "Map the input to an output through a table of key/value pairs.", "lookup"),
        "lower" to Triple("Lower", "Convert text to lower case.", "lower"),
        "normalizeNames" to Triple("Name Normalizer", "Tidy up the capitalization and spacing of a name.", "name-normalizer"),
        "randomAlphaNumeric" to Triple("Random Alphanumeric", "Generate a random string of letters and digits.", "random-alphanumeric"),
        "randomNumeric" to Triple("Random Numeric", "Generate a random string of digits.", "random-numeric"),
        "reference" to Triple("Reference", "Run another transform that's already saved in the tenant.", "reference"),
        "replace" to Triple("Replace", "Replace everything matching a pattern.", "replace"),
        "replaceAll" to Triple("Replace All", "Apply a table of replacements in turn.", "replace-all"),
        "rfc5646" to Triple("RFC 5646", "Convert a language name or code to an RFC 5646 tag.", "rfc-5646"),
        "rightPad" to Triple("Right Pad", "Pad a string on the right to a given length.", "right-pad"),
        "rule" to Triple("Rule", "Run a connector or Seaspray rule.", "rule"),
        "split" to Triple("Split", "Split the input and return one piece of it.", "split"),
        "static" to Triple("Static", "Return a fixed value, or a Velocity template.", "static"),
        "substring" to Triple("Substring", "Take part of a string by position.", "substring"),
        "trim" to Triple("Trim", "Remove leading and trailing spaces.", "trim"),
        "upper" to Triple("Upper", "Convert text to upper case.", "upper"),
        "usernameGenerator" to Triple("Username Generator", "Build a unique username from a list of patterns.", "username-generator"),
        "uuid" to Triple("UUID Generator", "Generate a UUID.", "uuid-generator"),
    )
}
