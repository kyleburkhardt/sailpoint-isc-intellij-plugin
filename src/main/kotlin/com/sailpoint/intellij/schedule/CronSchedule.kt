package com.sailpoint.intellij.schedule

/**
 * The aggregation schedules a person can build without knowing cron, and their Quartz cron form
 * (`seconds minutes hours day-of-month month day-of-week`, day of week 1-7 = Sunday-Saturday).
 * Anything else is a custom expression that only the raw cron field can edit.
 *
 * ISC stores the cron in UTC; [shifted] moves a schedule between UTC and the clock people read it in.
 */
sealed interface CronSchedule {
    fun toCron(): String

    fun describe(): String

    /**
     * The same moments on a clock [minutes] ahead (UTC to UTC-5 is `-300`, and back is `300`),
     * or null if cron can't express them there, e.g. a monthly run pushed into the previous month.
     */
    fun shifted(minutes: Int): CronSchedule?

    /** Hours 0, [interval], 2×[interval]… of every day, at [minute] past. */
    data class EveryHours(val interval: Int, val minute: Int) : CronSchedule {
        override fun toCron() = "0 $minute ${if (interval == 1) "*" else "0/$interval"} * * ?"

        override fun shifted(minutes: Int) = atHours((0 until 24 step interval).map { shift(it, minute, minutes) })

        override fun describe(): String {
            val times = (0 until 24 step interval).map { time(it, minute) }
            val examples = if (times.size <= 6) times.joinToString(", ") else times.take(3).joinToString(", ") + ", …"
            return "${if (interval == 1) "Every hour" else "Every $interval hours"} ($examples)"
        }
    }

    /** Every day at each of [hours], at [minute] past. */
    data class Daily(val hours: List<Int>, val minute: Int) : CronSchedule {
        override fun toCron() = "0 $minute ${hours.joinToString(",")} * * ?"

        override fun shifted(minutes: Int) = atHours(hours.map { shift(it, minute, minutes) })

        override fun describe() = "Every day at ${readableList(hours.map { time(it, minute) })}"
    }

    /** On each of [days] (1-7, Sunday-Saturday) at [hour]:[minute]. */
    data class Weekly(val days: List<Int>, val hour: Int, val minute: Int) : CronSchedule {
        override fun toCron() = "0 $minute $hour ? * ${days.joinToString(",")}"

        override fun shifted(minutes: Int): CronSchedule {
            val at = shift(hour, minute, minutes)
            return Weekly(days.map { Math.floorMod(it - 1 + at.dayShift, 7) + 1 }.sorted(), at.hour, at.minute)
        }

        override fun describe() = "Every ${readableList(days.map { DAY_NAMES[it - 1] })} at ${time(hour, minute)}"
    }

    /** On day [day] of each month at [hour]:[minute]; months without that day are skipped. */
    data class Monthly(val day: Int, val hour: Int, val minute: Int) : CronSchedule {
        override fun toCron() = "0 $minute $hour $day * ?"

        override fun shifted(minutes: Int): CronSchedule? {
            val at = shift(hour, minute, minutes)
            return (day + at.dayShift).takeIf { it in 1..31 }?.let { Monthly(it, at.hour, at.minute) }
        }

        override fun describe() =
            "On day $day of every month at ${time(hour, minute)}" + if (day > 28) " (skipped in months without that day)" else ""
    }

    companion object {
        val DAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

        private val EVERY = Regex("""[*0]/(\d+)""")
        private val ANY = setOf("*", "?")

        /** The schedule [cron] expresses, or null if it's a custom expression. */
        fun parse(cron: String): CronSchedule? {
            val fields = cron.trim().split(Regex("""\s+"""))
            if (fields.size !in 6..7 || fields[0] != "0" || fields[4] != "*" || fields.getOrElse(6) { "*" } != "*") return null
            val minute = fields[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            val (hour, dayOfMonth, dayOfWeek) = Triple(fields[2], fields[3], fields[5])
            val everyDay = dayOfMonth in ANY && dayOfWeek in ANY && !(dayOfMonth == "?" && dayOfWeek == "?")
            return when {
                everyDay && hour == "*" -> EveryHours(1, minute)
                everyDay && EVERY.matches(hour) ->
                    EVERY.matchEntire(hour)!!.groupValues[1].toIntOrNull()?.takeIf { it in 1..23 }?.let { EveryHours(it, minute) }
                everyDay -> numbers(hour, 0..23)?.let { Daily(it, minute) }
                dayOfMonth == "?" && dayOfWeek !in ANY -> {
                    val at = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                    days(dayOfWeek)?.let { Weekly(it, at, minute) }
                }
                dayOfWeek == "?" -> {
                    val at = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                    dayOfMonth.toIntOrNull()?.takeIf { it in 1..31 }?.let { Monthly(it, at, minute) }
                }
                else -> null
            }
        }

        private data class Shifted(val dayShift: Int, val hour: Int, val minute: Int)

        private fun shift(hour: Int, minute: Int, by: Int): Shifted {
            val total = hour * 60 + minute + by
            val ofDay = Math.floorMod(total, 24 * 60)
            return Shifted(Math.floorDiv(total, 24 * 60), ofDay / 60, ofDay % 60)
        }

        /** A run at each of [times] every day, as every-few-hours when the hours line up from midnight. */
        private fun atHours(times: List<Shifted>): CronSchedule {
            val hours = times.map { it.hour }.distinct().sorted()
            val minute = times.first().minute
            val step = if (hours.size > 1) hours[1] - hours[0] else 0
            return if (step > 0 && hours == (0 until 24 step step).toList()) EveryHours(step, minute) else Daily(hours, minute)
        }

        private fun numbers(field: String, range: IntRange): List<Int>? =
            field.split(',').map { part -> part.toIntOrNull()?.takeIf { it in range } ?: return null }.distinct().sorted()

        /** Day-of-week numbers (1-7) or names (`MON`). */
        private fun days(field: String): List<Int>? = field.split(',').map { part ->
            part.toIntOrNull()?.takeIf { it in 1..7 }
                ?: DAY_NAMES.indexOfFirst { it.take(3).equals(part, ignoreCase = true) }.takeIf { it >= 0 }?.plus(1)
                ?: return null
        }.distinct().sorted()

        private fun time(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

        private fun readableList(items: List<String>) =
            if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
