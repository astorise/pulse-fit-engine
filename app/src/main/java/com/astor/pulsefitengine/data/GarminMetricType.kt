package com.astor.pulsefitengine.data

enum class GarminMetricType(
    val wireId: Int,
    val wireName: String,
    val displayName: String,
    val defaultUnit: String = "",
) {
    CALORIES(0, "CALORIES", "Calories", "kcal"),
    CADENCE(3, "CADENCE", "Cadence", "rpm"),
    DISTANCE(6, "DISTANCE", "Distance", "km"),
    DISTANCE_LAP(7, "DISTANCE_LAP", "Distance tour", "km"),
    HEART_RATE(13, "HEART_RATE", "Rythme cardiaque", "bpm"),
    HEART_RATE_AVG(14, "HEART_RATE_AVG", "FC moyenne", "bpm"),
    HEART_RATE_LAP(15, "HEART_RATE_LAP", "FC tour", "bpm"),
    HEART_RATE_ZONE_NUMBER(22, "HEART_RATE_ZONE_NUMBER", "Zone FC"),
    LAPS(24, "LAPS", "Tours"),
    PACE(33, "PACE", "Allure", "/km"),
    PACE_AVG(34, "PACE_AVG", "Allure moyenne", "/km"),
    PACE_LAP(35, "PACE_LAP", "Allure tour", "/km"),
    POWER(36, "POWER", "Puissance", "W"),
    POWER_AVG(37, "POWER_AVG", "Puissance moyenne", "W"),
    POWER_LAP(39, "POWER_LAP", "Puissance tour", "W"),
    POWER_ZONE(43, "POWER_ZONE", "Zone puissance"),
    SPEED(48, "SPEED", "Vitesse", "km/h"),
    SPEED_AVG(49, "SPEED_AVG", "Vitesse moyenne", "km/h"),
    TIME_ELAPSED(55, "TIME_ELAPSED", "Temps ecoule"),
    TIME(56, "TIME", "Temps"),
    TIME_LAP(58, "TIME_LAP", "Temps tour"),
    WORKOUT_CALS_TO_GO(64, "WORKOUT_CALS_TO_GO", "Calories restantes", "kcal"),
    WORKOUT_DIST_TO_GO(65, "WORKOUT_DIST_TO_GO", "Distance restante", "km"),
    WORKOUT_REPS_TO_GO(67, "WORKOUT_REPS_TO_GO", "Repetitions restantes"),
    WORKOUT_TIME_TO_GO(68, "WORKOUT_TIME_TO_GO", "Temps restant"),
    LAP_SUMMARY_CALORIES(109, "LAP_SUMMARY_CALORIES", "Calories tour", "kcal"),
    STROKES(126, "STROKES", "Coups"),
    DISTANCE_SET(130, "DISTANCE_SET", "Distance set", "km"),
    ESTIMATED_FINISH_TIME(196, "ESTIMATED_FINISH_TIME", "Temps estime arrivee"),
    DISTANCE_REMAINING(197, "DISTANCE_REMAINING", "Distance restante", "km"),
    STROKE_RATE(205, "STROKE_RATE", "Frequence coups", "spm"),
    ACTIVE_CALORIES(245, "ACTIVE_CALORIES", "Calories actives", "kcal"),
    WORKOUT_INTERVAL_TIMER(303, "WORKOUT_INTERVAL_TIMER", "Timer intervalle"),
    WORKOUT_INTERVAL_SPEED(304, "WORKOUT_INTERVAL_SPEED", "Vitesse intervalle", "km/h"),
    WORKOUT_INTERVAL_PACE(305, "WORKOUT_INTERVAL_PACE", "Allure intervalle", "/km"),
    WORKOUT_INTERVAL_POWER_AVG(306, "WORKOUT_INTERVAL_POWER_AVG", "Puissance intervalle", "W"),
    WORKOUT_SET_COUNT(434, "WORKOUT_SET_COUNT", "Nombre de sets"),
    WORKOUT_SET_REPS(435, "WORKOUT_SET_REPS", "Repetitions set"),
    BREATHING_RATE(452, "BREATHING_RATE", "Respiration", "rpm"),
    STRESS(499, "STRESS", "Stress"),
    TIME_ACTIVE(503, "TIME_ACTIVE", "Temps actif"),
    INTERVALS(519, "INTERVALS", "Intervalles"),
    UNKNOWN(-1, "UNKNOWN", "Inconnue");

    companion object {
        val featured = listOf(
            HEART_RATE,
            SPEED,
            PACE,
            POWER,
            DISTANCE,
            ACTIVE_CALORIES,
            BREATHING_RATE,
            STRESS,
        )

        fun fromWireId(wireId: Int): GarminMetricType {
            return entries.firstOrNull { it.wireId == wireId } ?: UNKNOWN
        }
    }
}
