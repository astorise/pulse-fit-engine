package com.astor.pulsefitengine.data

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

class BluetoothGarminRealtimeSource(
    context: Context,
    private val decoder: GarminRealtimeDecoder = HeuristicGarminRealtimeDecoder(),
) : GarminRealtimeSource {
    private val appContext = context.applicationContext
    private val statusClock = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private val rawFrameLog by lazy {
        File(appContext.filesDir, "garmin-raw-frames.log")
    }

    override fun stream(): Flow<GarminRealtimeUpdate> = callbackFlow {
        val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
        val latestSamples = LinkedHashMap<GarminMetricType, GarminMetricSample>()
        var sourceLabel = "Montre Garmin via BLE"
        var transportStatus = "Initialisation du transport BLE..."

        fun emitUpdate(timestampMillis: Long = System.currentTimeMillis()) {
            trySend(
                GarminRealtimeUpdate(
                    sourceLabel = sourceLabel,
                    transportStatus = transportStatus,
                    samples = latestSamples.values.sortedBy { it.type.wireId },
                    updatedAtMillis = timestampMillis,
                ),
            )
        }

        fun setStatus(message: String, timestampMillis: Long = System.currentTimeMillis()) {
            transportStatus = message
            emitUpdate(timestampMillis)
        }

        if (bluetoothManager == null) {
            setStatus("BluetoothManager indisponible sur cet appareil")
            close()
            return@callbackFlow
        }

        emitUpdate()

        val connectionLoop = launch(Dispatchers.IO) {
            while (true) {
                if (!hasBluetoothConnectPermission()) {
                    sourceLabel = "Montre Garmin via BLE"
                    setStatus("Permission BLUETOOTH_CONNECT requise pour acceder a la montre")
                    delay(1_500)
                    continue
                }

                val adapter = bluetoothManager.adapter
                if (adapter == null) {
                    setStatus("Aucun adaptateur Bluetooth detecte")
                    delay(3_000)
                    continue
                }
                if (!adapter.isEnabled) {
                    setStatus("Bluetooth desactive sur le telephone")
                    delay(3_000)
                    continue
                }

                val device = findBondedGarminDevice(adapter)
                if (device == null) {
                    sourceLabel = "Montre Garmin via BLE"
                    setStatus("Aucune montre Garmin jumelee detectee")
                    delay(4_000)
                    continue
                }

                sourceLabel = "Montre ${device.name ?: device.address}"
                setStatus("Connexion GATT a ${device.name ?: device.address}")

                val disconnectSignal = CompletableDeferred<Unit>()
                val subscriptionQueue = ArrayDeque<BluetoothGattDescriptor>()
                val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
                var configuredNotifications = 0
                var completedReads = 0
                val multiLinkController = GarminMultiLinkController(
                    onStatus = { message -> setStatus(message) },
                )

                fun maybeStartMultiLink(gatt: BluetoothGatt) {
                    multiLinkController.startIfReady(gatt)?.let(::setStatus)
                }

                fun handleMultiLinkPayload(
                    serviceId: Int,
                    sourceCharacteristicUuid: UUID,
                    payload: ByteArray,
                    timestampMillis: Long,
                    transportSummary: String,
                ) {
                    val decoded = decoder.decodeMultiLinkService(
                        serviceId = serviceId,
                        payload = payload,
                        timestampMillis = timestampMillis,
                    )

                    val summary = decoded.debugSummary?.let { "$transportSummary: $it" } ?: transportSummary
                    appendRawFrame(
                        timestampMillis = timestampMillis,
                        serviceUuid = MULTI_LINK_SERVICE_UUID,
                        characteristicUuid = sourceCharacteristicUuid,
                        payload = payload,
                        decoderStatus = "Service $serviceId $summary",
                    )

                    decoded.samples.forEach { sample ->
                        latestSamples[sample.type] = sample
                    }

                    setStatus(
                        "Derniere trame ${statusClock.format(Date(timestampMillis))}: $summary",
                        timestampMillis,
                    )
                }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothGatt.STATE_CONNECTED -> {
                                setStatus("Connecte, decouverte des services GATT en cours")
                                if (!gatt.discoverServices()) {
                                    setStatus("Echec de discoverServices(), nouvelle tentative en cours")
                                    disconnectSignal.complete(Unit)
                                }
                            }

                            BluetoothGatt.STATE_DISCONNECTED -> {
                                setStatus("Deconnecte de ${device.name ?: device.address}, reconnexion en attente")
                                disconnectSignal.complete(Unit)
                            }

                            else -> {
                                setStatus("Etat Bluetooth inattendu: $newState")
                            }
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            setStatus("Decouverte des services echouee (code $status)")
                            disconnectSignal.complete(Unit)
                            return
                        }

                        val services = gatt.services.orEmpty()
                        logGattTopology(services)
                        multiLinkController.prepare(services)
                        val serviceSummary = services.joinToString { shortUuid(it.uuid) }
                        setStatus("Services detectes: $serviceSummary")

                        subscriptionQueue.clear()
                        readQueue.clear()
                        services
                            .flatMap(BluetoothGattService::getCharacteristics)
                            .forEach { characteristic ->
                                if (supportsRead(characteristic)) {
                                    readQueue += characteristic
                                }
                                if (supportsNotifications(characteristic)) {
                                    val cccd = characteristic.getDescriptor(CLIENT_CONFIG_UUID) ?: return@forEach
                                    if (gatt.setCharacteristicNotification(characteristic, true)) {
                                        cccd.value = when {
                                            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                                            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                        }
                                        subscriptionQueue += cccd
                                    }
                                }
                            }

                        if (subscriptionQueue.isEmpty()) {
                            if (readQueue.isEmpty()) {
                                setStatus("Aucune lecture initiale, demarrage de la couche MultiLink")
                                maybeStartMultiLink(gatt)
                                return
                            }
                            setStatus("Aucune notification native, lecture initiale des caracteristiques en cours")
                            if (!readNextCharacteristic(gatt, readQueue)) {
                                setStatus("Aucune lecture GATT initiale n'a pu etre demarree")
                            }
                            return
                        }

                        writeNextDescriptor(gatt, subscriptionQueue)
                    }

                    override fun onDescriptorWrite(
                        gatt: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int,
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            configuredNotifications += 1
                        }
                        if (!writeNextDescriptor(gatt, subscriptionQueue)) {
                            if (readQueue.isNotEmpty()) {
                                setStatus(
                                    "Notifications actives sur $configuredNotifications caracteristique(s), lecture initiale en cours",
                                )
                                if (!readNextCharacteristic(gatt, readQueue)) {
                                    setStatus("Notifications actives sur $configuredNotifications caracteristique(s)")
                                    maybeStartMultiLink(gatt)
                                }
                            } else {
                                setStatus("Notifications actives sur $configuredNotifications caracteristique(s)")
                                maybeStartMultiLink(gatt)
                            }
                        }
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        handleCharacteristicChange(gatt, characteristic, characteristic.value ?: ByteArray(0))
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        handleCharacteristicChange(gatt, characteristic, value)
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        val payload = characteristic.value ?: ByteArray(0)
                        handleReadCompletion(gatt, characteristic, payload, status)
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int,
                    ) {
                        handleReadCompletion(gatt, characteristic, value, status)
                    }

                    private fun handleReadCompletion(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        payload: ByteArray,
                        status: Int,
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            completedReads += 1
                            handleCharacteristic(characteristic, payload)
                        } else {
                            Log.w(
                                TAG,
                                "Read failed for ${shortUuid(characteristic.uuid)} on ${shortUuid(characteristic.service.uuid)}: $status",
                            )
                        }

                        if (!readNextCharacteristic(gatt, readQueue)) {
                            val suffix = if (completedReads > 0) {
                                " et $completedReads lecture(s) initiale(s)"
                            } else {
                                ""
                            }
                            setStatus("Notifications actives sur $configuredNotifications caracteristique(s)$suffix")
                            maybeStartMultiLink(gatt)
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        multiLinkController.onCharacteristicWrite(gatt, characteristic.uuid, status)?.let(::setStatus)
                    }

                    private fun handleCharacteristicChange(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        payload: ByteArray,
                    ) {
                        val timestampMillis = System.currentTimeMillis()
                        if (
                            characteristic.service?.uuid == MULTI_LINK_SERVICE_UUID &&
                            multiLinkController.isTransportCharacteristic(characteristic.uuid)
                        ) {
                            appendRawFrame(
                                timestampMillis = timestampMillis,
                                serviceUuid = characteristic.service?.uuid,
                                characteristicUuid = characteristic.uuid,
                                payload = payload,
                                decoderStatus = "Trame transport MultiLink",
                            )
                            val consumed = multiLinkController.handleNotification(
                                gatt = gatt,
                                characteristicUuid = characteristic.uuid,
                                payload = payload,
                                timestampMillis = timestampMillis,
                                onServicePayload = { serviceId, sourceCharacteristicUuid, servicePayload, transportSummary ->
                                    handleMultiLinkPayload(
                                        serviceId = serviceId,
                                        sourceCharacteristicUuid = sourceCharacteristicUuid,
                                        payload = servicePayload,
                                        timestampMillis = timestampMillis,
                                        transportSummary = transportSummary,
                                    )
                                },
                            )
                            if (consumed || payload.isNotEmpty()) {
                                return
                            }
                        }
                        handleCharacteristic(characteristic, payload)
                    }

                    private fun handleCharacteristic(
                        characteristic: BluetoothGattCharacteristic,
                        explicitValue: ByteArray? = null,
                    ) {
                        val timestampMillis = System.currentTimeMillis()
                        val payload = explicitValue ?: characteristic.value ?: ByteArray(0)
                        val serviceUuid = characteristic.service?.uuid
                        val decoded = decoder.decode(
                            serviceUuid = serviceUuid,
                            characteristicUuid = characteristic.uuid,
                            payload = payload,
                            timestampMillis = timestampMillis,
                        )

                        appendRawFrame(
                            timestampMillis = timestampMillis,
                            serviceUuid = serviceUuid,
                            characteristicUuid = characteristic.uuid,
                            payload = payload,
                            decoderStatus = decoded.debugSummary,
                        )

                        decoded.samples.forEach { sample ->
                            latestSamples[sample.type] = sample
                        }

                        val packetSummary = decoded.debugSummary
                            ?: "Trame ${shortUuid(characteristic.uuid)} (${payload.size} octets)"
                        setStatus(
                            "Derniere trame ${statusClock.format(Date(timestampMillis))}: $packetSummary",
                            timestampMillis,
                        )
                    }
                }

                val gatt = connectGatt(device, callback)
                try {
                    disconnectSignal.await()
                } finally {
                    gatt.disconnect()
                    gatt.close()
                }
                delay(1_500)
            }
        }

        awaitClose {
            connectionLoop.cancel()
        }
    }

    private fun connectGatt(
        device: BluetoothDevice,
        callback: BluetoothGattCallback,
    ): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, callback)
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun findBondedGarminDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val bondedDevices = runCatching { adapter.bondedDevices }.getOrDefault(emptySet())
        val candidates = bondedDevices.filter(::looksLikeGarminWatch)
        candidates.forEach { device ->
            Log.d(
                TAG,
                "Candidate Garmin device: ${device.name ?: "unknown"} ${device.address} score=${devicePriority(device)}",
            )
        }
        val chosen = candidates.firstOrNull { devicePriority(it) >= 100 }
            ?: candidates.maxByOrNull(::devicePriority)
        if (chosen != null) {
            Log.d(TAG, "Chosen Garmin device: ${chosen.name ?: "unknown"} ${chosen.address}")
        }
        return chosen
    }

    private fun looksLikeGarminWatch(device: BluetoothDevice): Boolean {
        val name = device.name?.lowercase(Locale.US).orEmpty()
        if (name.isBlank()) {
            return false
        }
        return GARMIN_NAME_HINTS.any { hint -> name.contains(hint) }
    }

    private fun devicePriority(device: BluetoothDevice): Int {
        val name = device.name?.lowercase(Locale.US).orEmpty()
        return when {
            "fenix" in name -> 100
            "epix" in name -> 95
            "enduro" in name -> 90
            "tactix" in name -> 85
            "descent" in name -> 80
            "marq" in name -> 75
            "forerunner" in name -> 70
            "venu" in name -> 65
            "vivoactive" in name -> 60
            "instinct" in name -> 55
            "vivomove" in name -> 50
            "garmin" in name -> 40
            else -> 10
        }
    }

    private fun supportsNotifications(characteristic: BluetoothGattCharacteristic): Boolean {
        val properties = characteristic.properties
        val notify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val indicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        return notify || indicate
    }

    private fun supportsRead(characteristic: BluetoothGattCharacteristic): Boolean {
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
    }

    private fun writeNextDescriptor(
        gatt: BluetoothGatt,
        subscriptionQueue: ArrayDeque<BluetoothGattDescriptor>,
    ): Boolean {
        val descriptor = subscriptionQueue.removeFirstOrNull() ?: return false
        @Suppress("DEPRECATION")
        return gatt.writeDescriptor(descriptor)
    }

    private fun readNextCharacteristic(
        gatt: BluetoothGatt,
        readQueue: ArrayDeque<BluetoothGattCharacteristic>,
    ): Boolean {
        while (true) {
            val characteristic = readQueue.removeFirstOrNull() ?: return false
            @Suppress("DEPRECATION")
            if (gatt.readCharacteristic(characteristic)) {
                return true
            }
            Log.w(
                TAG,
                "Unable to queue read on ${shortUuid(characteristic.uuid)} (${propertySummary(characteristic)})",
            )
        }
    }

    private fun logGattTopology(services: List<BluetoothGattService>) {
        services.forEach { service ->
            Log.d(TAG, "GATT service ${service.uuid}")
            service.characteristics.orEmpty().forEach { characteristic ->
                Log.d(
                    TAG,
                    "  characteristic ${characteristic.uuid} properties=${propertySummary(characteristic)}",
                )
            }
        }
    }

    private fun propertySummary(characteristic: BluetoothGattCharacteristic): String {
        val properties = buildList {
            val flags = characteristic.properties
            if (flags and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
            if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
            if (flags and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (flags and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        }
        return properties.joinToString(separator = "|").ifBlank { "NONE" }
    }

    private fun appendRawFrame(
        timestampMillis: Long,
        serviceUuid: UUID?,
        characteristicUuid: UUID,
        payload: ByteArray,
        decoderStatus: String?,
    ) {
        val line = buildString {
            append(statusClock.format(Date(timestampMillis)))
            append(" service=")
            append(serviceUuid?.toString() ?: "unknown")
            append(" characteristic=")
            append(characteristicUuid)
            append(" size=")
            append(payload.size)
            append(" hex=")
            append(payload.toHexString())
            if (!decoderStatus.isNullOrBlank()) {
                append(" decoded=")
                append(decoderStatus)
            }
        }
        runCatching {
            rawFrameLog.appendText(line + "\n")
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist raw frame", error)
        }
        Log.d(TAG, line)
    }

    private fun shortUuid(uuid: UUID): String {
        return uuid.toString().substringBefore('-')
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    companion object {
        private const val TAG = "PulseFitBle"

        private val CLIENT_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val MULTI_LINK_SERVICE_UUID =
            UUID.fromString("6a4e2800-667b-11e3-949a-0800200c9a66")

        private val GARMIN_NAME_HINTS = listOf(
            "garmin",
            "forerunner",
            "fenix",
            "epix",
            "venu",
            "vivomove",
            "vivoactive",
            "instinct",
            "descent",
            "enduro",
            "marq",
            "tactix",
            "approach",
            "lily",
        )
    }
}

private class GarminMultiLinkController(
    private val onStatus: (String) -> Unit,
) {
    private val candidatePairs = mutableListOf<MultiLinkPair>()
    private val serviceHandles = LinkedHashMap<Int, Int>()
    private val requestedServices = ArrayDeque(
        listOf(
            REAL_TIME_HR_SERVICE_ID,
            REAL_TIME_STRESS_SERVICE_ID,
            REAL_TIME_SPO2_SERVICE_ID,
            REAL_TIME_RESPIRATION_SERVICE_ID,
            REAL_TIME_ACTIVE_TIME_SERVICE_ID,
        ),
    )

    private var currentPairIndex = -1
    private var activePair: MultiLinkPair? = null
    private var phase = MultiLinkPhase.IDLE
    private var pendingServiceId: Int? = null
    private var writeInFlight = false
    private var sessionId = 0L
    private var sessionCounter = 1L
    private var started = false

    fun prepare(services: List<BluetoothGattService>) {
        candidatePairs.clear()
        val multiLinkService = services.firstOrNull { it.uuid == MULTI_LINK_SERVICE_UUID } ?: return
        val charsByUuid = multiLinkService.characteristics.orEmpty().associateBy { it.uuid }
        multiLinkService.characteristics.orEmpty()
            .filter { characteristic ->
                characteristic.uuid.toString().contains("6a4e281", ignoreCase = true) &&
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }
            .sortedBy { it.uuid.toString() }
            .forEach { readCharacteristic ->
                val writeUuid = pairedWriteUuid(readCharacteristic.uuid) ?: return@forEach
                val writeCharacteristic = charsByUuid[writeUuid] ?: return@forEach
                candidatePairs += MultiLinkPair(readCharacteristic, writeCharacteristic)
            }
    }

    fun startIfReady(gatt: BluetoothGatt): String? {
        if (started || candidatePairs.isEmpty()) {
            return null
        }
        started = true
        requestedServices.clear()
        requestedServices += listOf(
            REAL_TIME_HR_SERVICE_ID,
            REAL_TIME_STRESS_SERVICE_ID,
            REAL_TIME_SPO2_SERVICE_ID,
            REAL_TIME_RESPIRATION_SERVICE_ID,
            REAL_TIME_ACTIVE_TIME_SERVICE_ID,
        )
        serviceHandles.clear()
        return tryCandidate(gatt, 0)
    }

    fun handles(characteristicUuid: UUID): Boolean {
        return activePair?.readCharacteristic?.uuid == characteristicUuid
    }

    fun isTransportCharacteristic(characteristicUuid: UUID): Boolean {
        return candidatePairs.any { pair -> pair.readCharacteristic.uuid == characteristicUuid }
    }

    fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristicUuid: UUID,
        status: Int,
    ): String? {
        val pair = activePair ?: return null
        if (pair.writeCharacteristic.uuid != characteristicUuid) {
            return null
        }
        writeInFlight = false
        if (status == BluetoothGatt.GATT_SUCCESS) {
            return null
        }
        onStatus("Ecriture MultiLink echouee sur ${pair.label} (code $status), essai du canal suivant")
        return tryCandidate(gatt, currentPairIndex + 1)
    }

    fun handleNotification(
        gatt: BluetoothGatt,
        characteristicUuid: UUID,
        payload: ByteArray,
        timestampMillis: Long,
        onServicePayload: (serviceId: Int, sourceCharacteristicUuid: UUID, payload: ByteArray, summary: String) -> Unit,
    ): Boolean {
        val pair = activePair ?: return false
        if (pair.readCharacteristic.uuid != characteristicUuid || payload.isEmpty()) {
            return false
        }

        if (payload[0].toInt() == 0) {
            handleCommandResponse(gatt, pair, payload, timestampMillis)
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
        gatt: BluetoothGatt,
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
                if (!writeRegisterCommand(gatt, REGISTRATION_SERVICE_ID)) {
                    tryCandidate(gatt, currentPairIndex + 1)
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
                            openNextRealtimeService(gatt)
                            return
                        }

                        serviceHandles[handle] = responseServiceId
                        onStatus(
                            "MultiLink ${pair.label}: ${serviceLabel(responseServiceId)} ouvert sur handle=$handle flags=$flags",
                        )
                        phase = MultiLinkPhase.OPENING_REALTIME
                        openNextRealtimeService(gatt)
                    }

                    REGISTER_STATUS_ALREADY_IN_USE -> {
                        if (!buffer.hasRemaining()) {
                            onStatus("MultiLink ${pair.label}: canal deja utilise sans redirection exploitable")
                            tryCandidate(gatt, currentPairIndex + 1)
                            return
                        }
                        val redirectedReadUuid = redirectedUuid(buffer.short.toInt() and 0xffff)
                        val redirectedWriteUuid = pairedWriteUuid(redirectedReadUuid)
                        val redirectedIndex = candidatePairs.indexOfFirst { candidate ->
                            candidate.readCharacteristic.uuid == redirectedReadUuid &&
                                candidate.writeCharacteristic.uuid == redirectedWriteUuid
                        }
                        if (redirectedIndex >= 0) {
                            onStatus("MultiLink ${pair.label}: redirige vers ${candidatePairs[redirectedIndex].label}")
                            tryCandidate(gatt, redirectedIndex)
                        } else {
                            onStatus("MultiLink ${pair.label}: redirection ${redirectedReadUuid} absente, canal suivant")
                            tryCandidate(gatt, currentPairIndex + 1)
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
                            tryCandidate(gatt, currentPairIndex + 1)
                        } else {
                            phase = MultiLinkPhase.OPENING_REALTIME
                            openNextRealtimeService(gatt)
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
                    "MultiLink ${pair.label}: commande $commandType recue pour service $responseServiceId (${timestampMillis})",
                )
            }
        }
    }

    private fun openNextRealtimeService(gatt: BluetoothGatt) {
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

        if (!writeRegisterCommand(gatt, nextServiceId)) {
            tryCandidate(gatt, currentPairIndex + 1)
        }
    }

    private fun writeRegisterCommand(gatt: BluetoothGatt, serviceId: Int): Boolean {
        pendingServiceId = serviceId
        val frame = ByteBuffer.allocate(13)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .put(0)
            .putLong(sessionId)
            .putShort(serviceId.toShort())
            .put(0)
            .array()
        return writeCommand(gatt, frame)
    }

    private fun writeCloseAll(gatt: BluetoothGatt): Boolean {
        val frame = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .put(CLOSE_ALL_REQUEST.toByte())
            .putLong(sessionId)
            .putShort(0)
            .array()
        return writeCommand(gatt, frame)
    }

    private fun writeCommand(gatt: BluetoothGatt, payload: ByteArray): Boolean {
        val pair = activePair ?: return false
        if (writeInFlight) {
            return false
        }
        @Suppress("DEPRECATION")
        pair.writeCharacteristic.value = payload
        @Suppress("DEPRECATION")
        val queued = gatt.writeCharacteristic(pair.writeCharacteristic)
        writeInFlight = queued
        return queued
    }

    private fun tryCandidate(gatt: BluetoothGatt, pairIndex: Int): String? {
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

        return if (writeCloseAll(gatt)) {
            "Negociation MultiLink sur ${pair.label}, fermeture des handles existants"
        } else {
            tryCandidate(gatt, pairIndex + 1)
        }
    }

    private fun nextSessionId(): Long {
        val now = System.currentTimeMillis()
        return now xor (sessionCounter++)
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
        val readCharacteristic: BluetoothGattCharacteristic,
        val writeCharacteristic: BluetoothGattCharacteristic,
    ) {
        val label: String
            get() = "${readCharacteristic.uuid.toString().substring(4, 8)}/${writeCharacteristic.uuid.toString().substring(4, 8)}"
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
    }
}
