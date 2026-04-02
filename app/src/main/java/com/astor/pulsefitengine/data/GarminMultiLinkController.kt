package com.astor.pulsefitengine.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

internal data class GarminGattCharacteristicInfo(
    val uuid: UUID,
    val properties: Int,
)

internal data class GarminGattServiceInfo(
    val uuid: UUID,
    val characteristics: List<GarminGattCharacteristicInfo>,
)

internal interface GarminMultiLinkCommandSink {
    fun write(characteristicUuid: UUID, payload: ByteArray): Boolean
}

internal class GarminMultiLinkController(
    private val onStatus: (String) -> Unit,
    private val timeSource: () -> Long = System::currentTimeMillis,
) {
    private val candidatePairs = mutableListOf<MultiLinkPair>()
    private val serviceHandles = LinkedHashMap<Int, Int>()
    private val requestedServices = ArrayDeque(defaultRequestedServices())

    private var currentPairIndex = -1
    private var activePair: MultiLinkPair? = null
    private var phase = MultiLinkPhase.IDLE
    private var pendingServiceId: Int? = null
    private var writeInFlight = false
    private var sessionId = 0L
    private var sessionCounter = 1L
    private var started = false

    fun prepare(services: List<GarminGattServiceInfo>) {
        candidatePairs.clear()
        val multiLinkService = services.firstOrNull { it.uuid == MULTI_LINK_SERVICE_UUID } ?: return
        val charsByUuid = multiLinkService.characteristics.associateBy { it.uuid }
        multiLinkService.characteristics
            .filter { characteristic ->
                characteristic.uuid.toString().contains("6a4e281", ignoreCase = true) &&
                    characteristic.properties and PROPERTY_NOTIFY != 0
            }
            .sortedBy { it.uuid.toString() }
            .forEach { readCharacteristic ->
                val writeUuid = pairedWriteUuid(readCharacteristic.uuid) ?: return@forEach
                val writeCharacteristic = charsByUuid[writeUuid] ?: return@forEach
                candidatePairs += MultiLinkPair(
                    readUuid = readCharacteristic.uuid,
                    writeUuid = writeCharacteristic.uuid,
                )
            }
    }

    fun startIfReady(commandSink: GarminMultiLinkCommandSink): String? {
        if (started || candidatePairs.isEmpty()) {
            return null
        }
        started = true
        requestedServices.clear()
        requestedServices += defaultRequestedServices()
        serviceHandles.clear()
        return tryCandidate(commandSink, 0)
    }

    fun handles(characteristicUuid: UUID): Boolean {
        return activePair?.readUuid == characteristicUuid
    }

    fun isTransportCharacteristic(characteristicUuid: UUID): Boolean {
        return candidatePairs.any { pair -> pair.readUuid == characteristicUuid }
    }

    fun onCharacteristicWrite(
        commandSink: GarminMultiLinkCommandSink,
        characteristicUuid: UUID,
        status: Int,
    ): String? {
        val pair = activePair ?: return null
        if (pair.writeUuid != characteristicUuid) {
            return null
        }
        writeInFlight = false
        if (status == GATT_SUCCESS) {
            return null
        }
        onStatus("Ecriture MultiLink echouee sur ${pair.label} (code $status), essai du canal suivant")
        return tryCandidate(commandSink, currentPairIndex + 1)
    }

    fun handleNotification(
        commandSink: GarminMultiLinkCommandSink,
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
        onServicePayload: (serviceId: Int, sourceCharacteristicUuid: UUID, payload: ByteArray, summary: String) -> Unit,
    ): Boolean {
        val pair = activePair ?: return false
        if (pair.readUuid != characteristicUuid || payload.isEmpty()) {
            return false
        }

        if (payload[0].toInt() == 0) {
            handleCommandResponse(commandSink, pair, payload, timestampMillis)
            return true
        }

        val handle = payload[0].toInt() and 0xff
        val serviceId = serviceHandles[handle] ?: return false
        onServicePayload(
            serviceId,
            characteristicUuid,
            payload.copyOfRange(1, payload.size),
            "MultiLink ${serviceLabel(serviceId)} handle=$handle",
        )
        return true
    }

    private fun handleCommandResponse(
        commandSink: GarminMultiLinkCommandSink,
        pair: MultiLinkPair,
        payload: ByteArray,
        timestampMillis: Long,
    ) {
        if (payload.size < 13) {
            onStatus("Reponse MultiLink tronquee sur ${pair.label}")
            return
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val header = buffer.get().toInt() and 0xff
        val commandType = buffer.get().toInt()
        if (header != 0) {
            return
        }

        val responseSessionId = buffer.long
        val responseServiceId = buffer.short.toInt() and 0xffff
        if (commandType != INVALID_HANDLE_RESPONSE && responseSessionId != sessionId) {
            return
        }

        when (commandType) {
            CLOSE_ALL_RESPONSE -> {
                phase = MultiLinkPhase.OPENING_REGISTRATION
                onStatus("MultiLink ${pair.label}: fermeture globale OK, ouverture du service REGISTRATION")
                if (!writeRegisterCommand(commandSink, REGISTRATION_SERVICE_ID)) {
                    tryCandidate(commandSink, currentPairIndex + 1)
                }
            }

            REGISTER_RESPONSE -> {
                val status = buffer.get().toInt() and 0xff
                when (status) {
                    REGISTER_STATUS_SUCCESS -> {
                        val handle = if (buffer.hasRemaining()) buffer.get().toInt() and 0xff else 0
                        val flags = if (buffer.hasRemaining()) buffer.get().toInt() and 0xff else 0
                        if (responseServiceId == REGISTRATION_SERVICE_ID) {
                            phase = MultiLinkPhase.OPENING_REALTIME
                            onStatus(
                                "MultiLink ${pair.label}: REGISTRATION ouvert sur handle=$handle flags=$flags",
                            )
                            openNextRealtimeService(commandSink)
                            return
                        }

                        serviceHandles[handle] = responseServiceId
                        onStatus(
                            "MultiLink ${pair.label}: ${serviceLabel(responseServiceId)} ouvert sur handle=$handle flags=$flags",
                        )
                        phase = MultiLinkPhase.OPENING_REALTIME
                        openNextRealtimeService(commandSink)
                    }

                    REGISTER_STATUS_ALREADY_IN_USE -> {
                        if (!buffer.hasRemaining()) {
                            onStatus("MultiLink ${pair.label}: canal deja utilise sans redirection exploitable")
                            tryCandidate(commandSink, currentPairIndex + 1)
                            return
                        }
                        val redirectedReadUuid = redirectedUuid(buffer.short.toInt() and 0xffff)
                        val redirectedWriteUuid = pairedWriteUuid(redirectedReadUuid)
                        val redirectedIndex = candidatePairs.indexOfFirst { candidate ->
                            candidate.readUuid == redirectedReadUuid &&
                                candidate.writeUuid == redirectedWriteUuid
                        }
                        if (redirectedIndex >= 0) {
                            onStatus("MultiLink ${pair.label}: redirige vers ${candidatePairs[redirectedIndex].label}")
                            tryCandidate(commandSink, redirectedIndex)
                        } else {
                            onStatus("MultiLink ${pair.label}: redirection $redirectedReadUuid absente, canal suivant")
                            tryCandidate(commandSink, currentPairIndex + 1)
                        }
                    }

                    REGISTER_STATUS_PENDING_AUTH -> {
                        onStatus("MultiLink ${pair.label}: ${serviceLabel(responseServiceId)} en attente d'autorisation")
                    }

                    else -> {
                        onStatus(
                            "MultiLink ${pair.label}: echec ouverture ${serviceLabel(responseServiceId)} (status=$status)",
                        )
                        if (responseServiceId == REGISTRATION_SERVICE_ID) {
                            tryCandidate(commandSink, currentPairIndex + 1)
                        } else {
                            phase = MultiLinkPhase.OPENING_REALTIME
                            openNextRealtimeService(commandSink)
                        }
                    }
                }
            }

            CLOSE_HANDLE_RESPONSE -> {
                if (!buffer.hasRemaining()) {
                    return
                }
                val handle = buffer.get().toInt() and 0xff
                serviceHandles.remove(handle)
                onStatus("MultiLink ${pair.label}: fermeture handle=$handle")
            }

            INVALID_HANDLE_RESPONSE -> {
                if (payload.size > 12) {
                    val invalidHandle = payload[12].toInt() and 0xff
                    serviceHandles.remove(invalidHandle)
                    onStatus("MultiLink ${pair.label}: handle invalide $invalidHandle")
                }
            }

            else -> {
                onStatus(
                    "MultiLink ${pair.label}: commande $commandType recue pour service $responseServiceId ($timestampMillis)",
                )
            }
        }
    }

    private fun openNextRealtimeService(commandSink: GarminMultiLinkCommandSink) {
        val nextServiceId = requestedServices.removeFirstOrNull()
        if (nextServiceId == null) {
            phase = MultiLinkPhase.READY
            onStatus(
                if (serviceHandles.isEmpty()) {
                    "MultiLink actif mais aucun service temps reel n'a accepte l'ouverture"
                } else {
                    "MultiLink actif, services ouverts: ${serviceHandles.values.joinToString { serviceLabel(it) }}"
                },
            )
            return
        }

        if (!writeRegisterCommand(commandSink, nextServiceId)) {
            tryCandidate(commandSink, currentPairIndex + 1)
        }
    }

    private fun writeRegisterCommand(commandSink: GarminMultiLinkCommandSink, serviceId: Int): Boolean {
        pendingServiceId = serviceId
        val frame = ByteBuffer.allocate(13)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .put(0)
            .putLong(sessionId)
            .putShort(serviceId.toShort())
            .put(0)
            .array()
        return writeCommand(commandSink, frame)
    }

    private fun writeCloseAll(commandSink: GarminMultiLinkCommandSink): Boolean {
        val frame = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .put(CLOSE_ALL_REQUEST.toByte())
            .putLong(sessionId)
            .putShort(0)
            .array()
        return writeCommand(commandSink, frame)
    }

    private fun writeCommand(commandSink: GarminMultiLinkCommandSink, payload: ByteArray): Boolean {
        val pair = activePair ?: return false
        if (writeInFlight) {
            return false
        }
        val queued = commandSink.write(pair.writeUuid, payload)
        writeInFlight = queued
        return queued
    }

    private fun tryCandidate(commandSink: GarminMultiLinkCommandSink, pairIndex: Int): String? {
        val pair = candidatePairs.getOrNull(pairIndex)
        if (pair == null) {
            activePair = null
            phase = MultiLinkPhase.FAILED
            return "Aucun canal MultiLink exploitable n'a accepte la negotiation"
        }

        currentPairIndex = pairIndex
        activePair = pair
        phase = MultiLinkPhase.CLOSING_ALL
        writeInFlight = false
        serviceHandles.clear()
        sessionId = nextSessionId()
        pendingServiceId = null

        return if (writeCloseAll(commandSink)) {
            "Negociation MultiLink sur ${pair.label}, fermeture des handles existants"
        } else {
            tryCandidate(commandSink, pairIndex + 1)
        }
    }

    private fun nextSessionId(): Long {
        return timeSource() xor (sessionCounter++)
    }

    private fun redirectedUuid(shortValue: Int): UUID {
        return UUID.fromString(
            String.format(
                Locale.US,
                "6a4e%04x-667b-11e3-949a-0800200c9a66",
                shortValue,
            ),
        )
    }

    private fun pairedWriteUuid(readUuid: UUID): UUID? {
        val text = readUuid.toString()
        val marker = "6a4e281"
        val markerIndex = text.indexOf(marker, ignoreCase = true)
        if (markerIndex == -1) {
            return null
        }
        val replacement = buildString {
            append(text.substring(0, markerIndex))
            append("6a4e282")
            append(text.substring(markerIndex + marker.length))
        }
        return runCatching { UUID.fromString(replacement) }.getOrNull()
    }

    private fun serviceLabel(serviceId: Int): String {
        return when (serviceId) {
            REGISTRATION_SERVICE_ID -> "REGISTRATION"
            REAL_TIME_HR_SERVICE_ID -> "REAL_TIME_HR"
            REAL_TIME_STRESS_SERVICE_ID -> "REAL_TIME_STRESS"
            REAL_TIME_SPO2_SERVICE_ID -> "REAL_TIME_SPO2"
            REAL_TIME_RESPIRATION_SERVICE_ID -> "REAL_TIME_RESPIRATION"
            REAL_TIME_ACTIVE_TIME_SERVICE_ID -> "REAL_TIME_ACTIVE_TIME"
            else -> "service=$serviceId"
        }
    }

    private data class MultiLinkPair(
        val readUuid: UUID,
        val writeUuid: UUID,
    ) {
        val label: String
            get() = "${readUuid.toString().substring(4, 8)}/${writeUuid.toString().substring(4, 8)}"
    }

    private enum class MultiLinkPhase {
        IDLE,
        CLOSING_ALL,
        OPENING_REGISTRATION,
        OPENING_REALTIME,
        READY,
        FAILED,
    }

    private companion object {
        private const val PROPERTY_NOTIFY = 0x10
        private const val GATT_SUCCESS = 0
        private const val CLOSE_ALL_REQUEST = 5
        private const val REGISTER_RESPONSE = 1
        private const val CLOSE_ALL_RESPONSE = 6
        private const val CLOSE_HANDLE_RESPONSE = 3
        private const val INVALID_HANDLE_RESPONSE = 4

        private const val REGISTER_STATUS_SUCCESS = 0
        private const val REGISTER_STATUS_PENDING_AUTH = 2
        private const val REGISTER_STATUS_ALREADY_IN_USE = 3

        private const val REGISTRATION_SERVICE_ID = 4
        private const val REAL_TIME_HR_SERVICE_ID = 6
        private const val REAL_TIME_STRESS_SERVICE_ID = 13
        private const val REAL_TIME_SPO2_SERVICE_ID = 19
        private const val REAL_TIME_RESPIRATION_SERVICE_ID = 21
        private const val REAL_TIME_ACTIVE_TIME_SERVICE_ID = 26

        private val MULTI_LINK_SERVICE_UUID =
            UUID.fromString("6a4e2800-667b-11e3-949a-0800200c9a66")

        private fun defaultRequestedServices(): List<Int> {
            return listOf(
                REAL_TIME_HR_SERVICE_ID,
                REAL_TIME_STRESS_SERVICE_ID,
                REAL_TIME_SPO2_SERVICE_ID,
                REAL_TIME_RESPIRATION_SERVICE_ID,
                REAL_TIME_ACTIVE_TIME_SERVICE_ID,
            )
        }
    }
}
