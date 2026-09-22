package com.sailpoint.intellij.transform.ops

import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.Op
import com.sailpoint.intellij.transform.OpCall
import com.sailpoint.intellij.transform.Velocity

/** Operations that choose or produce a value rather than reshaping the input. */
internal object ValueOps {

    val all: Map<String, Op> = mapOf(
        "static" to Op(::static),
        "conditional" to Op(::conditional),
        "lookup" to Op(::lookup),
        "firstValid" to Op(::firstValid),
    )

    private fun static(call: OpCall): EvalResult {
        val value = call.value("value") ?: return call.missing("value")
        if (value !is EvalResult.Value) return value
        val template = value.text ?: return EvalResult.Value(null)
        return Velocity.render(template, call.variables())
    }

    private fun conditional(call: OpCall): EvalResult {
        val variables = call.variables()
        val expression = call.text("expression") ?: return call.missing("expression")
        val comparison = Velocity.substitute(expression, variables)
        if (comparison !is EvalResult.Value) return comparison

        // ISC accepts only "eq", and treats a null on either side as an error rather than a mismatch.
        val operands = comparison.text.orEmpty().split(EQ, limit = 2)
        if (operands.size != 2) return EvalResult.Failure("An expression has to read \"value eq value\"; \"$expression\" doesn't.")
        val (left, right) = operands.map { it.trim() }
        if (left.isEmpty() || right.isEmpty()) return EvalResult.Failure("Neither side of \"$expression\" can be empty.")

        val branch = if (left == right) "positiveCondition" else "negativeCondition"
        val chosen = call.text(branch) ?: return call.missing(branch)
        return Velocity.substitute(chosen, variables)
    }

    private fun lookup(call: OpCall): EvalResult {
        val table = call.raw("table")?.takeIf { it.isJsonObject }?.asJsonObject ?: return call.missing("table")
        val key = call.input
        val match = key?.let { table.get(it) } ?: table.get("default")
            ?: return EvalResult.Failure(
                if (key == null) "Nothing came in, and the table has no \"default\" entry."
                else "\"$key\" isn't in the table, and there's no \"default\" entry.",
            )
        return EvalResult.Value(match.takeUnless { it.isJsonNull }?.asString)
    }

    private fun firstValid(call: OpCall): EvalResult {
        val items = call.items("values")
        if (items.isEmpty()) return call.missing("values")
        val ignoreErrors = call.bool("ignoreErrors", false)
        for ((element, path) in items) {
            when (val result = call.resolve(element, path)) {
                is EvalResult.Value -> result.text?.takeIf { it.isNotEmpty() }?.let { return result }
                is EvalResult.Failure -> if (!ignoreErrors) return result
                // Whether this one wins depends on a value the preview doesn't have, so it asks instead of guessing.
                is EvalResult.Needs -> return result
            }
        }
        return EvalResult.Value(null)
    }

    private val EQ = Regex("""\s+eq\s+""")
}
