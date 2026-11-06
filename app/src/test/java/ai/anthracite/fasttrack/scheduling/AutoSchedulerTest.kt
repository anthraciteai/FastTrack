package ai.anthracite.fasttrack.scheduling

import ai.anthracite.fasttrack.data.local.EventType
import ai.anthracite.fasttrack.data.local.ScheduleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AutoSchedulerTest {

    /** Epoch ms for today (relative to [base]) at [hour]:[minute] local time. */
    private fun at(base: Long, dayOffset: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            timeInMillis = base
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun event(
        id: Long,
        type: EventType,
        start: Long,
        durationMin: Int,
        title: String = type.name
    ) = ScheduleEvent(
        id = id,
        title = title,
        startEpochMs = start,
        endEpochMs = start + durationMin * 60_000L,
        type = type.name
    )

    /** Anchor "now" at 5:00 AM so the whole 6:00–22:00 window is in the future for the test day. */
    private fun now(base: Long) = at(base, 0, 5, 0)

    @Test
    fun `run colliding with a meeting moves to the next free slot`() {
        val base = System.currentTimeMillis()
        val run = event(1, EventType.WORKOUT, at(base, 0, 9, 0), 45, "Morning Run")
        val meeting = event(2, EventType.OTHER, at(base, 0, 9, 0), 60, "Standup")

        val moves = AutoScheduler.resolve(listOf(run, meeting), now = now(base))

        assertEquals(1, moves.size)
        val move = moves.first()
        assertEquals(1L, move.event.id)
        // Meeting ends 10:00 → next 15-min-aligned free slot is 10:00.
        assertEquals(at(base, 0, 10, 0), move.newStartMs)
        // Duration preserved.
        assertEquals(45 * 60_000L, move.newEndMs - move.newStartMs)
    }

    @Test
    fun `no conflict produces no moves`() {
        val base = System.currentTimeMillis()
        val run = event(1, EventType.WORKOUT, at(base, 0, 7, 0), 45)
        val meeting = event(2, EventType.OTHER, at(base, 0, 12, 0), 60)

        val moves = AutoScheduler.resolve(listOf(run, meeting), now = now(base))

        assertTrue(moves.isEmpty())
    }

    @Test
    fun `meetings and meals are never moved`() {
        val base = System.currentTimeMillis()
        val meal = event(1, EventType.MEAL, at(base, 0, 9, 0), 30)
        val meeting = event(2, EventType.OTHER, at(base, 0, 9, 0), 60)

        val moves = AutoScheduler.resolve(listOf(meal, meeting), now = now(base))

        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a fast does not block a workout`() {
        val base = System.currentTimeMillis()
        // 16h fast covering the whole day; workout sits inside it with no other obstacle.
        val fast = event(1, EventType.FAST, at(base, 0, 0, 0), 16 * 60)
        val run = event(2, EventType.WORKOUT, at(base, 0, 9, 0), 45)

        val moves = AutoScheduler.resolve(listOf(fast, run), now = now(base))

        assertTrue(moves.isEmpty())
    }

    @Test
    fun `two runs do not get stacked onto the same slot`() {
        val base = System.currentTimeMillis()
        val meeting = event(1, EventType.OTHER, at(base, 0, 9, 0), 60)
        val runA = event(2, EventType.WORKOUT, at(base, 0, 9, 0), 45, "Run A")
        val runB = event(3, EventType.WORKOUT, at(base, 0, 9, 15), 45, "Run B")

        val moves = AutoScheduler.resolve(listOf(meeting, runA, runB), now = now(base))

        assertEquals(2, moves.size)
        val slots = moves.map { it.newStartMs to it.newEndMs }
        // No two placed runs overlap each other.
        for (i in slots.indices) for (j in i + 1 until slots.size) {
            val (aS, aE) = slots[i]; val (bS, bE) = slots[j]
            assertTrue("placed runs overlap", aE <= bS || bE <= aS)
        }
    }

    @Test
    fun `conflict late in the day rolls over to the next day's window`() {
        val base = System.currentTimeMillis()
        // Meeting occupies the tail of the window; a 90-min run at 21:00 cannot fit before 22:00.
        val meeting = event(1, EventType.OTHER, at(base, 0, 21, 0), 60)
        val run = event(2, EventType.WORKOUT, at(base, 0, 21, 0), 90)

        val moves = AutoScheduler.resolve(listOf(meeting, run), now = now(base))

        assertEquals(1, moves.size)
        // Should land at the next day's 6:00 window start.
        assertEquals(at(base, 1, 6, 0), moves.first().newStartMs)
    }
}
