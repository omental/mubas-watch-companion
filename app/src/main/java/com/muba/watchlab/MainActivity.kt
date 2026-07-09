package com.muba.watchlab

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.muba.watchlab.ble.BleBondManager
import com.muba.watchlab.ble.BleCharacteristicInfo
import com.muba.watchlab.ble.BleConnectionState
import com.muba.watchlab.ble.BleDevice
import com.muba.watchlab.ble.BleGattManager
import com.muba.watchlab.ble.BleScanner
import com.muba.watchlab.ble.BleServiceInfo
import com.muba.watchlab.ble.ClassicInspector
import com.muba.watchlab.ble.ClassicUuidInfo
import com.muba.watchlab.ble.JAVA_STREAM_HEADER
import com.muba.watchlab.ble.PairedDevice
import com.muba.watchlab.ble.SPP_UUID
import com.muba.watchlab.ble.SppManager
import com.muba.watchlab.ble.SppState
import com.muba.watchlab.ble.bytesToHex
import com.muba.watchlab.ble.isJavaStreamHeader
import com.muba.watchlab.ble.bytesToPrintableAscii
import com.muba.watchlab.ble.isAllPrintable
import com.muba.watchlab.ble.isBleConnectable
import com.muba.watchlab.ble.looksLikeWatchName
import com.muba.watchlab.ble.parseHexBytes
import com.muba.watchlab.ble.readPairedDevices
import com.muba.watchlab.ble.standardServiceName
import com.muba.watchlab.ble.toBleDevicePlaceholder
import com.muba.watchlab.ui.theme.MubaWatchLabTheme
import com.wiitetech.WiiWatchPro.bluetoothutil.MessageBean
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MubaWatchLabTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BleScannerScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/** Bluetooth permissions this app needs, which differ by Android version. */
private fun requiredBluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun hasPermissions(context: Context, permissions: Array<String>): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private const val MAX_LOG_LINES = 200
private const val MAX_SPP_HISTORY = 30
private const val MAX_SPP_FULL_LOG = 5000
private const val MAX_JAVA_EVENT_LOG = 500
private const val STRONG_SIGNAL_THRESHOLD_DBM = -75
private const val SPP_DURATION_TICK_MS = 1000L
private const val KC10_NAME = "KC10"
private const val KC10_ADDRESS = "2A:F1:A0:F3:13:03"
private const val APP_TITLE = "Muba’s Watch Companion"

private val PremiumBackground = Color(0xFF050711)
private val PremiumCard = Color(0xE6131A2A)
private val PremiumCardSoft = Color(0xCC111827)
private val PremiumBorder = Color(0xFF26324A)
private val PremiumText = Color(0xFFF8FAFC)
private val PremiumMuted = Color(0xFF94A3B8)
private val PremiumPurple = Color(0xFF8B5CF6)
private val PremiumCyan = Color(0xFF22D3EE)
private val PremiumGreen = Color(0xFF22C55E)
private val PremiumOrange = Color(0xFFFB923C)
private val PremiumRed = Color(0xFFEF4444)
private val PremiumBlue = Color(0xFF60A5FA)

// Only sent when the user explicitly taps "Send Test Bytes" AND has acknowledged the
// manual-send warning - never sent automatically on connect.
private val SPP_TEST_PROBES = listOf(
    byteArrayOf(0x0D),
    byteArrayOf(0x0A),
    "AT\r\n".toByteArray(Charsets.US_ASCII)
)

// Not a data class: ByteArray breaks generated equals()/hashCode(), which we never need here.
private class HandshakePreset(val label: String, val bytes: ByteArray)

private val HANDSHAKE_PRESETS = listOf(
    HandshakePreset("Send same packet (AC ED 00 05)", JAVA_STREAM_HEADER),
    HandshakePreset("Send header only (AC ED)", byteArrayOf(0xAC.toByte(), 0xED.toByte())),
    HandshakePreset("Send possible ACK 1 (AC ED 00 00)", byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0x00, 0x00)),
    HandshakePreset("Send possible ACK 2 (AC ED 00 01)", byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0x00, 0x01)),
    HandshakePreset("Send possible ACK 3 (AC ED 00 06)", byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0x00, 0x06))
)

private const val DISCONNECT_AFTER_SEND_DELAY_MS = 800L

// writeUTF() presets for probing what commands the watch's ObjectInputStream responds to.
private val JAVA_UTF_PRESETS = listOf(
    "ping", "hello", "time", "battery", "getBattery", "getTime",
    "sync", "steps", "heart", "health", "version", "device", "info"
)

// MessageBean presets reconstructed from the WiiWatch2 APK - simple read/toggle commands only.
// order/maxValue/currentValue default to -1 and str/identifier/bytes are left unset unless noted.
private class MessageBeanPreset(val label: String, val cmd: String, val trueFalse: Boolean = false, val order: Int = -1)

private val SAFE_CONTROL_PRESETS = listOf(
    MessageBeanPreset("Find watch ON", cmd = "Find the target", trueFalse = true),
    MessageBeanPreset("Find watch OFF", cmd = "Find the target", trueFalse = false),
    MessageBeanPreset("Refresh Battery", cmd = "Battery"),
    MessageBeanPreset("Start Heart Rate", cmd = "Heart-rate", trueFalse = true),
    MessageBeanPreset("Stop Heart Rate", cmd = "Heart-rate", trueFalse = false),
    MessageBeanPreset("Raise to Wake ON", cmd = "Raise to wake", trueFalse = true),
    MessageBeanPreset("Raise to Wake OFF", cmd = "Raise to wake", trueFalse = false)
)

private val EXPERIMENTAL_CONTROL_PRESETS = listOf(
    MessageBeanPreset("Ambient clock ON", cmd = "Ambient clock", trueFalse = true),
    MessageBeanPreset("Ambient clock OFF", cmd = "Ambient clock", trueFalse = false),
    MessageBeanPreset("Watch remind way ON", cmd = "Watch remind way", trueFalse = true),
    MessageBeanPreset("Watch remind way OFF", cmd = "Watch remind way", trueFalse = false),
    MessageBeanPreset("Sports Mode ON", cmd = "Sports mode", trueFalse = true),
    MessageBeanPreset("Sports Mode OFF", cmd = "Sports mode", trueFalse = false),
    MessageBeanPreset("Bluetooth Hands-free ON", cmd = "Bluetooth Hands-free", trueFalse = true),
    MessageBeanPreset("Bluetooth Hands-free OFF", cmd = "Bluetooth Hands-free", trueFalse = false)
)

private const val WIIWATCH2_SAFETY_WARNING = "These commands come from the original WiiWatch2 APK. " +
    "Use only simple read/toggle commands first. Avoid file/theme commands until protocol is confirmed."

private fun formatTimestamp(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** e.g. "RX len=4 hex=AC ED 00 05 dec=[172,237,0,5]", with ascii=... appended only if printable. */
private fun formatPacketLog(direction: String, bytes: ByteArray): String {
    val hex = bytesToHex(bytes)
    val dec = bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }
    val base = "$direction len=${bytes.size} hex=$hex dec=[$dec]"
    val withAscii = if (isAllPrintable(bytes)) "$base ascii=${bytesToPrintableAscii(bytes)}" else base
    return if (isJavaStreamHeader(bytes)) "$withAscii (Java serialization stream header)" else withAscii
}

private data class HealthSnapshot(
    val steps: String? = null,
    val distance: String? = null,
    val calories: String? = null,
    val value4: String? = null,
    val value5: String? = null
)

private data class DeviceStatisticsSnapshot(
    val deviceId: String? = null,
    val modelCode: String? = null,
    val deviceName: String? = null,
    val brandCode: String? = null,
    val firmware: String? = null,
    val androidVersion: String? = null,
    val unknownField: String? = null,
    val buildInfo: String? = null
)

private data class WatchDashboardState(
    val batteryPercentage: Int? = null,
    val health: HealthSnapshot = HealthSnapshot(),
    val movingTarget: String? = null,
    val raiseToWake: Boolean? = null,
    val ambientClock: Boolean? = null,
    val watchRemindWay: Boolean? = null,
    val sportsMode: Boolean? = null,
    val bluetoothHandsFree: Boolean? = null,
    val deviceStatistics: DeviceStatisticsSnapshot = DeviceStatisticsSnapshot()
)

private data class HistoryRecord(val timestampMillis: Long, val value: String)

private fun stripWatchTerminator(value: String): String = value.replace("\u5B8C", "").trim('|')

private fun commandKey(cmd: String, trueFalse: Boolean, order: Int, str: String = ""): String =
    "$cmd|$trueFalse|$order|$str"

private fun commandKey(preset: MessageBeanPreset): String = commandKey(preset.cmd, preset.trueFalse, preset.order)

private fun commandKey(bean: MessageBean): String = commandKey(bean.cmd, bean.true_false, bean.order, bean.str)

private fun parseHealthData(value: String): HealthSnapshot {
    val parts = stripWatchTerminator(value).split("|")
    return HealthSnapshot(
        steps = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
        distance = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
        calories = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
        value4 = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
        value5 = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
    )
}

private fun parseDeviceStatistics(value: String): DeviceStatisticsSnapshot {
    val parts = stripWatchTerminator(value).split("|")
    return DeviceStatisticsSnapshot(
        deviceId = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
        modelCode = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
        deviceName = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
        brandCode = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
        firmware = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
        androidVersion = parts.getOrNull(5)?.takeIf { it.isNotBlank() },
        unknownField = parts.getOrNull(6)?.takeIf { it.isNotBlank() },
        buildInfo = parts.getOrNull(7)?.takeIf { it.isNotBlank() }
    )
}

private fun WatchDashboardState.toJson(): String = JSONObject()
    .put("batteryPercentage", batteryPercentage)
    .put("steps", health.steps)
    .put("distance", health.distance)
    .put("calories", health.calories)
    .put("value4", health.value4)
    .put("value5", health.value5)
    .put("movingTarget", movingTarget)
    .put("raiseToWake", raiseToWake)
    .put("ambientClock", ambientClock)
    .put("watchRemindWay", watchRemindWay)
    .put("sportsMode", sportsMode)
    .put("bluetoothHandsFree", bluetoothHandsFree)
    .put("deviceId", deviceStatistics.deviceId)
    .put("modelCode", deviceStatistics.modelCode)
    .put("deviceName", deviceStatistics.deviceName)
    .put("brandCode", deviceStatistics.brandCode)
    .put("firmware", deviceStatistics.firmware)
    .put("androidVersion", deviceStatistics.androidVersion)
    .put("unknownField", deviceStatistics.unknownField)
    .put("buildInfo", deviceStatistics.buildInfo)
    .toString()

private fun dashboardFromJson(json: String?): WatchDashboardState {
    if (json.isNullOrBlank()) return WatchDashboardState()
    return try {
        val obj = JSONObject(json)
        WatchDashboardState(
            batteryPercentage = if (obj.isNull("batteryPercentage")) null else obj.optInt("batteryPercentage"),
            health = HealthSnapshot(
                steps = obj.optString("steps").takeIf { it.isNotBlank() },
                distance = obj.optString("distance").takeIf { it.isNotBlank() },
                calories = obj.optString("calories").takeIf { it.isNotBlank() },
                value4 = obj.optString("value4").takeIf { it.isNotBlank() },
                value5 = obj.optString("value5").takeIf { it.isNotBlank() }
            ),
            movingTarget = obj.optString("movingTarget").takeIf { it.isNotBlank() },
            raiseToWake = if (obj.isNull("raiseToWake")) null else obj.optBoolean("raiseToWake"),
            ambientClock = if (obj.isNull("ambientClock")) null else obj.optBoolean("ambientClock"),
            watchRemindWay = if (obj.isNull("watchRemindWay")) null else obj.optBoolean("watchRemindWay"),
            sportsMode = if (obj.isNull("sportsMode")) null else obj.optBoolean("sportsMode"),
            bluetoothHandsFree = if (obj.isNull("bluetoothHandsFree")) null else obj.optBoolean("bluetoothHandsFree"),
            deviceStatistics = DeviceStatisticsSnapshot(
                deviceId = obj.optString("deviceId").takeIf { it.isNotBlank() },
                modelCode = obj.optString("modelCode").takeIf { it.isNotBlank() },
                deviceName = obj.optString("deviceName").takeIf { it.isNotBlank() },
                brandCode = obj.optString("brandCode").takeIf { it.isNotBlank() },
                firmware = obj.optString("firmware").takeIf { it.isNotBlank() },
                androidVersion = obj.optString("androidVersion").takeIf { it.isNotBlank() },
                unknownField = obj.optString("unknownField").takeIf { it.isNotBlank() },
                buildInfo = obj.optString("buildInfo").takeIf { it.isNotBlank() }
            )
        )
    } catch (e: Exception) {
        WatchDashboardState()
    }
}

private fun historyToJson(history: List<HistoryRecord>): String {
    val array = JSONArray()
    history.take(50).forEach { record ->
        array.put(JSONObject().put("t", record.timestampMillis).put("v", record.value))
    }
    return array.toString()
}

private fun historyFromJson(json: String?): List<HistoryRecord> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            HistoryRecord(obj.optLong("t"), obj.optString("v"))
        }.filter { it.value.isNotBlank() }.take(50)
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
fun BleScannerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("kc10_companion_v01", Context.MODE_PRIVATE) }
    val bluetoothAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    val requiredPermissions = remember { requiredBluetoothPermissions() }

    var bluetoothEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }
    var permissionsGranted by remember { mutableStateOf(hasPermissions(context, requiredPermissions)) }
    var isScanning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var strongSignalOnly by remember { mutableStateOf(false) }
    var connectableOnly by remember { mutableStateOf(false) }
    val devices = remember { mutableStateMapOf<String, BleDevice>() }
    val markedWatchAddresses = remember { mutableStateSetOf<String>() }

    var selectedDevice by remember { mutableStateOf<BleDevice?>(null) }
    var connectionState by remember { mutableStateOf(BleConnectionState.DISCONNECTED) }
    var connectionSource by remember { mutableStateOf<String?>(null) }
    var lastGattStatus by remember { mutableStateOf<Int?>(null) }
    var services by remember { mutableStateOf<List<BleServiceInfo>>(emptyList()) }
    var batteryLevel by remember { mutableStateOf<Int?>(null) }
    var heartRateAvailable by remember { mutableStateOf(false) }

    var classicInspectedAddress by remember { mutableStateOf<String?>(null) }
    var classicUuids by remember { mutableStateOf<List<ClassicUuidInfo>>(emptyList()) }
    var classicFetchInProgress by remember { mutableStateOf(false) }
    var classicFetchAttempted by remember { mutableStateOf(false) }

    var sppState by remember { mutableStateOf(SppState.IDLE) }
    var companionConnectionState by remember { mutableStateOf(CompanionConnectionState.DISCONNECTED) }
    var socketConnected by remember { mutableStateOf(false) }
    val sppHistory = remember { mutableStateListOf<String>() }
    val sppFullLog = remember { mutableStateListOf<String>() }
    var hexInput by remember { mutableStateOf("") }
    var hexInputError by remember { mutableStateOf<String?>(null) }
    var asciiInput by remember { mutableStateOf("") }
    var manualSendAcknowledged by remember { mutableStateOf(false) }
    var javaOutputStreamReady by remember { mutableStateOf(false) }
    var javaInputStreamReady by remember { mutableStateOf(false) }
    var autoJavaHandshakeEnabled by remember { mutableStateOf(false) }
    var lastJavaSendResult by remember { mutableStateOf<String?>(null) }
    var lastJavaReceiveResult by remember { mutableStateOf<String?>(null) }
    var javaUtfInput by remember { mutableStateOf("") }
    var javaListenerActive by remember { mutableStateOf(false) }
    val objectReaderActive = javaListenerActive
    val rxObjectLog = remember { mutableStateListOf<String>() }
    val txObjectLog = remember { mutableStateListOf<String>() }
    var lastConnectAttemptWasChannel41 by remember { mutableStateOf(false) }
    var channel41SectionExpanded by remember { mutableStateOf(false) }
    var javaAdvancedExpanded by remember { mutableStateOf(false) }
    var javaAdvancedEnabled by remember { mutableStateOf(false) }
    var advancedLabExpanded by remember { mutableStateOf(false) }
    var companionLogsExpanded by remember { mutableStateOf(false) }
    var experimentalControlsExpanded by remember { mutableStateOf(false) }
    var rawLogsExpanded by remember { mutableStateOf(false) }
    var canarySocketConnected by remember { mutableStateOf(false) }
    var canaryJavaHandshakeReady by remember { mutableStateOf(false) }
    var selectedCompanionTab by remember { mutableStateOf(CompanionTab.OVERVIEW) }
    var healthHistoryMode by remember { mutableStateOf(HistoryMode.BATTERY) }
    var watchDashboard by remember { mutableStateOf(dashboardFromJson(prefs.getString("dashboard", null))) }
    var lastCommandResult by remember { mutableStateOf("No command sent") }
    var pendingCommandKey by remember { mutableStateOf<String?>(null) }
    var pendingCommandLabel by remember { mutableStateOf<String?>(null) }
    var pendingCommandExpiresAt by remember { mutableLongStateOf(0L) }
    var lastSyncMillis by remember { mutableStateOf(prefs.getLong("last_sync", 0L).takeIf { it > 0L }) }
    var lastHealthSyncMillis by remember { mutableStateOf(prefs.getLong("last_health_sync", 0L).takeIf { it > 0L }) }
    var findWatchRinging by remember { mutableStateOf(false) }
    var reconnectCooldownUntilMillis by remember { mutableLongStateOf(0L) }
    var reconnectCooldownTick by remember { mutableLongStateOf(0L) }
    val batteryHistory = remember { mutableStateListOf<HistoryRecord>().apply { addAll(historyFromJson(prefs.getString("battery_history", null))) } }
    val heartRateHistory = remember { mutableStateListOf<HistoryRecord>().apply { addAll(historyFromJson(prefs.getString("heart_history", null))) } }
    var protocolInfoExpanded by remember { mutableStateOf(false) }
    val lastCommandSentAt = remember { mutableStateMapOf<String, Long>() }
    val commandResultHandler = remember { Handler(Looper.getMainLooper()) }

    var beanCmd by remember { mutableStateOf("") }
    var beanTrueFalse by remember { mutableStateOf(false) }
    var beanOrderText by remember { mutableStateOf("-1") }
    var beanMaxValueText by remember { mutableStateOf("-1") }
    var beanCurrentValueText by remember { mutableStateOf("-1") }
    var beanStr by remember { mutableStateOf("") }
    var beanIdentifier by remember { mutableStateOf("") }
    var beanBytesHex by remember { mutableStateOf("") }
    var beanBytesHexError by remember { mutableStateOf<String?>(null) }

    var sppSessionStartMillis by remember { mutableStateOf<Long?>(null) }
    var sppSessionEndMillis by remember { mutableStateOf<Long?>(null) }
    var sppRxPacketCount by remember { mutableIntStateOf(0) }
    var sppTxPacketCount by remember { mutableIntStateOf(0) }
    var sppRxByteTotal by remember { mutableLongStateOf(0L) }
    var sppTxByteTotal by remember { mutableLongStateOf(0L) }
    var sppDurationTick by remember { mutableIntStateOf(0) }
    var sppInitialRxLine by remember { mutableStateOf<String?>(null) }
    var sppLastTxLine by remember { mutableStateOf<String?>(null) }
    var sppLastRxLine by remember { mutableStateOf<String?>(null) }
    var sppDisconnectReason by remember { mutableStateOf<String?>(null) }
    var lastPacketLine by remember { mutableStateOf<String?>(null) }
    var packetNoteInput by remember { mutableStateOf("") }
    var disconnectAfterSend by remember { mutableStateOf(false) }

    val logs = remember { mutableStateListOf<String>() }
    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.add(0, "[$timestamp] $message")
        while (logs.size > MAX_LOG_LINES) logs.removeAt(logs.lastIndex)
    }
    fun addSppHistory(entry: String) {
        sppHistory.add(0, entry)
        while (sppHistory.size > MAX_SPP_HISTORY) sppHistory.removeAt(sppHistory.lastIndex)
        sppFullLog.add(entry)
        while (sppFullLog.size > MAX_SPP_FULL_LOG) sppFullLog.removeAt(0)
    }
    fun addRxObjectLog(message: String) {
        val line = "[${formatTimestamp(System.currentTimeMillis())}] RX: $message"
        rxObjectLog.add(0, line)
        while (rxObjectLog.size > MAX_JAVA_EVENT_LOG) rxObjectLog.removeAt(rxObjectLog.lastIndex)
    }
    fun addTxObjectLog(message: String) {
        val line = "[${formatTimestamp(System.currentTimeMillis())}] TX: $message"
        txObjectLog.add(0, line)
        while (txObjectLog.size > MAX_JAVA_EVENT_LOG) txObjectLog.removeAt(txObjectLog.lastIndex)
    }
    // Direction/timestamp/length/hex/ascii, written once to both the Event Log and Command History.
    fun addPacketLog(direction: String, bytes: ByteArray): String {
        val line = "[${formatTimestamp(System.currentTimeMillis())}] " + formatPacketLog(direction, bytes)
        logs.add(0, line)
        while (logs.size > MAX_LOG_LINES) logs.removeAt(logs.lastIndex)
        addSppHistory(line)
        return line
    }

    val scanner = remember(bluetoothAdapter) {
        bluetoothAdapter?.let { adapter ->
            BleScanner(
                context = context,
                bluetoothAdapter = adapter,
                onDeviceFound = { device ->
                    if (!devices.containsKey(device.address)) {
                        addLog("Device found: ${device.address}")
                    }
                    devices[device.address] = device
                },
                onScanFailed = { message ->
                    errorMessage = message
                    isScanning = false
                    addLog("Scan failed: $message")
                }
            )
        }
    }

    val gattManager = remember(bluetoothAdapter) {
        BleGattManager(
            onStateChange = { state, status ->
                connectionState = state
                lastGattStatus = status
            },
            onServicesDiscovered = { discovered -> services = discovered },
            onBatteryLevel = { level -> batteryLevel = level },
            onHeartRateAvailable = { available -> heartRateAvailable = available },
            onLog = { message -> addLog(message) }
        )
    }

    var pairedDevicesRefreshTrigger by remember { mutableIntStateOf(0) }
    val pairedDevices = remember(pairedDevicesRefreshTrigger, permissionsGranted, bluetoothEnabled) {
        readPairedDevices(context, bluetoothAdapter)
    }

    val bondManager = remember {
        BleBondManager(
            onBondStateChanged = { address, label ->
                devices[address]?.let { existing -> devices[address] = existing.copy(bondStateLabel = label) }
                pairedDevicesRefreshTrigger++
            },
            onLog = { message -> addLog(message) }
        )
    }

    DisposableEffect(bondManager) {
        bondManager.start(context)
        onDispose { bondManager.stop(context) }
    }

    val classicInspector = remember {
        ClassicInspector(
            onUuidsUpdated = { uuids -> classicUuids = uuids },
            onFetchFinished = {
                classicFetchInProgress = false
                classicFetchAttempted = true
            },
            onLog = { message -> addLog(message) }
        )
    }

    DisposableEffect(classicInspector) {
        classicInspector.start(context)
        onDispose { classicInspector.stop(context) }
    }

    fun updateWatchDashboard(bean: MessageBean) {
        val now = System.currentTimeMillis()
        lastSyncMillis = now
        watchDashboard = when (bean.cmd) {
            "Battery" -> watchDashboard.copy(batteryPercentage = bean.currentValue.takeIf { it >= 0 })
            "Health data" -> watchDashboard.copy(health = parseHealthData(bean.str))
            "Moving target" -> watchDashboard.copy(movingTarget = stripWatchTerminator(bean.str).takeIf { it.isNotBlank() })
            "Raise to wake" -> watchDashboard.copy(raiseToWake = bean.true_false)
            "Ambient clock" -> watchDashboard.copy(ambientClock = bean.true_false)
            "Watch remind way" -> watchDashboard.copy(watchRemindWay = bean.true_false)
            "Sports mode" -> watchDashboard.copy(sportsMode = bean.true_false)
            "Bluetooth Hands-free" -> watchDashboard.copy(bluetoothHandsFree = bean.true_false)
            "Device statistics" -> watchDashboard.copy(deviceStatistics = parseDeviceStatistics(bean.str))
            else -> watchDashboard
        }
        if (bean.cmd == "Health data") {
            lastHealthSyncMillis = now
        }
        if (bean.cmd == "Battery" && bean.currentValue >= 0) {
            batteryHistory.add(0, HistoryRecord(now, "${bean.currentValue}%"))
            while (batteryHistory.size > 50) batteryHistory.removeAt(batteryHistory.lastIndex)
        }
        if (bean.cmd == "Health data") {
            watchDashboard.health.value4?.let { value ->
                heartRateHistory.add(0, HistoryRecord(now, value))
                while (heartRateHistory.size > 50) heartRateHistory.removeAt(heartRateHistory.lastIndex)
            }
        }
        prefs.edit()
            .putString("last_device_name", KC10_NAME)
            .putString("last_device_address", KC10_ADDRESS)
            .putString("dashboard", watchDashboard.toJson())
            .putString("battery_history", historyToJson(batteryHistory))
            .putString("heart_history", historyToJson(heartRateHistory))
            .putInt("latest_battery", watchDashboard.batteryPercentage ?: -1)
            .putString("latest_heart_rate_health_value", watchDashboard.health.value4.orEmpty())
            .putLong("last_sync", now)
            .putLong("last_health_sync", lastHealthSyncMillis ?: 0L)
            .apply()
        val pending = pendingCommandKey
        if (pending != null && now <= pendingCommandExpiresAt) {
            lastCommandResult = "Response received: ${bean.cmd}"
            pendingCommandKey = null
            pendingCommandLabel = null
            pendingCommandExpiresAt = 0L
        }
    }

    fun updateCompanionReadyState() {
        companionConnectionState = if (socketConnected && javaOutputStreamReady && javaInputStreamReady && javaListenerActive) {
            CompanionConnectionState.CONNECTED
        } else if (companionConnectionState == CompanionConnectionState.CONNECTED) {
            CompanionConnectionState.DISCONNECTED
        } else {
            companionConnectionState
        }
    }

    val sppManager = remember {
        SppManager(
            onStateChange = { newState, reason ->
                val wasConnected = sppState == SppState.CONNECTED
                if (newState == SppState.CONNECTING) {
                    companionConnectionState = CompanionConnectionState.CONNECTING
                    sppSessionStartMillis = System.currentTimeMillis()
                    sppSessionEndMillis = null
                    sppRxPacketCount = 0
                    sppTxPacketCount = 0
                    sppRxByteTotal = 0L
                    sppTxByteTotal = 0L
                    sppInitialRxLine = null
                    sppLastTxLine = null
                    sppLastRxLine = null
                    sppDisconnectReason = null
                    lastPacketLine = null
                    javaOutputStreamReady = false
                    javaInputStreamReady = false
                    lastJavaSendResult = null
                    lastJavaReceiveResult = null
                    javaListenerActive = false
                    rxObjectLog.clear()
                    txObjectLog.clear()
                    lastCommandResult = "No command sent"
                    pendingCommandKey = null
                    pendingCommandLabel = null
                    pendingCommandExpiresAt = 0L
                } else if (newState == SppState.CONNECTED) {
                    updateCompanionReadyState()
                } else if (wasConnected) {
                    sppSessionEndMillis = System.currentTimeMillis()
                }
                if (newState == SppState.DISCONNECTED || newState == SppState.IDLE) {
                    if (companionConnectionState != CompanionConnectionState.DISCONNECTING) {
                        companionConnectionState = CompanionConnectionState.DISCONNECTED
                    }
                    javaOutputStreamReady = false
                    javaInputStreamReady = false
                    javaListenerActive = false
                } else if (newState == SppState.FAILED) {
                    companionConnectionState = CompanionConnectionState.ERROR
                    javaOutputStreamReady = false
                    javaInputStreamReady = false
                    javaListenerActive = false
                }
                if (reason != null) sppDisconnectReason = reason
                sppState = newState
            },
            onBytesSent = { bytes ->
                sppTxPacketCount++
                sppTxByteTotal += bytes.size
                val line = addPacketLog("TX", bytes)
                sppLastTxLine = line
                lastPacketLine = line
            },
            onBytesReceived = { bytes ->
                sppRxPacketCount++
                sppRxByteTotal += bytes.size
                val line = addPacketLog("RX", bytes)
                if (sppInitialRxLine == null) sppInitialRxLine = line
                sppLastRxLine = line
                lastPacketLine = line
            },
            onLog = { message -> addLog(message) },
            onJavaOutputStreamReady = { ready ->
                javaOutputStreamReady = ready
                updateCompanionReadyState()
            },
            onJavaInputStreamReady = { ready ->
                javaInputStreamReady = ready
                updateCompanionReadyState()
            },
            onJavaSendResult = { message ->
                lastJavaSendResult = message
                addTxObjectLog(message)
            },
            onJavaReceiveResult = { message ->
                lastJavaReceiveResult = message
                addRxObjectLog(message)
            },
            onMessageBeanReceived = { bean -> updateWatchDashboard(bean) },
            onMessageBeanSendResult = { bean, success, message ->
                if (success) {
                    lastCommandResult = "Waiting for response"
                    val key = commandKey(bean)
                    pendingCommandKey = key
                    pendingCommandLabel = bean.cmd
                    pendingCommandExpiresAt = System.currentTimeMillis() + 5_000L
                    commandResultHandler.postDelayed(
                        {
                            if (pendingCommandKey == key && System.currentTimeMillis() >= pendingCommandExpiresAt) {
                                lastCommandResult = "Sent"
                                pendingCommandKey = null
                                pendingCommandLabel = null
                                pendingCommandExpiresAt = 0L
                            }
                        },
                        5_000L
                    )
                } else {
                    lastCommandResult = "Failed: $message"
                    pendingCommandKey = null
                    pendingCommandLabel = null
                    pendingCommandExpiresAt = 0L
                }
            },
            onJavaListenerActiveChange = { active ->
                javaListenerActive = active
                updateCompanionReadyState()
            },
            onCanaryConnectedChange = { connected -> canarySocketConnected = connected },
            onCanaryHandshakeReadyChange = { ready -> canaryJavaHandshakeReady = ready },
            onSocketConnectedChange = { connected ->
                socketConnected = connected
                updateCompanionReadyState()
            }
        )
    }

    DisposableEffect(sppManager) {
        onDispose {
            commandResultHandler.removeCallbacksAndMessages(null)
            sppManager.disconnect()
        }
    }

    // Live-updating "connected duration" while a session is active.
    DisposableEffect(sppState) {
        val handler = Handler(Looper.getMainLooper())
        var tickRunnable: Runnable? = null
        if (sppState == SppState.CONNECTED) {
            tickRunnable = object : Runnable {
                override fun run() {
                    sppDurationTick++
                    handler.postDelayed(this, SPP_DURATION_TICK_MS)
                }
            }
            handler.postDelayed(tickRunnable, SPP_DURATION_TICK_MS)
        }
        onDispose { tickRunnable?.let { handler.removeCallbacks(it) } }
    }

    DisposableEffect(reconnectCooldownUntilMillis) {
        val handler = Handler(Looper.getMainLooper())
        var tickRunnable: Runnable? = null
        if (reconnectCooldownUntilMillis > System.currentTimeMillis()) {
            tickRunnable = object : Runnable {
                override fun run() {
                    reconnectCooldownTick = System.currentTimeMillis()
                    if (reconnectCooldownUntilMillis > reconnectCooldownTick) {
                        handler.postDelayed(this, 250L)
                    }
                }
            }
            handler.post(tickRunnable)
        }
        onDispose { tickRunnable?.let { handler.removeCallbacks(it) } }
    }

    val sppDurationText = remember(sppSessionStartMillis, sppSessionEndMillis, sppDurationTick) {
        val start = sppSessionStartMillis
        if (start == null) {
            "—"
        } else {
            formatDuration((sppSessionEndMillis ?: System.currentTimeMillis()) - start)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        errorMessage = if (permissionsGranted) null else "Bluetooth permission is required to scan"
    }

    // Keep bluetoothEnabled in sync with the system Bluetooth toggle.
    DisposableEffect(bluetoothAdapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                bluetoothEnabled = bluetoothAdapter?.isEnabled == true
                if (!bluetoothEnabled) {
                    scanner?.stopScan()
                    isScanning = false
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Stop scanning / close the GATT connection if this screen leaves composition.
    DisposableEffect(scanner, gattManager) {
        onDispose {
            scanner?.stopScan()
            gattManager.disconnect()
        }
    }

    fun stopScan() {
        scanner?.stopScan()
        isScanning = false
    }

    fun startScan() {
        errorMessage = null
        when {
            bluetoothAdapter == null -> errorMessage = "This device does not support Bluetooth"
            !bluetoothEnabled -> errorMessage = "Turn on Bluetooth to scan for devices"
            !hasPermissions(context, requiredPermissions) -> permissionLauncher.launch(requiredPermissions)
            else -> {
                devices.clear()
                isScanning = true
                addLog("Scan started")
                scanner?.startScan()
            }
        }
    }

    fun beginConnection(target: BleDevice, remoteDevice: BluetoothDevice, source: String) {
        stopScan()
        selectedDevice = target
        connectionSource = source
        lastGattStatus = null
        services = emptyList()
        batteryLevel = null
        heartRateAvailable = false
        gattManager.connect(context, remoteDevice)
    }

    fun connectToDevice(device: BleDevice) {
        errorMessage = null
        addLog(
            "Connect tapped [BLE scan] address=${device.address}, name=${device.displayName}, " +
                "rssi=${device.rssi}dBm, connectable=${connectableLabel(device.isConnectable)}, " +
                "bond=${device.bondStateLabel}"
        )
        val remoteDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        if (remoteDevice == null) {
            errorMessage = "Unable to find device ${device.address}"
            return
        }
        beginConnection(device, remoteDevice, source = "scan")
    }

    fun findBondedDevice(address: String): BluetoothDevice? = try {
        bluetoothAdapter?.bondedDevices?.firstOrNull { it.address == address }
    } catch (e: SecurityException) {
        null
    }

    fun connectToPairedDevice(paired: PairedDevice) {
        errorMessage = null
        addLog(
            "Connect tapped [Paired section] address=${paired.address}, name=${paired.name}, " +
                "rssi=n/a, connectable=${if (paired.isBleConnectable) "Yes" else "No"}, " +
                "bond=${paired.bondStateLabel}"
        )
        val remoteDevice = findBondedDevice(paired.address)
        if (remoteDevice == null) {
            errorMessage = "Unable to find paired device ${paired.address}"
            return
        }
        beginConnection(paired.toBleDevicePlaceholder(), remoteDevice, source = "paired")
    }

    fun inspectClassicUuids(paired: PairedDevice) {
        errorMessage = null
        val remoteDevice = findBondedDevice(paired.address)
        if (remoteDevice == null) {
            errorMessage = "Unable to find paired device ${paired.address}"
            return
        }
        classicInspectedAddress = paired.address
        classicUuids = emptyList()
        classicFetchInProgress = true
        classicFetchAttempted = false
        classicInspector.inspect(context, remoteDevice)
    }

    fun sppConnect(paired: PairedDevice) {
        val adapter = bluetoothAdapter ?: return
        val remoteDevice = findBondedDevice(paired.address)
        if (remoteDevice == null) {
            errorMessage = "Unable to find paired device ${paired.address}"
            return
        }
        lastConnectAttemptWasChannel41 = false
        sppManager.connect(adapter, remoteDevice)
    }

    fun sppConnectInsecure(paired: PairedDevice) {
        val adapter = bluetoothAdapter ?: return
        val remoteDevice = findBondedDevice(paired.address)
        if (remoteDevice == null) {
            errorMessage = "Unable to find paired device ${paired.address}"
            return
        }
        addLog("Trying insecure SPP connect (manual fallback)")
        lastConnectAttemptWasChannel41 = false
        sppManager.connect(adapter, remoteDevice, insecure = true)
    }

    // Primary transport for WiiWatch2 protocol compatibility: a dedicated SppManager method
    // that only ever uses the normal SPP UUID (never createRfcommSocket(41) reflection) and
    // always sets up the ObjectStream handshake - entirely separate from plain connect()/the
    // "Auto Java Handshake on Connect" toggle, so the two paths can never get confused.
    fun sppConnectWiiWatch2(paired: PairedDevice) {
        val adapter = bluetoothAdapter ?: return
        val remoteDevice = findBondedDevice(paired.address)
        if (remoteDevice == null) {
            errorMessage = "Unable to find paired device ${paired.address}"
            return
        }
        addLog("Connect WiiWatch2 Protocol tapped (normal SPP UUID)")
        lastConnectAttemptWasChannel41 = false
        sppManager.connectWiiWatch2Protocol(adapter, remoteDevice)
    }

    fun sppConnectChannel41(paired: PairedDevice) {
        val adapter = bluetoothAdapter ?: return
        val remoteDevice = findBondedDevice(paired.address)
        if (remoteDevice == null) {
            errorMessage = "Unable to find paired device ${paired.address}"
            return
        }
        addLog("Experimental Channel 41 tapped (WiiWatch2 reflection RFCOMM channel 41)")
        lastConnectAttemptWasChannel41 = true
        sppManager.connectChannel41(adapter, remoteDevice)
    }

    fun connectSppCanary(device: PairedDevice) {
        val adapter = bluetoothAdapter ?: return
        val remoteDevice = findBondedDevice(device.address)
        if (remoteDevice == null) {
            errorMessage = "Unable to find paired device ${device.address}"
            return
        }
        addLog("SPP Canary Connect tapped")
        lastConnectAttemptWasChannel41 = false
        sppManager.connectSppCanary(adapter, remoteDevice)
    }

    fun kc10RemoteDevice(): BluetoothDevice? = try {
        findBondedDevice(KC10_ADDRESS) ?: bluetoothAdapter?.getRemoteDevice(KC10_ADDRESS)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: SecurityException) {
        null
    }

    fun connectKc10() {
        if (System.currentTimeMillis() < reconnectCooldownUntilMillis) {
            addLog("Reconnect cooldown active")
            errorMessage = "Please wait for Bluetooth session to close..."
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            errorMessage = "This device does not support Bluetooth"
            return
        }
        val remoteDevice = kc10RemoteDevice()
        if (remoteDevice == null) {
            errorMessage = "Unable to find KC10 device $KC10_ADDRESS"
            return
        }
        manualSendAcknowledged = true
        addLog("Connect KC10 tapped")
        sppManager.connectKc10Companion(adapter, remoteDevice)
    }

    fun connectKc10UsingCanaryInternals() {
        if (System.currentTimeMillis() < reconnectCooldownUntilMillis) {
            addLog("Reconnect cooldown active")
            errorMessage = "Please wait for Bluetooth session to close..."
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            errorMessage = "This device does not support Bluetooth"
            return
        }
        val remoteDevice = kc10RemoteDevice()
        if (remoteDevice == null) {
            errorMessage = "Unable to find KC10 device $KC10_ADDRESS"
            return
        }
        manualSendAcknowledged = true
        addLog("Connect KC10 Using Canary Internals tapped")
        sppManager.connectKc10UsingCanaryInternals(adapter, remoteDevice)
    }

    fun sppCanaryJavaHandshake() {
        addLog("SPP Canary Java Handshake tapped")
        sppManager.startSppCanaryJavaHandshake()
    }

    fun sppCanarySendBatteryMessageBean() {
        addLog("SPP Canary Send MessageBean Battery tapped")
        val bean = MessageBean()
        bean.cmd = "Battery"
        bean.true_false = false
        bean.order = -1
        sppManager.sendSppCanaryBatteryMessageBean(bean)
    }

    fun closeSppCanarySocket() {
        addLog("Close Canary Socket tapped")
        sppManager.closeSppCanarySocket()
    }

    fun sppDisconnect() {
        companionConnectionState = CompanionConnectionState.DISCONNECTING
        javaOutputStreamReady = false
        javaInputStreamReady = false
        javaListenerActive = false
        socketConnected = false
        Thread {
            sppManager.hardCleanupSpp(bluetoothAdapter, "Manual disconnect")
            Handler(Looper.getMainLooper()).post {
                companionConnectionState = CompanionConnectionState.DISCONNECTED
                reconnectCooldownUntilMillis = System.currentTimeMillis() + 5_000L
                reconnectCooldownTick = System.currentTimeMillis()
                addLog("Waiting for Bluetooth session to close...")
            }
        }.start()
    }

    fun sppForceReset() {
        sppManager.forceReset()
        sppSessionStartMillis = null
        sppSessionEndMillis = null
        sppRxPacketCount = 0
        sppTxPacketCount = 0
        sppRxByteTotal = 0L
        sppTxByteTotal = 0L
        sppInitialRxLine = null
        sppLastTxLine = null
        sppLastRxLine = null
        sppDisconnectReason = null
        lastPacketLine = null
        javaOutputStreamReady = false
        javaInputStreamReady = false
        lastJavaSendResult = null
        lastJavaReceiveResult = null
        javaListenerActive = false
        companionConnectionState = CompanionConnectionState.DISCONNECTED
        socketConnected = false
        lastConnectAttemptWasChannel41 = false
        canarySocketConnected = false
        canaryJavaHandshakeReady = false
        rxObjectLog.clear()
        txObjectLog.clear()
        watchDashboard = WatchDashboardState()
        lastCommandResult = "No command sent"
        pendingCommandKey = null
        pendingCommandLabel = null
        pendingCommandExpiresAt = 0L
    }

    fun sppSendTestBytes() {
        SPP_TEST_PROBES.forEach { probe -> sppManager.send(probe) }
    }

    fun sppSendHex() {
        val bytes = parseHexBytes(hexInput)
        if (bytes == null) {
            hexInputError = "Enter valid HEX bytes, e.g. 41 54 0D 0A"
            return
        }
        hexInputError = null
        sppManager.send(bytes)
    }

    fun sppSendAscii() {
        if (asciiInput.isEmpty()) return
        sppManager.send(asciiInput.toByteArray(Charsets.US_ASCII))
    }

    fun sppStartJavaStreamProbe() {
        addLog("Java Stream Probe tapped")
        sppManager.startJavaStreamProbe()
    }

    fun sppSetAutoJavaHandshake(enabled: Boolean) {
        autoJavaHandshakeEnabled = enabled
        sppManager.autoJavaHandshakeOnConnect = enabled
    }

    fun sppSendJavaUtf(text: String) {
        if (text.isEmpty()) return
        sppManager.sendJavaUtf(text)
    }

    fun sppSendJavaInt(value: Int) {
        sppManager.sendJavaInt(value)
    }

    fun sppSendJavaBoolean(value: Boolean) {
        sppManager.sendJavaBoolean(value)
    }

    fun sppSendJavaLong() {
        sppManager.sendJavaLong(System.currentTimeMillis())
    }

    fun sppSendJavaObjectString(value: String) {
        sppManager.sendJavaObject("\"$value\"", value)
    }

    fun sppSendJavaObjectCmd(cmd: String) {
        sppManager.sendJavaObject("{cmd=$cmd}", hashMapOf("cmd" to cmd))
    }

    fun sppToggleJavaListener() {
        if (javaListenerActive) {
            sppManager.stopJavaObjectListening()
        } else {
            sppManager.startJavaObjectListening()
        }
    }

    fun buildMessageBean(cmd: String, trueFalse: Boolean, order: Int): MessageBean {
        val bean = MessageBean()
        bean.cmd = cmd
        bean.true_false = trueFalse
        bean.order = order
        return bean
    }

    fun sppSendMessageBeanPreset(preset: MessageBeanPreset) {
        addLog("MessageBean preset tapped: ${preset.label}")
        sppManager.sendMessageBean(buildMessageBean(preset.cmd, preset.trueFalse, preset.order))
    }

    fun sendSafeControl(preset: MessageBeanPreset) {
        val key = commandKey(preset)
        val now = System.currentTimeMillis()
        val previous = lastCommandSentAt[key] ?: 0L
        if (now - previous < 1_000L) {
            lastCommandResult = "Waiting for debounce"
            addLog("Debounced duplicate command: ${preset.label}")
            return
        }
        lastCommandSentAt[key] = now
        lastCommandResult = "Sending ${preset.label}"
        if (preset.cmd == "Find the target") {
            findWatchRinging = preset.trueFalse
        }
        addLog("Safe Control tapped: ${preset.label}")
        sppManager.sendMessageBean(buildMessageBean(preset.cmd, preset.trueFalse, preset.order))
    }

    fun refreshBattery() {
        addLog("Refresh Battery tapped")
        sendSafeControl(MessageBeanPreset("Request Battery", cmd = "Battery"))
    }

    fun requestSyncSnapshot() {
        addLog("Request Sync Snapshot tapped")
        sppManager.sendMessageBeansSequential(
            "Request Sync Snapshot",
            listOf(
                buildMessageBean("Battery", false, -1),
                buildMessageBean("Health data", false, -1),
                buildMessageBean("Device statistics", false, -1)
            )
        )
    }

    fun sppSendMessageBean() {
        var bytes: ByteArray? = null
        if (beanBytesHex.isNotBlank()) {
            val parsed = parseHexBytes(beanBytesHex)
            if (parsed == null) {
                beanBytesHexError = "Enter valid HEX bytes, e.g. 41 54 0D 0A"
                return
            }
            bytes = parsed
        }
        beanBytesHexError = null
        val bean = MessageBean()
        bean.cmd = beanCmd
        bean.true_false = beanTrueFalse
        bean.order = beanOrderText.toIntOrNull() ?: -1
        bean.maxValue = beanMaxValueText.toIntOrNull() ?: -1
        bean.currentValue = beanCurrentValueText.toIntOrNull() ?: -1
        bean.str = beanStr
        bean.identifier = beanIdentifier
        bean.bytes = bytes
        sppManager.sendMessageBean(bean)
    }

    fun sendHandshakePreset(preset: HandshakePreset) {
        addLog("Handshake preset tapped: ${preset.label}")
        sppManager.send(preset.bytes)
        if (disconnectAfterSend) {
            Handler(Looper.getMainLooper()).postDelayed(
                { sppManager.disconnect() },
                DISCONNECT_AFTER_SEND_DELAY_MS
            )
        }
    }

    fun addPacketNote() {
        val note = packetNoteInput.trim()
        if (note.isEmpty()) return
        val target = lastPacketLine ?: "no packet yet"
        addLog("NOTE on last packet ($target): $note")
        addSppHistory("NOTE: $note (re: $target)")
        packetNoteInput = ""
    }

    fun copySppLog() {
        val combined = buildString {
            appendLine("Event Log:")
            logs.asReversed().forEach { appendLine(it) }
            appendLine()
            appendLine("Command History:")
            sppHistory.asReversed().forEach { appendLine(it) }
            appendLine()
            appendLine("RX Object Log:")
            rxObjectLog.asReversed().forEach { appendLine(it) }
            appendLine()
            appendLine("TX Object Log:")
            txObjectLog.asReversed().forEach { appendLine(it) }
        }
        clipboardManager.setText(AnnotatedString(combined))
        addLog("Copied log to clipboard")
    }

    fun exportSppLog(paired: PairedDevice) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val fileName = "spp_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val file = File(dir, fileName)
        try {
            file.bufferedWriter().use { writer ->
                writer.appendLine("Muba Watch Lab - SPP session export")
                writer.appendLine("Device: ${paired.name}")
                writer.appendLine("Address: ${paired.address}")
                writer.appendLine("UUID: $SPP_UUID")
                writer.appendLine("Session start: ${sppSessionStartMillis?.let { formatTimestamp(it) } ?: "n/a"}")
                writer.appendLine("Session end: ${sppSessionEndMillis?.let { formatTimestamp(it) } ?: "n/a"}")
                writer.appendLine("Status: ${sppStateLabel(sppState)}")
                writer.appendLine("Disconnect reason: ${sppDisconnectReason ?: "n/a"}")
                writer.appendLine("RX packets: $sppRxPacketCount, RX bytes: $sppRxByteTotal")
                writer.appendLine("TX packets: $sppTxPacketCount, TX bytes: $sppTxByteTotal")
                writer.appendLine()
                writer.appendLine("Packets:")
                sppFullLog.forEach { writer.appendLine(it) }
                writer.appendLine()
                writer.appendLine("RX Object Log:")
                if (rxObjectLog.isEmpty()) writer.appendLine("(none)")
                rxObjectLog.asReversed().forEach { writer.appendLine(it) }
                writer.appendLine()
                writer.appendLine("TX Object Log:")
                if (txObjectLog.isEmpty()) writer.appendLine("(none)")
                txObjectLog.asReversed().forEach { writer.appendLine(it) }
                writer.appendLine()
                writer.appendLine("Event Log:")
                logs.asReversed().forEach { writer.appendLine(it) }
            }
            addLog("Exported SPP log to ${file.absolutePath}")
        } catch (e: IOException) {
            addLog("Export SPP log failed: ${e.message}")
        }
    }

    fun exportCompanionLog() {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val fileName = "kc10_companion_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val file = File(dir, fileName)
        try {
            file.bufferedWriter().use { writer ->
                writer.appendLine("$APP_TITLE debug bundle")
                writer.appendLine("Device: $KC10_NAME")
                writer.appendLine("Address: $KC10_ADDRESS")
                writer.appendLine("Status: ${sppStateLabel(sppState)}")
                writer.appendLine("Last sync: ${lastSyncMillis?.let { formatTimestamp(it) } ?: "n/a"}")
                writer.appendLine("Last health sync: ${lastHealthSyncMillis?.let { formatTimestamp(it) } ?: "n/a"}")
                writer.appendLine()
                writer.appendLine("Latest Dashboard Snapshot:")
                writer.appendLine(watchDashboard.toJson())
                writer.appendLine()
                writer.appendLine("Device Info:")
                writer.appendLine("Device ID: ${watchDashboard.deviceStatistics.deviceId ?: "n/a"}")
                writer.appendLine("Model code: ${watchDashboard.deviceStatistics.modelCode ?: "n/a"}")
                writer.appendLine("Device name: ${watchDashboard.deviceStatistics.deviceName ?: "n/a"}")
                writer.appendLine("Brand/code: ${watchDashboard.deviceStatistics.brandCode ?: "n/a"}")
                writer.appendLine("Firmware: ${watchDashboard.deviceStatistics.firmware ?: "n/a"}")
                writer.appendLine("Android version: ${watchDashboard.deviceStatistics.androidVersion ?: "n/a"}")
                writer.appendLine("Build info: ${watchDashboard.deviceStatistics.buildInfo ?: "n/a"}")
                writer.appendLine()
                writer.appendLine("Battery History:")
                if (batteryHistory.isEmpty()) writer.appendLine("(none)")
                batteryHistory.asReversed().forEach { writer.appendLine("${formatTimestamp(it.timestampMillis)} ${it.value}") }
                writer.appendLine()
                writer.appendLine("Heart Rate / Health Value History:")
                if (heartRateHistory.isEmpty()) writer.appendLine("(none)")
                heartRateHistory.asReversed().forEach { writer.appendLine("${formatTimestamp(it.timestampMillis)} ${it.value}") }
                writer.appendLine()
                writer.appendLine("RX Object Log:")
                if (rxObjectLog.isEmpty()) writer.appendLine("(none)")
                rxObjectLog.asReversed().forEach { writer.appendLine(it) }
                writer.appendLine()
                writer.appendLine("TX Object Log:")
                if (txObjectLog.isEmpty()) writer.appendLine("(none)")
                txObjectLog.asReversed().forEach { writer.appendLine(it) }
                writer.appendLine()
                writer.appendLine("Event Log:")
                logs.asReversed().forEach { writer.appendLine(it) }
            }
            addLog("Exported KC10 debug bundle to ${file.absolutePath}")
        } catch (e: IOException) {
            addLog("Export KC10 debug bundle failed: ${e.message}")
        }
    }

    fun pairDevice(device: BleDevice) {
        val remoteDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: return
        bondManager.startBonding(remoteDevice)
    }

    fun toggleMarkedWatch(address: String) {
        if (!markedWatchAddresses.add(address)) markedWatchAddresses.remove(address)
    }

    val statusText = when {
        bluetoothAdapter == null -> "Bluetooth not supported on this device"
        !bluetoothEnabled -> "Bluetooth is off"
        !permissionsGranted -> "Bluetooth permission not granted"
        isScanning -> "Scanning for devices..."
        else -> "Bluetooth is on"
    }
    val lastSyncText = lastSyncMillis?.let { formatTimestamp(it) } ?: "Never"
    val cooldownActive = reconnectCooldownUntilMillis > maxOf(System.currentTimeMillis(), reconnectCooldownTick)
    val cooldownMessage = if (cooldownActive) "Waiting for Bluetooth session to close..." else null
    val isCompanionConnected = companionConnectionState == CompanionConnectionState.CONNECTED
    val canSendCommands = companionConnectionState == CompanionConnectionState.CONNECTED &&
        javaOutputStreamReady &&
        socketConnected
    val connectEnabled = companionConnectionState == CompanionConnectionState.DISCONNECTED ||
        companionConnectionState == CompanionConnectionState.ERROR
    val disconnectEnabled = companionConnectionState == CompanionConnectionState.CONNECTED ||
        companionConnectionState == CompanionConnectionState.CONNECTING
    val connectAllowed = connectEnabled && !cooldownActive

    PremiumScaffold(
        selectedTab = selectedCompanionTab,
        onTabSelected = { selectedCompanionTab = it },
        modifier = modifier
    ) {
        statusText.takeIf { it != "Bluetooth is on" }?.let { message ->
            GlassCard {
                Text(text = message, color = PremiumMuted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        errorMessage?.let { message ->
            GlassCard(borderColor = PremiumRed.copy(alpha = 0.45f)) {
                Text(text = message, color = PremiumRed, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        when (selectedCompanionTab) {
            CompanionTab.OVERVIEW -> OverviewTab(
                state = watchDashboard,
                connectionStatus = companionConnectionStateLabel(companionConnectionState),
                lastSyncText = lastSyncText,
                connectLabel = if (companionConnectionState == CompanionConnectionState.ERROR) "Reconnect Watch" else "Connect Watch",
                connectEnabled = connectAllowed,
                disconnectEnabled = disconnectEnabled,
                refreshBatteryEnabled = canSendCommands,
                helperMessage = cooldownMessage,
                canSendCommands = canSendCommands,
                onConnectClick = { connectKc10() },
                onDisconnectClick = { sppDisconnect() },
                onRefreshBatteryClick = { refreshBattery() }
            )
            CompanionTab.HEALTH -> HealthTab(
                state = watchDashboard,
                lastHealthSyncText = lastHealthSyncMillis?.let { formatTimestamp(it) } ?: "Never",
                batteryHistory = batteryHistory,
                heartRateHistory = heartRateHistory,
                historyMode = healthHistoryMode,
                onHistoryModeChange = { healthHistoryMode = it }
            )
            CompanionTab.DEVICE -> {
                DeviceTab(
                    state = watchDashboard,
                    connected = canSendCommands,
                    lastCommandResult = lastCommandResult,
                    findWatchRinging = findWatchRinging,
                    experimentalExpanded = experimentalControlsExpanded,
                    logsExpanded = companionLogsExpanded,
                    protocolExpanded = protocolInfoExpanded,
                    advancedExpanded = advancedLabExpanded,
                    rxObjectLog = rxObjectLog,
                    txObjectLog = txObjectLog,
                    eventLog = logs,
                    onExperimentalExpandedChange = { experimentalControlsExpanded = it },
                    onLogsExpandedChange = { companionLogsExpanded = it },
                    onProtocolExpandedChange = { protocolInfoExpanded = it },
                    onAdvancedExpandedChange = { advancedLabExpanded = it },
                    onActionClick = { preset -> sendSafeControl(preset) },
                    onExportLogClick = { exportCompanionLog() }
                )

                if (advancedLabExpanded) {
            Spacer(modifier = Modifier.height(12.dp))

        Row {
            Button(onClick = { startScan() }, enabled = !isScanning) {
                Text("Scan")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { stopScan() }, enabled = isScanning) {
                Text("Stop Scan")
            }
        }

        val pairedAddressToName = remember(pairedDevices) {
            pairedDevices.associate { it.address to it.name }
        }

        Spacer(modifier = Modifier.height(20.dp))
        PairedDevicesSection(
            pairedDevices = pairedDevices,
            onRefreshClick = { pairedDevicesRefreshTrigger++ },
            onConnectBleClick = { paired -> connectToPairedDevice(paired) },
            onInspectClassicClick = { paired -> inspectClassicUuids(paired) }
        )

        pairedDevices.firstOrNull { it.address == classicInspectedAddress }?.let { paired ->
            Spacer(modifier = Modifier.height(16.dp))
            ClassicDetailsCard(
                paired = paired,
                uuids = classicUuids,
                isFetching = classicFetchInProgress,
                fetchAttempted = classicFetchAttempted,
                canarySocketConnected = canarySocketConnected,
                canaryJavaHandshakeReady = canaryJavaHandshakeReady,
                onSppCanaryConnectClick = { connectSppCanary(paired) },
                onSppCanaryJavaHandshakeClick = { sppCanaryJavaHandshake() },
                onSppCanarySendBatteryClick = { sppCanarySendBatteryMessageBean() },
                onCloseCanarySocketClick = { closeSppCanarySocket() }
            )

            if (classicUuids.any { it.uuid == SPP_UUID }) {
                Spacer(modifier = Modifier.height(16.dp))
                SppConsoleCard(
                    state = sppState,
                    history = sppHistory,
                    sessionStartText = sppSessionStartMillis?.let { formatTimestamp(it) } ?: "—",
                    sessionDurationText = sppDurationText,
                    rxPacketCount = sppRxPacketCount,
                    txPacketCount = sppTxPacketCount,
                    rxByteTotal = sppRxByteTotal,
                    txByteTotal = sppTxByteTotal,
                    initialRxLine = sppInitialRxLine,
                    lastTxLine = sppLastTxLine,
                    lastRxLine = sppLastRxLine,
                    disconnectReason = sppDisconnectReason,
                    rawLogsExpanded = rawLogsExpanded,
                    onRawLogsExpandedChange = { rawLogsExpanded = it },
                    packetNoteInput = packetNoteInput,
                    onPacketNoteInputChange = { packetNoteInput = it },
                    onAddPacketNoteClick = { addPacketNote() },
                    manualSendAcknowledged = manualSendAcknowledged,
                    onManualSendAcknowledgedChange = { manualSendAcknowledged = it },
                    hexInput = hexInput,
                    onHexInputChange = { hexInput = it; hexInputError = null },
                    hexInputError = hexInputError,
                    asciiInput = asciiInput,
                    onAsciiInputChange = { asciiInput = it },
                    onConnectClick = { sppConnect(paired) },
                    onDisconnectClick = { sppDisconnect() },
                    onForceResetClick = { sppForceReset() },
                    onInsecureConnectClick = { sppConnectInsecure(paired) },
                    onSendTestBytesClick = { sppSendTestBytes() },
                    onSendHexClick = { sppSendHex() },
                    onSendAsciiClick = { sppSendAscii() },
                    onCopyLogClick = { copySppLog() },
                    onExportLogClick = { exportSppLog(paired) }
                )

                Spacer(modifier = Modifier.height(16.dp))
                MessageBeanCard(
                    state = sppState,
                    outputStreamReady = javaOutputStreamReady,
                    inputStreamReady = javaInputStreamReady,
                    manualSendAcknowledged = manualSendAcknowledged,
                    onManualSendAcknowledgedChange = { manualSendAcknowledged = it },
                    onConnectWiiWatch2Click = { sppConnectWiiWatch2(paired) },
                    onConnectChannel41Click = { sppConnectChannel41(paired) },
                    showChannel41FailureHint = sppState == SppState.FAILED && lastConnectAttemptWasChannel41,
                    channel41SectionExpanded = channel41SectionExpanded,
                    onChannel41SectionExpandedChange = { channel41SectionExpanded = it },
                    cmd = beanCmd,
                    onCmdChange = { beanCmd = it },
                    trueFalse = beanTrueFalse,
                    onTrueFalseChange = { beanTrueFalse = it },
                    orderText = beanOrderText,
                    onOrderTextChange = { beanOrderText = it },
                    maxValueText = beanMaxValueText,
                    onMaxValueTextChange = { beanMaxValueText = it },
                    currentValueText = beanCurrentValueText,
                    onCurrentValueTextChange = { beanCurrentValueText = it },
                    str = beanStr,
                    onStrChange = { beanStr = it },
                    identifier = beanIdentifier,
                    onIdentifierChange = { beanIdentifier = it },
                    bytesHex = beanBytesHex,
                    onBytesHexChange = { beanBytesHex = it; beanBytesHexError = null },
                    bytesHexError = beanBytesHexError,
                    onSendMessageBeanClick = { sppSendMessageBean() },
                    onSafeControlClick = { preset -> sendSafeControl(preset) },
                    lastCommandResult = lastCommandResult,
                    onRequestSyncSnapshotClick = { requestSyncSnapshot() },
                    watchDashboard = watchDashboard,
                    rxObjectLog = rxObjectLog,
                    txObjectLog = txObjectLog,
                    listenerActive = javaListenerActive,
                    onToggleListenerClick = { sppToggleJavaListener() }
                )

                Spacer(modifier = Modifier.height(16.dp))
                JavaStreamProbeCard(
                    connected = sppState == SppState.CONNECTED,
                    outputStreamReady = javaOutputStreamReady,
                    inputStreamReady = javaInputStreamReady,
                    lastSendResult = lastJavaSendResult,
                    lastReceiveResult = lastJavaReceiveResult,
                    autoHandshakeEnabled = autoJavaHandshakeEnabled,
                    onAutoHandshakeChange = { sppSetAutoJavaHandshake(it) },
                    onProbeClick = { sppStartJavaStreamProbe() },
                    advancedExpanded = javaAdvancedExpanded,
                    onAdvancedExpandedChange = { javaAdvancedExpanded = it },
                    advancedEnabled = javaAdvancedEnabled,
                    onAdvancedEnabledChange = { javaAdvancedEnabled = it },
                    javaUtfInput = javaUtfInput,
                    onJavaUtfInputChange = { javaUtfInput = it },
                    onSendJavaUtfPresetClick = { text -> sppSendJavaUtf(text) },
                    onSendJavaUtfClick = { sppSendJavaUtf(javaUtfInput) },
                    onSendIntClick = { value -> sppSendJavaInt(value) },
                    onSendBooleanClick = { value -> sppSendJavaBoolean(value) },
                    onSendLongClick = { sppSendJavaLong() },
                    manualSendAcknowledged = manualSendAcknowledged,
                    onSendObjectStringClick = { value -> sppSendJavaObjectString(value) },
                    onSendObjectCmdClick = { cmd -> sppSendJavaObjectCmd(cmd) }
                )

                Spacer(modifier = Modifier.height(16.dp))
                HandshakeExperimentsCard(
                    connected = sppState == SppState.CONNECTED,
                    manualSendAcknowledged = manualSendAcknowledged,
                    disconnectAfterSend = disconnectAfterSend,
                    onDisconnectAfterSendChange = { disconnectAfterSend = it },
                    onPresetClick = { preset -> sendHandshakePreset(preset) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Only strong nearby devices", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = strongSignalOnly, onCheckedChange = { strongSignalOnly = it })
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Only connectable devices", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = connectableOnly, onCheckedChange = { connectableOnly = it })
        }

        val visibleDevices = devices.values
            .filter { !strongSignalOnly || it.rssi >= STRONG_SIGNAL_THRESHOLD_DBM }
            .filter { !connectableOnly || it.isConnectable == true }
            .sortedByDescending { it.rssi }

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Found devices (${visibleDevices.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        val busyWithOtherDevice = connectionState != BleConnectionState.DISCONNECTED &&
            connectionState != BleConnectionState.FAILED

        visibleDevices.forEach { device ->
            val isSelected = device.address == selectedDevice?.address
            DeviceRow(
                device = device,
                connectionState = if (isSelected) connectionState else null,
                connectEnabled = !busyWithOtherDevice || isSelected,
                isMarkedWatch = markedWatchAddresses.contains(device.address),
                matchedPairedName = pairedAddressToName[device.address],
                onConnectClick = { connectToDevice(device) },
                onPairClick = { pairDevice(device) },
                onToggleMarkedWatch = { toggleMarkedWatch(device.address) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        selectedDevice?.let { device ->
            Spacer(modifier = Modifier.height(16.dp))
            val pairedConnectFailedHint = connectionSource == "paired" &&
                connectionState == BleConnectionState.FAILED &&
                lastGattStatus == 22
            DeviceDetailCard(
                device = device,
                connectionState = connectionState,
                services = services,
                batteryLevel = batteryLevel,
                heartRateAvailable = heartRateAvailable,
                showClassicSideHint = pairedConnectFailedHint,
                onDisconnectClick = { gattManager.disconnect() }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Event Log", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(text = "No events yet", style = MaterialTheme.typography.bodySmall)
                } else {
                    logs.forEach { line ->
                        Text(text = line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
                }
            }
        }
    }
}

@Composable
private fun CompanionDeviceCard(
    connectionStatus: String,
    lastSyncText: String,
    connectLabel: String,
    connectEnabled: Boolean,
    disconnectEnabled: Boolean,
    refreshBatteryEnabled: Boolean,
    helperMessage: String?,
    debugState: String,
    socketConnected: Boolean,
    objectOutReady: Boolean,
    objectInReady: Boolean,
    objectReaderActive: Boolean,
    canSendCommands: Boolean,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onRefreshBatteryClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = KC10_NAME, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(connectionStatus)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Address: $KC10_ADDRESS", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Last sync time: $lastSyncText", style = MaterialTheme.typography.bodyMedium)
            helperMessage?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "debug: state=$debugState socketConnected=$socketConnected objectOutReady=$objectOutReady " +
                    "objectInReady=$objectInReady objectReaderActive=$objectReaderActive canSendCommands=$canSendCommands",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnectClick, enabled = connectEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(connectLabel)
                }
                Button(onClick = onDisconnectClick, enabled = disconnectEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect")
                }
                Button(onClick = onRefreshBatteryClick, enabled = refreshBatteryEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh Battery")
                }
            }
        }
    }
}

enum class CompanionConnectionState {
    DISCONNECTED,
    DISCONNECTING,
    CONNECTING,
    CONNECTED,
    ERROR
}

private enum class CompanionTab(val label: String, val icon: String) {
    OVERVIEW("Overview", "▱"),
    HEALTH("Health", "♡"),
    DEVICE("Device", "⌬")
}

private enum class HistoryMode(val label: String) {
    BATTERY("Battery"),
    HEART("Heart Rate / Health Value")
}

@Composable
private fun PremiumScaffold(
    selectedTab: CompanionTab,
    onTabSelected: (CompanionTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    GradientBackground(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp)
            ) {
                content()
                Spacer(modifier = Modifier.height(18.dp))
            }
            BottomNavBar(selectedTab = selectedTab, onTabSelected = onTabSelected)
        }
    }
}

@Composable
private fun GradientBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    PremiumBackground,
                    Color(0xFF080B18),
                    Color(0xFF050711)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(PremiumPurple.copy(alpha = 0.26f), Color.Transparent)
                    )
                )
        )
        content()
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = PremiumBorder,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(PremiumCard.copy(alpha = 0.94f), PremiumCardSoft.copy(alpha = 0.86f))
                )
            )
            .border(1.dp, borderColor.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onActionClick: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = PremiumText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(text = action, color = PremiumCyan)
            }
        }
    }
}

@Composable
private fun OverviewTab(
    state: WatchDashboardState,
    connectionStatus: String,
    lastSyncText: String,
    connectLabel: String,
    connectEnabled: Boolean,
    disconnectEnabled: Boolean,
    refreshBatteryEnabled: Boolean,
    helperMessage: String?,
    canSendCommands: Boolean,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onRefreshBatteryClick: () -> Unit
) {
    Text(text = APP_TITLE, color = PremiumText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(text = "Your watch, your health.", color = PremiumMuted, style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(16.dp))
    DeviceHeroCard(
        state = state,
        connectionStatus = connectionStatus,
        lastSyncText = lastSyncText,
        helperMessage = helperMessage
    )
    Spacer(modifier = Modifier.height(14.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (connectionStatus == "Connected") {
            PremiumButton("Disconnect", "⛓", PremiumPurple, disconnectEnabled, Modifier.weight(1f), onDisconnectClick)
        } else {
            PremiumButton(connectLabel, "↻", PremiumPurple, connectEnabled, Modifier.weight(1f), onConnectClick)
        }
        PremiumButton("Refresh Battery", "↻", PremiumBlue, refreshBatteryEnabled, Modifier.weight(1f), onRefreshBatteryClick)
    }
    Spacer(modifier = Modifier.height(18.dp))
    SectionHeader("Overview")
    Spacer(modifier = Modifier.height(10.dp))
    OverviewStatGrid(state)
}

@Composable
private fun DeviceHeroCard(
    state: WatchDashboardState,
    connectionStatus: String,
    lastSyncText: String,
    helperMessage: String?
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (connectionStatus == "Connected") PremiumPurple.copy(alpha = 0.75f) else PremiumBorder
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WatchGlyph(modifier = Modifier.size(106.dp))
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = KC10_NAME, color = PremiumText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (connectionStatus == "Connected") PremiumGreen else PremiumMuted))
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusChip(connectionStatus)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = KC10_ADDRESS, color = PremiumMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                InfoRow("Battery", state.batteryPercentage?.let { "$it%" } ?: "n/a")
                ProgressBar(progress = (state.batteryPercentage ?: 0) / 100f, color = PremiumGreen)
                Spacer(modifier = Modifier.height(12.dp))
                InfoRow("Last sync", lastSyncText)
                helperMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = PremiumOrange, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun WatchGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF272B35), Color(0xFF070A12))))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brush.sweepGradient(listOf(PremiumPurple, PremiumCyan, PremiumRed, PremiumPurple))),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF0A0D18)), contentAlignment = Alignment.Center) {
                Text(text = "KC10", color = PremiumText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OverviewStatGrid(state: WatchDashboardState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Battery", state.batteryPercentage?.toString() ?: "n/a", "%", "▰", PremiumGreen, Modifier.weight(1f), progress = (state.batteryPercentage ?: 0) / 100f)
            StatCard("Steps", state.health.steps ?: "n/a", "steps", "⌁", PremiumBlue, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Distance", state.health.distance ?: "n/a", "km", "◇", PremiumCyan, Modifier.weight(1f))
            StatCard("Calories", state.health.calories ?: "n/a", "kcal", "◆", PremiumOrange, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Moving Target", state.movingTarget ?: "n/a", "steps", "◎", PremiumPurple, Modifier.weight(1f))
            StatCard("Heart Rate / Health Value", state.health.value4 ?: "n/a", "bpm/value", "♡", PremiumRed, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    unit: String,
    icon: String,
    accent: Color,
    modifier: Modifier = Modifier,
    progress: Float? = null
) {
    GlassCard(modifier = modifier.heightIn(min = 122.dp), borderColor = accent.copy(alpha = 0.25f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = icon, color = accent, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, color = PremiumText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = value, color = PremiumText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(5.dp))
                Text(text = unit, color = PremiumMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (progress != null) {
                ProgressBar(progress = progress, color = accent)
            } else {
                SparkBars(accent)
            }
        }
    }
}

@Composable
private fun SparkBars(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(24.dp)) {
        listOf(0.25f, 0.42f, 0.32f, 0.62f, 0.48f, 0.78f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.75f))
            )
        }
    }
}

@Composable
private fun HealthTab(
    state: WatchDashboardState,
    lastHealthSyncText: String,
    batteryHistory: List<HistoryRecord>,
    heartRateHistory: List<HistoryRecord>,
    historyMode: HistoryMode,
    onHistoryModeChange: (HistoryMode) -> Unit
) {
    SectionHeader("Health")
    Spacer(modifier = Modifier.height(10.dp))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HealthMetricRow("⌁", "Steps", state.health.steps ?: "n/a", "steps", PremiumBlue)
            HealthMetricRow("◇", "Distance", state.health.distance ?: "n/a", "km", PremiumCyan)
            HealthMetricRow("◆", "Calories", state.health.calories ?: "n/a", "kcal", PremiumOrange)
            HealthMetricRow("♡", "Heart Rate / Health Value", state.health.value4 ?: "n/a", "bpm/value", PremiumRed)
            HealthMetricRow("◈", "Extra Health Value", state.health.value5 ?: "n/a", "value", PremiumPurple)
            HealthMetricRow("◎", "Moving Target", state.movingTarget ?: "n/a", "steps", PremiumPurple)
            HealthMetricRow("◷", "Last health sync", lastHealthSyncText, "", PremiumMuted)
        }
    }
    Spacer(modifier = Modifier.height(18.dp))
    SectionHeader("Health History")
    Spacer(modifier = Modifier.height(10.dp))
    SegmentedHistorySelector(historyMode = historyMode, onHistoryModeChange = onHistoryModeChange)
    Spacer(modifier = Modifier.height(10.dp))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        val rows = if (historyMode == HistoryMode.BATTERY) batteryHistory else heartRateHistory
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rows.isEmpty()) {
                Text(text = "No history yet", color = PremiumMuted, style = MaterialTheme.typography.bodyMedium)
            } else {
                rows.take(10).forEach { record ->
                    HistoryRow(record = record, accent = if (historyMode == HistoryMode.BATTERY) PremiumGreen else PremiumRed)
                }
            }
        }
    }
}

@Composable
private fun HealthMetricRow(icon: String, label: String, value: String, unit: String, accent: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = icon, color = accent, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(34.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PremiumText, style = MaterialTheme.typography.bodyMedium)
        }
        Text(text = value, color = PremiumText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (unit.isNotBlank()) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(text = unit, color = PremiumMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SegmentedHistorySelector(historyMode: HistoryMode, onHistoryModeChange: (HistoryMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PremiumCardSoft)
            .border(1.dp, PremiumBorder, RoundedCornerShape(18.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HistoryMode.values().forEach { mode ->
            val selected = mode == historyMode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) PremiumPurple else Color.Transparent)
                    .clickable { onHistoryModeChange(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = mode.label, color = if (selected) PremiumText else PremiumMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DeviceTab(
    state: WatchDashboardState,
    connected: Boolean,
    lastCommandResult: String,
    findWatchRinging: Boolean,
    experimentalExpanded: Boolean,
    logsExpanded: Boolean,
    protocolExpanded: Boolean,
    advancedExpanded: Boolean,
    rxObjectLog: List<String>,
    txObjectLog: List<String>,
    eventLog: List<String>,
    onExperimentalExpandedChange: (Boolean) -> Unit,
    onLogsExpandedChange: (Boolean) -> Unit,
    onProtocolExpandedChange: (Boolean) -> Unit,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    onActionClick: (MessageBeanPreset) -> Unit,
    onExportLogClick: () -> Unit
) {
    SectionHeader("Device")
    Spacer(modifier = Modifier.height(10.dp))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Controls Status", color = PremiumText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ToggleTile("Raise to wake", state.raiseToWake, PremiumBlue)
            ToggleTile("Ambient clock", state.ambientClock, PremiumCyan)
            ToggleTile("Watch remind way", state.watchRemindWay, PremiumPurple)
            ToggleTile("Sports mode", state.sportsMode, PremiumGreen)
            ToggleTile("Bluetooth hands-free", state.bluetoothHandsFree, PremiumGreen)
        }
    }
    Spacer(modifier = Modifier.height(14.dp))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(text = "Confirmed Controls", color = PremiumText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = "Last command result: $lastCommandResult", color = PremiumMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(12.dp))
            ActionGrid(
                presets = listOf(
                    SAFE_CONTROL_PRESETS[0],
                    SAFE_CONTROL_PRESETS[1],
                    SAFE_CONTROL_PRESETS[2],
                    SAFE_CONTROL_PRESETS[3],
                    SAFE_CONTROL_PRESETS[4],
                    SAFE_CONTROL_PRESETS[if (state.raiseToWake == true) 6 else 5]
                ),
                enabled = connected,
                onActionClick = onActionClick
            )
            if (findWatchRinging) {
                Spacer(modifier = Modifier.height(10.dp))
                ActionTile(
                    title = "Stop Ring / Find Watch OFF",
                    subtitle = "Stop the current ring",
                    icon = "■",
                    accent = PremiumRed,
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onActionClick(SAFE_CONTROL_PRESETS[1]) }
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(14.dp))
    CollapsedSection(title = "Experimental Controls", expanded = experimentalExpanded, onExpandedChange = onExperimentalExpandedChange) {
        ActionGrid(presets = EXPERIMENTAL_CONTROL_PRESETS, enabled = connected, onActionClick = onActionClick)
    }
    Spacer(modifier = Modifier.height(14.dp))
    DeviceInfoPremiumCard(state.deviceStatistics)
    Spacer(modifier = Modifier.height(14.dp))
    ProtocolInfoPremiumSection(expanded = protocolExpanded, stats = state.deviceStatistics, onExpandedChange = onProtocolExpandedChange)
    Spacer(modifier = Modifier.height(14.dp))
    LogsPremiumSection(
        expanded = logsExpanded,
        rxObjectLog = rxObjectLog,
        txObjectLog = txObjectLog,
        eventLog = eventLog,
        onExpandedChange = onLogsExpandedChange,
        onExportLogClick = onExportLogClick
    )
    Spacer(modifier = Modifier.height(14.dp))
    CollapsedSection(title = "Advanced Lab", expanded = advancedExpanded, onExpandedChange = onAdvancedExpandedChange) {
        Text(text = "Canary tools, BLE scanner, GATT explorer, raw logs, and Channel 41 experimental tools are below.", color = PremiumMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionGrid(presets: List<MessageBeanPreset>, enabled: Boolean, onActionClick: (MessageBeanPreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        presets.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { preset ->
                    ActionTile(
                        title = preset.label,
                        subtitle = preset.cmd,
                        icon = actionIcon(preset.cmd),
                        accent = actionAccent(preset.cmd, preset.trueFalse),
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { onActionClick(preset) }
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun actionIcon(cmd: String): String = when (cmd) {
    "Find the target" -> "◎"
    "Battery" -> "▰"
    "Heart-rate" -> "♡"
    "Raise to wake" -> "◷"
    "Ambient clock" -> "◐"
    "Watch remind way" -> "◇"
    "Sports mode" -> "▵"
    "Bluetooth Hands-free" -> "⌕"
    else -> "•"
}

private fun actionAccent(cmd: String, state: Boolean): Color = when (cmd) {
    "Find the target" -> if (state) PremiumGreen else PremiumRed
    "Battery" -> PremiumBlue
    "Heart-rate" -> if (state) PremiumPurple else PremiumRed
    "Raise to wake" -> PremiumCyan
    "Ambient clock" -> PremiumCyan
    "Watch remind way" -> PremiumPurple
    "Sports mode" -> PremiumGreen
    "Bluetooth Hands-free" -> PremiumGreen
    else -> PremiumPurple
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: String,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.42f
    Box(
        modifier = modifier
            .heightIn(min = 78.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PremiumCardSoft.copy(alpha = alpha))
            .border(1.dp, accent.copy(alpha = if (enabled) 0.28f else 0.12f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, color = accent.copy(alpha = alpha), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = PremiumText.copy(alpha = alpha), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, color = PremiumMuted.copy(alpha = alpha), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ToggleTile(label: String, value: Boolean?, accent: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = PremiumText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (value == true) accent.copy(alpha = 0.18f) else PremiumCardSoft)
                .border(1.dp, if (value == true) accent.copy(alpha = 0.45f) else PremiumBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(text = value?.let(::onOffLabel) ?: "n/a", color = if (value == true) accent else PremiumMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DeviceInfoPremiumCard(stats: DeviceStatisticsSnapshot) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(text = "Device Info", color = PremiumText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            InfoRow("Device ID", stats.deviceId ?: "n/a")
            InfoRow("Model code", stats.modelCode ?: "n/a")
            InfoRow("Device name", stats.deviceName ?: KC10_NAME)
            InfoRow("Brand / code", stats.brandCode ?: "n/a")
            InfoRow("Firmware", stats.firmware ?: "n/a")
            InfoRow("Android version", stats.androidVersion ?: "n/a")
            InfoRow("Build info", stats.buildInfo ?: "n/a")
            InfoRow("Bluetooth address", KC10_ADDRESS)
        }
    }
}

@Composable
private fun ProtocolInfoPremiumSection(
    expanded: Boolean,
    stats: DeviceStatisticsSnapshot,
    onExpandedChange: (Boolean) -> Unit
) {
    CollapsedSection(title = "Protocol Info", expanded = expanded, onExpandedChange = onExpandedChange) {
        InfoRow("Transport", "Classic Bluetooth SPP")
        InfoRow("UUID", SPP_UUID.toString())
        InfoRow("Stream", "Java ObjectStream")
        InfoRow("Class", "com.wiitetech.WiiWatchPro.bluetoothutil.MessageBean")
        InfoRow("serialVersionUID", "12141117")
        InfoRow("Firmware", stats.firmware ?: "n/a")
        InfoRow("Android version", stats.androidVersion ?: "n/a")
    }
}

@Composable
private fun LogsPremiumSection(
    expanded: Boolean,
    rxObjectLog: List<String>,
    txObjectLog: List<String>,
    eventLog: List<String>,
    onExpandedChange: (Boolean) -> Unit,
    onExportLogClick: () -> Unit
) {
    CollapsedSection(title = "Advanced / Logs", expanded = expanded, onExpandedChange = onExpandedChange) {
        PremiumButton("Export Debug Bundle", "⇩", PremiumPurple, true, Modifier.fillMaxWidth(), onExportLogClick)
        Spacer(modifier = Modifier.height(12.dp))
        ObjectLogSection(title = "RX Object Log", lines = rxObjectLog)
        Spacer(modifier = Modifier.height(8.dp))
        ObjectLogSection(title = "TX Object Log", lines = txObjectLog)
        Spacer(modifier = Modifier.height(8.dp))
        ObjectLogSection(title = "Event Log", lines = eventLog)
    }
}

@Composable
private fun CollapsedSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, color = PremiumText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(text = if (expanded) "Hide" else "Show", color = PremiumCyan)
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(text = label, color = PremiumMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.42f))
        Text(
            text = value,
            color = PremiumText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.58f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProgressBar(progress: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
    }
}

@Composable
private fun PremiumButton(
    label: String,
    icon: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 58.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.95f),
            contentColor = PremiumText,
            disabledContainerColor = PremiumCardSoft.copy(alpha = 0.78f),
            disabledContentColor = PremiumMuted.copy(alpha = 0.72f)
        )
    ) {
        Text(text = "$icon  $label", maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HistoryRow(record: HistoryRecord, accent: Color) {
    val percent = record.value.filter { it.isDigit() }.toFloatOrNull()?.div(100f)?.coerceIn(0.08f, 1f) ?: 0.55f
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = formatTimestamp(record.timestampMillis), color = PremiumMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.06f))
        ) {
            Box(modifier = Modifier.fillMaxWidth(percent).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(accent.copy(alpha = 0.75f)))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = record.value, color = PremiumText, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BottomNavBar(selectedTab: CompanionTab, onTabSelected: (CompanionTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PremiumCard.copy(alpha = 0.96f))
            .border(1.dp, PremiumBorder.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompanionTab.values().forEach { tab ->
            val selected = tab == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) PremiumPurple.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = tab.icon, color = if (selected) PremiumPurple else PremiumMuted, style = MaterialTheme.typography.titleMedium)
                    Text(text = tab.label, color = if (selected) PremiumText else PremiumMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val accent = when (status) {
        "Connected" -> PremiumGreen
        "Connecting" -> PremiumCyan
        "Error" -> PremiumRed
        else -> PremiumMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.34f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = status, color = accent, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CompanionDashboardCards(
    state: WatchDashboardState,
    lastHealthSyncText: String,
    batteryHistory: List<HistoryRecord>,
    heartRateHistory: List<HistoryRecord>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Dashboard", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Battery", state.batteryPercentage?.let { "$it%" }, Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("Steps", state.health.steps, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Distance", state.health.distance, Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("Calories", state.health.calories, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Moving target", state.movingTarget, Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard("Heart Rate / Health Value", state.health.value4, Modifier.weight(1f), subtitle = "from health value 4")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard("Extra Health Value", state.health.value5, Modifier.weight(1f), subtitle = "unknown")
            Spacer(modifier = Modifier.width(8.dp))
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(text = "Health", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                DashboardLine("Steps", state.health.steps)
                DashboardLine("Distance", state.health.distance)
                DashboardLine("Calories", state.health.calories)
                DashboardLine("Heart Rate / Health Value", state.health.value4)
                DashboardLine("Extra Health Value", state.health.value5)
                DashboardLine("Moving Target", state.movingTarget)
                DashboardLine("Last health sync", lastHealthSyncText)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(text = "History", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(6.dp))
                HistoryList(title = "Battery history", records = batteryHistory)
                Spacer(modifier = Modifier.height(8.dp))
                HistoryList(title = "Heart rate / health value history", records = heartRateHistory)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(text = "Controls Status", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                DashboardLine("Raise to wake", state.raiseToWake?.let(::onOffLabel))
                DashboardLine("Ambient clock", state.ambientClock?.let(::onOffLabel))
                DashboardLine("Watch remind way", state.watchRemindWay?.let(::onOffLabel))
                DashboardLine("Sports mode", state.sportsMode?.let(::onOffLabel))
                DashboardLine("Bluetooth hands-free", state.bluetoothHandsFree?.let(::onOffLabel))
            }
        }
    }
}

@Composable
private fun HistoryList(title: String, records: List<HistoryRecord>) {
    Text(text = title, style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(2.dp))
    if (records.isEmpty()) {
        Text(
            text = "No records yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
            records.take(50).forEach { record ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatTimestamp(record.timestampMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = record.value, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String?, modifier: Modifier = Modifier, subtitle: String? = null) {
    Card(modifier = modifier.heightIn(min = 82.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value ?: "n/a", style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompanionDeviceInfoCard(stats: DeviceStatisticsSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "Device Info", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            DashboardLine("Device ID", stats.deviceId)
            DashboardLine("Model code", stats.modelCode)
            DashboardLine("Device name", stats.deviceName)
            DashboardLine("Brand/code", stats.brandCode)
            DashboardLine("Firmware", stats.firmware)
            DashboardLine("Android version", stats.androidVersion)
            DashboardLine("Build info", stats.buildInfo)
        }
    }
}

@Composable
private fun QuickActionsCard(
    connected: Boolean,
    lastCommandResult: String,
    findWatchRinging: Boolean,
    experimentalExpanded: Boolean,
    onExperimentalExpandedChange: (Boolean) -> Unit,
    onActionClick: (MessageBeanPreset) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "Controls", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "These commands are decoded from WiiWatch2 protocol. Use simple controls first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Last command result: $lastCommandResult", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Confirmed Controls", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            ActionRow(SAFE_CONTROL_PRESETS[0], SAFE_CONTROL_PRESETS[1], connected, onActionClick)
            if (findWatchRinging) {
                Button(
                    onClick = { onActionClick(SAFE_CONTROL_PRESETS[1]) },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop Ring / Find Watch OFF")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            ActionRow(SAFE_CONTROL_PRESETS[2], null, connected, onActionClick)
            ActionRow(SAFE_CONTROL_PRESETS[3], SAFE_CONTROL_PRESETS[4], connected, onActionClick)
            ActionRow(SAFE_CONTROL_PRESETS[5], SAFE_CONTROL_PRESETS[6], connected, onActionClick)

            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = { onExperimentalExpandedChange(!experimentalExpanded) }) {
                Text(if (experimentalExpanded) "Hide Experimental Controls" else "Show Experimental Controls")
            }
            if (experimentalExpanded) {
                ActionRow(EXPERIMENTAL_CONTROL_PRESETS[0], EXPERIMENTAL_CONTROL_PRESETS[1], connected, onActionClick)
                ActionRow(EXPERIMENTAL_CONTROL_PRESETS[2], EXPERIMENTAL_CONTROL_PRESETS[3], connected, onActionClick)
                ActionRow(EXPERIMENTAL_CONTROL_PRESETS[4], EXPERIMENTAL_CONTROL_PRESETS[5], connected, onActionClick)
                ActionRow(EXPERIMENTAL_CONTROL_PRESETS[6], EXPERIMENTAL_CONTROL_PRESETS[7], connected, onActionClick)
            }
        }
    }
}

@Composable
private fun ActionRow(
    first: MessageBeanPreset,
    second: MessageBeanPreset?,
    enabled: Boolean,
    onActionClick: (MessageBeanPreset) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onActionClick(first) },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(first.label)
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (second != null) {
            Button(
                onClick = { onActionClick(second) },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(second.label)
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ProtocolInfoSection(
    expanded: Boolean,
    stats: DeviceStatisticsSnapshot,
    onExpandedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Protocol Info", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            if (expanded) {
                DashboardLine("SPP UUID", SPP_UUID.toString())
                DashboardLine("Java ObjectStream", "ObjectOutputStream + ObjectInputStream")
                DashboardLine("MessageBean class", "com.wiitetech.WiiWatchPro.bluetoothutil.MessageBean")
                DashboardLine("serialVersionUID", "12141117")
                DashboardLine("Firmware", stats.firmware)
                DashboardLine("Android version", stats.androidVersion)
            }
        }
    }
}

@Composable
private fun CompanionLogsSection(
    expanded: Boolean,
    rxObjectLog: List<String>,
    txObjectLog: List<String>,
    eventLog: List<String>,
    onExpandedChange: (Boolean) -> Unit,
    onExportLogClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Logs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            if (expanded) {
                Button(onClick = onExportLogClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Export Debug Bundle")
                }
                Spacer(modifier = Modifier.height(8.dp))
                ObjectLogSection(title = "RX Object Log", lines = rxObjectLog)
                Spacer(modifier = Modifier.height(8.dp))
                ObjectLogSection(title = "TX Object Log", lines = txObjectLog)
                Spacer(modifier = Modifier.height(8.dp))
                ObjectLogSection(title = "Event Log", lines = eventLog)
            }
        }
    }
}

@Composable
fun PairedDevicesSection(
    pairedDevices: List<PairedDevice>,
    onRefreshClick: () -> Unit,
    onConnectBleClick: (PairedDevice) -> Unit,
    onInspectClassicClick: (PairedDevice) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Paired Bluetooth Devices (${pairedDevices.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRefreshClick) {
            Text("Refresh")
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Some watches use Classic Bluetooth for calls and BLE for health data. " +
            "The paired name may differ from the BLE scan name.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Dual devices may expose a Classic Bluetooth identity and a separate BLE " +
            "identity. If direct paired-device connection fails, use the strongest " +
            "connectable BLE scan result.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (pairedDevices.isEmpty()) {
        Text(text = "No paired devices found", style = MaterialTheme.typography.bodySmall)
    } else {
        pairedDevices.forEach { paired ->
            PairedDeviceRow(
                paired,
                onConnectBleClick = { onConnectBleClick(paired) },
                onInspectClassicClick = { onInspectClassicClick(paired) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PairedDeviceRow(
    paired: PairedDevice,
    onConnectBleClick: () -> Unit,
    onInspectClassicClick: () -> Unit
) {
    val looksLikeWatch = looksLikeWatchName(paired.name)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (looksLikeWatch) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = paired.name, style = MaterialTheme.typography.bodyLarge)
                if (looksLikeWatch) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Possible watch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(text = paired.address, style = MaterialTheme.typography.bodySmall)
            Text(text = "Bond: ${paired.bondStateLabel}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Type: ${paired.typeLabel}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                // Classic-only devices (e.g. TWS earbuds) have no BLE identity to connect to.
                if (paired.isBleConnectable) {
                    Button(onClick = onConnectBleClick) {
                        Text("Connect BLE")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(onClick = onInspectClassicClick) {
                    Text("Inspect Classic UUIDs")
                }
            }
        }
    }
}

@Composable
fun ClassicDetailsCard(
    paired: PairedDevice,
    uuids: List<ClassicUuidInfo>,
    isFetching: Boolean,
    fetchAttempted: Boolean,
    canarySocketConnected: Boolean,
    canaryJavaHandshakeReady: Boolean,
    onSppCanaryConnectClick: () -> Unit,
    onSppCanaryJavaHandshakeClick: () -> Unit,
    onSppCanarySendBatteryClick: () -> Unit,
    onCloseCanarySocketClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "Classic Bluetooth Details", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = paired.name, style = MaterialTheme.typography.bodyLarge)
            Text(text = paired.address, style = MaterialTheme.typography.bodySmall)
            Text(text = "Bond: ${paired.bondStateLabel}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Type: ${paired.typeLabel}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))

            if (uuids.isNotEmpty()) {
                Text(text = "UUIDs (${uuids.size})", style = MaterialTheme.typography.titleSmall)
                uuids.forEach { info ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = info.uuid.toString(), style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = info.friendlyName ?: "Unrecognized profile",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                val hasSpp = uuids.any { it.uuid == SPP_UUID }
                if (hasSpp) {
                    Text(
                        text = "SPP available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(text = "No SPP profile found", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                val emptyStateText = when {
                    isFetching -> "Fetching UUIDs..."
                    fetchAttempted -> "No UUIDs returned"
                    else -> "No UUIDs fetched yet"
                }
                Text(text = emptyStateText, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "SPP Canary", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Socket: ${if (canarySocketConnected) "Connected" else "Not connected"}  " +
                    "Java header: ${if (canaryJavaHandshakeReady) "Sent" else "Not sent"}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSppCanaryConnectClick,
                    enabled = !canarySocketConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SPP Canary Connect")
                }
                Button(
                    onClick = onCloseCanarySocketClick,
                    enabled = canarySocketConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close Canary Socket")
                }
                Button(
                    onClick = onSppCanaryJavaHandshakeClick,
                    enabled = canarySocketConnected && !canaryJavaHandshakeReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SPP Canary Java Handshake")
                }
                Button(
                    onClick = onSppCanarySendBatteryClick,
                    enabled = canaryJavaHandshakeReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SPP Canary Send MessageBean Battery")
                }
            }
        }
    }
}

@Composable
fun SppConsoleCard(
    state: SppState,
    history: List<String>,
    sessionStartText: String,
    sessionDurationText: String,
    rxPacketCount: Int,
    txPacketCount: Int,
    rxByteTotal: Long,
    txByteTotal: Long,
    initialRxLine: String?,
    lastTxLine: String?,
    lastRxLine: String?,
    disconnectReason: String?,
    rawLogsExpanded: Boolean,
    onRawLogsExpandedChange: (Boolean) -> Unit,
    packetNoteInput: String,
    onPacketNoteInputChange: (String) -> Unit,
    onAddPacketNoteClick: () -> Unit,
    manualSendAcknowledged: Boolean,
    onManualSendAcknowledgedChange: (Boolean) -> Unit,
    hexInput: String,
    onHexInputChange: (String) -> Unit,
    hexInputError: String?,
    asciiInput: String,
    onAsciiInputChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onForceResetClick: () -> Unit,
    onInsecureConnectClick: () -> Unit,
    onSendTestBytesClick: () -> Unit,
    onSendHexClick: () -> Unit,
    onSendAsciiClick: () -> Unit,
    onCopyLogClick: () -> Unit,
    onExportLogClick: () -> Unit
) {
    val connected = state == SppState.CONNECTED
    val manualSendEnabled = connected && manualSendAcknowledged
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "SPP Test Console", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Status: ${sppStateLabel(state)}", color = sppStateColor(state))

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(
                    onClick = onConnectClick,
                    enabled = state != SppState.CONNECTING && state != SppState.CONNECTED
                ) {
                    Text("Passive Connect")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onDisconnectClick,
                    enabled = state == SppState.CONNECTED || state == SppState.CONNECTING
                ) {
                    Text("Disconnect SPP")
                }
            }
            Text(
                text = "Passive Connect only opens the link and reads incoming bytes; it never sends anything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(onClick = onForceResetClick) {
                    Text("Force Reset SPP")
                }
                if (state == SppState.FAILED) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onInsecureConnectClick) {
                        Text("Try insecure SPP Connect")
                    }
                }
            }
            if (state == SppState.FAILED) {
                Text(
                    text = "Manual fallback only - uses createInsecureRfcommSocketToServiceRecord().",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Session", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Start: $sessionStartText  •  Duration: $sessionDurationText", style = MaterialTheme.typography.bodySmall)
            Text(text = "RX: $rxPacketCount packets, $rxByteTotal bytes", style = MaterialTheme.typography.bodySmall)
            Text(text = "TX: $txPacketCount packets, $txByteTotal bytes", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Session Summary", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Initial RX: ${initialRxLine ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Last TX: ${lastTxLine ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Last RX: ${lastRxLine ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Disconnect reason: ${disconnectReason ?: "—"}", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = packetNoteInput,
                onValueChange = onPacketNoteInputChange,
                label = { Text("Note on latest RX/TX packet") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onAddPacketNoteClick, enabled = packetNoteInput.isNotBlank()) {
                Text("Mark Packet Note")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(onClick = onCopyLogClick) {
                    Text("Copy Log")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onExportLogClick) {
                    Text("Export SPP Log")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Unknown commands may disconnect the watch. Prefer passive logging first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = manualSendAcknowledged, onCheckedChange = onManualSendAcknowledgedChange)
                Text(text = "I understand", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onSendTestBytesClick, enabled = manualSendEnabled) {
                Text("Send Test Bytes (0D, 0A, \"AT\\r\\n\")")
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = hexInput,
                onValueChange = onHexInputChange,
                label = { Text("HEX bytes, e.g. 41 54 0D 0A") },
                isError = hexInputError != null,
                modifier = Modifier.fillMaxWidth()
            )
            hexInputError?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onSendHexClick, enabled = manualSendEnabled) {
                Text("Send HEX")
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = asciiInput,
                onValueChange = onAsciiInputChange,
                label = { Text("ASCII text") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onSendAsciiClick, enabled = manualSendEnabled) {
                Text("Send ASCII")
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { onRawLogsExpandedChange(!rawLogsExpanded) }) {
                Text(if (rawLogsExpanded) "Hide Advanced Raw HEX Logs" else "Show Advanced Raw HEX Logs")
            }
            if (rawLogsExpanded) {
                Text(text = "Raw HEX Logs", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                if (history.isEmpty()) {
                    Text(text = "No raw packets yet", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        history.forEach { line ->
                            Text(text = line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBeanCard(
    state: SppState,
    outputStreamReady: Boolean,
    inputStreamReady: Boolean,
    manualSendAcknowledged: Boolean,
    onManualSendAcknowledgedChange: (Boolean) -> Unit,
    onConnectWiiWatch2Click: () -> Unit,
    onConnectChannel41Click: () -> Unit,
    showChannel41FailureHint: Boolean,
    channel41SectionExpanded: Boolean,
    onChannel41SectionExpandedChange: (Boolean) -> Unit,
    cmd: String,
    onCmdChange: (String) -> Unit,
    trueFalse: Boolean,
    onTrueFalseChange: (Boolean) -> Unit,
    orderText: String,
    onOrderTextChange: (String) -> Unit,
    maxValueText: String,
    onMaxValueTextChange: (String) -> Unit,
    currentValueText: String,
    onCurrentValueTextChange: (String) -> Unit,
    str: String,
    onStrChange: (String) -> Unit,
    identifier: String,
    onIdentifierChange: (String) -> Unit,
    bytesHex: String,
    onBytesHexChange: (String) -> Unit,
    bytesHexError: String?,
    onSendMessageBeanClick: () -> Unit,
    onSafeControlClick: (MessageBeanPreset) -> Unit,
    lastCommandResult: String,
    onRequestSyncSnapshotClick: () -> Unit,
    watchDashboard: WatchDashboardState,
    rxObjectLog: List<String>,
    txObjectLog: List<String>,
    listenerActive: Boolean,
    onToggleListenerClick: () -> Unit
) {
    val connected = state == SppState.CONNECTED
    val notBusy = state != SppState.CONNECTING && state != SppState.CONNECTED
    val sendEnabled = connected && outputStreamReady && manualSendAcknowledged
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "WiiWatch2 Protocol (MessageBean)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Reconstructed from the WiiWatch2 APK: exchanges " +
                    "com.wiitetech.WiiWatchPro.bluetoothutil.MessageBean objects instead of ad-hoc bytes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = WIIWATCH2_SAFETY_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recommended flow: 1) Force Reset SPP (above)  2) Connect WiiWatch2 Protocol  " +
                    "3) Send Battery  4) Send Find Watch ON/OFF",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onConnectWiiWatch2Click, enabled = notBusy) {
                Text("Connect WiiWatch2 Protocol")
            }
            Text(
                text = "Primary (and only) transport for this protocol: normal SPP UUID connect " +
                    "(00001101-0000-1000-8000-00805f9b34fb) - never createRfcommSocket(41). Creates " +
                    "ObjectOutputStream first, flushes, then ObjectInputStream(BufferedInputStream(...)).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = "ObjectOutputStream ready: ${if (outputStreamReady) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "ObjectInputStream ready: ${if (inputStreamReady) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Object reader active: ${if (listenerActive) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(12.dp))
            WatchDashboardCard(watchDashboard)

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Safe Controls", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "These commands are decoded from WiiWatch2 protocol. Use simple controls first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Last command result: $lastCommandResult", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = manualSendAcknowledged, onCheckedChange = onManualSendAcknowledgedChange)
                Text(text = "I understand", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(4.dp))
            SAFE_CONTROL_PRESETS.forEach { preset ->
                Button(
                    onClick = { onSafeControlClick(preset) },
                    enabled = sendEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(preset.label)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            Button(
                onClick = onRequestSyncSnapshotClick,
                enabled = sendEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request Sync Snapshot")
            }
            Text(
                text = "Sends only confirmed safe snapshot requests: Battery, Health data, and Device statistics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { onChannel41SectionExpandedChange(!channel41SectionExpanded) }) {
                Text(if (channel41SectionExpanded) "Hide Advanced / Experimental" else "Show Advanced / Experimental")
            }
            if (channel41SectionExpanded) {
                Button(onClick = onConnectChannel41Click, enabled = notBusy) {
                    Text("Experimental Channel 41")
                }
                Text(
                    text = "Uses BluetoothDevice.createRfcommSocket(41) via reflection (no SDP/UUID lookup). " +
                        "Currently fails with InvocationTargetException on this device - not the default, " +
                        "kept only for testing on other watches. Never used by Connect WiiWatch2 Protocol above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showChannel41FailureHint) {
                    Text(
                        text = "Channel 41 failed; use normal SPP UUID connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Send MessageBean", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(value = cmd, onValueChange = onCmdChange, label = { Text("cmd") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "true_false", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = trueFalse, onCheckedChange = onTrueFalseChange)
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = orderText,
                onValueChange = onOrderTextChange,
                label = { Text("order (default -1)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = maxValueText,
                onValueChange = onMaxValueTextChange,
                label = { Text("maxValue (default -1)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = currentValueText,
                onValueChange = onCurrentValueTextChange,
                label = { Text("currentValue (default -1)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(value = str, onValueChange = onStrChange, label = { Text("str") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = identifier,
                onValueChange = onIdentifierChange,
                label = { Text("identifier") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = bytesHex,
                onValueChange = onBytesHexChange,
                label = { Text("bytes (HEX, optional)") },
                isError = bytesHexError != null,
                modifier = Modifier.fillMaxWidth()
            )
            bytesHexError?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onSendMessageBeanClick, enabled = sendEnabled) {
                Text("Send MessageBean")
            }
            Text(
                text = "flush() -> writeObject(bean) -> flush(), matching WiiWatch2's exact send sequence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "The object reader starts automatically after ObjectInputStream is ready. Use Stop only if you need to pause readObject() for debugging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onToggleListenerClick, enabled = connected && inputStreamReady) {
                Text(if (listenerActive) "Stop Object Reader" else "Start Object Reader")
            }
            Spacer(modifier = Modifier.height(8.dp))
            ObjectLogSection(title = "RX Object Log", lines = rxObjectLog)
            Spacer(modifier = Modifier.height(8.dp))
            ObjectLogSection(title = "TX Object Log", lines = txObjectLog)
        }
    }
}

@Composable
private fun WatchDashboardCard(state: WatchDashboardState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Watch Dashboard", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        DashboardLine("Battery", state.batteryPercentage?.let { "$it%" })
        DashboardLine("Steps", state.health.steps)
        DashboardLine("Distance", state.health.distance)
        DashboardLine("Calories", state.health.calories)
        DashboardLine("Health value 4", state.health.value4)
        DashboardLine("Health value 5", state.health.value5)
        DashboardLine("Moving target", state.movingTarget)
        DashboardLine("Raise to wake", state.raiseToWake?.let(::onOffLabel))
        DashboardLine("Ambient clock", state.ambientClock?.let(::onOffLabel))
        DashboardLine("Watch remind way", state.watchRemindWay?.let(::onOffLabel))
        DashboardLine("Sports mode", state.sportsMode?.let(::onOffLabel))
        DashboardLine("Bluetooth hands-free", state.bluetoothHandsFree?.let(::onOffLabel))

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Device Statistics", style = MaterialTheme.typography.titleSmall)
        DashboardLine("deviceId", state.deviceStatistics.deviceId)
        DashboardLine("modelCode", state.deviceStatistics.modelCode)
        DashboardLine("deviceName", state.deviceStatistics.deviceName)
        DashboardLine("brandCode", state.deviceStatistics.brandCode)
        DashboardLine("firmware", state.deviceStatistics.firmware)
        DashboardLine("androidVersion", state.deviceStatistics.androidVersion)
        DashboardLine("unknownField", state.deviceStatistics.unknownField)
        DashboardLine("buildInfo", state.deviceStatistics.buildInfo)
    }
}

@Composable
private fun DashboardLine(label: String, value: String?) {
    Text(text = "$label: ${value ?: "n/a"}", style = MaterialTheme.typography.bodySmall)
}

private fun onOffLabel(value: Boolean): String = if (value) "On" else "Off"

@Composable
private fun ObjectLogSection(title: String, lines: List<String>) {
    Text(text = title, style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(4.dp))
    if (lines.isEmpty()) {
        Text(text = "No objects yet", style = MaterialTheme.typography.bodySmall)
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
        ) {
            lines.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun JavaStreamProbeCard(
    connected: Boolean,
    outputStreamReady: Boolean,
    inputStreamReady: Boolean,
    lastSendResult: String?,
    lastReceiveResult: String?,
    autoHandshakeEnabled: Boolean,
    onAutoHandshakeChange: (Boolean) -> Unit,
    onProbeClick: () -> Unit,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    advancedEnabled: Boolean,
    onAdvancedEnabledChange: (Boolean) -> Unit,
    javaUtfInput: String,
    onJavaUtfInputChange: (String) -> Unit,
    onSendJavaUtfPresetClick: (String) -> Unit,
    onSendJavaUtfClick: () -> Unit,
    onSendIntClick: (Int) -> Unit,
    onSendBooleanClick: (Boolean) -> Unit,
    onSendLongClick: () -> Unit,
    manualSendAcknowledged: Boolean,
    onSendObjectStringClick: (String) -> Unit,
    onSendObjectCmdClick: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "Java ObjectStream Probe (generic)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Low-level ObjectOutputStream/ObjectInputStream connect for the AC ED 00 05 " +
                    "handshake over the standard SPP UUID. For the confirmed WiiWatch2 protocol, use " +
                    "the MessageBean card above instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "ObjectOutputStream ready: ${if (outputStreamReady) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "ObjectInputStream ready: ${if (inputStreamReady) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Last Java send result: ${lastSendResult ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Last Java receive result: ${lastReceiveResult ?: "—"}", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onProbeClick, enabled = connected) {
                Text("Java Stream Probe")
            }
            Text(
                text = "Creates ObjectOutputStream, flushes the AC ED 00 05 header, then creates " +
                    "ObjectInputStream once the passive read loop safely steps aside.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Auto Java Handshake on Connect", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = autoHandshakeEnabled, onCheckedChange = onAutoHandshakeChange)
            }
            Text(
                text = "When enabled, the stream header is sent immediately after the next " +
                    "successful SPP connect, before the watch can time out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { onAdvancedExpandedChange(!advancedExpanded) }) {
                Text(if (advancedExpanded) "Hide Advanced (writeUTF / primitives / raw writeObject)" else "Show Advanced (writeUTF / primitives / raw writeObject)")
            }

            if (advancedExpanded) {
                Text(
                    text = "Unverified exploratory sends - off by default and not part of the " +
                        "confirmed WiiWatch2 MessageBean protocol.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = advancedEnabled, onCheckedChange = onAdvancedEnabledChange)
                    Text(text = "Enable advanced Java tests", style = MaterialTheme.typography.bodyMedium)
                }

                val advancedButtonsEnabled = connected && outputStreamReady && advancedEnabled

                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Java UTF Presets", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                JAVA_UTF_PRESETS.forEach { preset ->
                    Button(
                        onClick = { onSendJavaUtfPresetClick(preset) },
                        enabled = advancedButtonsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("writeUTF(\"$preset\")")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = javaUtfInput,
                    onValueChange = onJavaUtfInputChange,
                    label = { Text("Custom UTF string") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onSendJavaUtfClick,
                    enabled = advancedButtonsEnabled && javaUtfInput.isNotEmpty()
                ) {
                    Text("Send Java UTF")
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Java Primitives", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Button(onClick = { onSendIntClick(0) }, enabled = advancedButtonsEnabled) {
                        Text("writeInt(0)")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onSendIntClick(1) }, enabled = advancedButtonsEnabled) {
                        Text("writeInt(1)")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Button(onClick = { onSendBooleanClick(true) }, enabled = advancedButtonsEnabled) {
                        Text("writeBoolean(true)")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onSendBooleanClick(false) }, enabled = advancedButtonsEnabled) {
                        Text("writeBoolean(false)")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = onSendLongClick, enabled = advancedButtonsEnabled) {
                    Text("writeLong(currentTimeMillis)")
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Java Objects (raw String/HashMap - not MessageBean)", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                val objectsEnabled = advancedButtonsEnabled && manualSendAcknowledged
                Button(onClick = { onSendObjectStringClick("ping") }, enabled = objectsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("writeObject(\"ping\")")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { onSendObjectStringClick("hello") }, enabled = objectsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("writeObject(\"hello\")")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { onSendObjectCmdClick("ping") }, enabled = objectsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("writeObject({cmd=ping})")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { onSendObjectCmdClick("battery") }, enabled = objectsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("writeObject({cmd=battery})")
                }
            }
        }
    }
}

@Composable
private fun HandshakeExperimentsCard(
    connected: Boolean,
    manualSendAcknowledged: Boolean,
    disconnectAfterSend: Boolean,
    onDisconnectAfterSendChange: (Boolean) -> Unit,
    onPresetClick: (HandshakePreset) -> Unit
) {
    val presetsEnabled = connected && manualSendAcknowledged
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = "Handshake Experiments", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (connected) {
                    "Send one preset probe at a time and watch for an RX response. Nothing repeats automatically."
                } else {
                    "Connect via SPP (Passive Connect above) to enable these presets."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Disconnect after send", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = disconnectAfterSend, onCheckedChange = onDisconnectAfterSendChange)
            }

            Spacer(modifier = Modifier.height(8.dp))
            HANDSHAKE_PRESETS.forEach { preset ->
                Button(
                    onClick = { onPresetClick(preset) },
                    enabled = presetsEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(preset.label)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

private fun sppStateLabel(state: SppState): String = when (state) {
    SppState.IDLE -> "Idle"
    SppState.CONNECTING -> "Connecting"
    SppState.CONNECTED -> "Connected"
    SppState.FAILED -> "Failed"
    SppState.DISCONNECTED -> "Disconnected"
}

private fun companionConnectionStateLabel(state: CompanionConnectionState): String = when (state) {
    CompanionConnectionState.DISCONNECTED -> "Disconnected"
    CompanionConnectionState.DISCONNECTING -> "Disconnecting"
    CompanionConnectionState.CONNECTING -> "Connecting"
    CompanionConnectionState.CONNECTED -> "Connected"
    CompanionConnectionState.ERROR -> "Error"
}

@Composable
private fun sppStateColor(state: SppState) = when (state) {
    SppState.CONNECTED -> MaterialTheme.colorScheme.primary
    SppState.FAILED -> MaterialTheme.colorScheme.error
    SppState.IDLE, SppState.CONNECTING, SppState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun DeviceRow(
    device: BleDevice,
    connectionState: BleConnectionState?,
    connectEnabled: Boolean,
    isMarkedWatch: Boolean,
    matchedPairedName: String?,
    onConnectClick: () -> Unit,
    onPairClick: () -> Unit,
    onToggleMarkedWatch: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = device.displayName, style = MaterialTheme.typography.bodyLarge)
                    if (isMarkedWatch) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Marked Watch",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (isLikelyWatchCandidate(device)) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Likely watch candidate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(text = device.address, style = MaterialTheme.typography.bodySmall)
                Text(text = "RSSI: ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                Text(text = "Connectable: ${connectableLabel(device.isConnectable)}", style = MaterialTheme.typography.bodySmall)
                if (device.isConnectable == false) {
                    Text(
                        text = "Advertisement only — cannot connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (device.advertisedName != null && device.advertisedName != device.displayName) {
                    Text(text = "Advertised: ${device.advertisedName}", style = MaterialTheme.typography.bodySmall)
                }
                if (device.bluetoothName != null && device.bluetoothName != device.displayName) {
                    Text(text = "Bluetooth name: ${device.bluetoothName}", style = MaterialTheme.typography.bodySmall)
                }
                Text(text = "Bond: ${device.bondStateLabel}", style = MaterialTheme.typography.bodySmall)
                if (matchedPairedName != null) {
                    Text(
                        text = "Matches paired device: $matchedPairedName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (device.serviceUuids.isNotEmpty()) {
                    Text(
                        text = "Services: ${shortenUuidList(device.serviceUuids)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                device.manufacturerDataHex?.let { hex ->
                    Text(text = "Mfr data: ${shortenHex(hex)}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onToggleMarkedWatch, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        text = if (isMarkedWatch) "Unmark Watch" else "Mark as Watch",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // Non-connectable advertisements have nothing to Pair or Connect to.
            if (device.isConnectable != false) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (device.bondStateLabel == "Not bonded") {
                        Button(onClick = onPairClick) {
                            Text("Pair")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Button(
                        onClick = onConnectClick,
                        enabled = connectEnabled &&
                            connectionState != BleConnectionState.CONNECTED &&
                            connectionState != BleConnectionState.CONNECTING
                    ) {
                        Text(connectButtonLabel(connectionState))
                    }
                }
            }
        }
    }
}

private fun connectableLabel(isConnectable: Boolean?): String = when (isConnectable) {
    true -> "Yes"
    false -> "No"
    null -> "Unknown"
}

private const val LIKELY_WATCH_RSSI_THRESHOLD_DBM = -70

private fun isLikelyWatchCandidate(device: BleDevice): Boolean =
    device.rssi >= LIKELY_WATCH_RSSI_THRESHOLD_DBM &&
        device.isConnectable != false &&
        (device.manufacturerDataHex != null || device.serviceUuids.isNotEmpty())

private const val MAX_SERVICE_UUIDS_SHOWN = 2
private const val MAX_MANUFACTURER_HEX_CHARS = 24

private fun shortenUuidList(uuids: List<String>): String =
    if (uuids.size <= MAX_SERVICE_UUIDS_SHOWN) {
        uuids.joinToString(", ")
    } else {
        uuids.take(MAX_SERVICE_UUIDS_SHOWN).joinToString(", ") + ", +${uuids.size - MAX_SERVICE_UUIDS_SHOWN} more"
    }

private fun shortenHex(hex: String): String =
    if (hex.length <= MAX_MANUFACTURER_HEX_CHARS) hex else hex.take(MAX_MANUFACTURER_HEX_CHARS) + "…"

private fun connectButtonLabel(state: BleConnectionState?): String = when (state) {
    BleConnectionState.CONNECTING -> "Connecting..."
    BleConnectionState.CONNECTED -> "Connected"
    BleConnectionState.FAILED -> "Retry"
    BleConnectionState.DISCONNECTED, null -> "Connect"
}

@Composable
fun DeviceDetailCard(
    device: BleDevice,
    connectionState: BleConnectionState,
    services: List<BleServiceInfo>,
    batteryLevel: Int?,
    heartRateAvailable: Boolean,
    showClassicSideHint: Boolean,
    onDisconnectClick: () -> Unit
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = device.displayName, style = MaterialTheme.typography.titleMedium)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Status: ${connectionStateLabel(connectionState)}",
                    color = connectionStateColor(connectionState)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onDisconnectClick,
                    enabled = connectionState == BleConnectionState.CONNECTED ||
                        connectionState == BleConnectionState.CONNECTING
                ) {
                    Text("Disconnect")
                }
            }

            if (showClassicSideHint) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This paired address may be the Classic Bluetooth side. Try a " +
                        "connectable BLE scan result with strong RSSI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (device.bondStateLabel == "Bonded") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Android has no safe public API to remove a bond. To forget this " +
                        "device, use system Bluetooth settings.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                    Text("Open Bluetooth Settings")
                }
            }

            batteryLevel?.let { level ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Battery: $level%", style = MaterialTheme.typography.bodyLarge)
            }
            if (heartRateAvailable) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Heart Rate Service available", style = MaterialTheme.typography.bodyLarge)
            }

            if (services.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Services (${services.size})", style = MaterialTheme.typography.titleSmall)
                services.forEach { service ->
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    ServiceItem(service)
                }
            }
        }
    }
}

@Composable
private fun ServiceItem(service: BleServiceInfo) {
    Column {
        Text(text = service.uuid.toString(), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = standardServiceName(service.uuid)?.let { "Standard ($it)" }
                ?: if (service.isStandard) "Standard" else "Custom",
            style = MaterialTheme.typography.bodySmall
        )
        service.characteristics.forEach { characteristic ->
            Spacer(modifier = Modifier.height(4.dp))
            CharacteristicItem(characteristic)
        }
    }
}

@Composable
private fun CharacteristicItem(characteristic: BleCharacteristicInfo) {
    Column(modifier = Modifier.padding(start = 12.dp)) {
        Text(text = characteristic.uuid.toString(), style = MaterialTheme.typography.bodySmall)
        val propertiesText = if (characteristic.properties.isEmpty()) {
            "No properties"
        } else {
            characteristic.properties.joinToString(", ")
        }
        Text(text = propertiesText, style = MaterialTheme.typography.bodySmall)
    }
}

private fun connectionStateLabel(state: BleConnectionState): String = when (state) {
    BleConnectionState.CONNECTING -> "Connecting"
    BleConnectionState.CONNECTED -> "Connected"
    BleConnectionState.DISCONNECTED -> "Disconnected"
    BleConnectionState.FAILED -> "Connection failed"
}

@Composable
private fun connectionStateColor(state: BleConnectionState) = when (state) {
    BleConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
    BleConnectionState.FAILED -> MaterialTheme.colorScheme.error
    BleConnectionState.CONNECTING, BleConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Preview(showBackground = true)
@Composable
fun BleScannerScreenPreview() {
    MubaWatchLabTheme {
        BleScannerScreen()
    }
}
