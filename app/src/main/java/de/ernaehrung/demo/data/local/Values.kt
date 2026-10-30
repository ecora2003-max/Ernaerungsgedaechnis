package de.ernaehrung.demo.data.local

import androidx.room.TypeConverter
import java.math.BigDecimal
import java.math.RoundingMode

enum class BaseUnit(val label: String) { GRAM("g"), MILLILITRE("ml"), PORTION("Portion") }
enum class Quality { UNKNOWN, ESTIMATED, SOURCED, CONFIRMED }
enum class Source { USER, LABEL, MANUFACTURER, RETAILER, OPEN_FOOD_FACTS, DEMO }
enum class ReleaseStatus { UNCHECKED, RELEASED, BLOCKED }
enum class MovementKind { RECEIPT, CONSUMPTION, WASTE, PRODUCTION_INPUT, PRODUCTION_OUTPUT, TRANSFER, CORRECTION }
enum class DayStatus { OPEN, PARTIAL, COMPLETE }
enum class PlanKind { DIRECT_CONSUMPTION, PREPARATION, PREPARED_PORTION }
enum class EntryStatus { DRAFT, CONFIRMED, CANCELLED }

/** All quantities use 1/1000 of their declared base unit, never floating point. */
object Amounts {
    const val MAX = 1_000_000_000_000L
    fun parse(text: String): Long {
        val normal = text.trim().replace(',', '.')
        require(normal.matches(Regex("[0-9]+(\\.[0-9]{1,3})?"))) { "Menge als positive Zahl mit höchstens 3 Nachkommastellen eingeben." }
        val amount = try { BigDecimal(normal).movePointRight(3).longValueExact() }
        catch (_: ArithmeticException) { throw IllegalArgumentException("Menge außerhalb des zulässigen Bereichs.") }
        return positive(amount)
    }
    fun positive(value: Long): Long {
        require(value in 1..MAX) { "Menge muss größer als 0 und im zulässigen Bereich sein." }
        return value
    }
    fun format(value: Long): String = BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString().replace('.', ',')
    fun convert(value: String, from: String, to: BaseUnit, confirmedPieceSize: Long? = null): Long {
        val parsed = parse(value)
        val result = when {
            from == to.label -> parsed
            from == "kg" && to == BaseUnit.GRAM || from == "l" && to == BaseUnit.MILLILITRE -> Math.multiplyExact(parsed, 1000)
            from in listOf("Stück", "Packung") && confirmedPieceSize != null -> {
                positive(confirmedPieceSize)
                BigDecimal.valueOf(parsed).multiply(BigDecimal.valueOf(confirmedPieceSize))
                    .divide(BigDecimal(1000), 0, RoundingMode.UNNECESSARY).longValueExact()
            }
            else -> throw IllegalArgumentException("Keine bestätigte Umrechnung für $from nach ${to.label}.")
        }
        return positive(result)
    }
}

class ValueConverters {
    @TypeConverter fun unit(value: String): BaseUnit = BaseUnit.valueOf(value)
    @TypeConverter fun unit(value: BaseUnit): String = value.name
    @TypeConverter fun quality(value: String): Quality = Quality.valueOf(value)
    @TypeConverter fun quality(value: Quality): String = value.name
    @TypeConverter fun source(value: String): Source = Source.valueOf(value)
    @TypeConverter fun source(value: Source): String = value.name
    @TypeConverter fun release(value: String): ReleaseStatus = ReleaseStatus.valueOf(value)
    @TypeConverter fun release(value: ReleaseStatus): String = value.name
    @TypeConverter fun movement(value: String): MovementKind = MovementKind.valueOf(value)
    @TypeConverter fun movement(value: MovementKind): String = value.name
    @TypeConverter fun day(value: String): DayStatus = DayStatus.valueOf(value)
    @TypeConverter fun day(value: DayStatus): String = value.name
    @TypeConverter fun plan(value: String): PlanKind = PlanKind.valueOf(value)
    @TypeConverter fun plan(value: PlanKind): String = value.name
    @TypeConverter fun status(value: String): EntryStatus = EntryStatus.valueOf(value)
    @TypeConverter fun status(value: EntryStatus): String = value.name
}
