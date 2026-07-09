package com.muba.watchlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Starts bonding (pairing) with a device and reports bond state changes as they arrive
 * from the system, so the UI can update without needing another scan.
 */
class BleBondManager(
    private val onBondStateChanged: (address: String, label: String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        if (receiver != null) return
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.bluetoothDeviceExtra() ?: return
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val label = bondStateLabel(bondState)
                onLog("Bond state changed for ${device.address}: $label")
                onBondStateChanged(device.address, label)
            }
        }
        ContextCompat.registerReceiver(
            context,
            newReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver = newReceiver
    }

    fun stop(context: Context) {
        val currentReceiver = receiver ?: return
        try {
            context.unregisterReceiver(currentReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    fun startBonding(device: BluetoothDevice) {
        onLog("Starting bonding with ${device.address}")
        try {
            val started = device.createBond()
            if (!started) onLog("createBond() returned false for ${device.address}")
        } catch (e: SecurityException) {
            onLog("Missing permission to bond with ${device.address}")
        }
    }
}
