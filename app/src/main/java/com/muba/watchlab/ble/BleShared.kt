package com.muba.watchlab.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** On Android 12+, reading device name/bondState/bondedDevices or creating bonds needs BLUETOOTH_CONNECT. */
fun hasConnectPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
}

fun bondStateLabel(bondState: Int): String = when (bondState) {
    BluetoothDevice.BOND_BONDING -> "Bonding"
    BluetoothDevice.BOND_BONDED -> "Bonded"
    BluetoothDevice.BOND_NONE -> "Not bonded"
    else -> "Unknown"
}

/** Reads the BluetoothDevice extra from a Bluetooth broadcast, the right way per API level. */
fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
