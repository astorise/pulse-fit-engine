package com.astor.pulsefitengine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class HeuristicGarminRealtimeDecoderTest {
    private val decoder = HeuristicGarminRealtimeDecoder()

    @Test
    fun decodeReturnsEmptyFrameForEmptyPayload() {
        val frame = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = byteArrayOf(),
            timestampMillis = 1000L,
        )

        assertTrue(frame.samples.isEmpty())
        assertEquals("Trame vide", frame.debugSummary)
    }

    @Test
    fun decodeMultiLinkServiceHandlesDirectRealtimePayloadsAndFallbacks() {
        val heartRate = decoder.decodeMultiLinkService(6, byteArrayOf(0x03, 0x5a), 101L)
        assertSample(heartRate, GarminMetricType.HEART_RATE, 90f, 101L)
        assertEquals("REAL_TIME_HR: 90 bpm", heartRate.debugSummary)

        val heartRateTooShort = decoder.decodeMultiLinkService(6, byteArrayOf(0x03), 101L)
        assertEquals("Service temps reel 6 (03)", heartRateTooShort.debugSummary)

        val heartRateInvalid = decoder.decodeMultiLinkService(6, byteArrayOf(0x03, 0x05), 101L)
        assertEquals("Service temps reel 6 (0305)", heartRateInvalid.debugSummary)

        val stress = decoder.decodeMultiLinkService(13, byteArrayOf(0x58, 0x00), 102L)
        assertSample(stress, GarminMetricType.STRESS, 88f, 102L)
        assertEquals("REAL_TIME_STRESS: 88", stress.debugSummary)

        val stressTooShort = decoder.decodeMultiLinkService(13, byteArrayOf(0x01), 102L)
        assertEquals("Service temps reel 13 (01)", stressTooShort.debugSummary)

        val stressInvalid = decoder.decodeMultiLinkService(13, byteArrayOf(0xc9.toByte(), 0x00), 102L)
        assertEquals("Service temps reel 13 (c900)", stressInvalid.debugSummary)

        val spo2 = decoder.decodeMultiLinkService(19, byteArrayOf(0x61, 0x00), 103L)
        assertSample(spo2, GarminMetricType.SPO2, 97f, 103L)
        assertEquals("REAL_TIME_SPO2: 97 %", spo2.debugSummary)

        val spo2Unavailable = decoder.decodeMultiLinkService(
            19,
            byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
            103L,
        )
        assertTrue(spo2Unavailable.samples.isEmpty())
        assertEquals("REAL_TIME_SPO2: mesure indisponible", spo2Unavailable.debugSummary)

        val spo2Invalid = decoder.decodeMultiLinkService(19, byteArrayOf(0x3c), 103L)
        assertEquals("Service temps reel 19 (3c)", spo2Invalid.debugSummary)

        val respiration = decoder.decodeMultiLinkService(21, byteArrayOf(0x0b), 104L)
        assertSample(respiration, GarminMetricType.BREATHING_RATE, 11f, 104L)
        assertEquals("REAL_TIME_RESPIRATION: 11 rpm", respiration.debugSummary)

        val respirationInvalid = decoder.decodeMultiLinkService(21, byteArrayOf(0x02), 104L)
        assertEquals("Service temps reel 21 (02)", respirationInvalid.debugSummary)

        val emptyPayload = decoder.decodeMultiLinkService(21, byteArrayOf(), 104L)
        assertEquals("Service temps reel vide", emptyPayload.debugSummary)

        val unknownService = decoder.decodeMultiLinkService(99, byteArrayOf(0x01, 0x02, 0x03), 105L)
        assertEquals("Service temps reel 99 (010203)", unknownService.debugSummary)
    }

    @Test
    fun decodeHandlesStandardHeartRateServiceVariants() {
        val eightBit = decoder.decode(
            serviceUuid = HEART_RATE_SERVICE_UUID,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x00, 0x4b),
            timestampMillis = 200L,
        )
        assertSample(eightBit, GarminMetricType.HEART_RATE, 75f, 200L)
        assertEquals("Heart Rate service: 75 bpm", eightBit.debugSummary)

        val sixteenBit = decoder.decode(
            serviceUuid = null,
            characteristicUuid = HEART_RATE_MEASUREMENT_UUID,
            payload = byteArrayOf(0x01, 0x34, 0x00),
            timestampMillis = 201L,
        )
        assertSample(sixteenBit, GarminMetricType.HEART_RATE, 52f, 201L)

        val invalid = decoder.decode(
            serviceUuid = HEART_RATE_SERVICE_UUID,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x00),
            timestampMillis = 202L,
        )
        assertEquals("Mesure cardio BLE invalide", invalid.debugSummary)

        val incomplete16Bit = decoder.decode(
            serviceUuid = null,
            characteristicUuid = HEART_RATE_MEASUREMENT_UUID,
            payload = byteArrayOf(0x01, 0x34),
            timestampMillis = 203L,
        )
        assertEquals("Mesure cardio 16 bits incomplete", incomplete16Bit.debugSummary)

        val outOfRange = decoder.decode(
            serviceUuid = HEART_RATE_SERVICE_UUID,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x00, 0x05),
            timestampMillis = 204L,
        )
        assertTrue(outOfRange.debugSummary!!.startsWith("Trame non decodee"))
    }

    @Test
    fun decodeHandlesRunningSpeedAndCadenceVariants() {
        val running = decoder.decode(
            serviceUuid = RSC_SERVICE_UUID,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x04, 0x00, 0x03, 0xb4.toByte()),
            timestampMillis = 300L,
        )
        assertEquals(3, running.samples.size)
        assertTrue(running.debugSummary!!.contains("180 spm"))
        assertTrue(running.debugSummary!!.contains("running"))

        val walking = decoder.decode(
            serviceUuid = null,
            characteristicUuid = RSC_MEASUREMENT_UUID,
            payload = byteArrayOf(0x00, 0x80.toByte(), 0x01, 0x64),
            timestampMillis = 301L,
        )
        assertTrue(walking.debugSummary!!.contains("100 spm"))
        assertTrue(walking.debugSummary!!.contains("walking"))

        val incomplete = decoder.decode(
            serviceUuid = RSC_SERVICE_UUID,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x00, 0x01, 0x02),
            timestampMillis = 302L,
        )
        assertEquals("Mesure RSC incomplete", incomplete.debugSummary)

        val invalid = decoder.decode(
            serviceUuid = RSC_SERVICE_UUID,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x00, 0x00, 0x00, 0x00),
            timestampMillis = 303L,
        )
        assertTrue(invalid.debugSummary!!.startsWith("Trame non decodee"))
    }

    @Test
    fun decodeHandlesMlrTransportControlFragmentsAndBlobReassembly() {
        val control = decoder.decode(
            serviceUuid = null,
            characteristicUuid = MLR_DATA_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0xf1.toByte(), 0x01, 0x02),
            timestampMillis = 400L,
        )
        assertEquals("MLR controle 0102", control.debugSummary)

        val raw = decoder.decode(
            serviceUuid = null,
            characteristicUuid = MLR_DATA_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x02, 0x7f),
            timestampMillis = 401L,
        )
        assertEquals("MLR brut 027f", raw.debugSummary)

        val emptyBlob = decoder.decode(
            serviceUuid = null,
            characteristicUuid = MLR_DATA_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x11),
            timestampMillis = 402L,
        )
        assertEquals("MLR fin de blob vide", emptyBlob.debugSummary)

        val metricBlob = dataNotificationPayload(
            encodeMetricFloat(
                type = GarminMetricType.POWER.wireId,
                value = 251.5f,
                timestampSeconds = 9L,
                measurementUnit = 14,
            ),
            fieldLengthDelimited(2, byteArrayOf(0x7f, 0x7e, 0x7d, 0x7c, 0x7b)),
        )
        val firstFragment = byteArrayOf(0x11) + metricBlob.copyOfRange(0, 19)
        val secondFragment = byteArrayOf(0x21) + metricBlob.copyOfRange(19, metricBlob.size)

        val firstResult = decoder.decode(
            serviceUuid = null,
            characteristicUuid = MLR_DATA_CHARACTERISTIC_UUID,
            payload = firstFragment,
            timestampMillis = 403L,
        )
        assertEquals("MLR fragment seq=1 total=19 octets", firstResult.debugSummary)

        val secondResult = decoder.decode(
            serviceUuid = null,
            characteristicUuid = MLR_DATA_CHARACTERISTIC_UUID,
            payload = secondFragment,
            timestampMillis = 404L,
        )
        assertSample(secondResult, GarminMetricType.POWER, 251.5f, 9_000L, "W")
        assertTrue(secondResult.debugSummary!!.startsWith("MLR blob "))
        assertTrue(secondResult.debugSummary!!.contains("POWER"))

        val mismatchedSequenceBuffered = decoder.decode(
            serviceUuid = null,
            characteristicUuid = MLR_DATA_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x31) + ByteArray(19) { 0x00 },
            timestampMillis = 405L,
        )
        assertEquals("MLR fragment seq=3 total=19 octets", mismatchedSequenceBuffered.debugSummary)

        val mismatchedSequenceFlush = decoder.decode(
            serviceUuid = null,
            characteristicUuid = MLR_DATA_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x51, 0x00, 0x2d, 0x00),
            timestampMillis = 406L,
        )
        assertTrue(mismatchedSequenceFlush.debugSummary!!.startsWith("MLR blob "))
    }

    @Test
    fun decodeParsesNestedSmartLiveMetricPayloads() {
        val metric = encodeMetricFloat(
            type = GarminMetricType.POWER.wireId,
            value = 320.25f,
            timestampSeconds = 123L,
            measurementUnit = 14,
        )
        val dataNotification = fieldLengthDelimited(2, byteArrayOf(0x55, 0x66)) +
            fieldLengthDelimited(1, metric)
        val liveSession = fieldLengthDelimited(8, byteArrayOf(0x01)) +
            fieldLengthDelimited(3, dataNotification)
        val alert = fieldVarint(1, 7) +
            fieldFixed64(2, 12L) +
            fieldLengthDelimited(1012, liveSession)
        val eventSharing = fieldFixed32(2, 42) +
            fieldLengthDelimited(3, alert)
        val smartPayload = fieldVarint(1, 1) +
            fieldLengthDelimited(30, eventSharing)

        val decoded = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = smartPayload,
            timestampMillis = 500L,
        )

        assertSample(decoded, GarminMetricType.POWER, 320.25f, 123_000L, "W")
        assertEquals("DataNotification: POWER", decoded.debugSummary)
    }

    @Test
    fun decodeParsesDirectMetricsStringsAndAllMeasurementUnitOverrides() {
        val stringMetric = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = dataNotificationPayload(
                encodeMetricString(
                    type = GarminMetricType.INTERVALS.wireId,
                    value = "warmup",
                    timestampSeconds = 77L,
                ),
            ),
            timestampMillis = 600L,
        )
        val stringSample = stringMetric.samples.single()
        assertEquals(GarminMetricType.INTERVALS, stringSample.type)
        assertEquals("warmup", stringSample.textValue)
        assertEquals(77_000L, stringSample.timestampMillis)

        val expectedUnits = linkedMapOf(
            1 to "m",
            2 to "km",
            5 to "mi",
            9 to "m/s",
            10 to "km/h",
            11 to "mph",
            13 to "bpm",
            14 to "W",
            99 to null,
        )

        expectedUnits.forEach { (unitCode, expectedUnit) ->
            val decoded = decoder.decode(
                serviceUuid = null,
                characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
                payload = dataNotificationPayload(
                    encodeMetricInt(
                        type = GarminMetricType.DISTANCE.wireId,
                        value = 42,
                        timestampSeconds = unitCode.toLong(),
                        measurementUnit = unitCode,
                        leadingFixed32UnknownField = true,
                    ),
                ),
                timestampMillis = 601L,
            )

            val sample = decoded.samples.single()
            assertEquals(expectedUnit, sample.unitOverride)
            assertEquals(unitCode * 1_000L, sample.timestampMillis)
        }

        val unknownType = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = dataNotificationPayload(encodeMetricInt(type = 9_999, value = 1)),
            timestampMillis = 602L,
        )
        assertTrue(unknownType.debugSummary!!.startsWith("Trame non decodee"))
    }

    @Test
    fun decodeParsesHeartRateAlertsAndUnknownAlertFallbacks() {
        val alertHeartRate = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = fieldVarint(1, 5) +
                fieldLengthDelimited(
                    1013,
                    encodeHeartRateProto(bpm = 88, confident = true, beatPeriod = 512),
                ),
            timestampMillis = 700L,
        )

        assertSample(alertHeartRate, GarminMetricType.HEART_RATE, 88f, 700L)
        assertEquals("HeartRate proto: 88 bpm, confident, beat_period=512", alertHeartRate.debugSummary)

        val alertWithoutKnownExtension = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = fieldVarint(1, 9) + fieldLengthDelimited(99, byteArrayOf(0x01)),
            timestampMillis = 701L,
        )
        assertEquals("AlertNotification sans extension connue", alertWithoutKnownExtension.debugSummary)

        val invalidDirectHeartRateProto = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = fieldVarint(1, 5) +
                fieldLengthDelimited(1013, encodeHeartRateProto(bpm = 10)),
            timestampMillis = 702L,
        )
        assertTrue(invalidDirectHeartRateProto.debugSummary!!.startsWith("Trame non decodee"))
    }

    @Test
    fun decodeFallsBackForMalformedTruncatedAndOversizedPayloads() {
        val unterminatedVarint = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = byteArrayOf(0x80.toByte()),
            timestampMillis = 800L,
        )
        assertTrue(unterminatedVarint.debugSummary!!.startsWith("Trame non decodee"))

        val invalidLengthDelimited = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = fieldKey(30, 2) + encodeVarint(5) + byteArrayOf(0x01),
            timestampMillis = 801L,
        )
        assertTrue(invalidLengthDelimited.debugSummary!!.startsWith("Trame non decodee"))

        val unsupportedWireType = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = fieldKey(1, 3),
            timestampMillis = 802L,
        )
        assertTrue(unsupportedWireType.debugSummary!!.startsWith("Trame non decodee"))

        val oversizedUnknown = decoder.decode(
            serviceUuid = null,
            characteristicUuid = GENERIC_CHARACTERISTIC_UUID,
            payload = ByteArray(30) { index -> (index + 1).toByte() },
            timestampMillis = 803L,
        )
        assertTrue(oversizedUnknown.debugSummary!!.contains("..."))
    }

    private fun assertSample(
        frame: GarminDecodedFrame,
        expectedType: GarminMetricType,
        expectedValue: Float,
        expectedTimestamp: Long,
        expectedUnit: String? = null,
    ) {
        val sample = frame.samples.single()
        assertEquals(expectedType, sample.type)
        assertEquals(expectedValue, sample.numericValue!!, 0.0001f)
        assertEquals(expectedTimestamp, sample.timestampMillis)
        assertEquals(expectedUnit, sample.unitOverride)
    }

    private fun encodeMetricFloat(
        type: Int,
        value: Float,
        timestampSeconds: Long? = null,
        measurementUnit: Int? = null,
    ): ByteArray {
        val fields = mutableListOf<ByteArray>()
        if (timestampSeconds != null) {
            fields += fieldVarint(1, timestampSeconds)
        }
        fields += fieldVarint(2, type.toLong())
        fields += fieldFixed32Float(3, value)
        if (measurementUnit != null) {
            fields += fieldVarint(6, measurementUnit.toLong())
        }
        return fields.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
    }

    private fun encodeMetricInt(
        type: Int,
        value: Int,
        timestampSeconds: Long? = null,
        measurementUnit: Int? = null,
        leadingFixed32UnknownField: Boolean = false,
    ): ByteArray {
        val fields = mutableListOf<ByteArray>()
        if (leadingFixed32UnknownField) {
            fields += fieldFixed32(8, 17)
        }
        if (timestampSeconds != null) {
            fields += fieldVarint(1, timestampSeconds)
        }
        fields += fieldVarint(2, type.toLong())
        fields += fieldVarint(4, value.toLong())
        if (measurementUnit != null) {
            fields += fieldVarint(6, measurementUnit.toLong())
        }
        return fields.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
    }

    private fun encodeMetricString(
        type: Int,
        value: String,
        timestampSeconds: Long? = null,
    ): ByteArray {
        val fields = mutableListOf<ByteArray>()
        if (timestampSeconds != null) {
            fields += fieldVarint(1, timestampSeconds)
        }
        fields += fieldVarint(2, type.toLong())
        fields += fieldLengthDelimited(5, value.encodeToByteArray())
        return fields.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
    }

    private fun encodeHeartRateProto(
        bpm: Int,
        confident: Boolean = false,
        beatPeriod: Int? = null,
    ): ByteArray {
        val fields = mutableListOf<ByteArray>()
        fields += fieldVarint(1, bpm.toLong())
        if (confident) {
            fields += fieldVarint(2, 1)
        }
        if (beatPeriod != null) {
            fields += fieldVarint(3, beatPeriod.toLong())
        }
        return fields.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
    }

    private fun dataNotificationPayload(
        metricPayload: ByteArray,
        vararg additionalFields: ByteArray,
    ): ByteArray {
        val fields = listOf(fieldLengthDelimited(1, metricPayload)) + additionalFields
        return fields.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
    }

    private fun fieldVarint(fieldNumber: Int, value: Long): ByteArray {
        return fieldKey(fieldNumber, 0) + encodeVarint(value)
    }

    private fun fieldLengthDelimited(fieldNumber: Int, value: ByteArray): ByteArray {
        return fieldKey(fieldNumber, 2) + encodeVarint(value.size.toLong()) + value
    }

    private fun fieldFixed32(fieldNumber: Int, value: Int): ByteArray {
        return fieldKey(fieldNumber, 5) +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun fieldFixed32Float(fieldNumber: Int, value: Float): ByteArray {
        return fieldKey(fieldNumber, 5) +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
    }

    private fun fieldFixed64(fieldNumber: Int, value: Long): ByteArray {
        return fieldKey(fieldNumber, 1) +
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }

    private fun fieldKey(fieldNumber: Int, wireType: Int): ByteArray {
        return encodeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun encodeVarint(value: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var remaining = value
        while (true) {
            if (remaining and 0x7f.inv().toLong() == 0L) {
                output.write(remaining.toInt())
                return output.toByteArray()
            }
            output.write((remaining.toInt() and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
    }

    private companion object {
        private val GENERIC_CHARACTERISTIC_UUID =
            UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")
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
