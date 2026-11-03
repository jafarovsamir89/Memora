package ai.memora.app.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskReminderTimeTest {
    @Test fun resolvesRelativeMinutesFromDeviceInstantInBaku() {
        assertEquals("2026-11-03T12:15", TaskReminderTime.resolveRelative(
            15, "Asia/Baku", Instant.parse("2026-11-03T08:00:00Z")))
    }

    @Test fun relativeReminderCanCrossLocalMidnight() {
        assertEquals("2026-11-04T00:10", TaskReminderTime.resolveRelative(
            15, "Asia/Baku", Instant.parse("2026-11-03T19:55:00Z")))
    }

    @Test fun rejectsInvalidIntervalsZonesAndAmbiguousLocalTimes() {
        assertNull(TaskReminderTime.resolveRelative(0, "Asia/Baku", Instant.EPOCH))
        assertNull(TaskReminderTime.resolveRelative(15, "not-a-zone", Instant.EPOCH))
        assertNull(TaskReminderTime.resolveRelative(20, "America/New_York", Instant.parse("2026-11-01T05:50:00Z")))
    }
}
