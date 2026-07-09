package com.muba.watchlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat

private const val SDP_TIMEOUT_MS = 15_000L

private fun Intent.parcelUuidArrayExtra(): Array<ParcelUuid>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.mapNotNull { it as? ParcelUuid }?.toTypedArray()
    }

private fun Array<ParcelUuid>.toUuidInfos(): List<ClassicUuidInfo> =
    map { ClassicUuidInfo(it.uuid, classicProfileName(it.uuid)) }

/**
 * Looks up classic Bluetooth (SDP) profile UUIDs for a paired device via
 * BluetoothDevice.fetchUuidsWithSdp(). This is entirely separate from BLE/GATT (it
 * never touches BluetoothGatt or connectGatt()) and from RFCOMM/SPP sessions, which
 * [SppManager] handles once SPP is confirmed present.
 */
class ClassicInspector(
    private val onUuidsUpdated: (List<ClassicUuidInfo>) -> Unit,
    private val onFetchFinished: () -> Unit,
    private val onLog: (String) -> Unit
) {
    private var receiver: BroadcastReceiver? = null
    private var targetAddress: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    fun start(context: Context) {
        if (receiver != null) return
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_UUID) return
                val device = intent.bluetoothDeviceExtra() ?: return
                // Ignore results for any device other than the one we're currently inspecting.
                if (device.address != targetAddress) return
                cancelTimeout()
                onLog("ACTION_UUID received for ${device.address}")

                val parcelUuids = intent.parcelUuidArrayExtra()
                if (parcelUuids.isNullOrEmpty()) {
                    onLog("No UUIDs returned from SDP")
                    onUuidsUpdated(emptyList())
                } else {
                    val uuidInfos = parcelUuids.toUuidInfos()
                    onLog("UUID count: ${uuidInfos.size}")
                    uuidInfos.forEach { info ->
                        onLog("UUID: ${info.uuid}" + (info.friendlyName?.let { " ($it)" } ?: ""))
                    }
                    onUuidsUpdated(uuidInfos)
                }
                onFetchFinished()
            }
        }
        ContextCompat.registerReceiver(
            context,
            newReceiver,
            IntentFilter(BluetoothDevice.ACTION_UUID),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver = newReceiver
    }

    fun stop(context: Context) {
        cancelTimeout()
        val currentReceiver = receiver ?: return
        try {
            context.unregisterReceiver(currentReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        receiver = null
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    /** Starts (or restarts) a Classic SDP UUID inspection. Never touches BLE/GATT. */
    @SuppressLint("MissingPermission")
    fun inspect(context: Context, device: BluetoothDevice) {
        onLog("Inspect Classic tapped for ${device.address}")
        start(context)
        onLog("ACTION_UUID receiver registered")

        targetAddress = device.address
        cancelTimeout()

        // Show whatever the system already has cached, in case ACTION_UUID never arrives.
        val cachedUuids = try {
            device.uuids
        } catch (e: SecurityException) {
            null
        }
        if (!cachedUuids.isNullOrEmpty()) {
            val cachedInfos = cachedUuids.toUuidInfos()
            onLog("Showing ${cachedInfos.size} cached UUID(s) while SDP fetch runs")
            onUuidsUpdated(cachedInfos)
        }

        val started = try {
            device.fetchUuidsWithSdp()
        } catch (e: SecurityException) {
            onLog("Missing permission to fetch UUIDs for ${device.address}")
            false
        }
        onLog("fetchUuidsWithSdp() returned $started for ${device.address}")

        if (!started) {
            // No broadcast will ever arrive for this attempt, so we're done.
            onFetchFinished()
            return
        }

        val runnable = Runnable {
            if (targetAddress != device.address) return@Runnable
            onLog("SDP UUID fetch timed out")
            if (cachedUuids.isNullOrEmpty()) {
                onLog("No UUIDs returned")
                onUuidsUpdated(emptyList())
            }
            onFetchFinished()
        }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, SDP_TIMEOUT_MS)
    }
}
