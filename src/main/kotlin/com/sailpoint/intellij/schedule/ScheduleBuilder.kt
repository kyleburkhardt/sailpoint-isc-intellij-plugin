package com.sailpoint.intellij.schedule

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.selectedValueMatches
import com.sailpoint.intellij.schedule.ScheduleBuilder.Frequency.CUSTOM
import com.sailpoint.intellij.schedule.ScheduleBuilder.Frequency.DAILY
import com.sailpoint.intellij.schedule.ScheduleBuilder.Frequency.EVERY_HOURS
import com.sailpoint.intellij.schedule.ScheduleBuilder.Frequency.MONTHLY
import com.sailpoint.intellij.schedule.ScheduleBuilder.Frequency.WEEKLY
import java.awt.event.ItemEvent
import javax.swing.event.DocumentEvent

/**
 * Point-and-click schedule controls kept in sync both ways with a raw cron expression field:
 * changing the controls rewrites the cron, and typing cron updates the controls. Expressions
 * the controls can't represent switch them to Custom, leaving the cron field in charge.
 *
 * The cron is in UTC, as ISC stores it; the controls and summary use [zone].
 */
class ScheduleBuilder(initialCron: String, private val zone: ScheduleTimeZone) {
    enum class Frequency(private val label: String) {
        EVERY_HOURS("Every few hours"),
        DAILY("Every day"),
        WEEKLY("Every week"),
        MONTHLY("Every month"),
        CUSTOM("Custom cron expression");

        override fun toString() = label
    }

    private val frequency = ComboBox(Frequency.entries.toTypedArray())
    private val interval = JBIntSpinner(4, 1, 23)
    private val hours = (0..23).map { JBCheckBox("%02d".format(it)) }
    private val days = CronSchedule.DAY_NAMES.map { JBCheckBox(it.take(3)) }
    private val dayOfMonth = JBIntSpinner(1, 1, 31)
    /** Minute past the hour, for schedules that run at several hours. */
    private val minutePastHour = JBIntSpinner(0, 0, 59)
    /** Time of day, for schedules that run once on the chosen days. */
    private val hour = JBIntSpinner(6, 0, 23)
    private val minute = JBIntSpinner(0, 0, 59)
    private val cronField = JBTextField(initialCron)
    private val summary = JBLabel()
    /** Set while one side writes to the other, so the change doesn't echo back. */
    private var syncing = false

    val cronExpression: String get() = cronField.text.trim()

    init {
        showCron()
        frequency.addItemListener { if (it.stateChange == ItemEvent.SELECTED) controlsChanged() }
        listOf(interval, dayOfMonth, minutePastHour, hour, minute).forEach { it.addChangeListener { controlsChanged() } }
        (hours + days).forEach { it.addItemListener { controlsChanged() } }
        cronField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!syncing) showCron()
            }
        })
    }

    fun addTo(panel: Panel) = with(panel) {
        row("Runs:") { cell(frequency) }
        row("Every:") { cell(interval); label("hours") }.visibleIf(shownFor(EVERY_HOURS))
        row("At hours:") { hours.take(12).forEach { cell(it) } }.visibleIf(shownFor(DAILY))
        row("") { hours.drop(12).forEach { cell(it) } }.visibleIf(shownFor(DAILY))
        row("On:") { days.forEach { cell(it) } }.visibleIf(shownFor(WEEKLY))
        row("On day:") { cell(dayOfMonth); label("of the month") }.visibleIf(shownFor(MONTHLY))
        row("Minutes past the hour:") { cell(minutePastHour) }.visibleIf(shownFor(EVERY_HOURS, DAILY))
        row("At:") { cell(hour); label(":"); cell(minute) }.visibleIf(shownFor(WEEKLY, MONTHLY))
        row("") { cell(summary) }
            .rowComment(zone.note)
        row("Cron expression (UTC):") {
            cell(cronField).align(AlignX.FILL)
                .validationOnApply { validate() }
                .comment("Seconds, minutes, hours, day of month, month, day of week (1-7, Sunday-Saturday), in UTC.")
        }
    }

    private fun shownFor(vararg shown: Frequency): ComponentPredicate =
        frequency.selectedValueMatches { it != null && it in shown }

    private fun validate(): ValidationInfo? = when {
        frequency.selectedItem == DAILY && hours.none { it.isSelected } -> ValidationInfo("Pick at least one hour", hours.first())
        frequency.selectedItem == WEEKLY && days.none { it.isSelected } -> ValidationInfo("Pick at least one day", days.first())
        crossesMonth() -> ValidationInfo(CROSSES_MONTH, dayOfMonth)
        cronExpression.isEmpty() -> ValidationInfo("Cron expression is required", cronField)
        cronExpression.split(Regex("""\s+""")).size !in 6..7 -> ValidationInfo("A cron expression has 6 or 7 fields", cronField)
        else -> null
    }

    /** The schedule the controls describe, or null for Custom or an incomplete selection. */
    private fun builtSchedule(): CronSchedule? = when (frequency.selectedItem as Frequency) {
        EVERY_HOURS -> CronSchedule.EveryHours(interval.number, minutePastHour.number)
        DAILY -> hours.indices.filter { hours[it].isSelected }.ifEmpty { null }?.let { CronSchedule.Daily(it, minutePastHour.number) }
        WEEKLY -> days.indices.filter { days[it].isSelected }.map { it + 1 }.ifEmpty { null }
            ?.let { CronSchedule.Weekly(it, hour.number, minute.number) }
        MONTHLY -> CronSchedule.Monthly(dayOfMonth.number, hour.number, minute.number)
        CUSTOM -> null
    }

    /** A monthly run that falls in a different month in UTC, which cron can't express. */
    private fun crossesMonth() = frequency.selectedItem == MONTHLY && builtSchedule()?.shifted(-zone.offsetMinutes) == null

    private fun controlsChanged() {
        if (syncing) return
        builtSchedule()?.shifted(-zone.offsetMinutes)?.let { utc -> sync { cronField.text = utc.toCron() } }
        updateSummary()
    }

    /** Sets the controls from the cron field. */
    private fun showCron() {
        sync {
            when (val schedule = CronSchedule.parse(cronField.text)?.shifted(zone.offsetMinutes)) {
                is CronSchedule.EveryHours -> {
                    frequency.selectedItem = EVERY_HOURS
                    interval.number = schedule.interval
                    minutePastHour.number = schedule.minute
                }
                is CronSchedule.Daily -> {
                    frequency.selectedItem = DAILY
                    hours.forEachIndexed { h, box -> box.isSelected = h in schedule.hours }
                    minutePastHour.number = schedule.minute
                }
                is CronSchedule.Weekly -> {
                    frequency.selectedItem = WEEKLY
                    days.forEachIndexed { d, box -> box.isSelected = d + 1 in schedule.days }
                    hour.number = schedule.hour
                    minute.number = schedule.minute
                }
                is CronSchedule.Monthly -> {
                    frequency.selectedItem = MONTHLY
                    dayOfMonth.number = schedule.day
                    hour.number = schedule.hour
                    minute.number = schedule.minute
                }
                null -> frequency.selectedItem = CUSTOM
            }
        }
        updateSummary()
    }

    private fun updateSummary() {
        summary.text = when {
            frequency.selectedItem == DAILY && hours.none { it.isSelected } -> "Pick at least one hour."
            frequency.selectedItem == WEEKLY && days.none { it.isSelected } -> "Pick at least one day."
            crossesMonth() -> "$CROSSES_MONTH."
            else -> CronSchedule.parse(cronField.text)?.shifted(zone.offsetMinutes)?.describe()?.let { "$it (${zone.label})" }
                ?: "Custom schedule: edit the UTC cron expression directly."
        }
    }

    private companion object {
        const val CROSSES_MONTH = "In UTC this run falls in a different month, which cron can't express; pick another day or time"
    }

    private fun sync(block: () -> Unit) {
        syncing = true
        try {
            block()
        } finally {
            syncing = false
        }
    }
}
