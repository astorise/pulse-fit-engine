package com.astor.pulsefitengine.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class GarminDecodedFrame(
    val samples: List<GarminMetricSample> = emptyList(),
    val debugSummary: String? = null,
)

interface GarminRealtimeDecoder {
    fun decode(
        serviceUuid: UUID?,
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame

    fun decodeMultiLinkService(
        serviceId: Int,
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame
}

class HeuristicGarminRealtimeDecoder : GarminRealtimeDecoder {
    private val mlrStreams = mutableMapOf<UUID, MlrStreamState>()

    override fun decode(
        serviceUuid: UUID?,
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame {
        if (payload.isEmpty()) {
            return GarminDecodedFrame(debugSummary = "Trame vide")
        }

        decodeStandardHeartRate(serviceUuid, characteristicUuid, payload, timestampMillis)?.let {
            return it
        }

        decodeRunningSpeedAndCadence(serviceUuid, characteristicUuid, payload, timestampMillis)?.let {
            return it
        }

        consumeMlrPacket(characteristicUuid, payload, timestampMillis)?.let {
            return it
        }

        return decodeGarminPayload(characteristicUuid, payload, timestampMillis)
    }

    override fun decodeMultiLinkService(
        serviceId: Int,
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame {
        if (payload.isEmpty()) {
            return GarminDecodedFrame(debugSummary = "Service temps reel vide")
        }

        when (serviceId) {
            REAL_TIME_HR_SERVICE_ID -> decodeDirectRealtimeHeartRate(payload, timestampMillis)?.let { return it }
            REAL_TIME_STRESS_SERVICE_ID -> decodeDirectRealtimeStress(payload, timestampMillis)?.let { return it }
            REAL_TIME_SPO2_SERVICE_ID -> decodeDirectRealtimeSpo2(payload, timestampMillis)?.let { return it }
            REAL_TIME_RESPIRATION_SERVICE_ID -> decodeDirectRealtimeRespiration(payload, timestampMillis)?.let { return it }
        }

        return GarminDecodedFrame(
            debugSummary = "Service temps reel $serviceId (${payload.toHexString(MAX_HEX_PREVIEW_BYTES)})",
        )
    }

    private fun decodeDirectRealtimeHeartRate(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        if (payload.size < 2) {
            return null
        }
        val bpm = payload[1].toInt() and 0xff
        if (bpm !in 20..255) {
            return null
        }
        return GarminDecodedFrame(
            samples = listOf(
                GarminMetricSample(
                    type = GarminMetricType.HEART_RATE,
                    timestampMillis = timestampMillis,
                    numericValue = bpm.toFloat(),
                ),
            ),
            debugSummary = "REAL_TIME_HR: $bpm bpm",
        )
    }

    private fun decodeDirectRealtimeStress(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        if (payload.size < 2) {
            return null
        }
        val stress = ByteBuffer.wrap(payload, 0, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xffff
        if (stress !in 0..100) {
            return null
        }
        return GarminDecodedFrame(
            samples = listOf(
                GarminMetricSample(
                    type = GarminMetricType.STRESS,
                    timestampMillis = timestampMillis,
                    numericValue = stress.toFloat(),
                ),
            ),
            debugSummary = "REAL_TIME_STRESS: $stress",
        )
    }

    private fun decodeDirectRealtimeSpo2(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        if (payload.all { byte -> byte.toInt() and 0xff == 0xff }) {
            return GarminDecodedFrame(debugSummary = "REAL_TIME_SPO2: mesure indisponible")
        }
        val spo2 = payload[0].toInt() and 0xff
        if (spo2 !in 70..100) {
            return null
        }
        return GarminDecodedFrame(
            samples = listOf(
                GarminMetricSample(
                    type = GarminMetricType.SPO2,
                    timestampMillis = timestampMillis,
                    numericValue = spo2.toFloat(),
                ),
            ),
            debugSummary = "REAL_TIME_SPO2: $spo2 %",
        )
    }

    private fun decodeDirectRealtimeRespiration(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        val respiration = payload.firstOrNull()?.toInt()?.and(0xff) ?: return null
        if (respiration !in 4..60) {
            return null
        }
        return GarminDecodedFrame(
            samples = listOf(
                GarminMetricSample(
                    type = GarminMetricType.BREATHING_RATE,
                    timestampMillis = timestampMillis,
                    numericValue = respiration.toFloat(),
                ),
            ),
            debugSummary = "REAL_TIME_RESPIRATION: $respiration rpm",
        )
    }

    private fun decodeGarminPayload(
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame {
        decodeSmartContainer(payload, timestampMillis)?.let {
            return it
        }

        decodeAlertNotification(payload, timestampMillis)?.let {
            return it
        }

        decodeLiveSessionEvent(payload, timestampMillis)?.let {
            return it
        }

        decodeDataNotification(payload, timestampMillis)?.let {
            return it
        }

        if (payload.size <= MAX_DIRECT_METRIC_BYTES) {
            decodeMetric(payload, timestampMillis)?.let {
                return it
            }
        }

        if (payload.size <= MAX_DIRECT_HEART_RATE_PROTO_BYTES) {
            decodeHeartRateMeasurementProto(payload, timestampMillis)?.let {
                return it
            }
        }

        return GarminDecodedFrame(debugSummary = describeUnknownPayload(characteristicUuid, payload))
    }

    private fun decodeStandardHeartRate(
        serviceUuid: UUID?,
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        if (serviceUuid != HEART_RATE_SERVICE_UUID && characteristicUuid != HEART_RATE_MEASUREMENT_UUID) {
            return null
        }
        if (payload.size < 2) {
            return GarminDecodedFrame(debugSummary = "Mesure cardio BLE invalide")
        }

        val flags = payload[0].toInt() and 0xff
        val isSixteenBitHeartRate = flags and 0x01 != 0
        val heartRate = if (isSixteenBitHeartRate) {
            if (payload.size < 3) {
                return GarminDecodedFrame(debugSummary = "Mesure cardio 16 bits incomplete")
            }
            ((payload[2].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        } else {
            payload[1].toInt() and 0xff
        }
        if (heartRate !in 20..255) {
            return null
        }

        return GarminDecodedFrame(
            samples = listOf(
                GarminMetricSample(
                    type = GarminMetricType.HEART_RATE,
                    timestampMillis = timestampMillis,
                    numericValue = heartRate.toFloat(),
                ),
            ),
            debugSummary = "Heart Rate service: ${heartRate} bpm",
        )
    }

    private fun decodeRunningSpeedAndCadence(
        serviceUuid: UUID?,
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        if (serviceUuid != RSC_SERVICE_UUID && characteristicUuid != RSC_MEASUREMENT_UUID) {
            return null
        }
        if (payload.size < 4) {
            return GarminDecodedFrame(debugSummary = "Mesure RSC incomplete")
        }

        val flags = payload[0].toInt() and 0xff
        val speedMetersPerSecond = (((payload[2].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)) / 256f
        val cadenceStepsPerMinute = payload[3].toInt() and 0xff
        if (speedMetersPerSecond < 0f || cadenceStepsPerMinute <= 0) {
            return null
        }

        val samples = mutableListOf(
            GarminMetricSample(
                type = GarminMetricType.SPEED,
                timestampMillis = timestampMillis,
                numericValue = speedMetersPerSecond * 3.6f,
            ),
            GarminMetricSample(
                type = GarminMetricType.CADENCE,
                timestampMillis = timestampMillis,
                numericValue = cadenceStepsPerMinute.toFloat(),
            ),
        )

        if (speedMetersPerSecond > 0.2f) {
            samples += GarminMetricSample(
                type = GarminMetricType.PACE,
                timestampMillis = timestampMillis,
                numericValue = 16.666667f / speedMetersPerSecond,
            )
        }

        val state = when {
            flags and RSC_RUNNING_STATUS_FLAG != 0 -> ", running"
            else -> ", walking"
        }

        return GarminDecodedFrame(
            samples = samples,
            debugSummary = "RSC measurement: %.1f km/h, %d spm%s".format(
                speedMetersPerSecond * 3.6f,
                cadenceStepsPerMinute,
                state,
            ),
        )
    }

    private fun consumeMlrPacket(
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        if (characteristicUuid != MLR_DATA_CHARACTERISTIC_UUID || payload.isEmpty()) {
            return null
        }

        val header = payload[0].toInt() and 0xff
        if (header == MLR_CONTROL_HEADER) {
            val control = payload.copyOfRange(1, payload.size)
            return GarminDecodedFrame(
                debugSummary = if (control.isEmpty()) {
                    "MLR controle vide"
                } else {
                    "MLR controle ${control.toHexString(MAX_HEX_PREVIEW_BYTES)}"
                },
            )
        }

        if (header and MLR_FRAGMENT_TYPE_MASK != MLR_DATA_FRAGMENT_TYPE) {
            return GarminDecodedFrame(
                debugSummary = "MLR brut ${payload.toHexString(MAX_HEX_PREVIEW_BYTES)}",
            )
        }

        val state = mlrStreams.getOrPut(characteristicUuid) { MlrStreamState() }
        val sequence = (header ushr 4) and 0x0f
        val payloadFragment = payload.copyOfRange(1, payload.size)
        val reset = !state.append(sequence, payloadFragment)
        if (reset) {
            state.reset()
            state.append(sequence, payloadFragment)
        }

        if (payload.size >= MLR_FULL_PACKET_SIZE) {
            return GarminDecodedFrame(
                debugSummary = "MLR fragment seq=$sequence total=${state.bufferedSize} octets",
            )
        }

        val blob = state.takeBlob()
        if (blob.isEmpty()) {
            return GarminDecodedFrame(debugSummary = "MLR fin de blob vide")
        }

        val decoded = decodeGarminPayload(characteristicUuid, blob, timestampMillis)
        val summary = decoded.debugSummary?.let { base ->
            "MLR blob ${blob.size} octets: $base"
        } ?: "MLR blob ${blob.size} octets"
        return decoded.copy(debugSummary = summary)
    }

    private fun decodeSmartContainer(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        val reader = ProtoReader(payload)
        while (!reader.isAtEnd) {
            val tag = reader.readVarint() ?: return null
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (fieldNumber == SMART_EVENT_SHARING_FIELD && wireType == WIRE_LENGTH_DELIMITED) {
                val nestedPayload = reader.readLengthDelimited() ?: return null
                val nested = decodeEventSharingService(nestedPayload, timestampMillis) ?: return null
                return nested.copy(debugSummary = nested.debugSummary ?: "Smart.event_sharing")
            }
            if (!reader.skipField(wireType)) {
                return null
            }
        }
        return null
    }

    private fun decodeEventSharingService(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        val reader = ProtoReader(payload)
        while (!reader.isAtEnd) {
            val tag = reader.readVarint() ?: return null
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (fieldNumber == EVENT_SHARING_ALERT_NOTIFICATION_FIELD && wireType == WIRE_LENGTH_DELIMITED) {
                val nestedPayload = reader.readLengthDelimited() ?: return null
                return decodeAlertNotification(nestedPayload, timestampMillis)
            }
            if (!reader.skipField(wireType)) {
                return null
            }
        }
        return null
    }

    private fun decodeAlertNotification(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        val reader = ProtoReader(payload)
        var sawAlertType = false
        while (!reader.isAtEnd) {
            val tag = reader.readVarint() ?: return null
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            when {
                fieldNumber == ALERT_TYPE_FIELD && wireType == WIRE_VARINT -> {
                    sawAlertType = true
                    reader.readVarint() ?: return null
                }

                fieldNumber == ALERT_LIVE_SESSION_EXTENSION && wireType == WIRE_LENGTH_DELIMITED -> {
                    val nestedPayload = reader.readLengthDelimited() ?: return null
                    val decoded = decodeLiveSessionEvent(nestedPayload, timestampMillis) ?: return null
                    return decoded.copy(
                        debugSummary = decoded.debugSummary ?: "AlertNotification.live_session",
                    )
                }

                fieldNumber == ALERT_HEART_RATE_EXTENSION && wireType == WIRE_LENGTH_DELIMITED -> {
                    val nestedPayload = reader.readLengthDelimited() ?: return null
                    val decoded = decodeHeartRateMeasurementProto(nestedPayload, timestampMillis)
                        ?: return null
                    return decoded.copy(
                        debugSummary = decoded.debugSummary ?: "AlertNotification.heart_rate",
                    )
                }

                else -> if (!reader.skipField(wireType)) {
                    return null
                }
            }
        }
        return if (sawAlertType) GarminDecodedFrame(debugSummary = "AlertNotification sans extension connue") else null
    }

    private fun decodeLiveSessionEvent(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        val reader = ProtoReader(payload)
        while (!reader.isAtEnd) {
            val tag = reader.readVarint() ?: return null
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (fieldNumber == LIVE_SESSION_DATA_NOTIFICATION_FIELD && wireType == WIRE_LENGTH_DELIMITED) {
                val nestedPayload = reader.readLengthDelimited() ?: return null
                val decoded = decodeDataNotification(nestedPayload, timestampMillis) ?: return null
                return decoded.copy(
                    debugSummary = decoded.debugSummary ?: "LiveSession.data_notification",
                )
            }
            if (!reader.skipField(wireType)) {
                return null
            }
        }
        return null
    }

    private fun decodeDataNotification(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        val reader = ProtoReader(payload)
        val samples = mutableListOf<GarminMetricSample>()
        while (!reader.isAtEnd) {
            val tag = reader.readVarint() ?: return null
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (fieldNumber == DATA_NOTIFICATION_METRICS_FIELD && wireType == WIRE_LENGTH_DELIMITED) {
                val metricPayload = reader.readLengthDelimited() ?: return null
                samples += decodeMetric(metricPayload, timestampMillis)?.samples.orEmpty()
            } else if (!reader.skipField(wireType)) {
                return null
            }
        }
        return if (samples.isEmpty()) {
            null
        } else {
            GarminDecodedFrame(
                samples = samples,
                debugSummary = "DataNotification: ${samples.joinToString { it.type.wireName }}",
            )
        }
    }

    private fun decodeMetric(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        val reader = ProtoReader(payload)
        var metricTypeValue: Int? = null
        var metricValue: Float? = null
        var measurementUnit: Int? = null
        var sampleTimestamp = timestampMillis
        while (!reader.isAtEnd) {
            val tag = reader.readVarint() ?: return null
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            when (fieldNumber) {
                METRIC_TIMESTAMP_FIELD -> {
                    val seconds = reader.readVarint() ?: return null
                    if (seconds in 1..Int.MAX_VALUE.toLong()) {
                        sampleTimestamp = seconds * 1_000L
                    }
                }

                METRIC_TYPE_FIELD -> metricTypeValue = reader.readVarint()?.toInt()
                METRIC_FLOAT_VALUE_FIELD -> metricValue = reader.readFixed32AsFloat()
                METRIC_INT_VALUE_FIELD -> metricValue = reader.readVarint()?.toFloat()
                METRIC_STRING_VALUE_FIELD -> {
                    val text = reader.readLengthDelimitedAsString() ?: return null
                    val type = metricTypeValue?.let(GarminMetricType::fromWireId) ?: GarminMetricType.UNKNOWN
                    if (type == GarminMetricType.UNKNOWN) {
                        return null
                    }
                    return GarminDecodedFrame(
                        samples = listOf(
                            GarminMetricSample(
                                type = type,
                                timestampMillis = sampleTimestamp,
                                textValue = text,
                            ),
                        ),
                        debugSummary = "Metric ${type.wireName}=$text",
                    )
                }

                METRIC_MEASUREMENT_UNIT_FIELD -> measurementUnit = reader.readVarint()?.toInt()
                else -> if (!reader.skipField(wireType)) {
                    return null
                }
            }
        }

        val type = metricTypeValue?.let(GarminMetricType::fromWireId) ?: return null
        val numericValue = metricValue ?: return null
        if (type == GarminMetricType.UNKNOWN) {
            return null
        }

        return GarminDecodedFrame(
            samples = listOf(
                GarminMetricSample(
                    type = type,
                    timestampMillis = sampleTimestamp,
                    numericValue = numericValue,
                    unitOverride = measurementUnit?.let(::measurementUnitOverride),
                ),
            ),
            debugSummary = "Metric ${type.wireName}=$numericValue",
        )
    }

    private fun decodeHeartRateMeasurementProto(
        payload: ByteArray,
        timestampMillis: Long,
    ): GarminDecodedFrame? {
        val reader = ProtoReader(payload)
        var heartRateBpm: Int? = null
        var isConfident = false
        var beatPeriod: Int? = null
        while (!reader.isAtEnd) {
            val tag = reader.readVarint() ?: return null
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            when (fieldNumber) {
                HEART_RATE_BPM_FIELD -> heartRateBpm = reader.readVarint()?.toInt()
                HEART_RATE_CONFIDENCE_FIELD -> isConfident = (reader.readVarint() ?: return null) != 0L
                HEART_RATE_BEAT_PERIOD_FIELD -> beatPeriod = reader.readVarint()?.toInt()
                else -> if (!reader.skipField(wireType)) {
                    return null
                }
            }
        }

        val bpm = heartRateBpm ?: return null
        if (bpm !in 20..255) {
            return null
        }

        return GarminDecodedFrame(
            samples = listOf(
                GarminMetricSample(
                    type = GarminMetricType.HEART_RATE,
                    timestampMillis = timestampMillis,
                    numericValue = bpm.toFloat(),
                ),
            ),
            debugSummary = buildString {
                append("HeartRate proto: ")
                append(bpm)
                append(" bpm")
                if (isConfident) {
                    append(", confident")
                }
                if (beatPeriod != null) {
                    append(", beat_period=")
                    append(beatPeriod)
                }
            },
        )
    }

    private fun measurementUnitOverride(unit: Int): String? {
        return when (unit) {
            1 -> "m"
            2 -> "km"
            5 -> "mi"
            9 -> "m/s"
            10 -> "km/h"
            11 -> "mph"
            13 -> "bpm"
            14 -> "W"
            else -> null
        }
    }

    private fun shortUuid(uuid: UUID): String {
        return uuid.toString().substringBefore('-')
    }

    private fun describeUnknownPayload(characteristicUuid: UUID, payload: ByteArray): String {
        return buildString {
            append("Trame non decodee sur ")
            append(shortUuid(characteristicUuid))
            append(" (")
            append(payload.size)
            append(" octets")
            if (payload.isNotEmpty()) {
                append(", hex=")
                append(payload.toHexString(MAX_HEX_PREVIEW_BYTES))
            }
            append(")")
        }
    }

    private fun ByteArray.toHexString(maxBytes: Int): String {
        val preview = take(maxBytes)
        val hex = preview.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return if (size > maxBytes) {
            "$hex..."
        } else {
            hex
        }
    }

    private companion object {
        private const val WIRE_VARINT = 0
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val MAX_DIRECT_METRIC_BYTES = 96
        private const val MAX_DIRECT_HEART_RATE_PROTO_BYTES = 32
        private const val MAX_HEX_PREVIEW_BYTES = 24

        private const val REAL_TIME_HR_SERVICE_ID = 6
        private const val REAL_TIME_STRESS_SERVICE_ID = 13
        private const val REAL_TIME_SPO2_SERVICE_ID = 19
        private const val REAL_TIME_RESPIRATION_SERVICE_ID = 21

        private const val SMART_EVENT_SHARING_FIELD = 30
        private const val EVENT_SHARING_ALERT_NOTIFICATION_FIELD = 3
        private const val ALERT_TYPE_FIELD = 1
        private const val ALERT_LIVE_SESSION_EXTENSION = 1012
        private const val ALERT_HEART_RATE_EXTENSION = 1013
        private const val LIVE_SESSION_DATA_NOTIFICATION_FIELD = 3
        private const val DATA_NOTIFICATION_METRICS_FIELD = 1

        private const val METRIC_TIMESTAMP_FIELD = 1
        private const val METRIC_TYPE_FIELD = 2
        private const val METRIC_FLOAT_VALUE_FIELD = 3
        private const val METRIC_INT_VALUE_FIELD = 4
        private const val METRIC_STRING_VALUE_FIELD = 5
        private const val METRIC_MEASUREMENT_UNIT_FIELD = 6

        private const val HEART_RATE_BPM_FIELD = 1
        private const val HEART_RATE_CONFIDENCE_FIELD = 2
        private const val HEART_RATE_BEAT_PERIOD_FIELD = 3
        private const val RSC_RUNNING_STATUS_FLAG = 0x04
        private const val MLR_CONTROL_HEADER = 0xF1
        private const val MLR_FRAGMENT_TYPE_MASK = 0x0F
        private const val MLR_DATA_FRAGMENT_TYPE = 0x01
        private const val MLR_FULL_PACKET_SIZE = 20

        private val HEART_RATE_SERVICE_UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val RSC_SERVICE_UUID =
            UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        private val RSC_MEASUREMENT_UUID =
            UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        private val MLR_DATA_CHARACTERISTIC_UUID =
            UUID.fromString("6a4ecd28-667b-11e3-949a-0800200c9a66")
    }
}

private class MlrStreamState {
    private val buffer = ByteArrayOutputStream()
    private var expectedSequence: Int? = null

    val bufferedSize: Int
        get() = buffer.size()

    fun append(sequence: Int, payload: ByteArray): Boolean {
        val expected = expectedSequence
        if (expected != null && sequence != expected) {
            return false
        }
        buffer.write(payload)
        expectedSequence = (sequence + 1) % 15
        return true
    }

    fun takeBlob(): ByteArray {
        val blob = buffer.toByteArray()
        reset()
        return blob
    }

    fun reset() {
        buffer.reset()
        expectedSequence = null
    }
}

private class ProtoReader(private val payload: ByteArray) {
    private var offset = 0

    val isAtEnd: Boolean
        get() = offset >= payload.size

    fun readVarint(): Long? {
        var result = 0L
        var shift = 0
        while (shift < 64 && offset < payload.size) {
            val byte = payload[offset++].toInt() and 0xff
            result = result or (((byte and 0x7f).toLong()) shl shift)
            if (byte and 0x80 == 0) {
                return result
            }
            shift += 7
        }
        return null
    }

    fun readLengthDelimited(): ByteArray? {
        val length = readVarint()?.toInt() ?: return null
        if (length < 0 || offset + length > payload.size) {
            return null
        }
        val result = payload.copyOfRange(offset, offset + length)
        offset += length
        return result
    }

    fun readLengthDelimitedAsString(): String? {
        return readLengthDelimited()?.decodeToString()
    }

    fun readFixed32AsFloat(): Float? {
        if (offset + 4 > payload.size) {
            return null
        }
        val value = ByteBuffer.wrap(payload, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .float
        offset += 4
        return value
    }

    fun skipField(wireType: Int): Boolean {
        return when (wireType) {
            0 -> readVarint() != null
            1 -> skipBytes(8)
            2 -> {
                val length = readVarint()?.toInt() ?: return false
                skipBytes(length)
            }

            5 -> skipBytes(4)
            else -> false
        }
    }

    private fun skipBytes(length: Int): Boolean {
        if (length < 0 || offset + length > payload.size) {
            return false
        }
        offset += length
        return true
    }
}
