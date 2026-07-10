package com.ubermicrostudios.textimagecleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Pure logic tests for inclusive date-range filtering (mirrors ViewModel snapshot rules).
 */
class DateRangeLogicTest {

    data class FakeItem(val id: String, val date: Long)

    private fun itemsInRange(
        items: List<FakeItem>,
        start: Long,
        end: Long
    ): List<FakeItem> {
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        return items.filter { it.date in lo..hi }
    }

    @Test
    fun inclusiveRangeContainsBoundaries() {
        val items = listOf(
            FakeItem("a", 100),
            FakeItem("b", 200),
            FakeItem("c", 300)
        )
        val mid = itemsInRange(items, 100, 200).map { it.id }
        assertEquals(listOf("a", "b"), mid)
    }

    @Test
    fun swappedBoundsStillWork() {
        val items = listOf(FakeItem("x", 50), FakeItem("y", 150))
        assertEquals(2, itemsInRange(items, 200, 0).size)
    }

    @Test
    fun monthsFromEpoch() {
        val zone = ZoneId.of("UTC")
        val jan = Instant.parse("2024-01-15T12:00:00Z").toEpochMilli()
        val mar = Instant.parse("2024-03-01T00:00:00Z").toEpochMilli()
        val months = listOf(jan, mar).map {
            YearMonth.from(Instant.ofEpochMilli(it).atZone(zone).toLocalDate())
        }.toSet()
        assertTrue(YearMonth.of(2024, 1) in months)
        assertTrue(YearMonth.of(2024, 3) in months)
        assertFalse(YearMonth.of(2024, 2) in months)
    }
}
