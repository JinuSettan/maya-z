package com.cocateail.maya.ui.components

import android.Manifest
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

enum class CallState {
    LISTENING,
    THINKING,
    SPEAKING,
    MUTED
}

@Composable
fun VoiceCallDialog(
    character: MayaCharacter,
    accentColor: Color,
    onSendMessage: suspend (String) -> String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var callState by remember { mutableStateOf(CallState.SPEAKING) }
    var isMuted by remember { mutableStateOf(false) }
    var transcribedText by remember { mutableStateOf("") }
    var aiResponseText by remember { mutableStateOf("") }
    var callDurationSeconds by remember { mutableLongStateOf(0L) }

    // Speech & TTS References
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    var hasGreeted by remember { mutableStateOf(false) }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            onDismiss()
        }
    }

    // Call Timer Effect
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        while (true) {
            delay(1000)
            callDurationSeconds++
        }
    }

    // Initialize SpeechRecognizer & TTS with natural human voice tuning
    DisposableEffect(Unit) {
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine?.language = Locale.US
                // Pitch & speed tuning for warm, sharp, natural human-like voice
                if (character == MayaCharacter.ROCKY) {
                    ttsEngine?.setPitch(1.15f)
                    ttsEngine?.setSpeechRate(1.05f)
                } else {
                    ttsEngine?.setPitch(1.08f)
                    ttsEngine?.setSpeechRate(0.98f)
                }
                isTtsReady = true
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        onDispose {
            try {
                speechRecognizer?.destroy()
                ttsEngine?.stop()
                ttsEngine?.shutdown()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Function to start listening for user speech
    val startListening = remember(speechRecognizer) {
        {
            if (!isMuted) {
                callState = CallState.LISTENING
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        callState = CallState.THINKING
                    }

                    override fun onError(error: Int) {
                        // Restart listening if quiet
                        if (callState == CallState.LISTENING && !isMuted) {
                            speechRecognizer?.startListening(intent)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()
                        if (!spoken.isNullOrBlank()) {
                            transcribedText = spoken
                            callState = CallState.THINKING
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                speechRecognizer?.startListening(intent)
            }
        }
    }

    // Speak helper function
    val speakText = remember(ttsEngine) {
        { text: String ->
            aiResponseText = text
            callState = CallState.SPEAKING

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "CALL_UTTERANCE")
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)

            ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    callState = CallState.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    if (!isMuted) {
                        startListening()
                    } else {
                        callState = CallState.MUTED
                    }
                }

                override fun onError(utteranceId: String?) {
                    if (!isMuted) startListening()
                }
            })

            ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "CALL_UTTERANCE")
        }
    }

    // Proactive initial greeting on call start
    LaunchedEffect(isTtsReady) {
        if (isTtsReady && !hasGreeted) {
            hasGreeted = true
            delay(300)
            val initialGreeting = if (character == MayaCharacter.ROCKY) {
                "Hey there! Rocky here, ready to roll! What's on your mind today?"
            } else {
                "Hello! I'm Maya, I'm right here listening to you. How can I help you today?"
            }
            speakText(initialGreeting)
        }
    }

    // Process spoken text through AI when transcribedText updates
    LaunchedEffect(transcribedText) {
        if (transcribedText.isNotBlank()) {
            callState = CallState.THINKING
            val spokenPrompt = transcribedText
            transcribedText = ""

            val response = onSendMessage(spokenPrompt)
            if (response.isNotBlank() && isTtsReady) {
                speakText(response)
            } else {
                startListening()
            }
        }
    }

    // Dynamic Pulsing Ring Animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale"
    )

    val formatTime = { seconds: Long ->
        val mins = seconds / 60
        val secs = seconds % 60
        String.format("%02d:%02d", mins, secs)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Call Header
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    Text(
                        text = "MAYA VOICE CALL",
                        color = VermilionPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = character.displayName,
                        color = TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatTime(callDurationSeconds),
                        color = TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Animated Avatar Sphere
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(240.dp)
                ) {
                    // Outer Pulsing Aura
                    Box(
                        modifier = Modifier
                            .size(if (callState == CallState.SPEAKING || callState == CallState.LISTENING) 220.dp else 180.dp)
                            .scale(if (callState == CallState.SPEAKING || callState == CallState.LISTENING) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.2f))
                    )

                    // Inner Avatar Sphere
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .clip(CircleShape)
                            .background(DarkCard)
                            .border(3.dp, accentColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = character.avatarLetter,
                            color = accentColor,
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Call Status & Live Subtitle Card
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = DarkSurface,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = when (callState) {
                                    CallState.LISTENING -> "🎤 Listening to you..."
                                    CallState.THINKING -> "🧠 ${character.displayName} is thinking..."
                                    CallState.SPEAKING -> "🗣️ ${character.displayName} is speaking..."
                                    CallState.MUTED -> "⏸️ Microphone Muted"
                                },
                                color = accentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )

                            if (aiResponseText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = aiResponseText.take(140) + if (aiResponseText.length > 140) "..." else "",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    maxLines = 3
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Call Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mute Button
                        IconButton(
                            onClick = {
                                isMuted = !isMuted
                                if (isMuted) {
                                    callState = CallState.MUTED
                                    speechRecognizer?.stopListening()
                                } else {
                                    startListening()
                                }
                            },
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(if (isMuted) ErrorRed else DarkSurface)
                                .border(1.dp, DarkBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute",
                                tint = TextPrimary
                            )
                        }

                        // End Call Button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(ErrorRed)
                                .testTag("end_voice_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                tint = TextPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Speaker Icon
                        IconButton(
                            onClick = { },
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(DarkSurface)
                                .border(1.dp, DarkBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Speaker",
                                tint = accentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
