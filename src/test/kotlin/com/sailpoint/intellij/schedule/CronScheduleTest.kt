package com.sailpoint.intellij.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CronScheduleTest {
    @Test
    fun `ISC's default of several times a day is a daily schedule`() {
        val schedule = CronSchedule.parse("0 0 5,13,21 * * ?")
        assertEquals(CronSchedule.Daily(listOf(5, 13, 21), 0), schedule)
        assertEquals("Every day at 05:00, 13:00 and 21:00", schedule!!.describe())
    }

    @Test
    fun `every few hours`() {
        assertEquals(CronSchedule.EveryHours(4, 30), CronSchedule.parse("0 30 0/4 * * ?"))
        assertEquals(CronSchedule.EveryHours(4, 30), CronSchedule.parse("0 30 */4 * * ?"))
        assertEquals(CronSchedule.EveryHours(1, 0), CronSchedule.parse("0 0 * * * ?"))
        assertEquals("Every 8 hours (00:15, 08:15, 16:15)", CronSchedule.EveryHours(8, 15).describe())
    }

    @Test
    fun `weekly accepts day numbers and names`() {
        val expected = CronSchedule.Weekly(listOf(2, 4), 6, 0)
        assertEquals(expected, CronSchedule.parse("0 0 6 ? * 2,4"))
        assertEquals(expected, CronSchedule.parse("0 0 6 ? * MON,WED"))
        assertEquals("Every Monday and Wednesday at 06:00", expected.describe())
    }

    @Test
    fun `monthly`() {
        assertEquals(CronSchedule.Monthly(1, 2, 30), CronSchedule.parse("0 30 2 1 * ?"))
    }

    @Test
    fun `built schedules round-trip through cron`() {
        listOf(
            CronSchedule.EveryHours(1, 5),
            CronSchedule.EveryHours(6, 0),
            CronSchedule.Daily(listOf(0, 12), 45),
            CronSchedule.Weekly(listOf(1, 7), 23, 59),
            CronSchedule.Monthly(31, 0, 0),
        ).forEach { assertEquals(it, CronSchedule.parse(it.toCron())) }
    }

    @Test
    fun `shifting moves times and wraps days`() {
        // ISC's default, read in GMT-5: 00:00, 08:00 and 16:00.
        assertEquals(CronSchedule.EveryHours(8, 0), CronSchedule.Daily(listOf(5, 13, 21), 0).shifted(-5 * 60))
        // Monday 02:00 at UTC+5:30 is Sunday 20:30 UTC.
        assertEquals(CronSchedule.Weekly(listOf(1), 20, 30), CronSchedule.Weekly(listOf(2), 2, 0).shifted(-330))
        // Saturday 22:00 at UTC-5 is Sunday 03:00 UTC.
        assertEquals(CronSchedule.Weekly(listOf(1), 3, 0), CronSchedule.Weekly(listOf(7), 22, 0).shifted(300))
        // Every 5 hours from midnight at UTC-5 isn't every 5 hours from midnight in UTC.
        assertEquals(CronSchedule.Daily(listOf(1, 5, 10, 15, 20), 0), CronSchedule.EveryHours(5, 0).shifted(300))
    }

    @Test
    fun `monthly runs that change month can't be shifted`() {
        assertNull(CronSchedule.Monthly(1, 0, 30).shifted(-60))
        assertNull(CronSchedule.Monthly(31, 23, 0).shifted(120))
        assertEquals(CronSchedule.Monthly(15, 23, 0), CronSchedule.Monthly(16, 1, 0).shifted(-120))
    }

    @Test
    fun `shifting there and back is lossless`() {
        listOf(
            CronSchedule.Daily(listOf(2, 9, 17), 15),
            CronSchedule.EveryHours(6, 0),
            CronSchedule.Weekly(listOf(1, 4, 7), 23, 45),
            CronSchedule.Monthly(15, 12, 0),
        ).forEach { schedule ->
            listOf(-600, -330, 0, 60, 345, 780).forEach { offset ->
                assertEquals("$schedule at $offset", schedule, schedule.shifted(offset)!!.shifted(-offset))
            }
        }
    }

    @Test
    fun `expressions the builder can't show are custom`() {
        listOf(
            "0 0 8-17 * * ?", // hour range
            "30 0 5 * * ?", // non-zero seconds
            "0 0 5 * 1 ?", // specific month
            "0 0 2/4 * * ?", // offset interval
            "0 0 5 L * ?", // last day of month
            "0 0 5 ? * 2#1", // first Monday
            "0 0 5 * * ? 2027", // specific year
            "0 0 5 * *", // too few fields
            "",
        ).forEach { assertNull(it, CronSchedule.parse(it)) }
    }
}
