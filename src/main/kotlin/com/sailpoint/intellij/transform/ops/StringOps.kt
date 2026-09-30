package com.sailpoint.intellij.transform.ops

import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.Op
import com.sailpoint.intellij.transform.OpCall
import com.sailpoint.intellij.transform.firstIssue
import java.text.Normalizer
import java.util.Base64
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
        "indexOf" to Op { call -> indexOf(call) { input, text -> input.indexOf(text) } },
        "lastIndexOf" to Op { call -> indexOf(call) { input, text -> input.lastIndexOf(text) } },
        "leftPad" to Op { call -> pad(call, left = true) },
        "rightPad" to Op { call -> pad(call, left = false) },
        "replaceAll" to Op(::replaceAll),
        "getEndOfString" to Op(::getEndOfString),
        "base64Encode" to Op { call -> call.map { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) } },
        "base64Decode" to Op(::base64Decode),
        // As SailPoint documents it: NFKD, then every combining mark removed.
        "decomposeDiacriticalMarks" to Op { call -> call.map { Normalizer.normalize(it, Normalizer.Form.NFKD).replace(COMBINING_MARKS, "") } },
        "normalizeNames" to Op { call -> call.map(::normalizeName) },
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

    /** Where the `substring` attribute is in the input, by [find]; -1 when it isn't there. */
    private fun indexOf(call: OpCall, find: (String, String) -> Int): EvalResult {
        val text = call.text("substring") ?: return call.missing("substring")
        val input = call.input ?: return EvalResult.Value(null)
        return EvalResult.Value(find(input, text).toString())
    }

    /** Pads the input out to `length` with `padding` (a space by default); a longer input is left as it is. */
    private fun pad(call: OpCall, left: Boolean): EvalResult {
        val length = call.int("length") ?: return if (call.raw("length") == null) call.missing("length")
            else EvalResult.Failure("Length has to be a whole number.")
        val padding = call.text("padding")?.takeIf { it.isNotEmpty() } ?: " "
        val input = call.input ?: return EvalResult.Value(null)
        val needed = length - input.length
        if (needed <= 0) return EvalResult.Value(input)
        // A padding of several characters repeats and is cut to fit, as Apache Commons' leftPad and rightPad do.
        val fill = padding.repeat(needed / padding.length + 1).take(needed)
        return EvalResult.Value(if (left) fill + input else input + fill)
    }

    /** Each pattern in the table replaced by its value, in the table's order. No table leaves the input as it is. */
    private fun replaceAll(call: OpCall): EvalResult {
        val input = call.input ?: return EvalResult.Value(null)
        val table = call.raw("table")?.takeIf { it.isJsonObject }?.asJsonObject ?: return EvalResult.Value(input)
        var result: String = input
        for ((pattern, replacement) in table.entrySet()) {
            val regex = try {
                Regex(pattern)
            } catch (e: PatternSyntaxException) {
                return EvalResult.Failure("\"$pattern\" isn't a valid regular expression: ${e.description}.")
            }
            val with = replacement.takeUnless { it.isJsonNull }?.asString.orEmpty()
            result = try {
                regex.replace(result, with)
            } catch (e: RuntimeException) {
                return EvalResult.Failure("\"$with\" isn't a valid replacement: ${e.message}.")
            }
        }
        return EvalResult.Value(result)
    }

    /** The last `numChars` characters; nothing when the input is shorter than that. */
    internal fun getEndOfString(call: OpCall): EvalResult {
        val count = call.int("numChars") ?: return if (call.raw("numChars") == null) call.missing("numChars")
            else EvalResult.Failure("The number of characters has to be a whole number.")
        val input = call.input ?: return EvalResult.Value(null)
        if (count < 0) return EvalResult.Failure("The number of characters can't be negative.")
        return EvalResult.Value(if (count > input.length) null else input.takeLast(count))
    }

    private fun base64Decode(call: OpCall): EvalResult {
        val input = call.input ?: return EvalResult.Value(null)
        return try {
            EvalResult.Value(String(Base64.getDecoder().decode(input.trim()), Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            EvalResult.Failure("\"$input\" isn't base64: ${e.message}.", brief = "isn't base64")
        }
    }

    /**
     * A name cased the way SailPoint's name normalizer documents: each part split off by a space, hyphen or apostrophe
     * gets a capital first letter; Mc and Mac prefixes capitalize the letter after them; the particles von, del, of,
     * de, la and y are lower case; and a Roman numeral at the end is upper case.
     */
    internal fun normalizeName(name: String): String {
        val parts = name.split(NAME_BREAKS)
        val lastWord = parts.indexOfLast { it.isNotBlank() && it !in BREAKS }
        return parts.mapIndexed { index, part ->
            val lower = part.lowercase()
            when {
                part.isEmpty() || part in BREAKS -> part
                lower in PARTICLES -> lower
                index == lastWord && index > 0 && ROMAN.matches(lower) -> part.uppercase()
                lower.startsWith("mc") && lower.length > 2 -> "Mc" + lower.substring(2).replaceFirstChar(Char::uppercase)
                lower.startsWith("mac") && lower.length >= MAC_MIN_LENGTH -> "Mac" + lower.substring(3).replaceFirstChar(Char::uppercase)
                else -> lower.replaceFirstChar(Char::uppercase)
            }
        }.joinToString("")
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

    private val COMBINING_MARKS = Regex("""\p{InCombiningDiacriticalMarks}""")

    /** Splits a name into its parts, keeping each space, hyphen and apostrophe as a part of its own. */
    private val NAME_BREAKS = Regex("""(?<=[ '\-])|(?=[ '\-])""")
    private val BREAKS = setOf(" ", "-", "'")
    private val PARTICLES = setOf("von", "del", "of", "de", "la", "y")
    private val ROMAN = Regex("""(?:i{1,3}|iv|vi{0,3}|ix|x)""")

    /** Shorter "mac" words (Mack, Macy) are ordinary names rather than a prefix. */
    private const val MAC_MIN_LENGTH = 6
}
