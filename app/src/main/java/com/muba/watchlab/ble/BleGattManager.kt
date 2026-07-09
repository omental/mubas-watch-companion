package com.muba.watchlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

enum class BleConnectionState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

data class BleCharacteristicInfo(
    val uuid: UUID,
    val properties: List<String>
)

data class BleServiceInfo(
    val uuid: UUID,
    val isStandard: Boolean,
    val characteristics: List<BleCharacteristicInfo>
)

private const val SERVICE_DISCOVERY_DELAY_MS = 500L
private const val REQUESTED_MTU = 247

/** Human-readable meaning for the GATT status codes we see most often while testing. */
private fun statusDescription(status: Int): String = when (status) {
    BluetoothGatt.GATT_SUCCESS -> "success"
    133 -> "GATT_ERROR (generic failure, often out of range or a stack issue)"
    8 -> "timeout / connection lost"
    19 -> "remote device terminated the connection"
    22 -> "connection terminated locally (often means this address is Classic-only, not BLE)"
    62 -> "connection failed to establish"
    else -> "unrecognized status"
}

private fun connectionStateName(newState: Int): String = when (newState) {
    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
    else -> "UNKNOWN($newState)"
}

private fun characteristicProperties(characteristic: BluetoothGattCharacteristic): List<String> {
    val flags = characteristic.properties
    val props = mutableListOf<String>()
    if (flags and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
    if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
    if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WRITE_NO_RESPONSE")
    if (flags and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
    if (flags and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("INDICATE")
    return props
}

/**
 * Manages a single BluetoothGatt connection: connect, discover services, and read the
 * Battery Level characteristic if the device exposes the standard Battery Service.
 */
class BleGattManager(
    private val onStateChange: (BleConnectionState, status: Int) -> Unit,
    private val onServicesDiscovered: (List<BleServiceInfo>) -> Unit,
    private val onBatteryLevel: (Int) -> Unit,
    private val onHeartRateAvailable: (Boolean) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var gatt: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            onLog(
                "onConnectionStateChange: status=$status (${statusDescription(status)}), " +
                    "newState=${connectionStateName(newState)}"
            )
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStateChange(BleConnectionState.CONNECTED, status)
                    // Some devices behave badly if services are requested immediately
                    // after connecting, so give the link a moment to settle first.
                    mainHandler.postDelayed({ requestMtuThenDiscoverServices(g) }, SERVICE_DISCOVERY_DELAY_MS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val failed = status != BluetoothGatt.GATT_SUCCESS
                    onStateChange(if (failed) BleConnectionState.FAILED else BleConnectionState.DISCONNECTED, status)
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            onLog("onMtuChanged: mtu=$mtu, status=$status (${statusDescription(status)})")
            // Discover services regardless of whether the MTU request succeeded.
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            onLog("onServicesDiscovered: status=$status (${statusDescription(status)})")
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val services = g.services.map { service ->
                BleServiceInfo(
                    uuid = service.uuid,
                    isStandard = isStandardUuid(service.uuid),
                    characteristics = service.characteristics.map { characteristic ->
                        BleCharacteristicInfo(characteristic.uuid, characteristicProperties(characteristic))
                    }
                )
            }
            onLog("Services discovered, count: ${services.size}")
            onServicesDiscovered(services)

            if (services.any { it.uuid == HEART_RATE_SERVICE_UUID }) {
                onHeartRateAvailable(true)
                onLog("Heart Rate Service available")
            }

            val batteryCharacteristic = g.getService(BATTERY_SERVICE_UUID)
                ?.getCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)
            if (batteryCharacteristic != null) {
                readBatteryLevel(g, batteryCharacteristic)
            }
        }

        // The 3-argument overload is deprecated in favor of one that also passes the
        // value, but the framework still calls this one on every supported API level.
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != BATTERY_LEVEL_CHARACTERISTIC_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Battery read failed (status $status: ${statusDescription(status)})")
                return
            }
            val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
            if (level == null) {
                onLog("Battery read failed: empty value")
            } else {
                onLog("Battery read success: $level%")
                onBatteryLevel(level)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestMtuThenDiscoverServices(g: BluetoothGatt) {
        if (gatt !== g) return // Disconnected or replaced while we were waiting.
        val requestSent = try {
            g.requestMtu(REQUESTED_MTU)
        } catch (e: SecurityException) {
            false
        }
        onLog("Requested MTU $REQUESTED_MTU: ${if (requestSent) "sent" else "failed to send"}")
        if (!requestSent) {
            // onMtuChanged will never arrive, so discover services right away.
            g.discoverServices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        disconnect()
        onLog("Connecting to ${device.address}")
        onStateChange(BleConnectionState.CONNECTING, BluetoothGatt.GATT_SUCCESS)
        // minSdk is 24, so the TRANSPORT_LE overload (added in API 23) is always available.
        gatt = device.connectGatt(
            context.applicationContext,
            false,
            callback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        // Do not close() here: closing immediately would suppress the async
        // STATE_DISCONNECTED callback, which is what reports the disconnect and
        // performs the actual close().
        gatt?.disconnect()
    }
}
