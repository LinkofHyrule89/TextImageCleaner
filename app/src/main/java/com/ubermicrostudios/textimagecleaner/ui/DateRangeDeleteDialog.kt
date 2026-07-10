package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Material3 date range picker for optional date-range deletion.
 * Only months that contain media (for the current filter) are selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDeleteDialog(
    monthsWithMedia: Set<YearMonth>,
    yearRange: IntRange,
    matchCountForRange: (startInclusiveMs: Long, endInclusiveMs: Long) -> Int,
    onDismiss: () -> Unit,
    onConfirmRange: (startInclusiveMs: Long, endInclusiveMs: Long) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()) }

    val selectableDates = remember(monthsWithMedia) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                // M3 cells use UTC midnight representing a calendar date; match YearMonth of that date.
                val date = Instant.ofEpochMilli(utcTimeMillis)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                return YearMonth.of(date.year, date.month) in monthsWithMedia
            }

            override fun isSelectableYear(year: Int): Boolean =
                monthsWithMedia.any { it.year == year }
        }
    }

    val safeYearRange = remember(yearRange) {
        val start = yearRange.first.coerceAtMost(yearRange.last)
        val end = yearRange.last.coerceAtLeast(yearRange.first)
        IntRange(start, end)
    }

    val state = rememberDateRangePickerState(
        yearRange = safeYearRange,
        selectableDates = selectableDates
    )

    val startUtc = state.selectedStartDateMillis
    val endUtc = state.selectedEndDateMillis

    // Picker returns UTC midnight; convert to local day bounds for matching MediaItem.date.
    val localBounds = remember(startUtc, endUtc, zone) {
        if (startUtc == null || endUtc == null) null
        else {
            val startDay = Instant.ofEpochMilli(startUtc).atZone(ZoneOffset.UTC).toLocalDate()
            val endDay = Instant.ofEpochMilli(endUtc).atZone(ZoneOffset.UTC).toLocalDate()
            val startMs = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = endDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            startMs to endMs
        }
    }

    val matchCount = localBounds?.let { (s, e) -> matchCountForRange(s, e) } ?: 0
    val rangeLabel = localBounds?.let { (s, e) ->
        val a = Instant.ofEpochMilli(s).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(e).atZone(zone).toLocalDate()
        "${a.format(fmt)} – ${b.format(fmt)}"
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = localBounds != null && matchCount > 0,
                onClick = {
                    val bounds = localBounds ?: return@TextButton
                    onConfirmRange(bounds.first, bounds.second)
                }
            ) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        Column {
            DateRangePicker(
                state = state,
                title = {
                    Text(
                        "Delete media by date",
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                    )
                },
                headline = {
                    Text(
                        rangeLabel ?: "Select start and end dates",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                },
                showModeToggle = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Only months that contain media (for the current All/Images/Videos filter) are selectable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            if (localBounds != null) {
                Text(
                    text = if (matchCount == 0) {
                        "No media in this range."
                    } else {
                        "$matchCount item(s) match. You’ll get the same delete options next."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (matchCount == 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                )
            }
        }
    }
}
