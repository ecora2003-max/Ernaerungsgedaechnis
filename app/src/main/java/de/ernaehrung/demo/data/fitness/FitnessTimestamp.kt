package de.ernaehrung.demo.data.fitness

import java.time.*
import java.time.format.DateTimeParseException

internal data class FitnessTimestampInput(val localDate: String, val localTime: String? = null,
    val zoneId: String? = null, val explicitOffset: String? = null)
internal data class NormalizedFitnessTime(val utc: String?, val offsetSeconds: Int?, val precision: String)

/** Shared lossless time rules for local body and training observations. */
internal object FitnessTimestampRules {
    fun normalize(input: FitnessTimestampInput, now: Instant): NormalizedFitnessTime {
        try {
            val date = LocalDate.parse(input.localDate)
            require(date.toString() == input.localDate) { "Datum als JJJJ-MM-TT eingeben." }
            val zone = input.zoneId?.let { require(it.length <= 100); ZoneId.of(it) }
            val time = input.localTime?.let {
                require(it.matches(Regex("[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]{1,9})?)?"))) { "Uhrzeit als HH:MM oder HH:MM:SS eingeben." }
                LocalTime.parse(it)
            }
            val chosenOffset = input.explicitOffset?.let { ZoneOffset.of(it) }
            require(time != null || chosenOffset == null) { "Ohne Uhrzeit ist kein UTC-Versatz bestimmbar." }
            val local = time?.let { LocalDateTime.of(date, it) }
            val offsets = if (local != null && zone != null) zone.rules.getValidOffsets(local) else emptyList()
            if (local != null && zone != null) {
                require(offsets.isNotEmpty()) { "Diese Ortszeit existiert wegen der Zeitumstellung nicht." }
                require(chosenOffset == null || chosenOffset in offsets) { "UTC-Versatz passt nicht zu Ortszeit und Zeitzone." }
                require(offsets.size == 1 || chosenOffset != null) { "Diese Uhrzeit kommt zweimal vor. UTC-Versatz ausdrücklich wählen." }
            }
            val offset = chosenOffset ?: offsets.singleOrNull()
            val utc = if (local != null && offset != null) local.toInstant(offset) else null
            require(utc == null || utc <= now) { "Zeitpunkt liegt in der Zukunft." }
            if (zone != null) require(date <= now.atZone(zone).toLocalDate()) { "Datum liegt in der Zukunft." }
            val precision = when {
                input.localTime == null -> "DATE_ONLY"
                '.' in input.localTime -> "FRACTION_${input.localTime.substringAfter('.').length}"
                input.localTime.length == 5 -> "MINUTE"
                else -> "SECOND"
            }
            return NormalizedFitnessTime(utc?.toString(), offset?.totalSeconds, precision)
        } catch (_: DateTimeParseException) { throw IllegalArgumentException("Datum, Uhrzeit oder UTC-Versatz ist ungültig.") }
        catch (_: DateTimeException) { throw IllegalArgumentException("Zeitzone ist ungültig. Beispiel: Europe/Berlin.") }
    }
}
