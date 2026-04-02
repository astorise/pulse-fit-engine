package com.astor.pulsefitengine.data

/**
 * Point d'extension pour brancher le vrai flux Garmin quand le transport proprietaire aura ete
 * reconstitue.
 *
 * Contrats protobuf identifies dans Garmin Connect:
 * - AlertNotification ext 1012 -> GDILiveSessionProto.LiveSessionEventNotification
 * - AlertNotification ext 1013 -> GDIHeartRate.MeasurementNotification
 */
interface GarminRealtimeDecoder {
    fun decodeAlertNotification(payload: ByteArray): List<GarminMetricSample>
}

class NoOpGarminRealtimeDecoder : GarminRealtimeDecoder {
    override fun decodeAlertNotification(payload: ByteArray): List<GarminMetricSample> = emptyList()
}
