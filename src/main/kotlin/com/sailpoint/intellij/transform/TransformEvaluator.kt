package com.sailpoint.intellij.transform

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sailpoint.intellij.api.string
import java.time.Instant
import kotlin.random.Random

/** What one transform produced. */
sealed interface EvalResult {

    /** A value; [text] is null when the transform produced nothing, which is a normal outcome. */
    data class Value(val text: String?) : EvalResult

    /** Data only the tenant has. The preview asks for it rather than guessing. */
    data class Needs(val need: NeededInput) : EvalResult

    /** The transform can't run as written: a missing attribute, a bad pattern, an ISC error. */
    data class Failure(val message: String) : EvalResult

    /** The value, or null when this result isn't one. */
    val textOrNull: String? get() = (this as? Value)?.text
}

enum class NeedKind { IDENTITY_ATTRIBUTE, ACCOUNT_ATTRIBUTE, REFERENCE_IDENTITY_ATTRIBUTE, TRANSFORM, RULE, SOURCE_CHECK }

/**
 * A value the preview can't work out on its own, so it asks for it. [qualifier] holds the source for an account
 * attribute, or the other identity for a reference attribute.
 */
data class NeededInput(val kind: NeedKind, val name: String, val qualifier: String? = null) {

    val label: String
        get() = when (kind) {
            NeedKind.IDENTITY_ATTRIBUTE -> "Identity attribute '$name'"
            NeedKind.ACCOUNT_ATTRIBUTE -> "'$name' on ${qualifier ?: "a source"}"
            NeedKind.REFERENCE_IDENTITY_ATTRIBUTE -> "'$name' of ${qualifier ?: "another identity"}"
            NeedKind.TRANSFORM -> "Transform '$name'"
            NeedKind.RULE -> "Rule '$name'"
            NeedKind.SOURCE_CHECK -> "Account check on ${qualifier ?: "the source"}"
        }

    /** What the preview says when nothing has been supplied. */
    val prompt: String
        get() = when (kind) {
            NeedKind.TRANSFORM -> "$label isn't in this tenant, or couldn't be loaded."
            NeedKind.RULE -> "$label runs in ISC and can't be previewed here."
            NeedKind.SOURCE_CHECK -> "$label needs the tenant, so the first pattern is used."
            else -> "Enter a test value for $label."
        }
}

/** The values a preview runs against. [now] and [random] are supplied so results are repeatable in tests. */
data class EvalContext(
    /** The attribute value flowing into the transform, as ISC would pass it in. */
    val input: String? = null,
    val identityAttributes: Map<String, String> = emptyMap(),
    /** Source name (or ID) to that account's attributes. */
    val accountAttributes: Map<String, Map<String, String>> = emptyMap(),
    /** Attributes of identities referenced by `getReferenceIdentityAttribute`, keyed by its `uid`. */
    val referenceAttributes: Map<String, Map<String, String>> = emptyMap(),
    val now: Instant = Instant.now(),
    val random: Random = Random.Default,
    /** Loads a transform by ID for `reference`, normally from the tenant. */
    val resolveTransform: (String) -> JsonObject? = { null },
)

/**
 * One transform in the tree and what it produced. [path] addresses the node: `""` is the transform itself,
 * `"input"` its input, `"values[1].input"` a nested one.
 */
data class Step(val path: String, val type: String, val input: String?, val result: EvalResult)

/** The outcome of a preview: the final [result], plus every node's own result. */
data class Trace(val result: EvalResult, val steps: List<Step>) {
    private val byPath = steps.associateBy { it.path }

    fun at(path: String): Step? = byPath[path]
}

/** A transform's attributes, or an empty object when it has none or they aren't an object. */
internal fun JsonObject.attributes(): JsonObject = get("attributes")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

/** Runs [transform] against [context] without calling ISC. */
fun evaluate(transform: JsonObject, context: EvalContext = EvalContext()): Trace =
    TransformEvaluator(context).run(transform)

/**
 * Evaluates a transform the way ISC's Seaspray engine would. Operations that need the tenant report a [NeededInput]
 * instead of failing, so the preview can ask for a test value and carry on.
 */
class TransformEvaluator(private val context: EvalContext) {

    private val steps = mutableListOf<Step>()
    private var depth = 0

    fun run(transform: JsonObject): Trace {
        steps.clear()
        val result = eval(transform, context.input, "")
        return Trace(result, steps.toList())
    }

    /** Resolves [element]: a literal stays as it is, a transform object is evaluated. */
    internal fun resolve(element: JsonElement?, input: String?, path: String): EvalResult = when {
        element == null || element.isJsonNull -> EvalResult.Value(null)
        element.isJsonObject && element.asJsonObject.has("type") -> eval(element.asJsonObject, input, path)
        element.isJsonPrimitive -> EvalResult.Value(element.asString)
        else -> EvalResult.Failure("Expected a value or a transform.")
    }

    private fun eval(node: JsonObject, input: String?, path: String): EvalResult {
        if (depth >= MAX_DEPTH) return EvalResult.Failure("This transform nests too deeply to preview.")
        val type = node.string("type")
            ?: return EvalResult.Failure("This transform has no type.").also { record(path, "?", input, it) }
        val attributes = node.attributes()
        val op = OPERATIONS[type]

        depth++
        val result = try {
            if (op == null) {
                EvalResult.Failure(unsupported(type))
            } else {
                // An explicit `input` attribute replaces whatever was flowing in.
                when (val incoming = if (attributes.has("input")) resolve(attributes.get("input"), input, join(path, "input")) else EvalResult.Value(input)) {
                    is EvalResult.Value -> runCatching { op.eval(OpCall(type, attributes, incoming.text, context, this, path)) }
                        .getOrElse { EvalResult.Failure(it.message ?: it.toString()) }
                    else -> incoming
                }
            }
        } finally {
            depth--
        }
        record(path, type, input, result)
        return result
    }

    private fun record(path: String, type: String, input: String?, result: EvalResult) {
        steps += Step(path, type, input, result)
    }

    private fun unsupported(type: String): String = TransformCatalog[type]
        ?.let { "The ${it.label} operation isn't previewed yet." }
        ?: "'$type' isn't a transform operation ISC knows."

    companion object {
        private const val MAX_DEPTH = 50

        internal fun join(path: String, child: String): String = if (path.isEmpty()) child else "$path.$child"
    }
}

/** One operation's evaluation, given its attributes and the value flowing in. */
fun interface Op {
    fun eval(call: OpCall): EvalResult
}

/** What an [Op] is handed: its attributes, already-resolved input, and a way to resolve nested transforms. */
class OpCall(
    val type: String,
    val attributes: JsonObject,
    /** The value flowing in, after any explicit `input` attribute was applied. */
    val input: String?,
    val context: EvalContext,
    private val evaluator: TransformEvaluator,
    private val path: String,
) {
    private val definition: OpDef? get() = TransformCatalog[type]

    fun raw(name: String): JsonElement? = attributes.get(name)?.takeUnless { it.isJsonNull }

    /** [name] as text, evaluating it first if it holds a nested transform. Null when the attribute is absent. */
    fun value(name: String): EvalResult? = raw(name)?.let { evaluator.resolve(it, input, TransformEvaluator.join(path, name)) }

    /** [name] as a plain string, for attributes that are never transforms (patterns, formats, delimiters). */
    fun text(name: String): String? = raw(name)?.takeIf { it.isJsonPrimitive }?.asString

    fun int(name: String): Int? = text(name)?.trim()?.toIntOrNull()

    fun bool(name: String, default: Boolean): Boolean =
        raw(name)?.takeIf { it.isJsonPrimitive }?.let { it.asJsonPrimitive.runCatching { asBoolean }.getOrNull() } ?: default

    /** The entries of a list attribute, each with the path the preview addresses it by. */
    fun items(name: String): List<Pair<JsonElement, String>> {
        val array = raw(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapIndexed { index, element -> element to TransformEvaluator.join(path, "$name[$index]") }
    }

    fun resolve(element: JsonElement, path: String): EvalResult = evaluator.resolve(element, input, path)

    /**
     * The operation's extra attributes, which Velocity templates reference as `$name`. Each is resolved the same way
     * as any other value, so a variable can itself be a transform.
     */
    fun variables(): Map<String, EvalResult> = attributes.entrySet()
        // `input` is never a variable: it's the value flowing in, and it has already been resolved.
        .filter { (name, _) -> name != "input" && (definition?.isVariable(name) ?: true) }
        .associate { (name, element) -> name to evaluator.resolve(element, input, TransformEvaluator.join(path, name)) }

    fun missing(attribute: String): EvalResult.Failure =
        EvalResult.Failure("${definition?.label ?: type} needs '${definition?.attribute(attribute)?.label ?: attribute}'.")
}

/**
 * Every tenant value [transform] would read, so the preview can ask for them up front. Duplicates are removed and the
 * order is the order they appear in.
 */
fun neededInputs(transform: JsonObject): List<NeededInput> {
    val found = LinkedHashSet<NeededInput>()
    fun walk(element: JsonElement?) {
        when {
            element == null -> Unit
            element.isJsonArray -> element.asJsonArray.forEach(::walk)
            element.isJsonObject -> {
                val obj = element.asJsonObject
                needOf(obj)?.let(found::add)
                obj.entrySet().forEach { (_, child) -> walk(child) }
            }
        }
    }
    walk(transform)
    return found.toList()
}

/**
 * Whether [transform] reads the value ISC passes in (its implicit input). It doesn't when every operation that reads an
 * input is given an explicit one, or when nothing in it reads an input at all, e.g. a `static` or an `identityAttribute`.
 */
fun readsImplicitInput(transform: JsonObject): Boolean {
    // Every element walked here is handed the implicit input; an explicit `input` stops it reaching anything below it.
    fun walk(element: JsonElement?): Boolean = when {
        element == null || !(element.isJsonObject || element.isJsonArray) -> false
        element.isJsonArray -> element.asJsonArray.any(::walk)
        !element.asJsonObject.has("type") -> element.asJsonObject.entrySet().any { (_, child) -> walk(child) }
        else -> {
            val node = element.asJsonObject
            val attributes = node.attributes()
            if (attributes.has("input")) walk(attributes.get("input"))
            else readsInput(node.string("type")) || attributes.entrySet().any { (_, child) -> walk(child) }
        }
    }
    return walk(transform)
}

/**
 * Whether an operation works on its input, which the schema says by giving it an `input` attribute. A `reference` or
 * `rule` passes it on to code the preview can't see, and an unknown type might use it, so those count as reading it.
 */
private fun readsInput(type: String?): Boolean {
    val op = type?.let { TransformCatalog[it] } ?: return true
    return op.attribute("input") != null || type == "reference" || type == "rule"
}

/** The tenant value this node reads, if it reads one. */
private fun needOf(node: JsonObject): NeededInput? {
    val attributes = node.attributes()
    return when (node.string("type")) {
        "identityAttribute" -> attributes.string("name")?.let { NeededInput(NeedKind.IDENTITY_ATTRIBUTE, it) }
        "accountAttribute" -> attributes.string("attributeName")?.let {
            NeededInput(NeedKind.ACCOUNT_ATTRIBUTE, it, attributes.string("sourceName") ?: attributes.string("applicationId"))
        }
        "getReferenceIdentityAttribute" -> attributes.string("attributeName")?.let {
            NeededInput(NeedKind.REFERENCE_IDENTITY_ATTRIBUTE, it, attributes.string("uid"))
        }
        "reference" -> attributes.string("id")?.let { NeededInput(NeedKind.TRANSFORM, it) }
        "rule" -> attributes.string("name")?.let { NeededInput(NeedKind.RULE, it) }
        else -> null
    }
}
