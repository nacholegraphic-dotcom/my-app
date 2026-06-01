package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Firebase REST client instance (initially pointed to the client's RTDB)
    private lateinit var firebaseClient: FirebaseRestClient

    // Alerts helpers
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    // Logging & logs list
    private val logs = mutableStateListOf<String>()

    // Core States
    private var baseFirebaseUrl by mutableStateOf("https://ihelp-125ea-default-rtdb.firebaseio.com/")
    private var localPortState by mutableStateOf("5002")
    private var remotePortState by mutableStateOf("5001")
    private var targetIpState by mutableStateOf("127.0.0.1")
    private var selectedModeState by mutableStateOf(VoiceEngine.ConnectionMode.Cloud_WebSocket_Relay)
    private var relayServerUrlState by mutableStateOf("wss://pos-voice-support.onrender.com")

    // Call state mapping
    private var isCallConnected by mutableStateOf(false)
    private var currentCallerId by mutableStateOf("")
    private var shopName by mutableStateOf("---")
    private var callerIdDisplay by mutableStateOf("---")
    private var smsBoxText by mutableStateOf("")
    private var labelMissedCall by mutableStateOf("📞No Active Calls")
    private var callDurationFormatted by mutableStateOf("00:00")
    
    // UI Helpers
    private var isPollingActive by mutableStateOf(true)
    private var isSimulationActive by mutableStateOf(false)
    private var isSettingsExpanded by mutableStateOf(false)
    private var dbConnectionStatus by mutableStateOf("Listening") // "Listening", "Ringing", "Connected", "Error"
    
    // Background execution handles
    private var pollingJob: Job? = null
    private var callTimerJob: Job? = null
    private var callStartTime: Long = 0

    // Permission receiver
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            logSystem("Microphone recording permission granted.")
        } else {
            logSystem("Warning: Microphone record permission is denied. Streaming audio capture will not function.")
            Toast.makeText(this, "Microphone permission is required to stream audio!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        firebaseClient = FirebaseRestClient(baseFirebaseUrl)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        // Request recording permission on launch
        checkAndRequestMicrophonePermission()

        // Start real-time background Firebase polling loop
        startFirebaseCallbackLoop()

        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF000502), // Deep pitch black-green
                                        Color(0xFF021005), // Subtle math-green matrix accent glow
                                        Color(0xFF000000)  // Solid pitch black base
                                    )
                                )
                            )
                            .padding(innerPadding)
                    ) {
                        // Drawing subtle green scanner grid lines (Hacker Matrix style)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeW = 1.dp.toPx()
                            val colorGrid = Color(0xFF00FF66).copy(alpha = 0.05f)
                            val step = 40.dp.toPx()
                            
                            // Vertical grid lines
                            var x = 0f
                            while (x < size.width) {
                                drawLine(
                                    color = colorGrid,
                                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                                    strokeWidth = strokeW
                                )
                                x += step
                            }
                            
                            // Horizontal grid lines
                            var y = 0f
                            while (y < size.height) {
                                drawLine(
                                    color = colorGrid,
                                    start = androidx.compose.ui.geometry.Offset(0f, y),
                                    end = androidx.compose.ui.geometry.Offset(size.width, y),
                                    strokeWidth = strokeW
                                )
                                y += step
                            }
                            
                            // Draw some faint circular sonar radar lines in the center background
                            drawCircle(
                                color = Color(0xFF00FF66).copy(alpha = 0.03f),
                                radius = size.minDimension * 0.4f,
                                style = Stroke(width = 1.dp.toPx())
                            )
                            drawCircle(
                                color = Color(0xFF00FF66).copy(alpha = 0.02f),
                                radius = size.minDimension * 0.25f,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }

                        SupportConsoleLayout()
                    }
                }
            }
        }
    }

    private fun checkAndRequestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Ticking Timer Call loop - Runs every 1 sec exactly like .NET's TimerCheckCall_Tick
     */
    private fun startFirebaseCallbackLoop() {
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isPollingActive) {
                try {
                    if (isSimulationActive) {
                        delay(1000)
                        continue
                    }
                    if (!isCallConnected) {
                        // 1. CALL STANDBY LOOKUP (No active connection joined yet)
                        val allCalls = firebaseClient.fetchAllCalls()
                        
                        withContext(Dispatchers.Main) {
                            if (allCalls != null && allCalls.isNotEmpty()) {
                                var incomingFound = false
                                for ((callerKey, details) in allCalls) {
                                    if (details.call_status == "calling") {
                                        incomingFound = true
                                        
                                        // Lock active incoming connection ID
                                        currentCallerId = callerKey
                                        shopName = details.shop_name ?: "Unknown Client"
                                        callerIdDisplay = details.caller_id ?: callerKey
                                        smsBoxText = details.sms ?: ""
                                        labelMissedCall = "📞INCOMING CALL : $shopName"
                                        targetIpState = details.client_ip ?: "127.0.0.1"
                                        dbConnectionStatus = "Ringing"

                                        // Start audio calling ringtone and device vibration loops
                                        triggerRingtoneAlert(true)
                                        triggerVibrateAlert(true)
                                        break
                                    }
                                }
                                if (!incomingFound) {
                                    // No active calls ringing
                                    triggerRingtoneAlert(false)
                                    triggerVibrateAlert(false)
                                    labelMissedCall = "📞No Active Calls"
                                    dbConnectionStatus = "Listening"
                                    shopName = "---"
                                    callerIdDisplay = "---"
                                    smsBoxText = ""
                                }
                            } else {
                                // No calls in database node
                                triggerRingtoneAlert(false)
                                triggerVibrateAlert(false)
                                labelMissedCall = "📞No Active Calls"
                                dbConnectionStatus = "Listening"
                                shopName = "---"
                                callerIdDisplay = "---"
                                smsBoxText = ""
                            }
                        }
                    } else {
                        // 2. ACTIVE CALL CONNECTED MONITORING
                        if (currentCallerId.isNotEmpty()) {
                            val details = firebaseClient.fetchCallDetails(currentCallerId)
                            withContext(Dispatchers.Main) {
                                if (details != null) {
                                    smsBoxText = details.sms ?: ""
                                    
                                    // If database state is updated to ended, flush line locally
                                    if (details.call_status == "ended") {
                                        triggerLocalReset()
                                        logSystem("Call ended remotely by Client.")
                                    }
                                } else {
                                    triggerLocalReset()
                                    logSystem("Call removed from remote server.")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Polling Tick failure", e)
                    withContext(Dispatchers.Main) {
                        dbConnectionStatus = "Error"
                    }
                }
                delay(1000) // 1-second interval ticking
            }
        }
    }

    /**
     * Start/Stop ringtone playback seamlessly without locking UI Threads using MediaPlayer
     */
    private fun triggerRingtoneAlert(play: Boolean) {
        if (play) {
            if (mediaPlayer?.isPlaying == true) return
            try {
                val ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@MainActivity, ringUri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                // Synthesizer fallback/Alarm tone if system default is locked
                try {
                    val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(this@MainActivity, alertUri)
                        isLooping = true
                        prepare()
                        start()
                    }
                } catch (e2: Exception) {
                    Log.e("MainActivity", "Failed to play backup ringtone alert", e2)
                }
            }
        } else {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (e: Exception) {
                // Safety block
            }
            mediaPlayer = null
        }
    }

    /**
     * Start/Stop system warning haptic vibrations
     */
    private fun triggerVibrateAlert(vibrate: Boolean) {
        vibrator?.let { v ->
            if (vibrate) {
                if (v.hasVibrator()) {
                    val pattern = longArrayOf(0, 800, 800) // Vibrate 800ms, sleep 800ms
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 loops continuously
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(pattern, 0)
                    }
                }
            } else {
                v.cancel()
            }
        }
    }

    /**
     * Starts call connection stopwatch timer
     */
    private fun startCallStopwatch() {
        callTimerJob?.cancel()
        callStartTime = System.currentTimeMillis()
        callTimerJob = CoroutineScope(Dispatchers.Main).launch {
            while (isCallConnected) {
                val duration = System.currentTimeMillis() - callStartTime
                val minutes = (duration / 1000) / 60
                val seconds = (duration / 1000) % 60
                callDurationFormatted = String.format("%02d:%02d", minutes, seconds)
                delay(1000)
            }
        }
    }

    /**
     * Action Caller Accept - Trigger Voip Audio active stream
     */
    private fun acceptIncomingCall() {
        if (currentCallerId.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Halt alerting ringtones
                withContext(Dispatchers.Main) {
                    triggerRingtoneAlert(false)
                    triggerVibrateAlert(false)
                    isCallConnected = true
                    dbConnectionStatus = "Connected"
                }

                // 2. Set Firebase Realtime status to "received" (or skip if simulation)
                val myIP = VoiceEngine.getLocalIPAddress()
                val isSaved = if (isSimulationActive) true else firebaseClient.updateCallStatus(currentCallerId, "received", myIP)

                if (isSaved) {
                    // Update Local configurations inside Engine static parameters
                    VoiceEngine.selectedMode = selectedModeState
                    VoiceEngine.relayServerUrl = relayServerUrlState

                    // Start microphone capturing and playback streaming
                    val lPort = localPortState.toIntOrNull() ?: 5002
                    val rPort = remotePortState.toIntOrNull() ?: 5001

                    withContext(Dispatchers.Main) {
                        logSystem("Database updated. Joining Isolated Audio Room: $currentCallerId")
                        VoiceEngine.startStream(lPort, targetIpState, rPort, currentCallerId) { message ->
                            logSystem(message)
                        }
                        startCallStopwatch()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isCallConnected = false
                        dbConnectionStatus = "Error"
                        logSystem("Error: Failed to register call acceptance to Database.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCallConnected = false
                    dbConnectionStatus = "Error"
                    logSystem("Accept call breakdown: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Action Call Hang-up Click
     */
    private fun triggerSimulatedIncomingCall() {
        isSimulationActive = true
        currentCallerId = "simulated_test_call_99"
        shopName = "Feroz Android Lab (Simulated)"
        callerIdDisplay = "FEROZ_DEV_VoIP"
        smsBoxText = "Design is stunning! Simulated call is active. Press the green Accept button to answer and play."
        labelMissedCall = "📞INCOMING CALL : $shopName"
        targetIpState = "127.0.0.1"
        dbConnectionStatus = "Ringing"
        triggerRingtoneAlert(true)
        triggerVibrateAlert(true)
        logSystem("Initiated local simulated VoIP signal test.")
    }

    private fun endActiveCall() {
        val tempId = currentCallerId
        triggerLocalReset()

        if (tempId.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    firebaseClient.updateCallStatus(tempId, "ended")
                    withContext(Dispatchers.Main) {
                        logSystem("Support secure line ended successfully.")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Firebase EndCall failed", e)
                }
            }
        }
    }

    /**
     * Clean and disconnect all hardware references and clock timelines safely
     */
    private fun triggerLocalReset() {
        isCallConnected = false
        isSimulationActive = false
        triggerRingtoneAlert(false)
        triggerVibrateAlert(false)
        
        callTimerJob?.cancel()
        callDurationFormatted = "00:00"
        dbConnectionStatus = "Listening"
        labelMissedCall = "📞No Active Calls"
        shopName = "---"
        callerIdDisplay = "---"
        smsBoxText = ""

        // Safe release of VoIP background threads
        CoroutineScope(Dispatchers.Main).launch {
            VoiceEngine.stopStream()
            logSystem("VoIP engine streaming terminated. Awaiting connection invite...")
        }
    }

    private fun logSystem(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            logs.add("[$callDurationFormatted] $message")
            if (logs.size > 15) {
                logs.removeAt(0)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPollingActive = false
        pollingJob?.cancel()
        callTimerJob?.cancel()
        triggerRingtoneAlert(false)
        triggerVibrateAlert(false)
        VoiceEngine.stopStream()
    }

    //========================================================================
    // COMPOSE LAYOUT PRESENTATION
    //========================================================================
    @Composable
    private fun SupportConsoleLayout() {
        val contentPadding = 16.dp
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Elegant Header
            HeaderView()

            // Status Dashboard Monitor Card (Pulse Radar)
            StatusRadarView()

            // Simulation Action Banner when idle
            AnimatedVisibility(visible = currentCallerId.isEmpty()) {
                SimulatedCallActionBanner()
            }

            // Active Calling Controls
            AnimatedVisibility(visible = currentCallerId.isNotEmpty()) {
                CallDetailCard()
            }

            // Connection Configuration Panel
            AnimatedVisibility(visible = !isCallConnected) {
                SettingsPanelCard()
            }

            // Real-Time Activity Logs console terminal
            ActivityLogsConsole()
        }
    }

    @Composable
    private fun SimulatedCallActionBanner() {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFF00FF66), Color(0xFF1E1B4B))
                    ),
                    shape = RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF030704)) // Pitch-black green container
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF9100))
                    )
                    Text(
                        text = "VOIP SIGNAL TEST BENCH",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF66),
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "If no client is currently calling from Firebase, press below to trigger a simulated incoming signal. This allows full local testing of the ringtone playback, haptic warnings, call connection, and hangup behaviors.",
                    fontSize = 11.sp,
                    color = Color(0xFFBDC8CC),
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.SansSerif,
                    lineHeight = 15.sp
                )
                Button(
                    onClick = { triggerSimulatedIncomingCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF66)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("btn_simulate_test_call")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Simulate",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "SIMULATE INCOMING CALL",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun HeaderView() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "My Contact",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00FF66), // Sizzling matrix neon green
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "App Developer by Feroz Ahmed",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBDC8CC), // High-visibility hacker text
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }

            // Status Badge based on DB state connection
            val (badgeBg, badgeText, badgeColor) = when (dbConnectionStatus) {
                "Listening" -> Triple(Color(0xFF1E261F), "LISTENING", Color(0xFF4CAF50))
                "Ringing" -> Triple(Color(0xFF331E1E), "ALERTING", Color(0xFFFF3D00))
                "Connected" -> Triple(Color(0xFF1B2E3C), "STREAMING", Color(0xFF00B0FF))
                else -> Triple(Color(0xFF242424), "CONNECTING", Color(0xFFFFEB3B))
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(badgeBg)
                    .border(1.dp, badgeColor, RoundedCornerShape(32.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusRadarView() {
        val infiniteTransition = rememberInfiniteTransition(label = "RadarAnimation")
        
        // Scan pulse size indicator
        val scalePulse by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse"
        )
        
        // Scan pulse opacity indicator
        val alphaPulse by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "alpha"
        )

        // Logo continuous rotation angle
        val logoRotAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(6000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "logoRotation"
        )

        // Custom pulsing cyber alert outline
        val pulseAlertColor by infiniteTransition.animateColor(
            initialValue = Color(0xFF00FF66),
            targetValue = Color(0xFFFF1744),
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "colorAlert"
        )

        val strokeColor = if (dbConnectionStatus == "Ringing") pulseAlertColor else Color(0xFF00FF66)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    2.dp,
                    if (dbConnectionStatus == "Ringing") pulseAlertColor else Color(0xFF00FF66),
                    RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF020403)) // Absolute scary hacker black container
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                
                // Double Glowing Green Neon Light Halos and rotating logo box
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(150.dp)
                ) {
                    // Pulsing Outer Green Neon Rosni Halo ("সবুজ রোসনি স্যাডো")
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(scalePulse)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF00FF66).copy(alpha = 0.40f * alphaPulse),
                                        Color(0xFF00FF66).copy(alpha = 0.10f * alphaPulse),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // Inner static glowing green core halo shadow
                    Box(
                        modifier = Modifier
                            .size(115.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF00FF66).copy(alpha = 0.30f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // Rotating Cyber Frame carrying Feroz's dynamic vector logo
                    Box(
                        modifier = Modifier
                            .size(105.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF000000)) // Pitch Black logo core
                            .border(1.5.dp, strokeColor, CircleShape)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        FerozHackerLogo(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(rotationZ = logoRotAngle) // Smooth dynamic 360 rotation
                        )
                    }

                    // Rotating dotted radar scanner line outside the core
                    Canvas(
                        modifier = Modifier
                            .size(135.dp)
                            .graphicsLayer(rotationZ = logoRotAngle * 1.5f)
                    ) {
                        drawCircle(
                            color = strokeColor.copy(alpha = 0.15f),
                            radius = size.width / 2f,
                            style = Stroke(width = 1.dp.toPx())
                        )
                        // Decorative crosshair sweep tick
                        drawCircle(
                            color = strokeColor.copy(alpha = 0.4f),
                            radius = 6f,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Prominent Brand Text - requested by user below logo
                    Text(
                        text = "Freelancer Feroz",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF00FF66), // Glowing green monospace color
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        letterSpacing = 1.5.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (dbConnectionStatus == "Ringing") "ALARM: INCOMING SIGNAL!" else labelMissedCall,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = when (dbConnectionStatus) {
                            "Listening" -> "Real-time dispatch system active and monitoring Firebase node successfully."
                            "Ringing" -> "An incoming support line is waiting. Review details below and accept."
                            "Connected" -> "High-speed encrypted audio transmission line active. Room isolate verified."
                            else -> "Initializing line database configurations..."
                        },
                        fontSize = 11.sp,
                        color = Color(0xFF90A4AE),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun FerozHackerLogo(modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height
            val inset = w * 0.22f
            
            // 1. Draw outer Octagon Border (matching purple and navy design)
            val octagonPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(inset, 0f)
                lineTo(w - inset, 0f)
                lineTo(w, inset)
                lineTo(w, h - inset)
                lineTo(w - inset, h)
                lineTo(inset, h)
                lineTo(0f, h - inset)
                lineTo(0f, inset)
                close()
            }
            
            // Outer glowing octagonal ring (deep violet)
            drawPath(
                path = octagonPath,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF8E24AA), Color(0xFF3F51B5))
                ),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )

            // Inner styling lines replicating modern octagonal geometry
            val innerInset = w * 0.26f
            val iShift = 6.dp.toPx()
            val innerOctagonPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(innerInset, iShift)
                lineTo(w - innerInset, iShift)
                lineTo(w - iShift, innerInset)
                lineTo(w - iShift, h - innerInset)
                lineTo(w - innerInset, h - iShift)
                lineTo(innerInset, h - iShift)
                lineTo(iShift, h - innerInset)
                lineTo(iShift, innerInset)
                close()
            }
            drawPath(
                path = innerOctagonPath,
                color = Color(0xFF4A148C).copy(alpha = 0.5f),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Interlocking "FF" Letters from Freelancer Feroz's image
            // Top 'F' (Bright violet color)
            val topFPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.35f, h * 0.38f)
                lineTo(w * 0.35f, h * 0.22f)
                quadraticBezierTo(w * 0.35f, h * 0.15f, w * 0.48f, h * 0.15f)
                lineTo(w * 0.75f, h * 0.15f)
                quadraticBezierTo(w * 0.82f, h * 0.20f, w * 0.72f, h * 0.28f)
                lineTo(w * 0.48f, h * 0.28f)
                lineTo(w * 0.48f, h * 0.38f)
                close()
            }
            drawPath(
                path = topFPath,
                color = Color(0xFFAB47BC)
            )

            // Bottom 'F' (Extremely dark navy-black shadow F)
            val bottomFPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.35f, h * 0.38f)
                lineTo(w * 0.62f, h * 0.38f)
                quadraticBezierTo(w * 0.70f, h * 0.42f, w * 0.66f, h * 0.48f)
                lineTo(w * 0.44f, h * 0.48f)
                lineTo(w * 0.44f, h * 0.72f)
                quadraticBezierTo(w * 0.40f, h * 0.82f, w * 0.32f, h * 0.74f)
                lineTo(w * 0.32f, h * 0.38f)
                close()
            }
            drawPath(
                path = bottomFPath,
                color = Color(0xFF1E1B4B)
            )
        }
    }

    @Composable
    private fun CallDetailCard() {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, Color(0xFF00E676), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B22))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header call info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isCallConnected) "CONNECTED LINE" else "WAITING ANSWER...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isCallConnected) Color(0xFF00B0FF) else Color(0xFFFF9100)
                    )
                    
                    // Connected stopwatch timer
                    Text(
                        text = "CALL TIME: $callDurationFormatted",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF263238))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                HorizontalDivider(color = Color(0xFF1F2936))

                // Detail entries
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailRow(label = "CLIENT (SHOP)", value = shopName)
                    DetailRow(label = "CALLER ID", value = callerIdDisplay)
                    DetailRow(
                        label = "CLIENT NETWORK IP",
                        value = if (selectedModeState == VoiceEngine.ConnectionMode.Local_Direct_UDP) targetIpState else "Cloud Server Isolated"
                    )
                }

                // SMS Live Note Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0C1014))
                        .border(1.dp, Color(0xFF1F2E3B), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "SMS Note (Real-Time Client Input):",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = smsBoxText.ifEmpty { "No SMS note generated by client node." },
                        fontSize = 13.sp,
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.testTag("sms_note_text")
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Control Action Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isCallConnected) {
                        // Accept Call Button
                        Button(
                            onClick = { acceptIncomingCall() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(50.dp)
                                .testTag("btn_accept_call")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = "Accept",
                                    tint = Color.Black
                                )
                                Text(
                                    text = "ACCEPT Support",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.Black
                                )
                            }
                        }

                        // Dismiss/Reject call
                        Button(
                            onClick = { endActiveCall() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("btn_dismiss_call")
                        ) {
                            Text(
                                text = "MUTE / END",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    } else {
                        // Hang up line button (End Call)
                        Button(
                            onClick = { endActiveCall() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("btn_hangup_line")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Hang up",
                                    tint = Color.White
                                )
                                Text(
                                    text = "SECURE LINE : DISCONNECT HANGUP",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = Color(0xFF90A4AE),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = value,
                fontSize = 12.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun SettingsPanelCard() {
        val keyboardScrollTrigger = rememberScrollState()

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1F2936), RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111418))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header title toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isSettingsExpanded = !isSettingsExpanded }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Network Settings Icon",
                            tint = Color(0xFF90A4AE)
                        )
                        Text(
                            text = "VoIP Connection & Network Settings",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Icon(
                        imageVector = if (isSettingsExpanded) Icons.Default.Close else Icons.Default.Settings,
                        contentDescription = "Arrow Toggle",
                        tint = Color(0xFF90A4AE),
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(visible = isSettingsExpanded) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HorizontalDivider(color = Color(0xFF1F2936))

                        // Connection Mode Select
                        Text(
                            text = "Connection Protocol Mode:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF90A4AE),
                            fontFamily = FontFamily.Monospace
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Cloud mode selector button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (selectedModeState == VoiceEngine.ConnectionMode.Cloud_WebSocket_Relay)
                                            Color(0xFF00E676).copy(alpha = 0.15f)
                                        else
                                            Color(0xFF1A1F26)
                                    )
                                    .border(
                                        1.dp,
                                        if (selectedModeState == VoiceEngine.ConnectionMode.Cloud_WebSocket_Relay)
                                            Color(0xFF00E676)
                                        else
                                            Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        selectedModeState = VoiceEngine.ConnectionMode.Cloud_WebSocket_Relay
                                        logSystem("Voice connection mode adjusted to: WebSocket Relay Server.")
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Cloud WS Relay",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedModeState == VoiceEngine.ConnectionMode.Cloud_WebSocket_Relay)
                                        Color(0xFF00E676)
                                    else
                                        Color.White
                                )
                            }

                            // Local direct mode selector button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (selectedModeState == VoiceEngine.ConnectionMode.Local_Direct_UDP)
                                            Color(0xFF00E676).copy(alpha = 0.15f)
                                        else
                                            Color(0xFF1A1F26)
                                    )
                                    .border(
                                        1.dp,
                                        if (selectedModeState == VoiceEngine.ConnectionMode.Local_Direct_UDP)
                                            Color(0xFF00E676)
                                        else
                                            Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        selectedModeState = VoiceEngine.ConnectionMode.Local_Direct_UDP
                                        logSystem("Voice connection mode adjusted to: Direct UDP Sockets.")
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Local Direct UDP",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedModeState == VoiceEngine.ConnectionMode.Local_Direct_UDP)
                                        Color(0xFF00E676)
                                    else
                                        Color.White
                                )
                            }
                        }

                        // Network parameters input
                        OutlinedTextField(
                            value = baseFirebaseUrl,
                            onValueChange = {
                                baseFirebaseUrl = it
                                firebaseClient = FirebaseRestClient(it)
                            },
                            label = { Text("Firebase URL BasePath") },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E676)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field_firebase_url")
                        )

                        if (selectedModeState == VoiceEngine.ConnectionMode.Cloud_WebSocket_Relay) {
                            OutlinedTextField(
                                value = relayServerUrlState,
                                onValueChange = { relayServerUrlState = it },
                                label = { Text("Relay WS URL link") },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF00E676)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = localPortState,
                                    onValueChange = { localPortState = it },
                                    label = { Text("Local UDP Port") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = remotePortState,
                                    onValueChange = { remotePortState = it },
                                    label = { Text("Remote Port") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Flush reset hardware triggers button
                        Button(
                            onClick = { triggerLocalReset() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Halt Icon",
                                    modifier = Modifier.size(14.dp)
                                )
                                Text("Emergency Terminate & Reset Hub", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Display brief info strip of local static IPv4 parameters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1C222B))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LOCAL DEVICE IPv4:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF90A4AE),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = VoiceEngine.getLocalIPAddress(),
                        fontSize = 11.sp,
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    @Composable
    private fun ActivityLogsConsole() {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1F2936), RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF080C0F))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676))
                        )
                        Text(
                            text = "VOICE ENGINE LOG TERMINAL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    
                    // Clear logs button
                    Text(
                        text = "CLEAR",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5252),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable { logs.clear() }
                            .padding(4.dp)
                    )
                }

                HorizontalDivider(color = Color(0xFF1F2936))

                // Log outputs console screen
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 150.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "[00:00] System ready. Ready for incoming database signals...",
                            fontSize = 11.sp,
                            color = Color(0xFF455A64),
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        logs.forEach { log ->
                            Text(
                                text = log,
                                fontSize = 11.sp,
                                color = if (log.contains("error", ignoreCase = true) || log.contains("Crash", ignoreCase = true)) Color(0xFFFF5252) else Color(0xFFBDC8CC),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
