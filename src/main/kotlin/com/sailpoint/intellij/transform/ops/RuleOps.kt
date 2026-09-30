package com.sailpoint.intellij.transform.ops

import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.NeedKind
import com.sailpoint.intellij.transform.NeededInput
import com.sailpoint.intellij.transform.Op
import com.sailpoint.intellij.transform.OpCall

/**
 * Rules. A rule's code lives in ISC, so only the operations of SailPoint's own Cloud Services Deployment Utility, which
 * are documented, are previewed; any other rule says it can't be.
 */
internal object RuleOps {

    val all: Map<String, Op> = mapOf("rule" to Op(::rule))

    private fun rule(call: OpCall): EvalResult {
        val name = call.text("name") ?: return call.missing("name")
        if (name != UTILITY) return EvalResult.Needs(NeededInput(NeedKind.RULE, name))
        return when (val operation = call.text("operation")) {
            "getEndOfString" -> StringOps.getEndOfString(call)
            "generateRandomString" -> randomString(call)
            null -> call.missing("operation")
            else -> EvalResult.Failure("The $UTILITY has no operation \"$operation\" the preview knows.")
        }
    }

    /** Letters, plus digits and SailPoint's special characters when they're asked for. */
    private fun randomString(call: OpCall): EvalResult {
        val length = call.int("length") ?: return call.missing("length")
        if (length !in 1..MAX_LENGTH) return EvalResult.Failure("Length has to be between 1 and $MAX_LENGTH.")
        val alphabet = LETTERS +
            (if (call.bool("includeNumbers", false)) DIGITS else "") +
            (if (call.bool("includeSpecialChars", false)) SPECIALS else "")
        val random = call.context.random
        return EvalResult.Value(String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] }))
    }

    private const val UTILITY = "Cloud Services Deployment Utility"
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SPECIALS = "!@#$%&*()+<>?"
    private const val MAX_LENGTH = 450
}
