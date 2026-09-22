package com.sailpoint.intellij.transform

/**
 * The part of Velocity Template Language that transforms actually use: `$variable` references and `#if` / `#elseif` /
 * `#else` / `#end`. Variables are the transform's own extra attributes.
 *
 * Anything outside that — `#foreach`, `#set`, macros, method calls — is reported as unsupported rather than guessed
 * at, so the preview never shows an answer ISC wouldn't give.
 */
internal object Velocity {

    /** Renders [template], resolving `$variable` and evaluating `#if` blocks. */
    fun render(template: String, variables: Map<String, EvalResult>): EvalResult = run(template, variables, directives = true)

    /** Resolves `$variable` only, for attributes that hold a value rather than a template. */
    fun substitute(template: String, variables: Map<String, EvalResult>): EvalResult = run(template, variables, directives = false)

    private fun run(template: String, variables: Map<String, EvalResult>, directives: Boolean): EvalResult = try {
        EvalResult.Value(Renderer(template, variables, directives).render())
    } catch (e: Stop) {
        e.result
    }

    /** Carries a non-value result out of the recursive rendering. */
    private class Stop(val result: EvalResult) : RuntimeException(null, null, false, false)

    private class Renderer(
        private val template: String,
        private val variables: Map<String, EvalResult>,
        private val directives: Boolean,
    ) {
        private var at = 0

        fun render(): String = parse(setOf())

        /** Renders until one of [stopAt] ("elseif", "else", "end") or the end of the template. */
        private fun parse(stopAt: Set<String>): String {
            val out = StringBuilder()
            while (at < template.length) {
                when (template[at]) {
                    '$' -> out.append(reference())
                    '#' -> {
                        if (!directives) {
                            out.append(template[at++])
                            continue
                        }
                        val keyword = directive()
                        if (keyword in stopAt) return out.toString()
                        when (keyword) {
                            "if" -> out.append(ifBlock())
                            "" -> out.append(comment())
                            in ENDINGS -> fail("'#$keyword' has no matching #if.")
                            else -> fail("The preview doesn't support '#$keyword'.")
                        }
                    }
                    else -> out.append(template[at++])
                }
            }
            return out.toString()
        }

        /** `#if(cond) … #elseif(cond) … #else … #end`, with only the chosen branch rendered. */
        private fun ifBlock(): String {
            var taken: String? = null
            var keyword = "if"
            while (true) {
                when (keyword) {
                    "if", "elseif" -> {
                        skipKeyword(keyword)
                        val condition = condition()
                        val body = parse(ENDINGS)
                        if (taken == null && condition) taken = body
                    }
                    "else" -> {
                        skipKeyword(keyword)
                        val body = parse(ENDINGS)
                        if (taken == null) taken = body
                    }
                    "end" -> {
                        skipKeyword(keyword)
                        return taken.orEmpty()
                    }
                }
                if (at >= template.length) fail("This #if has no #end.")
                keyword = directive()
                if (keyword !in ENDINGS) fail("The preview doesn't support '#$keyword'.")
            }
        }

        /** The keyword of the directive at [at], for both `#end` and `#{end}`. */
        private fun directive(): String = if (template.getOrNull(at + 1) == '{') word(at + 2) else word(at + 1)

        /** Steps over `#keyword` or `#{keyword}`. */
        private fun skipKeyword(keyword: String) {
            at++
            if (at < template.length && template[at] == '{') at += keyword.length + 2 else at += keyword.length
        }

        /** `##` to the end of the line, or `#* … *#`. */
        private fun comment(): String {
            when {
                template.startsWith("##", at) -> {
                    val end = template.indexOf('\n', at)
                    at = if (end == -1) template.length else end + 1
                }
                template.startsWith("#*", at) -> {
                    val end = template.indexOf("*#", at)
                    at = if (end == -1) template.length else end + 2
                }
                else -> return template[at++].toString()
            }
            return ""
        }

        /** The parenthesised condition after `#if` / `#elseif`, evaluated. */
        private fun condition(): Boolean {
            while (at < template.length && template[at].isWhitespace()) at++
            if (at >= template.length || template[at] != '(') fail("An #if needs a condition in brackets.")
            var depth = 0
            val start = at
            while (at < template.length) {
                when (template[at]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) {
                        at++
                        return Conditions(template.substring(start + 1, at - 1), ::variable, ::fail).evaluate()
                    }
                }
                at++
            }
            fail("This condition's brackets don't close.")
        }

        /** `$name`, `${name}` or the quiet `$!name`; anything else is a plain dollar sign. */
        private fun reference(): String {
            val start = at
            at++
            if (at < template.length && template[at] == '!') at++
            val braced = at < template.length && template[at] == '{'
            if (braced) at++
            val name = word(at)
            if (name.isEmpty()) {
                at = start + 1
                return "$"
            }
            at += name.length
            if (braced) {
                if (at >= template.length || template[at] != '}') fail("'\${$name' has no closing brace.")
                at++
            }
            return variable(name).orEmpty()
        }

        /** The value of `$name`, failing when nothing defines it — the preview says so rather than printing `$name`. */
        private fun variable(name: String): String? = when (val value = variables[name]) {
            null -> fail("'\$$name' isn't defined. Add an attribute called '$name' to this transform.")
            is EvalResult.Value -> value.text
            else -> throw Stop(value)
        }

        private fun word(from: Int): String {
            var end = from
            while (end < template.length && (template[end].isLetterOrDigit() || template[end] == '_' || template[end] == '-')) end++
            return template.substring(from.coerceAtMost(template.length), end)
        }

        private fun fail(message: String): Nothing = throw Stop(EvalResult.Failure(message))
    }

    private val ENDINGS = setOf("elseif", "else", "end")

    /** Evaluates one `#if` condition: comparisons joined with `&&` and `||`, and `!` for negation. */
    private class Conditions(
        private val text: String,
        private val variable: (String) -> String?,
        private val fail: (String) -> Nothing,
    ) {
        private var at = 0

        fun evaluate(): Boolean = or().also { if (skipSpace() < text.length) fail("\"$text\" isn't a condition the preview understands.") }

        private fun or(): Boolean {
            var value = and()
            while (take("||")) value = and() || value
            return value
        }

        private fun and(): Boolean {
            var value = unary()
            while (take("&&")) value = unary() && value
            return value
        }

        private fun unary(): Boolean {
            if (take("!")) return !unary()
            if (take("(")) {
                val value = or()
                if (!take(")")) fail("\"$text\" has an unclosed bracket.")
                return value
            }
            return comparison()
        }

        private fun comparison(): Boolean {
            val left = operand()
            val operator = OPERATORS.firstOrNull { take(it) } ?: return truthy(left)
            val right = operand()
            return when (operator) {
                "==", "eq" -> left == right
                "!=", "ne" -> left != right
                else -> compare(left, right, operator)
            }
        }

        private fun compare(left: String?, right: String?, operator: String): Boolean {
            val a = left?.toDoubleOrNull()
            val b = right?.toDoubleOrNull()
            val order = if (a != null && b != null) a.compareTo(b) else left.orEmpty().compareTo(right.orEmpty())
            return when (operator) {
                ">" -> order > 0
                ">=" -> order >= 0
                "<" -> order < 0
                else -> order <= 0
            }
        }

        private fun operand(): String? {
            skipSpace()
            if (at >= text.length) fail("\"$text\" is missing a value.")
            return when (val c = text[at]) {
                '$' -> {
                    at++
                    if (at < text.length && text[at] == '!') at++
                    val braced = at < text.length && text[at] == '{'
                    if (braced) at++
                    val name = word()
                    if (braced && !take("}")) fail("\"$text\" has an unclosed brace.")
                    variable(name)
                }
                '\'', '"' -> {
                    at++
                    val end = text.indexOf(c, at)
                    if (end == -1) fail("\"$text\" has an unclosed quote.")
                    text.substring(at, end).also { at = end + 1 }
                }
                else -> word().ifEmpty { fail("\"$text\" isn't a condition the preview understands.") }
            }
        }

        private fun truthy(value: String?): Boolean = !value.isNullOrEmpty() && value != "false"

        private fun word(): String {
            val start = at
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] in "_-.")) at++
            return text.substring(start, at)
        }

        private fun take(token: String): Boolean {
            skipSpace()
            // "eq" and "ne" are words, so they must not swallow the start of an identifier.
            if (!text.startsWith(token, at)) return false
            if (token[0].isLetter() && at + token.length < text.length && text[at + token.length].isLetterOrDigit()) return false
            at += token.length
            return true
        }

        private fun skipSpace(): Int {
            while (at < text.length && text[at].isWhitespace()) at++
            return at
        }

        companion object {
            /** Longest first, so `>=` wins over `>`. */
            private val OPERATORS = listOf("==", "!=", ">=", "<=", ">", "<", "eq", "ne")
        }
    }
}
