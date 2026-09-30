package com.sailpoint.intellij.transform.ops

import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.Op
import com.sailpoint.intellij.transform.OpCall
import com.sailpoint.intellij.transform.Velocity

/** Operations that make up a value rather than read one. They draw on the context's random source, so tests repeat. */
internal object GeneratorOps {

    val all: Map<String, Op> = mapOf(
        "uuid" to Op(::uuid),
        "randomAlphaNumeric" to Op { call -> random(call, ALPHANUMERIC, default = 32) },
        "randomNumeric" to Op { call -> random(call, DIGITS, default = 10) },
        "usernameGenerator" to Op(::usernameGenerator),
    )

    /**
     * The first username ISC would try: the first pattern whose values are all there, with `uniqueCounter` empty. Only
     * ISC can tell whether it's taken and move on to the next pattern or count up.
     */
    private fun usernameGenerator(call: OpCall): EvalResult {
        val patterns = call.items("patterns").mapNotNull { (element, _) -> element.takeIf { it.isJsonPrimitive }?.asString }
        if (patterns.isEmpty()) return call.missing("patterns")
        val variables = call.variables() + (UNIQUE_COUNTER to EvalResult.Value(""))
        for (pattern in patterns) {
            val used = VARIABLE.findAll(pattern).map { it.groupValues[1] }.filter { it != UNIQUE_COUNTER }.toList()
            used.map { variables[it] }.firstOrNull { it != null && it !is EvalResult.Value }?.let { return it }
            // A pattern missing any of its values is passed over, as ISC does.
            if (used.any { variables[it]?.textOrNull.isNullOrEmpty() }) continue
            return Velocity.substitute(pattern, variables)
        }
        return EvalResult.Failure("No pattern can be filled in: each one uses a value that's empty.", brief = "no pattern fits")
    }

    /** A version 4 UUID: 36 characters, as ISC produces. */
    private fun uuid(call: OpCall): EvalResult {
        val random = call.context.random
        val bytes = random.nextBytes(16)
        bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return EvalResult.Value("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
    }

    private fun random(call: OpCall, alphabet: String, default: Int): EvalResult {
        val length = if (call.raw("length") == null) default else call.int("length")
            ?: return EvalResult.Failure("Length has to be a whole number.")
        if (length !in 1..MAX_LENGTH) return EvalResult.Failure("Length has to be between 1 and $MAX_LENGTH.")
        val random = call.context.random
        return EvalResult.Value(String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] }))
    }

    private const val UNIQUE_COUNTER = "uniqueCounter"
    private val VARIABLE = Regex("""\$!?\{?([A-Za-z_][A-Za-z0-9_]*)""")
    private const val DIGITS = "0123456789"
    private const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ$DIGITS"
    private const val MAX_LENGTH = 450
}
