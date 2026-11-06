package de.ernaehrung.demo.data.fitness

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal data class ManualWorkoutSetInput(val exercise: String, val repetitions: String? = null,
    val loadKg: String? = null, val effort: String? = null, val note: String? = null)
internal data class ManualWorkoutInput(val sport: String, val time: FitnessTimestampInput,
    val durationMinutes: String? = null, val distanceMetres: String? = null,
    val intensity: String? = null, val effort: String? = null, val device: String? = null,
    val conditions: String? = null, val note: String? = null,
    val sets: List<ManualWorkoutSetInput> = emptyList(), val correctionReason: String? = null)
internal data class NormalizedWorkoutSet(val input: ManualWorkoutSetInput, val repetitions: Long?, val loadKg: String?)
internal data class NormalizedManualWorkout(val input: ManualWorkoutInput, val time: NormalizedFitnessTime,
    val durationMinutes: String?, val distanceMetres: String?, val endUtc: String?, val sets: List<NormalizedWorkoutSet>)

internal object ManualWorkoutRules {
    private fun text(value: String?, required: Boolean = false) {
        require((value == null && !required) || (value != null && value.isNotBlank() && value.length <= 1000)) {
            "Erforderliche Trainingsangabe fehlt oder Text ist zu lang. Leere optionale Angaben weglassen."
        }
    }
    private fun decimal(raw: String?): BigDecimal? = raw?.let {
        require(it.length <= 80 && it.trim().matches(Regex("[+]?[0-9]+([.,][0-9]+)?"))) {
            "Menge als nichtnegative Dezimalzahl angeben oder unbekannt lassen."
        }
        BigDecimal(it.trim().replace(',', '.'))
    }
    private fun BigDecimal.canonical() = stripTrailingZeros().toPlainString()

    fun normalize(original: ManualWorkoutInput, now: Instant): NormalizedManualWorkout {
        val input = original.copy(sets = original.sets.toList())
        text(input.sport, true)
        listOf(input.intensity, input.effort, input.device, input.conditions, input.note, input.correctionReason).forEach { text(it) }
        require(input.sets.size <= 100) { "Höchstens 100 Kraftsätze je Trainingsversion erfassen." }
        val time = FitnessTimestampRules.normalize(input.time, now)
        require(LocalDate.parse(input.time.localDate) <= now.atZone(ZoneOffset.MAX).toLocalDate()) {
            "Ein abgeschlossenes Training darf kein zukünftiges Datum haben."
        }
        val duration = decimal(input.durationMinutes)
        val distance = decimal(input.distanceMetres)
        val end = if (time.utc != null && duration != null) {
            try {
                val seconds = duration.multiply(BigDecimal(60))
                val whole = seconds.setScale(0, RoundingMode.DOWN)
                Instant.parse(time.utc).plusSeconds(whole.longValueExact())
                    .plusNanos(seconds.subtract(whole).movePointRight(9).longValueExact()).also {
                        require(it <= now) { "Ein abgeschlossenes Training darf nicht in der Zukunft enden." }
                    }.toString()
            } catch (_: ArithmeticException) { throw IllegalArgumentException("Dauer lässt sich nicht verlustfrei als Zeitintervall darstellen.") }
            catch (_: DateTimeException) { throw IllegalArgumentException("Dauer liegt außerhalb des darstellbaren Zeitbereichs.") }
        } else null
        val sets = input.sets.map { set ->
            text(set.exercise, true); text(set.effort); text(set.note)
            val repetitions = set.repetitions?.let {
                require(it.length <= 80 && it.trim().matches(Regex("[+]?[0-9]+"))) { "Wiederholungen ganzzahlig und nichtnegativ angeben oder unbekannt lassen." }
                try { it.trim().toLong() } catch (_: NumberFormatException) { throw IllegalArgumentException("Wiederholungszahl ist zu groß.") }
            }
            NormalizedWorkoutSet(set, repetitions, decimal(set.loadKg)?.canonical())
        }
        return NormalizedManualWorkout(input, time, duration?.canonical(), distance?.canonical(), end, sets)
    }
}
