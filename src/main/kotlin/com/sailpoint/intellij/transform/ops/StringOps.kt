package com.sailpoint.intellij.transform.ops

import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.Op
import com.sailpoint.intellij.transform.OpCall
import com.sailpoint.intellij.transform.firstIssue
import java.util.regex.PatternSyntaxException

/** Operations that reshape a string. */
internal object StringOps {

    val all: Map<String, Op> = mapOf(
        "upper" to Op { call -> call.map { it.uppercase() } },
        "lower" to Op { call -> call.map { it.lowercase() } },
        "trim" to Op { call -> call.map { it.trim() } },
        "concat" to Op(::concat),
        "join" to Op(::join),
        "substring" to Op(::substring),
        "replace" to Op(::replace),
        "split" to Op(::split),
    )

    /** Applies [transform] to the incoming value; nothing in means nothing out. */
    private fun OpCall.map(transform: (String) -> CharSequence): EvalResult =
        EvalResult.Value(input?.let { transform(it).toString() })

    private fun concat(call: OpCall): EvalResult = joined(call, separator = "")

    private fun join(call: OpCall): EvalResult = joined(call, separator = call.text("separator") ?: ",")

    private fun joined(call: OpCall, separator: String): EvalResult {
        val items = call.items("values")
        if (items.isEmpty()) return call.missing("values")
        val results = items.map { (element, path) -> call.resolve(element, path) }
        results.firstIssue()?.let { return it }
        return EvalResult.Value(results.joinToString(separator) { it.textOrNull.orEmpty() })
    }

    private fun substring(call: OpCall): EvalResult {
        val input = call.input ?: return EvalResult.Value(null)
        val begin = call.int("begin") ?: return call.missing("begin")
        val end = call.int("end")
        // -1 means "from the very start"/"to the very end", and the offsets don't apply to it.
        val from = if (begin == -1) 0 else begin + (call.int("beginOffset") ?: 0)
        val to = if (end == null || end == -1) input.length else end + (call.int("endOffset") ?: 0)
        if (from < 0 || from > input.length) return EvalResult.Failure("Begin $from is outside \"$input\" (${input.length} characters).")
        if (to < from || to > input.length) return EvalResult.Failure("End $to is outside \"$input\" (${input.length} characters).")
        return EvalResult.Value(input.substring(from, to))
    }

    private fun replace(call: OpCall): EvalResult {
        val pattern = call.text("regex") ?: return call.missing("regex")
        val replacement = call.text("replacement") ?: return call.missing("replacement")
        val input = call.input ?: return EvalResult.Value(null)
        val regex = try {
            Regex(pattern)
        } catch (e: PatternSyntaxException) {
            return EvalResult.Failure("\"$pattern\" isn't a valid regular expression: ${e.description}.")
        }
        // Java's replaceAll semantics, so $1 in the replacement is a group reference, as it is in ISC.
        return try {
            EvalResult.Value(regex.replace(input, replacement))
        } catch (e: RuntimeException) {
            EvalResult.Failure("\"$replacement\" isn't a valid replacement: ${e.message}.")
        }
    }

    private fun split(call: OpCall): EvalResult {
        val delimiter = call.text("delimiter") ?: return call.missing("delimiter")
        val index = call.int("index") ?: return call.missing("index")
        val input = call.input ?: return EvalResult.Value(null)
        val parts = try {
            input.split(Regex(delimiter))
        } catch (e: PatternSyntaxException) {
            return EvalResult.Failure("\"$delimiter\" isn't a valid delimiter: ${e.description}.")
        }
        return when {
            index in parts.indices -> EvalResult.Value(parts[index])
            // ISC throws when `throws` is on, which is the default, and returns nothing otherwise.
            call.bool("throws", true) ->
                EvalResult.Failure("Index $index is past the end: \"$input\" splits into ${parts.size} ${if (parts.size == 1) "piece" else "pieces"}.")
            else -> EvalResult.Value(null)
        }
    }
}
