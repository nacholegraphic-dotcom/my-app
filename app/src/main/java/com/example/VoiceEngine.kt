package com.example

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object VoiceEngine {

    enum class ConnectionMode {
        Local_Direct_UDP,
        Cloud_WebSocket_Relay
    }

    var selectedMode = ConnectionMode.Cloud_WebSocket_Relay
    var relayServerUrl = "wss://pos-voice-support.onrender.com"

    @Volatile
    var isRunning = false
        private set

    // Audio Settings: standard VoIP 8000Hz, 16bit, Mono PCM
    private const val SAMPLE_RATE = 8000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val FRAME_BUFFER_SIZE_MS = 40 // Matches NAudio 40ms frame size
    private const val BYTES_PER_SAMPLE = 2 // 16 bits = 2 bytes
    private const val FRAME_SIZE_BYTES = (SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNEL_IN * (FRAME_BUFFER_SIZE_MS / 1000.0)).toInt() // 640 bytes

    // Android Recording and Playback targets
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Streaming Threads / Objects
    private var recordingThread: Thread? = null
    private var udpReceiveThread: Thread? = null
    
    // WebSockets references
    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    // UDP references
    private var udpSocketOut: DatagramSocket? = null
    private var udpSocketIn: DatagramSocket? = null

    /**
     * Start voice capture and streaming playback
     */
    @SuppressLint("MissingPermission")
    fun startStream(
        localPort: Int,
        targetIP: String,
        remotePort: Int,
        roomId: String = "",
        onLog: (String) -> Unit = {}
    ) {
        if (isRunning) return
        isRunning = true
        onLog("Initializing VoIP Engine in ${selectedMode.name} room: $roomId")

        try {
            // 1. Initialize Android Audio playback
            val minPlayBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
            val playBufSize = Math.max(4096, minPlayBuf)
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_OUT,
                AUDIO_FORMAT,
                playBufSize,
                AudioTrack.MODE_STREAM
            ).apply {
                play()
            }
            onLog("Audiospeaker playback output initiated.")

            // 2. Initialize connection modes
            if (selectedMode == ConnectionMode.Cloud_WebSocket_Relay) {
                // Cloud WebSocket Relay Configuration
                var finalUrl = relayServerUrl
                if (roomId.isNotEmpty()) {
                    finalUrl = if (finalUrl.contains("?")) {
                        "$finalUrl&room=${android.net.Uri.encode(roomId)}"
                    } else {
                        "$finalUrl?room=${android.net.Uri.encode(roomId)}"
                    }
                }

                okHttpClient = OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS) // Disable timeout for long persistent WS
                    .build()

                val request = Request.Builder().url(finalUrl).build()
                onLog("Connecting to WebSocket relay: $finalUrl")

                webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        onLog("Voice line connected over WebSocket Relay.")
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        try {
                            val rawData = bytes.toByteArray()
                            audioTrack?.write(rawData, 0, rawData.size)
                        } catch (e: Exception) {
                            Log.e("VoiceEngine", "WS Playback failed", e)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        onLog("WebSocket voice transport error: ${t.localizedMessage}")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        onLog("WebSocket voice session disconnected: $reason")
                    }
                })
            } else {
                // Local Direct UDP mode
                onLog("Starting local direct UDP stream target: $targetIP:$remotePort")
                udpSocketOut = DatagramSocket()
                udpSocketIn = DatagramSocket(localPort)

                val targetAddress = InetAddress.getByName(targetIP)

                udpReceiveThread = thread(isDaemon = true, name = "UDP-Receive") {
                    val incomingBuffer = ByteArray(4096)
                    while (isRunning) {
                        try {
                            val packet = DatagramPacket(incomingBuffer, incomingBuffer.size)
                            udpSocketIn?.receive(packet)
                            if (packet.length > 0) {
                                audioTrack?.write(packet.data, 0, packet.length)
                            }
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
            }

            // 3. Initialize Audio Recording from microphone
            val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
            val recBufSize = Math.max(FRAME_SIZE_BYTES * 10, minRecBuf)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT,
                recBufSize
            ).apply {
                startRecording()
            }

            // 4. Start Mic Capturing Thread
            recordingThread = thread(isDaemon = true, name = "Audio-Record") {
                val micBuffer = ByteArray(FRAME_SIZE_BYTES)
                var targetAddress: InetAddress? = null
                if (selectedMode == ConnectionMode.Local_Direct_UDP) {
                    targetAddress = InetAddress.getByName(targetIP)
                }

                while (isRunning) {
                    try {
                        val bytesRead = audioRecord?.read(micBuffer, 0, FRAME_SIZE_BYTES) ?: -1
                        if (bytesRead > 0 && isRunning) {
                            if (selectedMode == ConnectionMode.Cloud_WebSocket_Relay) {
                                webSocket?.send(micBuffer.toByteString(0, bytesRead))
                            } else {
                                targetAddress?.let { address ->
                                    val packet = DatagramPacket(micBuffer, bytesRead, address, remotePort)
                                    udpSocketOut?.send(packet)
                                }
                            }
                        } else {
                            Thread.sleep(10)
                        }
                    } catch (e: Exception) {
                        Log.e("VoiceEngine", "Mic capture error", e)
                        break
                    }
                }
            }

            onLog("Voice recording started. Size check: $FRAME_SIZE_BYTES Bytes Frame.")

        } catch (ex: Exception) {
            onLog("Error initializing Voice Engine: ${ex.message}")
            Log.e("VoiceEngine", "Startup Crash", ex)
            stopStream()
        }
    }

    /**
     * Safe termination of VoIP streaming and releases all hardware resources
     */
    fun stopStream() {
        if (!isRunning) return
        isRunning = false

        // Stop Recording
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // safety block
        }
        audioRecord = null

        // Stop Playback
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // safety block
        }
        audioTrack = null

        // Terminate WebSocket
        try {
            webSocket?.close(1001, "Stream ended")
        } catch (e: Exception) {
            // safety
        }
        webSocket = null
        okHttpClient = null

        // Terminate UDP Socket
        try {
            udpSocketIn?.close()
            udpSocketOut?.close()
        } catch (e: Exception) {
            // safety
        }
        udpSocketIn = null
        udpSocketOut = null

        // Clean up thread references
        recordingThread = null
        udpReceiveThread = null
    }

    /**
     * Walks network adapters to look for local private IPv4 addresses
     */
    fun getLocalIPAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: ""
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4 && !sAddr.startsWith("127")) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            // Fallback
        }
        return "127.0.0.1"
    }
}
