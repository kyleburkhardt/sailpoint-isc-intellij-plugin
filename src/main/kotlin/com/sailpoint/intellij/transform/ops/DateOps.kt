package com.sailpoint.intellij.transform.ops

import com.sailpoint.intellij.transform.EvalResult
import com.sailpoint.intellij.transform.Op
import com.sailpoint.intellij.transform.OpCall
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Operations on dates. ISC runs them in UTC, so the preview does too. */
internal object DateOps {

    val all: Map<String, Op> = mapOf(
        "dateFormat" to Op(::dateFormat),
        "dateMath" to Op(::dateMath),
        "dateCompare" to Op(::dateCompare),
    )

    // ------------------------------------------------------------ dateFormat

    private fun dateFormat(call: OpCall): EvalResult {
        val text = call.input?.takeIf { it.isNotEmpty() } ?: return EvalResult.Value(null)
        val inputFormat = call.text("inputFormat")?.takeIf { it.isNotBlank() } ?: ISO8601
        val outputFormat = call.text("outputFormat")?.takeIf { it.isNotBlank() } ?: ISO8601
        val instant = parse(text, inputFormat) ?: return EvalResult.Failure(
            "\"$text\" doesn't match the input format \"$inputFormat\".",
            brief = "doesn't match $inputFormat",
        )
        return runCatching { EvalResult.Value(format(instant, outputFormat)) }
            .getOrElse { EvalResult.Failure("\"$outputFormat\" isn't a date format: ${it.message}") }
    }

    private fun parse(text: String, format: String): Instant? = when (format) {
        ISO8601 -> parseIso(text)
        EPOCH_TIME_JAVA -> text.trim().toLongOrNull()?.let(Instant::ofEpochMilli)
        EPOCH_TIME_WIN32 -> text.trim().toLongOrNull()?.let { Instant.ofEpochMilli(it / 10_000 - WIN32_EPOCH_OFFSET_MILLIS) }
        else -> try {
            simpleFormat(NAMED[format] ?: format).parse(text).toInstant()
        } catch (e: ParseException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun format(instant: Instant, format: String): String = when (format) {
        EPOCH_TIME_JAVA -> instant.toEpochMilli().toString()
        EPOCH_TIME_WIN32 -> ((instant.toEpochMilli() + WIN32_EPOCH_OFFSET_MILLIS) * 10_000).toString()
        else -> simpleFormat(NAMED[format] ?: format).format(Date.from(instant))
    }

    private fun simpleFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    // -------------------------------------------------------------- dateMath

    private fun dateMath(call: OpCall): EvalResult {
        val expression = call.text("expression")?.trim() ?: return call.missing("expression")
        val roundUp = call.bool("roundUp", false)

        // "now" wins over any input, as it does in ISC.
        val fromNow = expression.startsWith("now")
        val start = when {
            fromNow -> call.context.now
            call.input.isNullOrEmpty() -> return EvalResult.Value(null)
            else -> parseIso(call.input) ?: return EvalResult.Failure(
                "Date math needs an ISO8601 date, not \"${call.input}\". A Date Format step can convert it first.",
                brief = "needs an ISO8601 date",
            )
        }

        var date = start.atZone(ZoneOffset.UTC)
        var rest = if (fromNow) expression.removePrefix("now") else expression
        while (rest.isNotEmpty()) {
            val match = TERM.matchAt(rest, 0)
                ?: return EvalResult.Failure("\"$expression\" isn't a date math expression; \"$rest\" can't be read.")
            val (operator, amount, unitName) = match.destructured
            val unit = UNITS.getValue(unitName)
            date = if (operator == "/") {
                if (unit == ChronoUnit.WEEKS) return EvalResult.Failure("Date math can't round to a week.")
                round(date, unit, roundUp)
            } else {
                val count = amount.ifEmpty { "1" }.toLong()
                date.plus(if (operator == "-") -count else count, unit)
            }
            rest = rest.substring(match.range.last + 1)
        }
        return EvalResult.Value(MATH_OUTPUT.format(date))
    }

    /** Cuts [date] down to the start of its [unit], or with [up], to the start of the next one. */
    private fun round(date: ZonedDateTime, unit: ChronoUnit, up: Boolean): ZonedDateTime {
        val down = when (unit) {
            ChronoUnit.YEARS -> date.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS)
            ChronoUnit.MONTHS -> date.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            else -> date.truncatedTo(unit)
        }
        return if (up) down.plus(1, unit) else down
    }

    // ----------------------------------------------------------- dateCompare

    private fun dateCompare(call: OpCall): EvalResult {
        val first = date(call, "firstDate") ?: return call.missing("firstDate")
        if (first !is Dated.At) return (first as Dated.Issue).result
        val second = date(call, "secondDate") ?: return call.missing("secondDate")
        if (second !is Dated.At) return (second as Dated.Issue).result
        val operator = call.text("operator") ?: return call.missing("operator")
        val order = first.instant.compareTo(second.instant)
        val holds = when (operator.uppercase()) {
            "LT" -> order < 0
            "LTE" -> order <= 0
            "GT" -> order > 0
            "GTE" -> order >= 0
            else -> return EvalResult.Failure("\"$operator\" isn't an operator; use LT, LTE, GT or GTE.")
        }
        val branch = if (holds) "positiveCondition" else "negativeCondition"
        return call.value(branch) ?: call.missing(branch)
    }

    private sealed interface Dated {
        data class At(val instant: Instant) : Dated
        data class Issue(val result: EvalResult) : Dated
    }

    /** One of dateCompare's dates: `now`, or an ISO8601 date written there or worked out by a nested transform. */
    private fun date(call: OpCall, name: String): Dated? {
        val result = call.value(name) ?: return null
        if (result !is EvalResult.Value) return Dated.Issue(result)
        val text = result.text?.trim()
        val label = if (name == "firstDate") "first date" else "second date"
        return when {
            text.isNullOrEmpty() -> Dated.Issue(EvalResult.Failure("There's no $label to compare.", brief = "no $label"))
            text.equals("now", ignoreCase = true) -> Dated.At(call.context.now)
            else -> parseIso(text)?.let { Dated.At(it) } ?: Dated.Issue(
                EvalResult.Failure("The $label, \"$text\", isn't an ISO8601 date. A Date Format step can convert it.", brief = "$label isn't ISO8601"),
            )
        }
    }

    // ---------------------------------------------------------------- shared

    /**
     * Reads an ISO8601 date however much of it is there: a date alone, minutes with no seconds (as date math writes
     * them), any fraction, and an offset as `Z`, `+05:00`, `+0500` or none at all, which is taken as UTC.
     */
    private fun parseIso(text: String): Instant? = runCatching {
        val parsed = ISO_LENIENT.parse(text.trim())
        val offset = if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
            ZoneOffset.ofTotalSeconds(parsed.get(ChronoField.OFFSET_SECONDS))
        } else {
            ZoneOffset.UTC
        }
        java.time.LocalDateTime.of(
            parsed.get(ChronoField.YEAR), parsed.get(ChronoField.MONTH_OF_YEAR), parsed.get(ChronoField.DAY_OF_MONTH),
            parsed.get(ChronoField.HOUR_OF_DAY), parsed.get(ChronoField.MINUTE_OF_HOUR), parsed.get(ChronoField.SECOND_OF_MINUTE),
            parsed.get(ChronoField.NANO_OF_SECOND),
        ).toInstant(offset)
    }.getOrNull()

    private const val ISO8601 = "ISO8601"
    private const val EPOCH_TIME_JAVA = "EPOCH_TIME_JAVA"
    private const val EPOCH_TIME_WIN32 = "EPOCH_TIME_WIN32"

    /** Milliseconds from 1601-01-01, where Windows file times start, to 1970-01-01. */
    private const val WIN32_EPOCH_OFFSET_MILLIS = 11_644_473_600_000L

    /** The named formats ISC documents, as the patterns they stand for. */
    private val NAMED = mapOf(
        ISO8601 to "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "LDAP" to "yyyyMMddHHmmss.Z",
        "PEOPLE_SOFT" to "MM/dd/yyyy",
    )

    /** Date math writes minutes and a UTC `Z`, e.g. `2025-01-14T06:00Z`, as ISC returns it. */
    private val MATH_OUTPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")

    private val TERM = Regex("""([+\-/])(\d*)([yMwdhms])""")

    private val UNITS = mapOf(
        "y" to ChronoUnit.YEARS, "M" to ChronoUnit.MONTHS, "w" to ChronoUnit.WEEKS, "d" to ChronoUnit.DAYS,
        "h" to ChronoUnit.HOURS, "m" to ChronoUnit.MINUTES, "s" to ChronoUnit.SECONDS,
    )

    private val ISO_LENIENT: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd")
        .optionalStart()
        .appendLiteral('T').appendPattern("HH:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .optionalEnd()
        .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
        .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
        .optionalStart().appendOffset("+HH", "Z").optionalEnd()
        .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
        .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
        .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
        .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
        .toFormatter(Locale.US)
}
