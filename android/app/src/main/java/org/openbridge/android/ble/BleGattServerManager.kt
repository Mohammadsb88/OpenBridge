package org.openbridge.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import org.json.JSONObject
import org.openbridge.android.data.BleConnectionState
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.protocol.BridgeConstants
import org.openbridge.android.protocol.BridgeCrypto
import org.openbridge.android.protocol.BridgeModels
import org.openbridge.android.protocol.FrameCodec
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class BleGattServerManager(
    private val context: Context,
    private val onEncryptedCommand: (JSONObject) -> Unit,
    private val onDeviceConnected: () -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var connectedDevice: BluetoothDevice? = null
    @Volatile
    private var notificationsEnabled = false
    @Volatile
    private var currentMtu = 23

    private val streamDecoder = FrameCodec.StreamDecoder()
    private val outgoingQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isSending = AtomicBoolean(false)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            BridgeStore.log("BLE advertising started (UUID=${BridgeConstants.SERVICE_UUID})")
            if (connectedDevice == null) {
                BridgeStore.setConnectionState(BleConnectionState.ADVERTISING)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            BridgeStore.log("BLE advertising failed with code=$errorCode")
            BridgeStore.setConnectionState(BleConnectionState.STOPPED)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                streamDecoder.reset()
                outgoingQueue.clear()
                isSending.set(false)
                BridgeStore.setConnectionState(BleConnectionState.CONNECTED)
                BridgeStore.log("BLE connected: ${device.address}")
                onDeviceConnected()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    connectedDevice = null
                    notificationsEnabled = false
                    outgoingQueue.clear()
                    isSending.set(false)
                    streamDecoder.reset()
                    BridgeStore.setConnectionState(BleConnectionState.ADVERTISING)
                    BridgeStore.log("BLE disconnected: ${device.address}")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            currentMtu = mtu.coerceIn(23, 512)
            BridgeStore.log("BLE MTU negotiated: $currentMtu")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == BridgeConstants.CCCD_UUID) {
                notificationsEnabled = value != null &&
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                BridgeStore.log("iOS TX notifications enabled=$notificationsEnabled")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                if (notificationsEnabled) {
                    onDeviceConnected()
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid == BridgeConstants.RX_CHAR_UUID && value != null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                val frames = streamDecoder.append(value)
                for (frame in frames) {
                    handleIncomingFrame(frame)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            isSending.set(false)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                BridgeStore.log("BLE notification failed status=$status")
            }
            drainOutgoingQueue()
        }
    }

    fun start(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            BridgeStore.log("Bluetooth adapter disabled or unavailable")
            BridgeStore.setConnectionState(BleConnectionState.STOPPED)
            return false
        }
        try {
            val server = bluetoothManager.openGattServer(context, gattCallback)
            if (server == null) {
                BridgeStore.log("Failed to open GATT server")
                return false
            }
            gattServer = server

            val service = BluetoothGattService(
                BridgeConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val rxChar = BluetoothGattCharacteristic(
                BridgeConstants.RX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val txChar = BluetoothGattCharacteristic(
                BridgeConstants.TX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            val cccd = BluetoothGattDescriptor(
                BridgeConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            txChar.addDescriptor(cccd)
            service.addCharacteristic(rxChar)
            service.addCharacteristic(txChar)
            txCharacteristic = txChar
            server.addService(service)

            val bleAdvertiser = adapter.bluetoothLeAdvertiser
            if (bleAdvertiser == null) {
                BridgeStore.log("BLE Peripheral advertising not supported on this device")
                return false
            }
            advertiser = bleAdvertiser

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(BridgeConstants.SERVICE_UUID))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            bleAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            return true
        } catch (e: SecurityException) {
            BridgeStore.log("Missing Bluetooth permission: ${e.message}")
            return false
        } catch (e: Exception) {
            BridgeStore.log("BLE start error: ${e.message}")
            return false
        }
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        advertiser = null
        gattServer = null
        txCharacteristic = null
        connectedDevice = null
        notificationsEnabled = false
        outgoingQueue.clear()
        isSending.set(false)
        BridgeStore.setConnectionState(BleConnectionState.STOPPED)
        BridgeStore.log("BLE GATT server stopped")
    }

    fun isConnected(): Boolean = connectedDevice != null && notificationsEnabled

    fun sendEncryptedJson(plainJson: String): Boolean {
        val key = BridgeStore.getSharedKey()
        if (key == null) {
            BridgeStore.log("Cannot send encrypted packet: not paired yet")
            return false
        }
        return try {
            val (nonce, cipher) = BridgeCrypto.encrypt(
                key,
                plainJson.toByteArray(StandardCharsets.UTF_8)
            )
            val envelope = BridgeModels.encryptedEnvelope(
                BridgeCrypto.toBase64(nonce),
                BridgeCrypto.toBase64(cipher)
            )
            enqueueFrame(envelope.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            BridgeStore.log("Encrypt error: ${e.message}")
            false
        }
    }

    private fun enqueueFrame(payload: ByteArray): Boolean {
        if (connectedDevice == null || !notificationsEnabled) {
            return false
        }
        val frame = FrameCodec.encode(payload)
        val chunkMax = (currentMtu - 3).coerceAtLeast(20)
        for (chunk in FrameCodec.chunk(frame, chunkMax)) {
            outgoingQueue.add(chunk)
        }
        drainOutgoingQueue()
        return true
    }

    private fun drainOutgoingQueue() {
        val device = connectedDevice ?: return
        val server = gattServer ?: return
        val txChar = txCharacteristic ?: return
        if (!notificationsEnabled) return
        if (!isSending.compareAndSet(false, true)) return

        val next = outgoingQueue.poll()
        if (next == null) {
            isSending.set(false)
            return
        }

        try {
            val dispatched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, txChar, false, next) ==
                    BluetoothStatusCodesCompat.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                txChar.value = next
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, txChar, false)
            }
            if (!dispatched) {
                isSending.set(false)
                BridgeStore.log("Failed to dispatch BLE notification chunk")
            }
        } catch (e: Exception) {
            isSending.set(false)
            BridgeStore.log("Notify exception: ${e.message}")
        }
    }

    private fun handleIncomingFrame(frameBytes: ByteArray) {
        val raw = String(frameBytes, StandardCharsets.UTF_8)
        try {
            val json = JSONObject(raw)
            if (json.optInt("e", 0) == 1) {
                val key = BridgeStore.getSharedKey()
                if (key == null) {
                    BridgeStore.log("Received encrypted frame but no shared key")
                    return
                }
                val nonce = BridgeCrypto.fromBase64(json.getString("n"))
                val cipher = BridgeCrypto.fromBase64(json.getString("c"))
                val plain = String(BridgeCrypto.decrypt(key, nonce, cipher), StandardCharsets.UTF_8)
                val inner = JSONObject(plain)
                handleDecryptedMessage(inner)
            } else if (json.optString("type") == "PAIR_HELLO") {
                handlePairHello(json)
            } else {
                BridgeStore.log("Ignored unknown unencrypted frame: ${json.optString("type")}")
            }
        } catch (e: Exception) {
            BridgeStore.log("Frame parse/decrypt error: ${e.message}")
        }
    }

    private fun handlePairHello(json: JSONObject) {
        val peerId = json.optString("deviceId", "ios-peer")
        val peerName = json.optString("deviceName", "iPhone")
        val peerPubB64 = json.optString("pub", "")
        val priv = BridgeStore.getPrivateKey()
        if (priv == null || peerPubB64.isEmpty()) {
            BridgeStore.log("PAIR_HELLO failed: missing keys")
            return
        }
        try {
            val peerPubBytes = BridgeCrypto.fromBase64(peerPubB64)
            val sharedKey = BridgeCrypto.deriveSessionKey(priv, peerPubBytes)
            BridgeStore.savePairing(peerId, peerName, sharedKey)
            sendEncryptedJson(BridgeModels.pairAckJson(BridgeStore.getDeviceName()))
            onDeviceConnected()
        } catch (e: Exception) {
            BridgeStore.log("PAIR_HELLO crypto error: ${e.message}")
        }
    }

    private fun handleDecryptedMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "PING" -> sendEncryptedJson(BridgeModels.pongJson())
            else -> onEncryptedCommand(msg)
        }
    }

    private object BluetoothStatusCodesCompat {
        const val SUCCESS = 0
    }
}
