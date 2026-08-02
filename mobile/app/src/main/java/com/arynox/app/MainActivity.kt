package com.arynox.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

val Bg = Color(0xFF0F1117)
val Surface1 = Color(0xFF1B1F2A)
val Accent = Color(0xFF4F8CFF)
val Accent2 = Color(0xFF8AB4F8)
val Green = Color(0xFF34D399)
val Red = Color(0xFFF87171)
val Amber = Color(0xFFFBBF24)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    ArynoxApp()
                }
            }
        }
    }
}

@Composable
fun ArynoxApp(vm: ChatViewModel = viewModel()) {
    val phase by vm.phase.collectAsState()
    val logs by vm.logs.collectAsState()
    val messages by vm.messages.collectAsState()
    val tier by vm.tier.collectAsState()
    val installed by vm.installed.collectAsState()
    val progress by vm.installProgress.collectAsState()
    val typing by vm.typing.collectAsState()
    val ttsEnabled by vm.ttsEnabled.collectAsState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val cameraFile = remember { File(context.cacheDir, "capture.jpg") }

    val sendImage = { uri: Uri, text: String ->
        busy = true
        val bytes = try {
            val stream = ByteArrayOutputStream()
            val bmp = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
            val scaled = scaleDown(bmp, 1280)
            bmp?.recycle()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            stream.toByteArray()
        } catch (_: Exception) {
            null
        }
        if (bytes != null) {
            vm.send(text, Base64.encodeToString(bytes, Base64.NO_WRAP))
        } else {
            vm.log("Could not read that image.")
        }
        busy = false
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && !busy) sendImage(uri, input.ifEmpty { "What do you see here?" })
        input = ""
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok && !busy) sendImage(Uri.fromFile(cameraFile), input.ifEmpty { "What do you see here?" })
        input = ""
    }

    val camPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera(context, cameraFile, cameraLauncher)
        else vm.log("Camera permission denied.")
    }

    val micPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening(context, vm) { s -> listening = s }
        else vm.log("Microphone permission denied.")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF12151E), Bg)))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Accent, Accent2))),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("ARYNOX", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    when (phase) {
                        is ChatViewModel.Phase.Running -> "● online - I see, hear & remember"
                        is ChatViewModel.Phase.Download -> "… downloading"
                        is ChatViewModel.Phase.Start -> "… starting"
                        is ChatViewModel.Phase.Error -> "! needs attention"
                        else -> "○ offline"
                    },
                    color = when (phase) {
                        is ChatViewModel.Phase.Running -> Green
                        is ChatViewModel.Phase.Download, is ChatViewModel.Phase.Start -> Amber
                        is ChatViewModel.Phase.Error -> Red
                        else -> Color.Gray
                    },
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.weight(1f))
            if (installed) {
                Text("tier: $tier", color = Accent2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = { vm.toggleTts() }) {
                Text(if (ttsEnabled) "🔊" else "🔇", fontSize = 18.sp)
            }
        }

        // Progress card
        if (progress != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = Surface1
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(progress!!.first, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${(progress!!.second * 100).toInt()}%", color = Accent2, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress!!.second },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Accent,
                        trackColor = Color(0xFF2A3040)
                    )
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!installed) {
                Button(
                    onClick = { vm.install() },
                    enabled = phase !is ChatViewModel.Phase.Download && phase !is ChatViewModel.Phase.Error,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("⬇ Install AI (${Models.TIERS[tier]?.needGb ?: 5} GB)")
                }
            } else {
                if (phase is ChatViewModel.Phase.Running) {
                    Button(
                        onClick = { vm.stop() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Red)
                    ) { Text("■ Stop") }
                } else {
                    Button(
                        onClick = { vm.start() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("▶ Start AI") }
                }
            }
        }

        // Logs while not chatting yet
        if (messages.isEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { line ->
                    Text(
                        line,
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            // Chat
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { m ->
                    val mine = m.role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .clip(RoundedCornerShape(16.dp)),
                            color = if (mine) Accent else Surface1
                        ) {
                            Text(
                                m.text,
                                modifier = Modifier.padding(12.dp),
                                color = if (mine) Color.White else Accent2,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                if (typing) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(16.dp), color = Surface1) {
                                Text("Arynox is thinking...", Modifier.padding(12.dp),
                                    color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // Input bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Surface1
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledIconButton(
                        onClick = {
                            val p = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            if (p == PackageManager.PERMISSION_GRANTED) {
                                startListening(context, vm) { s -> listening = s }
                            } else {
                                micPerm.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = phase is ChatViewModel.Phase.Running && !busy,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (listening) Red else Color(0xFF2A3040))
                    ) { Text(if (listening) "🟥" else "🎤", fontSize = 16.sp) }
                    FilledIconButton(
                        onClick = {
                            val p = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            if (p == PackageManager.PERMISSION_GRANTED) {
                                launchCamera(context, cameraFile, cameraLauncher)
                            } else {
                                camPerm.launch(Manifest.permission.CAMERA)
                            }
                        },
                        enabled = phase is ChatViewModel.Phase.Running && !busy,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2A3040))
                    ) { Text("📷", fontSize = 16.sp) }
                    FilledIconButton(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = phase is ChatViewModel.Phase.Running && !busy,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2A3040))
                    ) { Text("🖼", fontSize = 16.sp) }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask me anything...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Color(0xFF2A3040),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Accent
                        ),
                        maxLines = 2
                    )
                    Button(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = phase is ChatViewModel.Phase.Running && input.isNotBlank() && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Send") }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(
                        "What do you see?" to "Describe my surroundings.",
                        "Remember this as Sam" to "Save a face from a photo.",
                        "Who is Sam?" to "Recall a memory.",
                        "What can you do?" to "Help.",
                    ).forEach { (label, hint) ->
                        TextButton(
                            onClick = { input = label },
                            enabled = phase is ChatViewModel.Phase.Running
                        ) { Text(label, color = Accent2, fontSize = 12.sp) }
                    }
                }
                Text(
                    "100% offline • models stored on your phone",
                    color = Color(0xFF6B7280),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

private fun launchCamera(
    context: android.content.Context,
    file: File,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<Uri, Boolean>
) {
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file)
    launcher.launch(uri)
}

private fun startListening(
    context: android.content.Context,
    vm: ChatViewModel,
    setListening: (Boolean) -> Unit
) {
    val sr = SpeechRecognizer.createSpeechRecognizer(context)
    sr.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() { setListening(true) }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            setListening(false)
            sr.destroy()
        }
        override fun onResults(results: android.os.Bundle?) {
            setListening(false)
            sr.destroy()
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrEmpty()) vm.send(text)
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    })
    val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    try {
        sr.startListening(intent)
    } catch (_: Exception) {
        setListening(false)
        sr.destroy()
        vm.log("Voice input unavailable on this device.")
    }
}

private fun scaleDown(bmp: Bitmap?, maxDim: Int): Bitmap {
    if (bmp == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val w = bmp.width
    val h = bmp.height
    val m = maxOf(w, h)
    if (m <= maxDim) return bmp
    val scale = maxDim.toFloat() / m
    return Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
}
