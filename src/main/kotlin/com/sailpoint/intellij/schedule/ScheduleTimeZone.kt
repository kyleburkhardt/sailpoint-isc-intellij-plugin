package com.sailpoint.intellij.schedule

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.sailpoint.intellij.api.IscClient
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The clock schedules are shown in: the tenant's own time zone, in standard time.
 * ISC keeps the cron itself in UTC.
 */
data class ScheduleTimeZone(
    /** Minutes ahead of UTC. */
    val offsetMinutes: Int,
    /** Short name shown after times, e.g. `UTC-05:00`. */
    val label: String,
    /** Where the time zone comes from and any caveats. */
    val note: String,
) {
    companion object {
        private val UTC = ScheduleTimeZone(0, "UTC", "Times are shown in UTC.")

        /** Looks up the tenant's time zone with a modal progress, falling back to UTC. Call on the EDT. */
        fun resolve(project: Project, tenantId: String): ScheduleTimeZone = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { lookup(tenantId) }, "Reading Tenant Time Zone", true, project,
            )
        } catch (e: ProcessCanceledException) {
            UTC
        } catch (e: Exception) {
            logger<ScheduleTimeZone>().warn("Could not read the tenant time zone", e)
            UTC.copy(note = "Times are shown in UTC because the tenant's time zone couldn't be read: ${e.message ?: e}")
        }

        private fun lookup(tenantId: String): ScheduleTimeZone {
            val zone = service<IscClient>().timeZone(tenantId)
            val rules = ZoneId.of(zone).rules
            val minutes = rules.getStandardOffset(Instant.now()).totalSeconds / 60
            val daylightSaving = if (rules.isFixedOffset || rules.nextTransition(Instant.now()) == null) "" else
                " The cron is in UTC, so during daylight saving time the runs happen later on the local clock."
            return ScheduleTimeZone(
                minutes, label(minutes),
                "Times are shown in the tenant time zone, $zone, in standard time (${label(minutes)}).$daylightSaving",
            )
        }

        private fun label(minutes: Int) =
            ZoneOffset.ofTotalSeconds(minutes * 60).id.let { if (it == "Z") "UTC" else "UTC$it" }
    }
}
