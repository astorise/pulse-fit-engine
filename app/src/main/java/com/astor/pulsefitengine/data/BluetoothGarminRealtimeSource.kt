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
                var commandSink: GarminMultiLinkCommandSink? = null
                val multiLinkController = GarminMultiLinkController(
                    onStatus = { message -> setStatus(message) },
                )

                fun maybeStartMultiLink() {
                    val sink = commandSink ?: return
                    multiLinkController.startIfReady(sink)?.let(::setStatus)
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
                        commandSink = BluetoothGattCommandSink(gatt, services)
                        multiLinkController.prepare(services.toGarminGattServices())
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
                                maybeStartMultiLink()
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
                                    maybeStartMultiLink()
                                }
                            } else {
                                setStatus("Notifications actives sur $configuredNotifications caracteristique(s)")
                                maybeStartMultiLink()
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
                            maybeStartMultiLink()
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        val sink = commandSink ?: return
                        multiLinkController.onCharacteristicWrite(sink, characteristic.uuid, status)?.let(::setStatus)
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
                            val sink = commandSink
                            appendRawFrame(
                                timestampMillis = timestampMillis,
                                serviceUuid = characteristic.service?.uuid,
                                characteristicUuid = characteristic.uuid,
                                payload = payload,
                                decoderStatus = "Trame transport MultiLink",
                            )
                            val consumed = sink?.let {
                                multiLinkController.handleNotification(
                                    commandSink = it,
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
                            } ?: false
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

    private fun List<BluetoothGattService>.toGarminGattServices(): List<GarminGattServiceInfo> {
        return map { service ->
            GarminGattServiceInfo(
                uuid = service.uuid,
                characteristics = service.characteristics.orEmpty().map { characteristic ->
                    GarminGattCharacteristicInfo(
                        uuid = characteristic.uuid,
                        properties = characteristic.properties,
                    )
                },
            )
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

private class BluetoothGattCommandSink(
    private val gatt: BluetoothGatt,
    services: List<BluetoothGattService>,
) : GarminMultiLinkCommandSink {
    private val characteristicsByUuid = services
        .flatMap { service -> service.characteristics.orEmpty() }
        .associateBy { characteristic -> characteristic.uuid }

    override fun write(characteristicUuid: UUID, payload: ByteArray): Boolean {
        val characteristic = characteristicsByUuid[characteristicUuid] ?: return false
        @Suppress("DEPRECATION")
        characteristic.value = payload
        @Suppress("DEPRECATION")
        return gatt.writeCharacteristic(characteristic)
    }
}
