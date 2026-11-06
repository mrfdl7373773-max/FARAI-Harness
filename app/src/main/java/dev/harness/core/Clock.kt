package dev.harness.core

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The one place the agent learns what time it is: weekday, full date, time, zone and offset. */
object Clock {
    private val day = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US)
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun nowLine(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): String {
        val off = now.offset.id.let { if (it == "Z") "+00:00" else it }
        return "Current date and time: ${day.format(now)}, ${time.format(now)} (${now.zone.id}, UTC$off). " +
            "Yesterday was ${day.format(now.minusDays(1))}; tomorrow is ${day.format(now.plusDays(1))}. " +
            "Use this, never your training data, for any date."
    }
}
