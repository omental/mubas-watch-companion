package com.muba.watchlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.wiitetech.WiiWatchPro.bluetoothutil.MessageBean
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.Serializable
import java.lang.reflect.Modifier
import java.util.UUID

enum class SppState { IDLE, CONNECTING, CONNECTED, FAILED, DISCONNECTED }

private enum class SppReadMode { NONE, RAW_MODE, OBJECT_STREAM_MODE }

// Give the Bluetooth stack time to fully release the old socket before reusing the channel -
// reconnecting immediately after a close reliably fails on many stacks.
private const val CLEANUP_SETTLE_DELAY_MS = 500L

private const val CANARY_CLEANUP_SETTLE_DELAY_MS = 800L
private const val HARD_CLEANUP_SETTLE_DELAY_MS = 1_500L
private const val SOCKET_CONNECT_RETRY_DELAY_MS = 3_000L

/** Idle poll interval for the passive read loop; also how quickly it notices a pause request. */
private const val READ_POLL_IDLE_MS = 40L

/** Raw RFCOMM channel WiiWatch2 connects to via reflection, bypassing SDP/UUID lookup entirely. */
private const val RFCOMM_CHANNEL_41 = 41

/** WiiWatch2 waits this long after connect() before opening any streams. */
private const val CHANNEL_41_POST_CONNECT_DELAY_MS = 1_000L

/** The Java Object Serialization stream magic + version (java.io.ObjectStreamConstants.STREAM_HEADER). */
val JAVA_STREAM_HEADER: ByteArray = byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0x00, 0x05)

/** True if [bytes] is exactly the Java serialization stream header (AC ED 00 05). */
fun isJavaStreamHeader(bytes: ByteArray): Boolean = bytes.contentEquals(JAVA_STREAM_HEADER)

/**
 * Tees every byte written to [delegate] into an internal buffer so callers can inspect
 * exactly what an ObjectOutputStream wrote for a single operation via [takeCaptured] -
 * ObjectOutputStream wraps primitive/UTF/object writes in its own internal block-data
 * framing, so this is the only reliable way to log the real wire bytes of a given call.
 */
private class CapturingOutputStream(private val delegate: OutputStream) : OutputStream() {
    private val captured = ByteArrayOutputStream()

    @Synchronized override fun write(b: Int) {
        delegate.write(b)
        captured.write(b)
    }

    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        captured.write(b, off, len)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()

    @Synchronized
    fun takeCaptured(): ByteArray {
        val bytes = captured.toByteArray()
        captured.reset()
        return bytes
    }
}

/**
 * Manages one classic Bluetooth RFCOMM/SPP session with a paired device: connect
 * (cancelling discovery first, with a full cleanup + settle delay before every attempt),
 * a background read loop, raw byte sends, and a Java ObjectStream command lab (the watch
 * speaks java.io.ObjectInputStream/ObjectOutputStream over RFCOMM instead of raw bytes).
 * All socket I/O runs off the main thread. Entirely separate from BLE/GATT.
 */
class SppManager(
    private val onStateChange: (state: SppState, reason: String?) -> Unit,
    private val onBytesSent: (ByteArray) -> Unit,
    private val onBytesReceived: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
    private val onJavaOutputStreamReady: (Boolean) -> Unit = {},
    private val onJavaInputStreamReady: (Boolean) -> Unit = {},
    private val onJavaSendResult: (String) -> Unit = {},
    private val onJavaReceiveResult: (String) -> Unit = {},
    private val onMessageBeanReceived: (MessageBean) -> Unit = {},
    private val onMessageBeanSendResult: (MessageBean, Boolean, String) -> Unit = { _, _, _ -> },
    private val onJavaListenerActiveChange: (Boolean) -> Unit = {},
    private val onCanaryConnectedChange: (Boolean) -> Unit = {},
    private val onCanaryHandshakeReadyChange: (Boolean) -> Unit = {},
    private val onSocketConnectedChange: (Boolean) -> Unit = {}
) {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var connected = false
    @Volatile private var readMode = SppReadMode.RAW_MODE
    @Volatile private var intentionalDisconnect = false

    @Volatile private var objectOutputStream: ObjectOutputStream? = null
    @Volatile private var objectOutputCapture: CapturingOutputStream? = null
    @Volatile private var objectInputStream: ObjectInputStream? = null
    private val javaWriteLock = Any()
    @Volatile private var javaListenerActive = false
    @Volatile private var objectReaderThread: Thread? = null
    @Volatile private var rawReaderThread: Thread? = null
    @Volatile private var canaryModeActive = false
    @Volatile private var canaryHandshakeReady = false
    @Volatile private var canaryRawHeaderCaptured = false
    @Volatile private var lastRawJavaHeader: ByteArray? = null

    // While true, the passive read loop stops pulling bytes off the input stream so an
    // ObjectInputStream can safely become its sole reader instead of racing it.
    @Volatile private var javaStreamModeActive = false

    // When enabled, connect() sends the Java ObjectStream header immediately after a
    // successful socket.connect(), before the passive read loop starts.
    @Volatile var autoJavaHandshakeOnConnect: Boolean = false

    /**
     * Connects to [device]'s SPP service. Set [insecure] to true only as a manual
     * fallback (createInsecureRfcommSocketToServiceRecord) after a normal attempt failed.
     */
    @SuppressLint("MissingPermission")
    fun connectSppCanary(bluetoothAdapter: BluetoothAdapter, device: BluetoothDevice) {
        onLog("CANARY PATH = NORMAL_SPP_UUID_ONLY")
        onStateChange(SppState.CONNECTING, null)
        Thread {
            onLog("CANARY cleanup: closing existing SPP socket/read thread/object streams")
            cleanup()
            onLog("CANARY cleanup complete: socket=${socket == null}, input=${inputStream == null}, output=${outputStream == null}, objectOut=${objectOutputStream == null}, objectIn=${objectInputStream == null}")
            sleepQuietly(CANARY_CLEANUP_SETTLE_DELAY_MS)

            var localSocket: BluetoothSocket? = null
            try {
                try {
                    bluetoothAdapter.cancelDiscovery()
                    onLog("CANARY cancelDiscovery() called")
                } catch (e: SecurityException) {
                    onLog("CANARY cancelDiscovery() denied: ${e.message}")
                }

                onLog("CANARY socket is null before connect: ${socket == null}")
                localSocket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
                )
                socket = localSocket
                onLog("CANARY socket created with normal SPP UUID only")
                localSocket.connect()
                onLog("CANARY socket.connect success")
                onLog("CANARY socket.isConnected after connect: ${localSocket.isConnected}")
                onSocketConnectedChange(localSocket.isConnected)

                inputStream = PushbackInputStream(localSocket.inputStream, JAVA_STREAM_HEADER.size)
                outputStream = localSocket.outputStream
                connected = true
                canaryModeActive = true
                canaryHandshakeReady = false
                canaryRawHeaderCaptured = false
                readMode = SppReadMode.RAW_MODE
                onCanaryConnectedChange(true)
                onCanaryHandshakeReadyChange(false)
                onStateChange(SppState.CONNECTED, null)

                canaryReadLoop(inputStream!!)
            } catch (e: IOException) {
                logCanaryException("connect", e, localSocket)
                onStateChange(SppState.FAILED, "Canary connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            } catch (e: SecurityException) {
                logCanaryException("connect", e, localSocket)
                onStateChange(SppState.FAILED, "Canary connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            }
        }.start()
    }

    fun startSppCanaryJavaHandshake() {
        val out = outputStream
        val input = inputStream
        if (!connected || !canaryModeActive || out == null || input == null || socket?.isConnected != true) {
            onLog("CANARY HANDSHAKE blocked: canary socket is not connected")
            return
        }
        if (!canaryRawHeaderCaptured) {
            onLog("CANARY HANDSHAKE blocked: RAW_MODE has not captured Java header yet")
            return
        }
        if (canaryHandshakeReady) {
            onLog("CANARY HANDSHAKE already ready")
            return
        }
        onLog("CANARY PATH = JAVA_HANDSHAKE_ONLY")
        Thread {
            val capture = CapturingOutputStream(out)
            try {
                val oos = ObjectOutputStream(capture)
                oos.flush()
                objectOutputStream = oos
                objectOutputCapture = capture
                canaryHandshakeReady = true
                readMode = SppReadMode.OBJECT_STREAM_MODE
                val headerBytes = capture.takeCaptured()
                onLog("CANARY TX AC ED 00 05")
                onLog("OBJECT_STREAM_MODE started")
                onBytesSent(if (headerBytes.isNotEmpty()) headerBytes else JAVA_STREAM_HEADER)
                onJavaOutputStreamReady(true)
                try {
                    val ois = ObjectInputStream(BufferedInputStream(input))
                    objectInputStream = ois
                    onLog("ObjectInputStream ready")
                    onJavaInputStreamReady(true)
                    startJavaObjectListening()
                } catch (e: IOException) {
                    logCanaryException("ObjectInputStream create", e, socket)
                    return@Thread
                }
                onCanaryHandshakeReadyChange(true)
            } catch (e: IOException) {
                canaryHandshakeReady = false
                onCanaryHandshakeReadyChange(false)
                logCanaryException("java handshake", e, socket)
            }
        }.start()
    }

    fun sendSppCanaryBatteryMessageBean(bean: MessageBean) {
        val oos = objectOutputStream
        val capture = objectOutputCapture
        if (!connected || !canaryModeActive || !canaryHandshakeReady || oos == null || capture == null) {
            onLog("CANARY MessageBean Battery blocked: Java handshake not ready")
            onJavaSendResult("Canary Battery failed: handshake not ready")
            return
        }
        onLog("CANARY PATH = MESSAGEBEAN_BATTERY_ONLY")
        onLog("CANARY sending MessageBean cmd=\"${bean.cmd}\"")
        Thread {
            try {
                val wire = synchronized(javaWriteLock) {
                    oos.flush()
                    oos.writeObject(bean)
                    oos.flush()
                    capture.takeCaptured()
                }
                onLog("CANARY MessageBean Battery serialized send success wire=${bytesToHex(wire)}")
                onLog("TX MessageBean cmd=${bean.cmd}")
                if (wire.isNotEmpty()) onBytesSent(wire)
                val message = "Sent cmd=${bean.cmd} true_false=${bean.true_false} order=${bean.order} str=\"${bean.str}\" (${wire.size} bytes)"
                onJavaSendResult(message)
                onMessageBeanSendResult(bean, true, message)
            } catch (e: IOException) {
                val message = "Failed cmd=${bean.cmd} true_false=${bean.true_false} order=${bean.order} str=\"${bean.str}\": ${e.javaClass.simpleName}: ${e.message}"
                onJavaSendResult(message)
                onMessageBeanSendResult(bean, false, message)
                logCanaryException("MessageBean Battery send", e, socket)
            }
        }.start()
    }

    fun closeSppCanarySocket() {
        onLog("CANARY PATH = CLOSE_CANARY_SOCKET")
        cleanup()
        onStateChange(SppState.DISCONNECTED, "Canary socket closed")
    }

    @SuppressLint("MissingPermission")
    fun connectKc10Companion(bluetoothAdapter: BluetoothAdapter, device: BluetoothDevice) {
        connectKc10CompanionInternal(bluetoothAdapter, device, "CONNECT KC10")
    }

    @SuppressLint("MissingPermission")
    fun connectKc10UsingCanaryInternals(bluetoothAdapter: BluetoothAdapter, device: BluetoothDevice) {
        connectKc10CompanionInternal(bluetoothAdapter, device, "CONNECT KC10 USING CANARY INTERNALS")
    }

    @SuppressLint("MissingPermission")
    private fun connectKc10CompanionInternal(
        bluetoothAdapter: BluetoothAdapter,
        device: BluetoothDevice,
        pathName: String
    ) {
        onStateChange(SppState.CONNECTING, null)
        Thread {
            var localSocket: BluetoothSocket? = null
            var step = "start"
            try {
                step = "start proven canary sequence"
                onLog("$pathName: starting proven canary sequence")
                onLog("$pathName: ignoring SDP/ACTION_UUID; no SDP fetch in this path")

                step = "cleanup"
                onLog("$pathName: cleanup old socket/streams/readers")
                hardCleanupSpp(bluetoothAdapter, "$pathName pre-connect cleanup")

                step = "cancel discovery"
                try {
                    bluetoothAdapter.cancelDiscovery()
                    onLog("$pathName: cancelDiscovery() called")
                } catch (e: SecurityException) {
                    onLog("$pathName: cancelDiscovery() denied: ${e.message}")
                }

                step = "create normal SPP UUID socket"
                localSocket = connectNormalSppSocketWithOneRetry(bluetoothAdapter, device, pathName)
                socket = localSocket
                onLog("$pathName: socket.connect success")
                onLog("$pathName: socket.isConnected=${localSocket.isConnected}")
                onSocketConnectedChange(localSocket.isConnected)

                step = "prepare raw header input"
                inputStream = PushbackInputStream(localSocket.inputStream, JAVA_STREAM_HEADER.size)
                outputStream = localSocket.outputStream
                connected = true
                canaryModeActive = true
                canaryRawHeaderCaptured = false
                canaryHandshakeReady = false
                readMode = SppReadMode.RAW_MODE
                onCanaryConnectedChange(true)
                onCanaryHandshakeReadyChange(false)

                step = "read first Java header"
                canaryReadLoop(inputStream!!)
                val rawHeader = lastRawJavaHeader
                if (!connected || !canaryRawHeaderCaptured || rawHeader == null) {
                    throw IOException("Java header was not captured")
                }
                if (!isJavaStreamHeader(rawHeader)) {
                    throw IOException("Unexpected Java header ${bytesToHex(rawHeader)}")
                }
                onLog("$pathName: RX Java header ${bytesToHex(rawHeader)}")

                step = "create ObjectOutputStream"
                val out = outputStream ?: throw IOException("outputStream is null")
                val input = inputStream ?: throw IOException("inputStream is null")
                val capture = CapturingOutputStream(out)
                val oos = ObjectOutputStream(capture)
                step = "flush ObjectOutputStream"
                oos.flush()
                objectOutputStream = oos
                objectOutputCapture = capture
                canaryHandshakeReady = true
                readMode = SppReadMode.OBJECT_STREAM_MODE
                val headerBytes = capture.takeCaptured()
                onLog("$pathName: TX Java header ${bytesToHex(if (headerBytes.isNotEmpty()) headerBytes else JAVA_STREAM_HEADER)}")
                onLog("OBJECT_STREAM_MODE started")
                onBytesSent(if (headerBytes.isNotEmpty()) headerBytes else JAVA_STREAM_HEADER)
                onJavaOutputStreamReady(true)

                step = "create ObjectInputStream"
                val ois = ObjectInputStream(BufferedInputStream(input))
                objectInputStream = ois
                onLog("$pathName: ObjectInputStream ready")
                onJavaInputStreamReady(true)
                onCanaryHandshakeReadyChange(true)
                step = "start object read loop"
                startJavaObjectListening()
                onStateChange(SppState.CONNECTED, null)
            } catch (e: IOException) {
                logKc10ConnectFailure(pathName, step, e, localSocket)
                onLog("Connect failed after retry")
                onStateChange(SppState.FAILED, "KC10 connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            } catch (e: SecurityException) {
                logKc10ConnectFailure(pathName, step, e, localSocket)
                onLog("Connect failed after retry")
                onStateChange(SppState.FAILED, "KC10 connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun connectNormalSppSocketWithOneRetry(
        bluetoothAdapter: BluetoothAdapter,
        device: BluetoothDevice,
        pathName: String
    ): BluetoothSocket {
        onLog("SPP connect attempt 1")
        var attemptSocket = device.createRfcommSocketToServiceRecord(
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        )
        socket = attemptSocket
        try {
            attemptSocket.connect()
            return attemptSocket
        } catch (e: IOException) {
            if (!isStaleRfcommConnectFailure(e)) throw e
            onLog("$pathName: stale RFCOMM connect failure: ${e.message}")
            closeBluetoothSocket(attemptSocket, "$pathName retry close failed socket")
            socket = null
            onSocketConnectedChange(false)
            sleepQuietly(SOCKET_CONNECT_RETRY_DELAY_MS)
            try {
                bluetoothAdapter.cancelDiscovery()
                onLog("$pathName: cancelDiscovery() before retry called")
            } catch (se: SecurityException) {
                onLog("$pathName: cancelDiscovery() before retry denied: ${se.message}")
            }
            onLog("SPP connect attempt 2 after cooldown")
            attemptSocket = device.createRfcommSocketToServiceRecord(
                UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
            )
            socket = attemptSocket
            attemptSocket.connect()
            return attemptSocket
        }
    }

    private fun isStaleRfcommConnectFailure(e: IOException): Boolean {
        val message = e.message ?: return false
        return message.contains("read failed", ignoreCase = true) &&
            message.contains("socket might closed or timeout", ignoreCase = true) &&
            message.contains("read ret: -1", ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    fun connect(bluetoothAdapter: BluetoothAdapter, device: BluetoothDevice, insecure: Boolean = false) {
        if (connected) return
        onLog("CONNECT PATH = NORMAL_SPP_UUID")
        onStateChange(SppState.CONNECTING, null)
        Thread {
            // Always start every attempt from a fully torn-down state: close the old socket,
            // null out socket/input/output/object streams (cleanup() does both), then give the
            // Bluetooth stack a moment to settle before reusing the same RFCOMM channel -
            // reconnecting immediately after a close reliably fails on many stacks.
            cleanup()
            sleepQuietly(CLEANUP_SETTLE_DELAY_MS)

            onLog("SPP connect attempt to ${device.address}" + if (insecure) " (insecure)" else "")
            var localSocket: BluetoothSocket? = null
            try {
                try {
                    bluetoothAdapter.cancelDiscovery()
                } catch (e: SecurityException) {
                    onLog("cancelDiscovery() denied: ${e.message}")
                }

                localSocket = if (insecure) {
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                } else {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                }
                socket = localSocket
                // socket.connect() is the only thing that can produce a FAILED state.
                // Anything that happens after this line succeeds is a CONNECTED session
                // ending in DISCONNECTED, never FAILED.
                localSocket.connect()
                onSocketConnectedChange(localSocket.isConnected)

                inputStream = localSocket.inputStream
                outputStream = localSocket.outputStream
                connected = true
                onLog("SPP connect success to ${device.address}")
                onStateChange(SppState.CONNECTED, null)

                val objectStreamsReady = if (autoJavaHandshakeOnConnect) {
                    onLog("Auto Java Handshake on Connect: sending stream header")
                    performJavaStreamHandshake(outputStream!!, inputStream)
                } else {
                    false
                }

                if (!objectStreamsReady) readLoop(inputStream!!)
            } catch (e: IOException) {
                logConnectFailure(e, localSocket, bluetoothAdapter, device)
                onStateChange(SppState.FAILED, "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            } catch (e: SecurityException) {
                logConnectFailure(e, localSocket, bluetoothAdapter, device)
                onStateChange(SppState.FAILED, "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            }
        }.start()
    }

    /**
     * Primary transport for WiiWatch2 protocol compatibility: a dedicated connect path that
     * only ever uses the normal RFCOMM/SPP UUID (never createRfcommSocket(41) reflection - that
     * is [connectChannel41], a fully separate method) and always establishes the ObjectStream
     * handshake. Kept entirely separate from plain [connect] so toggling "Auto Java Handshake on
     * Connect" elsewhere can never affect this path, and vice versa.
     */
    @SuppressLint("MissingPermission")
    fun connectWiiWatch2Protocol(bluetoothAdapter: BluetoothAdapter, device: BluetoothDevice) {
        if (connected) return
        onLog("CONNECT PATH = NORMAL_SPP_UUID")
        onStateChange(SppState.CONNECTING, null)
        Thread {
            cleanup()
            sleepQuietly(CLEANUP_SETTLE_DELAY_MS)

            try {
                bluetoothAdapter.cancelDiscovery()
            } catch (e: SecurityException) {
                onLog("cancelDiscovery() denied: ${e.message}")
            }

            onLog("SPP connect attempt to ${device.address} (normal SPP UUID, WiiWatch2 protocol)")
            var localSocket: BluetoothSocket? = null
            try {
                localSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = localSocket
                // socket.connect() is the only thing that can produce a FAILED state - if it
                // throws, we never reach the Object stream setup below.
                localSocket.connect()
                onSocketConnectedChange(localSocket.isConnected)
                onLog("Normal SPP UUID socket.connect success")

                inputStream = localSocket.inputStream
                outputStream = localSocket.outputStream
                connected = true
                onStateChange(SppState.CONNECTED, null)

                val streamsReady = performJavaStreamHandshake(outputStream!!, inputStream)
                if (streamsReady) onLog("WiiWatch2 protocol streams ready")
                if (!streamsReady) readLoop(inputStream!!)
            } catch (e: IOException) {
                logConnectFailure(e, localSocket, bluetoothAdapter, device)
                onStateChange(SppState.FAILED, "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            } catch (e: SecurityException) {
                logConnectFailure(e, localSocket, bluetoothAdapter, device)
                onStateChange(SppState.FAILED, "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            }
        }.start()
    }

    /**
     * Connects directly to RFCOMM channel 41 via reflection (BluetoothDevice.createRfcommSocket(int)),
     * bypassing SDP/UUID lookup entirely - exactly how the original WiiWatch2 APK connects. Waits
     * [CHANNEL_41_POST_CONNECT_DELAY_MS] after connect (also matching WiiWatch2) before creating
     * ObjectOutputStream, then ObjectInputStream wrapped in a BufferedInputStream, in that order.
     */
    @SuppressLint("MissingPermission")
    fun connectChannel41(bluetoothAdapter: BluetoothAdapter, device: BluetoothDevice) {
        if (connected) return
        onLog("CONNECT PATH = EXPERIMENTAL_CHANNEL_41")
        onStateChange(SppState.CONNECTING, null)
        Thread {
            cleanup()
            sleepQuietly(CLEANUP_SETTLE_DELAY_MS)

            onLog("SPP connect attempt to ${device.address} via RFCOMM channel $RFCOMM_CHANNEL_41 (WiiWatch2-style reflection)")
            var localSocket: BluetoothSocket? = null
            try {
                try {
                    bluetoothAdapter.cancelDiscovery()
                } catch (e: SecurityException) {
                    onLog("cancelDiscovery() denied: ${e.message}")
                }

                localSocket = try {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, RFCOMM_CHANNEL_41) as BluetoothSocket
                } catch (e: Exception) {
                    // Reflection can throw many exception types (NoSuchMethodException,
                    // IllegalAccessException, InvocationTargetException, ClassCastException) -
                    // fold them into an IOException so the existing connect-failure path handles it.
                    throw IOException("createRfcommSocket($RFCOMM_CHANNEL_41) reflection failed: ${e.javaClass.simpleName}: ${e.message}", e)
                }
                socket = localSocket
                localSocket.connect()
                onSocketConnectedChange(localSocket.isConnected)

                inputStream = localSocket.inputStream
                outputStream = localSocket.outputStream
                connected = true
                onLog("SPP connect success to ${device.address} (channel $RFCOMM_CHANNEL_41)")
                onStateChange(SppState.CONNECTED, null)

                onLog("Waiting ${CHANNEL_41_POST_CONNECT_DELAY_MS}ms before opening streams (matches WiiWatch2)")
                sleepQuietly(CHANNEL_41_POST_CONNECT_DELAY_MS)
                val streamsReady = performJavaStreamHandshake(outputStream!!, inputStream)
                if (!streamsReady) readLoop(inputStream!!)
            } catch (e: IOException) {
                logConnectFailure(e, localSocket, bluetoothAdapter, device)
                onStateChange(SppState.FAILED, "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            } catch (e: SecurityException) {
                logConnectFailure(e, localSocket, bluetoothAdapter, device)
                onStateChange(SppState.FAILED, "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                cleanup()
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun logConnectFailure(
        e: Exception,
        localSocket: BluetoothSocket?,
        bluetoothAdapter: BluetoothAdapter,
        device: BluetoothDevice
    ) {
        onLog("SPP connect failed to ${device.address}: ${e.javaClass.name}: ${e.message}")
        val trace = e.stackTrace.take(3).joinToString(separator = "\n") { "  at $it" }
        if (trace.isNotEmpty()) onLog("Stack trace (first 3 lines):\n$trace")
        onLog("Socket was ${if (localSocket == null) "null" else "non-null"} before failure; connected flag was $connected")
        onLog("Bluetooth adapter enabled: ${bluetoothAdapter.isEnabled}")
        val bondLabel = try {
            bondStateLabel(device.bondState)
        } catch (se: SecurityException) {
            "Unknown"
        }
        onLog("Device bond state: $bondLabel")
        val typeLabel = try {
            deviceTypeLabel(device.type)
        } catch (se: SecurityException) {
            "Unknown"
        }
        onLog("Device type: $typeLabel")
    }

    /**
     * Reads until the socket closes or errors, or until [javaStreamModeActive] takes over
     * ownership of the input stream. Runs on the same thread connect() started.
     *
     * Polls via available()/read() instead of a plain blocking read() so a pause request
     * (javaStreamModeActive flipping true) is noticed within one poll interval rather than
     * only after the next byte arrives from the watch - that's what makes it safe to then
     * hand the input stream to an ObjectInputStream without two threads racing to read it.
     */
    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(1024)
        var disconnectReason = "Remote closed the connection"
        var sawReadError = false
        while (connected && !javaStreamModeActive) {
            val available = try {
                input.available()
            } catch (e: IOException) {
                if (connected) {
                    onLog("SPP read error: ${e.message}")
                    sawReadError = true
                }
                -1
            }
            if (available < 0) break
            if (available == 0) {
                sleepQuietly(READ_POLL_IDLE_MS)
                continue
            }
            val count = try {
                input.read(buffer, 0, minOf(available, buffer.size))
            } catch (e: IOException) {
                // Only report a genuine read error; if connected is already false, this
                // read() simply unblocked because disconnect()/cleanup() closed the
                // socket on purpose, which is not a failure.
                if (connected) {
                    onLog("SPP read error: ${e.message}")
                    sawReadError = true
                }
                -1
            }
            if (count < 0) break
            if (count > 0) onBytesReceived(buffer.copyOf(count))
        }
        if (javaStreamModeActive) {
            // Ownership of the input stream was handed off intentionally; this is not a
            // disconnect, so leave state/session accounting untouched.
            return
        }
        if (connected) {
            // A read error after a successful connect still means the link is gone, not
            // that the connection attempt failed - so this is always DISCONNECTED, never
            // FAILED. sawReadError only changes *why* we log it, not the resulting state.
            if (sawReadError) onLog("Treating read error as remote disconnect, not a connect failure")
            connected = false
            cleanup()
            onStateChange(SppState.DISCONNECTED, disconnectReason)
        }
    }

    private fun canaryReadLoop(input: InputStream) {
        rawReaderThread = Thread.currentThread()
        readMode = SppReadMode.RAW_MODE
        val header = ByteArray(JAVA_STREAM_HEADER.size)
        var offset = 0
        while (connected && canaryModeActive && readMode == SppReadMode.RAW_MODE && offset < header.size) {
            val count = try {
                input.read(header, offset, header.size - offset)
            } catch (e: IOException) {
                if (connected) logCanaryException("raw read loop", e, socket)
                -1
            }
            if (count < 0) {
                if (connected) {
                    connected = false
                    cleanup()
                    onStateChange(SppState.DISCONNECTED, "Canary remote closed before Java header")
                }
                return
            }
            offset += count
        }

        if (!connected || !canaryModeActive) return

        val packet = header.copyOf(offset)
        lastRawJavaHeader = packet
        onLog("CANARY first RX HEX=${bytesToHex(packet)}")
        onBytesReceived(packet)
        if (input is PushbackInputStream) {
            input.unread(packet)
            onLog("CANARY Java header pushed back for ObjectInputStream")
        }
        canaryRawHeaderCaptured = true
        onLog("RAW_MODE stopped after Java header")
        rawReaderThread = null
    }

    fun send(bytes: ByteArray) {
        val out = outputStream
        if (!connected || out == null) {
            onLog("Cannot send: SPP not connected")
            return
        }
        if (javaStreamModeActive) {
            onLog("Warning: sending raw bytes while Java ObjectStream mode is active may corrupt the stream")
        }
        Thread {
            try {
                out.write(bytes)
                out.flush()
                onBytesSent(bytes)
            } catch (e: IOException) {
                onLog("SPP send failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Creates an ObjectOutputStream on the SPP output stream and flushes it immediately,
     * writing the Java serialization stream header (AC ED 00 05) to the watch. Then, if
     * the passive read loop was safely paused, creates an ObjectInputStream on the SPP
     * input stream. Logs every step.
     */
    fun startJavaStreamProbe() {
        val out = outputStream
        if (!connected || out == null) {
            onLog("Cannot start Java Stream Probe: SPP not connected")
            return
        }
        // Set before starting the worker thread so the read loop's next poll (at most
        // READ_POLL_IDLE_MS away) sees it and stops touching the input stream.
        javaStreamModeActive = true
        val input = inputStream
        Thread {
            // Give the passive read loop one full poll cycle to observe the flag and
            // stop calling read() before we hand the input stream to an ObjectInputStream -
            // this is what makes the handoff safe instead of racing two readers.
            sleepQuietly(READ_POLL_IDLE_MS * 2)
            performJavaStreamHandshake(out, input)
        }.start()
    }

    /**
     * Shared by the manual probe, "Auto Java Handshake on Connect", [connectWiiWatch2Protocol],
     * and the experimental channel-41 path. Creates streams in WiiWatch2's exact order:
     * ObjectOutputStream first, then ObjectInputStream wrapped in a BufferedInputStream.
     * Returns true only if both streams were created successfully.
     */
    private fun performJavaStreamHandshake(out: OutputStream, input: InputStream?): Boolean {
        javaStreamModeActive = true
        val capture = CapturingOutputStream(out)
        try {
            onLog("Creating ObjectOutputStream")
            val oos = ObjectOutputStream(capture)
            oos.flush()
            val headerBytes = capture.takeCaptured()
            objectOutputStream = oos
            objectOutputCapture = capture
            onLog("ObjectOutputStream header sent")
            onBytesSent(if (headerBytes.isNotEmpty()) headerBytes else JAVA_STREAM_HEADER)
            onJavaOutputStreamReady(true)
        } catch (e: IOException) {
            onLog("Creating ObjectOutputStream failed: ${e.javaClass.simpleName}: ${e.message}")
            javaStreamModeActive = false
            return false
        }

        if (input == null) {
            onLog("Skipping ObjectInputStream: no input stream available")
            return false
        }
        return try {
            readMode = SppReadMode.OBJECT_STREAM_MODE
            onLog("OBJECT_STREAM_MODE started")
            onLog("Creating ObjectInputStream (BufferedInputStream)")
            val ois = ObjectInputStream(BufferedInputStream(input))
            objectInputStream = ois
            onLog("ObjectInputStream ready")
            onJavaInputStreamReady(true)
            startJavaObjectListening()
            true
        } catch (e: IOException) {
            onLog("Creating ObjectInputStream failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Runs [block] against the live ObjectOutputStream, flushes, and logs the real wire bytes written. */
    private fun runJavaSend(description: String, block: (ObjectOutputStream) -> Unit) {
        val oos = objectOutputStream
        val capture = objectOutputCapture
        if (oos == null || capture == null) {
            onLog("Cannot send $description: ObjectOutputStream not ready")
            onJavaSendResult("$description failed: stream not ready")
            return
        }
        Thread {
            try {
                val wire = synchronized(javaWriteLock) {
                    block(oos)
                    oos.flush()
                    capture.takeCaptured()
                }
                onLog("Java send: $description wire=${bytesToHex(wire)}")
                if (wire.isNotEmpty()) onBytesSent(wire)
                onJavaSendResult("$description sent (${wire.size} bytes)")
            } catch (e: IOException) {
                onLog("Java send failed ($description): ${e.javaClass.simpleName}: ${e.message}")
                onJavaSendResult("$description failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    /** writeUTF(text) + flush. Logs the string and its exact wire bytes. */
    fun sendJavaUtf(text: String) = runJavaSend("UTF \"$text\"") { it.writeUTF(text) }

    fun sendJavaInt(value: Int) = runJavaSend("writeInt($value)") { it.writeInt(value) }

    fun sendJavaBoolean(value: Boolean) = runJavaSend("writeBoolean($value)") { it.writeBoolean(value) }

    fun sendJavaLong(value: Long) = runJavaSend("writeLong($value)") { it.writeLong(value) }

    /** writeObject(value) + flush. Only String/HashMap<String,String> are ever passed in - no custom classes. */
    fun sendJavaObject(description: String, value: Serializable) =
        runJavaSend("writeObject($description)") { it.writeObject(value) }

    /**
     * Sends a MessageBean using WiiWatch2's exact send sequence: flush() before writeObject(),
     * writeObject(bean), then flush() again. A broken pipe here means the watch closed the
     * connection while we were writing - that's a live-session failure, not a connect failure,
     * so it's reported as DISCONNECTED (never FAILED, per the FAILED-means-connect()-failed rule).
     */
    fun sendMessageBean(bean: MessageBean) {
        val oos = objectOutputStream
        val capture = objectOutputCapture
        if (oos == null || capture == null) {
            onLog("Cannot send MessageBean: ObjectOutputStream not ready")
            onJavaSendResult("MessageBean(cmd=${bean.cmd}) failed: stream not ready")
            return
        }
        onLog(
            "Sending MessageBean: cmd=\"${bean.cmd}\" true_false=${bean.true_false} order=${bean.order} " +
                "maxValue=${bean.maxValue} currentValue=${bean.currentValue} str=\"${bean.str}\" " +
                "identifier=\"${bean.identifier}\" bytes=${bean.bytes?.let { bytesToHex(it) } ?: "null"}"
        )
        Thread {
            try {
                val wire = synchronized(javaWriteLock) {
                    oos.flush()
                    oos.writeObject(bean)
                    oos.flush()
                    capture.takeCaptured()
                }
                onLog("writeObject(MessageBean) success wire=${bytesToHex(wire)}")
                onLog("TX MessageBean cmd=${bean.cmd}")
                if (wire.isNotEmpty()) onBytesSent(wire)
                val message = "Sent cmd=${bean.cmd} true_false=${bean.true_false} order=${bean.order} str=\"${bean.str}\" (${wire.size} bytes)"
                onJavaSendResult(message)
                onMessageBeanSendResult(bean, true, message)
            } catch (e: IOException) {
                onLog("MessageBean send failed: ${e.javaClass.simpleName}: ${e.message}")
                val message = "Failed cmd=${bean.cmd} true_false=${bean.true_false} order=${bean.order} str=\"${bean.str}\": ${e.javaClass.simpleName}: ${e.message}"
                onJavaSendResult(message)
                onMessageBeanSendResult(bean, false, message)
                if (isBrokenPipe(e) && connected) {
                    onLog("Broken pipe detected - marking SPP as disconnected")
                    connected = false
                    cleanup()
                    onStateChange(SppState.DISCONNECTED, "Broken pipe while sending MessageBean")
                }
            }
        }.start()
    }

    fun sendMessageBeansSequential(description: String, beans: List<MessageBean>) {
        val oos = objectOutputStream
        val capture = objectOutputCapture
        if (oos == null || capture == null) {
            onLog("Cannot send $description: ObjectOutputStream not ready")
            onJavaSendResult("$description failed: stream not ready")
            return
        }
        Thread {
            synchronized(javaWriteLock) {
                beans.forEach { bean ->
                    try {
                        onLog("Sending $description item: MessageBean(cmd=${bean.cmd})")
                        oos.flush()
                        oos.writeObject(bean)
                        oos.flush()
                        val wire = capture.takeCaptured()
                        onLog("$description item sent: cmd=${bean.cmd} wire=${bytesToHex(wire)}")
                        onLog("TX MessageBean cmd=${bean.cmd}")
                        if (wire.isNotEmpty()) onBytesSent(wire)
                        val message = "$description: Sent cmd=${bean.cmd} true_false=${bean.true_false} order=${bean.order} str=\"${bean.str}\" (${wire.size} bytes)"
                        onJavaSendResult(message)
                        onMessageBeanSendResult(bean, true, message)
                    } catch (e: IOException) {
                        onLog("$description failed at cmd=${bean.cmd}: ${e.javaClass.simpleName}: ${e.message}")
                        val message = "$description: Failed cmd=${bean.cmd} true_false=${bean.true_false} order=${bean.order} str=\"${bean.str}\": ${e.javaClass.simpleName}: ${e.message}"
                        onJavaSendResult(message)
                        onMessageBeanSendResult(bean, false, message)
                        if (isBrokenPipe(e) && connected) {
                            connected = false
                            cleanup()
                            onStateChange(SppState.DISCONNECTED, "Broken pipe while sending $description")
                        }
                        return@synchronized
                    }
                }
            }
        }.start()
    }

    private fun isBrokenPipe(e: IOException): Boolean {
        val message = e.message ?: return false
        return message.contains("broken pipe", ignoreCase = true) || message.contains("epipe", ignoreCase = true)
    }

    /**
     * Loops ObjectInputStream.readObject() on a background thread until [stopJavaObjectListening]
     * is called or the connection drops, so watching for asynchronous replies to MessageBean
     * commands doesn't require repeated manual taps. Only one listener runs at a time.
     */
    fun startJavaObjectListening() {
        val ois = objectInputStream
        if (ois == null) {
            onLog("Cannot start Java object listener: ObjectInputStream not ready")
            onJavaReceiveResult("Listen failed: stream not ready")
            return
        }
        if (javaListenerActive) {
            onLog("Java object listener already running")
            return
        }
        javaListenerActive = true
        onJavaListenerActiveChange(true)
        onLog("Object read loop started")

        objectReaderThread = Thread {
            while (javaListenerActive && connected) {
                try {
                    val result = ois.readObject()
                    onLog("Java readObject() class=${result?.javaClass?.name ?: "null"}")
                    val description = describeJavaObject(result)
                    onLog("Java readObject() result: $description")
                    if (result is MessageBean) {
                        onLog("RX MessageBean cmd=${result.cmd}")
                        onJavaReceiveResult("RX MessageBean ${describeMessageBean(result)}")
                        onMessageBeanReceived(result)
                    } else {
                        onJavaReceiveResult(description)
                    }
                } catch (e: ClassNotFoundException) {
                    val className = e.message ?: "unknown class"
                    onLog("Java readObject() failed: class not found: $className")
                    onJavaReceiveResult("Read failed: class not found: $className")
                } catch (e: IOException) {
                    if (javaListenerActive) {
                        onLog("Java object listener stopped: ${e.javaClass.simpleName}: ${e.message}")
                        onJavaReceiveResult("Listener stopped: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    if (connected) {
                        connected = false
                        cleanup()
                        onStateChange(SppState.DISCONNECTED, "Object read loop stopped: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    break
                }
            }
            javaListenerActive = false
            onJavaListenerActiveChange(false)
            objectReaderThread = null
        }
        objectReaderThread?.start()
    }

    /**
     * Requests the loop started by [startJavaObjectListening] to stop after its current
     * readObject() call returns. If that call is currently blocked waiting for data (no
     * timeout is possible on a BluetoothSocket stream), it keeps blocking until data arrives
     * or the connection is torn down - this only prevents starting another read.
     */
    fun stopJavaObjectListening() {
        if (!javaListenerActive) return
        onLog("Java object listener stop requested")
        javaListenerActive = false
    }

    private fun describeJavaObject(value: Any?): String {
        if (value == null) return "null"
        val className = value.javaClass.name
        return when {
            value is MessageBean -> "$className ${describeMessageBean(value)}"
            value is String -> "$className value=\"$value\""
            value is Map<*, *> -> {
                val entries = value.entries.joinToString(", ") { "${it.key}=${it.value}" }
                "$className entries={$entries}"
            }
            else -> "$className toString=$value fields={${describeFieldsReflectively(value)}}"
        }
    }

    private fun describeMessageBean(value: MessageBean): String =
        "cmd=\"${value.cmd}\" true_false=${value.true_false} order=${value.order} " +
            "maxValue=${value.maxValue} currentValue=${value.currentValue} str=\"${value.str}\" " +
            "identifier=\"${value.identifier}\" bytes=${value.bytes?.let { bytesToHex(it) } ?: "null"}"

    /** Best-effort reflective dump of an unknown object's instance fields, for RE logging. */
    private fun describeFieldsReflectively(value: Any): String = try {
        value.javaClass.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .joinToString(", ") { field ->
                field.isAccessible = true
                val fieldValue = try {
                    field.get(value)
                } catch (e: IllegalAccessException) {
                    "<inaccessible>"
                }
                "${field.name}=$fieldValue"
            }
    } catch (e: SecurityException) {
        "<reflection denied>"
    }

    fun disconnect() {
        if (!connected && socket == null) return
        onLog("Disconnecting SPP")
        // Setting connected=false before closing (inside cleanup()) is what lets the
        // read loop's unblocked read() recognize this as an intentional disconnect
        // rather than reporting its own DISCONNECTED/FAILED state.
        connected = false
        cleanup()
        onStateChange(SppState.DISCONNECTED, "Manual disconnect")
    }

    /** Tears down everything and returns to IDLE, without touching any logs. */
    fun forceReset() {
        cleanup()
        onLog("SPP force reset complete")
        onStateChange(SppState.IDLE, null)
    }

    @SuppressLint("MissingPermission")
    fun hardCleanupSpp(bluetoothAdapter: BluetoothAdapter?, reason: String = "hard cleanup") {
        onLog("hardCleanupSpp start: $reason")
        intentionalDisconnect = true
        connected = false
        javaStreamModeActive = false
        readMode = SppReadMode.NONE
        canaryModeActive = false
        canaryHandshakeReady = false
        canaryRawHeaderCaptured = false
        lastRawJavaHeader = null
        if (javaListenerActive) {
            javaListenerActive = false
            onJavaListenerActiveChange(false)
        }
        objectReaderThread?.interrupt()
        onLog("hardCleanupSpp object reader thread interrupt requested")
        objectReaderThread = null
        rawReaderThread?.interrupt()
        onLog("hardCleanupSpp raw reader thread interrupt requested")
        rawReaderThread = null

        closeObjectInputStream(objectInputStream, "objectInputStream")
        objectInputStream = null
        onJavaInputStreamReady(false)
        closeObjectOutputStream(objectOutputStream, "objectOutputStream")
        objectOutputStream = null
        objectOutputCapture = null
        onJavaOutputStreamReady(false)
        closeInputStream(inputStream, "inputStream")
        inputStream = null
        closeOutputStream(outputStream, "outputStream")
        outputStream = null
        closeBluetoothSocket(socket, "socket")
        socket = null
        onSocketConnectedChange(false)
        onCanaryConnectedChange(false)
        onCanaryHandshakeReadyChange(false)

        sleepQuietly(HARD_CLEANUP_SETTLE_DELAY_MS)
        try {
            bluetoothAdapter?.cancelDiscovery()
            onLog("hardCleanupSpp cancelDiscovery() called")
        } catch (e: SecurityException) {
            onLog("hardCleanupSpp cancelDiscovery() denied: ${e.message}")
        }
        intentionalDisconnect = false
        onLog("Cleanup complete: socket=null, objectOut=null, objectIn=null")
    }

    private fun cleanup() {
        connected = false
        javaStreamModeActive = false
        readMode = SppReadMode.NONE
        canaryModeActive = false
        canaryHandshakeReady = false
        canaryRawHeaderCaptured = false
        lastRawJavaHeader = null
        onCanaryConnectedChange(false)
        onCanaryHandshakeReadyChange(false)
        if (javaListenerActive) {
            javaListenerActive = false
            onJavaListenerActiveChange(false)
        }
        try {
            objectOutputStream?.close()
        } catch (e: IOException) {
            // Ignore.
        }
        objectOutputStream = null
        objectOutputCapture = null
        onJavaOutputStreamReady(false)
        try {
            objectInputStream?.close()
        } catch (e: IOException) {
            // Ignore.
        }
        objectInputStream = null
        onJavaInputStreamReady(false)
        try {
            inputStream?.close()
        } catch (e: IOException) {
            // Ignore.
        }
        try {
            outputStream?.close()
        } catch (e: IOException) {
            // Ignore.
        }
        try {
            socket?.close()
        } catch (e: IOException) {
            // Ignore.
        }
        inputStream = null
        outputStream = null
        socket = null
        onSocketConnectedChange(false)
    }

    private fun closeObjectInputStream(stream: ObjectInputStream?, label: String) {
        try {
            stream?.close()
            onLog("hardCleanupSpp close $label: ok")
        } catch (e: IOException) {
            onLog("hardCleanupSpp close $label failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun closeObjectOutputStream(stream: ObjectOutputStream?, label: String) {
        try {
            stream?.close()
            onLog("hardCleanupSpp close $label: ok")
        } catch (e: IOException) {
            onLog("hardCleanupSpp close $label failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun closeInputStream(stream: InputStream?, label: String) {
        try {
            stream?.close()
            onLog("hardCleanupSpp close $label: ok")
        } catch (e: IOException) {
            onLog("hardCleanupSpp close $label failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun closeOutputStream(stream: OutputStream?, label: String) {
        try {
            stream?.close()
            onLog("hardCleanupSpp close $label: ok")
        } catch (e: IOException) {
            onLog("hardCleanupSpp close $label failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun closeBluetoothSocket(targetSocket: BluetoothSocket?, label: String) {
        try {
            targetSocket?.close()
            onLog("hardCleanupSpp close $label: ok")
        } catch (e: IOException) {
            onLog("hardCleanupSpp close $label failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun logCanaryException(path: String, e: Exception, localSocket: BluetoothSocket?) {
        onLog("CANARY $path exception class=${e.javaClass.name}")
        onLog("CANARY $path exception message=${e.message}")
        onLog("CANARY $path socket null=${localSocket == null}")
        val isConnected = try {
            localSocket?.isConnected
        } catch (se: SecurityException) {
            null
        }
        onLog("CANARY $path socket.isConnected=$isConnected")
        val trace = e.stackTrace.take(5).joinToString(separator = "\n") { "  at $it" }
        if (trace.isNotEmpty()) onLog("CANARY $path stack trace first 5 lines:\n$trace")
    }

    private fun logKc10ConnectFailure(pathName: String, step: String, e: Exception, localSocket: BluetoothSocket?) {
        onLog("$pathName failed at step=$step")
        onLog("$pathName exception class=${e.javaClass.name}")
        onLog("$pathName exception message=${e.message}")
        onLog("$pathName socket null=${localSocket == null}")
        val isConnected = try {
            localSocket?.isConnected
        } catch (se: SecurityException) {
            null
        }
        onLog("$pathName socket.isConnected=$isConnected")
        onLog("$pathName readMode=$readMode")
        onLog("$pathName objectOut null=${objectOutputStream == null}")
        onLog("$pathName objectIn null=${objectInputStream == null}")
        val trace = e.stackTrace.take(8).joinToString(separator = "\n") { "  at $it" }
        if (trace.isNotEmpty()) onLog("$pathName stack trace first 8 lines:\n$trace")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
