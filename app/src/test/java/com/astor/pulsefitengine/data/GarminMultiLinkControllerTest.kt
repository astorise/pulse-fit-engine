package com.astor.pulsefitengine.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class GarminMultiLinkControllerTest {
    @Test
    fun prepareBuildsCandidatePairsAndStartIfReadyUsesTheFirstPair() {
        val statuses = mutableListOf<String>()
        val controller = GarminMultiLinkController(statuses::add, timeSource = { 0x1000L })
        val sink = FakeCommandSink()

        assertNull(controller.startIfReady(sink))

        controller.prepare(
            listOf(
                GarminGattServiceInfo(
                    uuid = MULTI_LINK_SERVICE_UUID,
                    characteristics = listOf(
                        characteristic("6a4e2812-667b-11e3-949a-0800200c9a66", PROPERTY_NOTIFY),
                        characteristic("6a4e2822-667b-11e3-949a-0800200c9a66"),
                        characteristic("6a4e2810-667b-11e3-949a-0800200c9a66", PROPERTY_NOTIFY),
                        characteristic("6a4e2820-667b-11e3-949a-0800200c9a66"),
                        characteristic("6a4e2814-667b-11e3-949a-0800200c9a66", 0),
                        characteristic("6a4e2824-667b-11e3-949a-0800200c9a66"),
                        characteristic("6a4e2816-667b-11e3-949a-0800200c9a66", PROPERTY_NOTIFY),
                    ),
                ),
            ),
        )

        val startMessage = controller.startIfReady(sink)

        assertEquals("Negociation MultiLink sur 2810/2820, fermeture des handles existants", startMessage)
        assertTrue(controller.isTransportCharacteristic(uuid("6a4e2810-667b-11e3-949a-0800200c9a66")))
        assertTrue(controller.isTransportCharacteristic(uuid("6a4e2812-667b-11e3-949a-0800200c9a66")))
        assertFalse(controller.isTransportCharacteristic(uuid("6a4e2820-667b-11e3-949a-0800200c9a66")))
        assertTrue(controller.handles(uuid("6a4e2810-667b-11e3-949a-0800200c9a66")))
        assertEquals(uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), sink.writes.single().characteristicUuid)
        assertEquals(5, commandType(sink.writes.single().payload))
        assertNull(controller.startIfReady(sink))
        assertNull(controller.onCharacteristicWrite(sink, uuid("6a4e28ff-667b-11e3-949a-0800200c9a66"), 1))
    }

    @Test
    fun onCharacteristicWriteFailureTriesNextCandidateAndEventuallyFails() {
        val statuses = mutableListOf<String>()
        val controller = GarminMultiLinkController(statuses::add, timeSource = { 0x2000L })
        val sink = FakeCommandSink(results = listOf(true, true, false))
        controller.prepare(candidateServices())

        val firstStart = controller.startIfReady(sink)
        assertEquals("Negociation MultiLink sur 2810/2820, fermeture des handles existants", firstStart)

        val secondStart = controller.onCharacteristicWrite(
            sink,
            uuid("6a4e2820-667b-11e3-949a-0800200c9a66"),
            133,
        )
        assertEquals("Negociation MultiLink sur 2812/2822, fermeture des handles existants", secondStart)
        assertTrue(statuses.any { it.contains("Ecriture MultiLink echouee sur 2810/2820") })

        val finalFailure = controller.onCharacteristicWrite(
            sink,
            uuid("6a4e2822-667b-11e3-949a-0800200c9a66"),
            134,
        )
        assertEquals("Aucun canal MultiLink exploitable n'a accepte la negotiation", finalFailure)
    }

    @Test
    fun handleNotificationIgnoresInactiveCharacteristicsAndUnknownHandles() {
        val statuses = mutableListOf<String>()
        val controller = GarminMultiLinkController(statuses::add, timeSource = { 0x3000L })
        val sink = FakeCommandSink()

        assertFalse(
            controller.handleNotification(
                commandSink = sink,
                characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
                payload = byteArrayOf(0x00),
                timestampMillis = 1L,
            ) { _, _, _, _ -> error("no callback expected") },
        )

        controller.prepare(candidateServices())
        controller.startIfReady(sink)

        assertFalse(
            controller.handleNotification(
                commandSink = sink,
                characteristicUuid = uuid("6a4e2812-667b-11e3-949a-0800200c9a66"),
                payload = byteArrayOf(0x00),
                timestampMillis = 2L,
            ) { _, _, _, _ -> error("no callback expected") },
        )
        assertFalse(
            controller.handleNotification(
                commandSink = sink,
                characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
                payload = byteArrayOf(),
                timestampMillis = 3L,
            ) { _, _, _, _ -> error("no callback expected") },
        )
        assertFalse(
            controller.handleNotification(
                commandSink = sink,
                characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
                payload = byteArrayOf(0x63, 0x01),
                timestampMillis = 4L,
            ) { _, _, _, _ -> error("no callback expected") },
        )
    }

    @Test
    fun handleNotificationCoversRegistrationRealtimeDataAndCleanupPaths() {
        val statuses = mutableListOf<String>()
        val controller = GarminMultiLinkController(statuses::add, timeSource = { 0x4000L })
        val sink = FakeCommandSink()
        controller.prepare(candidateServices())
        controller.startIfReady(sink)
        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)

        val session = sessionId(sink.writes.first().payload)
        assertTrue(
            controller.handleNotification(
                commandSink = sink,
                characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
                payload = responseFrame(6, session, 0, byteArrayOf(0x00)),
                timestampMillis = 10L,
            ) { _, _, _, _ -> error("service payload not expected yet") },
        )
        assertEquals(4, serviceId(sink.writes[1].payload))

        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, session, 4, byteArrayOf(0x00, 0x01, 0x02)),
            timestampMillis = 11L,
        ) { _, _, _, _ -> error("service payload not expected yet") }
        assertEquals(6, serviceId(sink.writes[2].payload))

        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, session, 6, byteArrayOf(0x00, 0x14, 0x01)),
            timestampMillis = 12L,
        ) { _, _, _, _ -> error("service payload not expected yet") }
        assertEquals(13, serviceId(sink.writes[3].payload))

        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, session, 13, byteArrayOf(0x09)),
            timestampMillis = 13L,
        ) { _, _, _, _ -> error("service payload not expected yet") }
        assertEquals(19, serviceId(sink.writes[4].payload))

        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, session, 19, byteArrayOf(0x00, 0x16, 0x04)),
            timestampMillis = 14L,
        ) { _, _, _, _ -> error("service payload not expected yet") }
        assertEquals(21, serviceId(sink.writes[5].payload))

        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, session, 21, byteArrayOf(0x00, 0x17, 0x05)),
            timestampMillis = 15L,
        ) { _, _, _, _ -> error("service payload not expected yet") }
        assertEquals(26, serviceId(sink.writes[6].payload))

        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, session, 26, byteArrayOf(0x07)),
            timestampMillis = 16L,
        ) { _, _, _, _ -> error("service payload not expected yet") }

        val callbacks = mutableListOf<ServicePayload>()
        assertTrue(
            controller.handleNotification(
                commandSink = sink,
                characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
                payload = byteArrayOf(0x14, 0x01, 0x02, 0x03),
                timestampMillis = 17L,
            ) { serviceId, sourceUuid, payload, summary ->
                callbacks += ServicePayload(serviceId, sourceUuid, payload, summary)
            },
        )
        assertEquals(1, callbacks.size)
        assertEquals(6, callbacks.single().serviceId)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), callbacks.single().payload)
        assertEquals(uuid("6a4e2810-667b-11e3-949a-0800200c9a66"), callbacks.single().sourceUuid)
        assertTrue(callbacks.single().summary.contains("REAL_TIME_HR"))

        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(3, session, 0, byteArrayOf(0x14)),
            timestampMillis = 18L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(statuses.any { it.contains("fermeture handle=20") })

        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(4, 0L, 0, byteArrayOf(0x16)),
            timestampMillis = 19L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(statuses.any { it.contains("handle invalide 22") })

        assertFalse(
            controller.handleNotification(
                commandSink = sink,
                characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
                payload = byteArrayOf(0x16, 0x09),
                timestampMillis = 20L,
            ) { _, _, _, _ -> error("service payload not expected") },
        )

        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(9, session, 99, byteArrayOf(0x00)),
            timestampMillis = 21L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(statuses.any { it.contains("commande 9 recue pour service 99 (21)") })
        assertTrue(statuses.any { it.contains("MultiLink actif, services ouverts: REAL_TIME_HR") })
        assertTrue(statuses.any { it.contains("REAL_TIME_SPO2") })
        assertTrue(statuses.any { it.contains("REAL_TIME_RESPIRATION") })
    }

    @Test
    fun handleNotificationCoversPendingAuthRedirectAndMissingRedirect() {
        val redirectedStatuses = mutableListOf<String>()
        val redirectedController = GarminMultiLinkController(redirectedStatuses::add, timeSource = { 0x5000L })
        val redirectedSink = FakeCommandSink()
        redirectedController.prepare(candidateServices())
        redirectedController.startIfReady(redirectedSink)
        redirectedController.onCharacteristicWrite(redirectedSink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        val redirectedSession = sessionId(redirectedSink.writes.first().payload)
        redirectedController.handleNotification(
            commandSink = redirectedSink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, redirectedSession, 4, byteArrayOf(0x03) + shortBytes(0x2812)),
            timestampMillis = 30L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(redirectedStatuses.any { it.contains("redirige vers 2812/2822") })
        assertTrue(redirectedController.handles(uuid("6a4e2812-667b-11e3-949a-0800200c9a66")))

        val missingRedirectStatuses = mutableListOf<String>()
        val missingRedirectController = GarminMultiLinkController(missingRedirectStatuses::add, timeSource = { 0x6000L })
        val missingRedirectSink = FakeCommandSink()
        missingRedirectController.prepare(candidateServices())
        missingRedirectController.startIfReady(missingRedirectSink)
        missingRedirectController.onCharacteristicWrite(missingRedirectSink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        val missingRedirectSession = sessionId(missingRedirectSink.writes.first().payload)
        missingRedirectController.handleNotification(
            commandSink = missingRedirectSink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, missingRedirectSession, 4, byteArrayOf(0x03) + shortBytes(0x2818)),
            timestampMillis = 31L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(missingRedirectStatuses.any { it.contains("redirection 6a4e2818-667b-11e3-949a-0800200c9a66 absente") })

        val pendingStatuses = mutableListOf<String>()
        val pendingController = GarminMultiLinkController(pendingStatuses::add, timeSource = { 0x7000L })
        val pendingSink = FakeCommandSink()
        pendingController.prepare(candidateServices())
        pendingController.startIfReady(pendingSink)
        pendingController.onCharacteristicWrite(pendingSink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        val pendingSession = sessionId(pendingSink.writes.first().payload)
        pendingController.handleNotification(
            commandSink = pendingSink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, pendingSession, 19, byteArrayOf(0x02)),
            timestampMillis = 32L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(pendingStatuses.any { it.contains("REAL_TIME_SPO2 en attente d'autorisation") })
    }

    @Test
    fun handleNotificationCoversTruncatedMismatchedHeaderAndWriteInFlightFallbacks() {
        val statuses = mutableListOf<String>()
        val controller = GarminMultiLinkController(statuses::add, timeSource = { 0x8000L })
        val sink = FakeCommandSink()
        controller.prepare(candidateServices())
        controller.startIfReady(sink)
        val session = sessionId(sink.writes.first().payload)

        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = byteArrayOf(0x00, 0x06),
            timestampMillis = 40L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(statuses.any { it.contains("Reponse MultiLink tronquee") })

        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = byteArrayOf(0x01) + responseFrame(6, session, 0, byteArrayOf(0x00)).copyOfRange(1, 13),
            timestampMillis = 41L,
        ) { _, _, _, _ -> error("service payload not expected") }

        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(6, session + 1, 0, byteArrayOf(0x00)),
            timestampMillis = 42L,
        ) { _, _, _, _ -> error("service payload not expected") }

        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(6, session, 0, byteArrayOf(0x00)),
            timestampMillis = 43L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(controller.handles(uuid("6a4e2812-667b-11e3-949a-0800200c9a66")))
        assertEquals(uuid("6a4e2822-667b-11e3-949a-0800200c9a66"), sink.writes.last().characteristicUuid)
    }

    @Test
    fun registrationFailuresCanLeadToNoRealtimeServicesOpened() {
        val statuses = mutableListOf<String>()
        val controller = GarminMultiLinkController(statuses::add, timeSource = { 0x9000L })
        val sink = FakeCommandSink()
        controller.prepare(candidateServices())
        controller.startIfReady(sink)
        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        val session = sessionId(sink.writes.first().payload)

        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(6, session, 0, byteArrayOf(0x00)),
            timestampMillis = 50L,
        ) { _, _, _, _ -> error("service payload not expected") }
        controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        controller.handleNotification(
            commandSink = sink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, session, 4, byteArrayOf(0x00, 0x01, 0x00)),
            timestampMillis = 51L,
        ) { _, _, _, _ -> error("service payload not expected") }

        repeat(5) { index ->
            controller.onCharacteristicWrite(sink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
            val requestedService = listOf(6, 13, 19, 21, 26)[index]
            controller.handleNotification(
                commandSink = sink,
                characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
                payload = responseFrame(1, session, requestedService, byteArrayOf(0x09)),
                timestampMillis = 52L + index,
            ) { _, _, _, _ -> error("service payload not expected") }
        }

        assertTrue(statuses.any { it.contains("MultiLink actif mais aucun service temps reel n'a accepte l'ouverture") })
    }

    @Test
    fun registrationErrorsCanSkipToNextCandidateOrTerminate() {
        val noRedirectStatuses = mutableListOf<String>()
        val noRedirectController = GarminMultiLinkController(noRedirectStatuses::add, timeSource = { 0xa000L })
        val noRedirectSink = FakeCommandSink()
        noRedirectController.prepare(candidateServices())
        noRedirectController.startIfReady(noRedirectSink)
        noRedirectController.onCharacteristicWrite(noRedirectSink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        val noRedirectSession = sessionId(noRedirectSink.writes.first().payload)
        noRedirectController.handleNotification(
            commandSink = noRedirectSink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, noRedirectSession, 4, byteArrayOf(0x03)),
            timestampMillis = 60L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(noRedirectStatuses.any { it.contains("canal deja utilise sans redirection exploitable") })

        val regFailureStatuses = mutableListOf<String>()
        val regFailureController = GarminMultiLinkController(regFailureStatuses::add, timeSource = { 0xb000L })
        val regFailureSink = FakeCommandSink(results = listOf(true, true, false))
        regFailureController.prepare(candidateServices())
        regFailureController.startIfReady(regFailureSink)
        regFailureController.onCharacteristicWrite(regFailureSink, uuid("6a4e2820-667b-11e3-949a-0800200c9a66"), 0)
        val regFailureSession = sessionId(regFailureSink.writes.first().payload)
        regFailureController.handleNotification(
            commandSink = regFailureSink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(6, regFailureSession, 0, byteArrayOf(0x00)),
            timestampMillis = 61L,
        ) { _, _, _, _ -> error("service payload not expected") }
        regFailureController.handleNotification(
            commandSink = regFailureSink,
            characteristicUuid = uuid("6a4e2810-667b-11e3-949a-0800200c9a66"),
            payload = responseFrame(1, regFailureSession, 4, byteArrayOf(0x09)),
            timestampMillis = 62L,
        ) { _, _, _, _ -> error("service payload not expected") }
        assertTrue(regFailureStatuses.any { it.contains("echec ouverture REGISTRATION (status=9)") })
    }

    private fun candidateServices(): List<GarminGattServiceInfo> {
        return listOf(
            GarminGattServiceInfo(
                uuid = MULTI_LINK_SERVICE_UUID,
                characteristics = listOf(
                    characteristic("6a4e2810-667b-11e3-949a-0800200c9a66", PROPERTY_NOTIFY),
                    characteristic("6a4e2820-667b-11e3-949a-0800200c9a66"),
                    characteristic("6a4e2812-667b-11e3-949a-0800200c9a66", PROPERTY_NOTIFY),
                    characteristic("6a4e2822-667b-11e3-949a-0800200c9a66"),
                ),
            ),
        )
    }

    private fun characteristic(uuid: String, properties: Int = 0): GarminGattCharacteristicInfo {
        return GarminGattCharacteristicInfo(uuid(uuid), properties)
    }

    private fun responseFrame(commandType: Int, sessionId: Long, serviceId: Int, tail: ByteArray): ByteArray {
        return ByteBuffer.allocate(12 + tail.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .put(commandType.toByte())
            .putLong(sessionId)
            .putShort(serviceId.toShort())
            .put(tail)
            .array()
    }

    private fun sessionId(payload: ByteArray): Long {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getLong(2)
    }

    private fun commandType(payload: ByteArray): Int {
        return payload[1].toInt() and 0xff
    }

    private fun serviceId(payload: ByteArray): Int {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt() and 0xffff
    }

    private fun shortBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private data class ServicePayload(
        val serviceId: Int,
        val sourceUuid: UUID,
        val payload: ByteArray,
        val summary: String,
    )

    private data class Write(
        val characteristicUuid: UUID,
        val payload: ByteArray,
    )

    private class FakeCommandSink(
        results: List<Boolean> = emptyList(),
    ) : GarminMultiLinkCommandSink {
        private val queuedResults = ArrayDeque(results)
        val writes = mutableListOf<Write>()

        override fun write(characteristicUuid: UUID, payload: ByteArray): Boolean {
            val copy = payload.copyOf()
            writes += Write(characteristicUuid, copy)
            return queuedResults.removeFirstOrNull() ?: true
        }
    }

    private companion object {
        private const val PROPERTY_NOTIFY = 0x10
        private val MULTI_LINK_SERVICE_UUID =
            UUID.fromString("6a4e2800-667b-11e3-949a-0800200c9a66")
    }
}
