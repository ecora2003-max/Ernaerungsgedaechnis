package de.ernaehrung.demo.ui

import androidx.health.connect.client.records.ExerciseSessionRecord as Exercise
import androidx.health.connect.client.records.metadata.Device
import java.math.BigDecimal

/** Labels for the pinned public HC SDK. No inferred sport, energy or Watch model. */
internal object FitnessPresentation {
    fun duration(millis: Long): String {
        require(millis >= 0L)
        val minutes = millis / 60_000L
        val remainder = millis % 60_000L
        val seconds = BigDecimal.valueOf(remainder, 3).stripTrailingZeros().toPlainString().replace('.', ',')
        return if (minutes == 0L) "$seconds s" else "$minutes min" + if (remainder == 0L) "" else " $seconds s"
    }
    fun device(type: Int?): String = when (type) {
        Device.TYPE_WATCH -> "Uhr"
        Device.TYPE_PHONE -> "Telefon"
        Device.TYPE_SCALE -> "Waage"
        Device.TYPE_RING -> "Ring"
        Device.TYPE_HEAD_MOUNTED -> "Am Kopf getragenes Gerät"
        Device.TYPE_FITNESS_BAND -> "Fitnessarmband"
        Device.TYPE_CHEST_STRAP -> "Brustgurt"
        Device.TYPE_SMART_DISPLAY -> "Smart-Display"
        else -> "Unbekannt"
    }
    fun sport(type: Int?): String = when (type) {
        Exercise.EXERCISE_TYPE_OTHER_WORKOUT -> "Sonstiges Training"
        Exercise.EXERCISE_TYPE_BADMINTON -> "Badminton"
        Exercise.EXERCISE_TYPE_BASEBALL -> "Baseball"
        Exercise.EXERCISE_TYPE_BASKETBALL -> "Basketball"
        Exercise.EXERCISE_TYPE_BIKING -> "Radfahren"
        Exercise.EXERCISE_TYPE_BIKING_STATIONARY -> "Ergometer"
        Exercise.EXERCISE_TYPE_BOOT_CAMP -> "Bootcamp"
        Exercise.EXERCISE_TYPE_BOXING -> "Boxen"
        Exercise.EXERCISE_TYPE_CALISTHENICS -> "Calisthenics"
        Exercise.EXERCISE_TYPE_CRICKET -> "Cricket"
        Exercise.EXERCISE_TYPE_DANCING -> "Tanzen"
        Exercise.EXERCISE_TYPE_ELLIPTICAL -> "Crosstrainer"
        Exercise.EXERCISE_TYPE_EXERCISE_CLASS -> "Sportkurs"
        Exercise.EXERCISE_TYPE_FENCING -> "Fechten"
        Exercise.EXERCISE_TYPE_FOOTBALL_AMERICAN -> "American Football"
        Exercise.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN -> "Australian Football"
        Exercise.EXERCISE_TYPE_FRISBEE_DISC -> "Frisbee"
        Exercise.EXERCISE_TYPE_GOLF -> "Golf"
        Exercise.EXERCISE_TYPE_GUIDED_BREATHING -> "Angeleitete Atemübung"
        Exercise.EXERCISE_TYPE_GYMNASTICS -> "Turnen"
        Exercise.EXERCISE_TYPE_HANDBALL -> "Handball"
        Exercise.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "Intervalltraining (HIIT)"
        Exercise.EXERCISE_TYPE_HIKING -> "Wandern"
        Exercise.EXERCISE_TYPE_ICE_HOCKEY -> "Eishockey"
        Exercise.EXERCISE_TYPE_ICE_SKATING -> "Eislaufen"
        Exercise.EXERCISE_TYPE_MARTIAL_ARTS -> "Kampfsport"
        Exercise.EXERCISE_TYPE_PADDLING -> "Paddeln"
        Exercise.EXERCISE_TYPE_PARAGLIDING -> "Gleitschirmfliegen"
        Exercise.EXERCISE_TYPE_PILATES -> "Pilates"
        Exercise.EXERCISE_TYPE_RACQUETBALL -> "Racquetball"
        Exercise.EXERCISE_TYPE_ROCK_CLIMBING -> "Klettern"
        Exercise.EXERCISE_TYPE_ROLLER_HOCKEY -> "Rollhockey"
        Exercise.EXERCISE_TYPE_ROWING -> "Rudern"
        Exercise.EXERCISE_TYPE_ROWING_MACHINE -> "Rudergerät"
        Exercise.EXERCISE_TYPE_RUGBY -> "Rugby"
        Exercise.EXERCISE_TYPE_RUNNING -> "Laufen"
        Exercise.EXERCISE_TYPE_RUNNING_TREADMILL -> "Laufband"
        Exercise.EXERCISE_TYPE_SAILING -> "Segeln"
        Exercise.EXERCISE_TYPE_SCUBA_DIVING -> "Gerätetauchen"
        Exercise.EXERCISE_TYPE_SKATING -> "Rollschuhlaufen"
        Exercise.EXERCISE_TYPE_SKIING -> "Skifahren"
        Exercise.EXERCISE_TYPE_SNOWBOARDING -> "Snowboarden"
        Exercise.EXERCISE_TYPE_SNOWSHOEING -> "Schneeschuhwandern"
        Exercise.EXERCISE_TYPE_SOCCER -> "Fußball"
        Exercise.EXERCISE_TYPE_SOFTBALL -> "Softball"
        Exercise.EXERCISE_TYPE_SQUASH -> "Squash"
        Exercise.EXERCISE_TYPE_STAIR_CLIMBING -> "Treppensteigen"
        Exercise.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Treppengerät"
        Exercise.EXERCISE_TYPE_STRENGTH_TRAINING -> "Krafttraining"
        Exercise.EXERCISE_TYPE_STRETCHING -> "Dehnen"
        Exercise.EXERCISE_TYPE_SURFING -> "Surfen"
        Exercise.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Freiwasserschwimmen"
        Exercise.EXERCISE_TYPE_SWIMMING_POOL -> "Schwimmen im Becken"
        Exercise.EXERCISE_TYPE_TABLE_TENNIS -> "Tischtennis"
        Exercise.EXERCISE_TYPE_TENNIS -> "Tennis"
        Exercise.EXERCISE_TYPE_VOLLEYBALL -> "Volleyball"
        Exercise.EXERCISE_TYPE_WALKING -> "Gehen"
        Exercise.EXERCISE_TYPE_WATER_POLO -> "Wasserball"
        Exercise.EXERCISE_TYPE_WEIGHTLIFTING -> "Gewichtheben"
        Exercise.EXERCISE_TYPE_WHEELCHAIR -> "Rollstuhlfahren"
        Exercise.EXERCISE_TYPE_YOGA -> "Yoga"
        else -> "Unbekannt"
    }
}
