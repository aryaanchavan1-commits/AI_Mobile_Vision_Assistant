package com.arynox.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

val Bg = Color(0xFF0F1117)
val Surface1 = Color(0xFF1B1F2A)
val Accent = Color(0xFF4F8CFF)
val Accent2 = Color(0xFF8AB4F8)
val Green = Color(0xFF34D399)
val Red = Color(0xFFF87171)
val Amber = Color(0xFFFBBF24)
val Edge = Color(0xFF2A3040)

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
    val listenEnabled by vm.listenEnabled.collectAsState()
    val visionEnabled by vm.visionEnabled.collectAsState()
    val liveCaption by vm.liveCaption.collectAsState()
    val webNote by vm.webNote.collectAsState()
    val speaking by vm.speaking.collectAsState()
    var input by remember { mutableStateOf("") }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val handler = remember { Handler(Looper.getMainLooper()) }

    var camOk by remember { mutableStateOf(false) }
    var micOk by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        camOk = r[Manifest.permission.CAMERA] == true
        micOk = r[Manifest.permission.RECORD_AUDIO] == true
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 23) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    // ---- Camera: live preview + periodic capture for the vision loop ----
    val previewView = remember { PreviewView(context) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    LaunchedEffect(Unit) {
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                cameraCapture = capture
                cameraReady = true
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(cameraReady, phase, visionEnabled) {
        while (isActive && cameraReady && phase is ChatViewModel.Phase.Running && visionEnabled) {
            val cap = cameraCapture
            if (cap != null) {
                cap.takePicture(ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bmp = image.toBitmap()
                                val scaled = scaleDown(bmp, 720)
                                val stream = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                                val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                                if (b64.isNotEmpty()) vm.onVisionFrame(b64)
                            } catch (_: Exception) {
                            } finally {
                                image.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {}
                    })
            }
            delay(8000)
        }
    }

    // ---- Always listening: continuous speech recognition ----
    val recognizeIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val restartListen: () -> Unit = remember {
        {
            val sr = recognizer
            if (sr != null && micOk && listenEnabled &&
                phase is ChatViewModel.Phase.Running && !speaking
            ) {
                try {
                    sr.startListening(recognizeIntent)
                } catch (_: Exception) {
                }
            }
        }
    }
    LaunchedEffect(micOk) {
        if (micOk) {
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    handler.postDelayed(restartListen, 900)
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrEmpty()) vm.send(text)
                    handler.postDelayed(restartListen, 900)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else {
            recognizer?.destroy()
            recognizer = null
        }
    }
    LaunchedEffect(listenEnabled, speaking, phase, micOk) {
        if (micOk && listenEnabled && phase is ChatViewModel.Phase.Running && !speaking) {
            handler.postDelayed(restartListen, 400)
        } else {
            recognizer?.cancel()
        }
    }

    // ---- Camera / gallery / mic for chat input ----
    val cameraFile = remember { File(context.cacheDir, "capture.jpg") }
    val sendImage: (Uri, String) -> Unit = { uri, text ->
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
        if (bytes != null) vm.send(text, Base64.encodeToString(bytes, Base64.NO_WRAP))
        else vm.log("Could not read that image.")
    }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) sendImage(uri, input.ifEmpty { "What do you see here?" })
        input = ""
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) sendImage(Uri.fromFile(cameraFile), input.ifEmpty { "What do you see here?" })
        input = ""
    }
    val camPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera(context, cameraFile, cameraLauncher)
        else vm.log("Camera permission denied.")
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val running = phase is ChatViewModel.Phase.Running
    val statusText = when {
        phase is ChatViewModel.Phase.Running ->
            if (listenEnabled && visionEnabled) "● fully awake - sees, hears & remembers"
            else "● online"
        phase is ChatViewModel.Phase.Download -> "… downloading"
        phase is ChatViewModel.Phase.Start -> "… starting"
        phase is ChatViewModel.Phase.Error -> "! needs attention"
        else -> "○ offline"
    }
    val statusColor = when {
        phase is ChatViewModel.Phase.Running -> Green
        phase is ChatViewModel.Phase.Download || phase is ChatViewModel.Phase.Start -> Amber
        phase is ChatViewModel.Phase.Error -> Red
        else -> Color.Gray
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF12151E), Bg)))
    ) {
        // ---- Header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(Accent, Accent2))),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("ARYNOX", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(statusText, color = statusColor, fontSize = 11.5.sp)
                    if (webNote != null) {
                        Spacer(Modifier.width(6.dp))
                        Text("🌐", fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (installed) {
                Text("tier: $tier", color = Accent2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = { vm.toggleTts() }) {
                Text(if (ttsEnabled) "🔊" else "🔇", fontSize = 17.sp)
            }
        }

        // ---- Live camera card ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(210.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Edge, RoundedCornerShape(20.dp))
        ) {
            if (camOk) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                // bottom scrim + live caption
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xCC000000))
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            if (running && visionEnabled) {
                                if (liveCaption.isNotBlank()) "👁 AI: $liveCaption" else "👁 AI is watching..."
                            } else if (running) "👁 vision paused" else "👁 camera ready",
                            color = Color.White, fontSize = 12.sp, maxLines = 2
                        )
                    }
                }
                if (!running) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("AI offline - camera preview", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF141824)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Camera permission needed\nfor 24/7 vision",
                        color = Color(0xFF9CA3AF), fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ---- Mode toggles ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = listenEnabled,
                onClick = { vm.toggleListen() },
                enabled = micOk,
                label = { Text(if (listenEnabled) "👂 Always listening" else "👂 Hearing off", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent,
                    selectedLabelColor = Color.White,
                    containerColor = Surface1,
                    labelColor = Color(0xFF9CA3AF)
                )
            )
            FilterChip(
                selected = visionEnabled,
                onClick = { vm.toggleVision() },
                enabled = camOk,
                label = { Text(if (visionEnabled) "📷 Vision 24/7" else "📷 Vision off", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent,
                    selectedLabelColor = Color.White,
                    containerColor = Surface1,
                    labelColor = Color(0xFF9CA3AF)
                )
            )
        }

        // ---- Action button (auto runs, but keep manual control) ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!installed) {
                Button(
                    onClick = { vm.install() },
                    enabled = phase !is ChatViewModel.Phase.Download,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text(if (phase is ChatViewModel.Phase.Download) "Installing..." else "⬇ Install AI")
                }
            } else {
                if (running) {
                    Button(
                        onClick = { vm.stop() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Red)
                    ) { Text("■ Stop") }
                } else {
                    Button(
                        onClick = { vm.start() },
                        enabled = phase !is ChatViewModel.Phase.Start,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("▶ Start AI") }
                }
            }
        }

        // ---- Download progress ----
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
                        Text(progress!!.first, color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.weight(1f))
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
                        trackColor = Edge
                    )
                }
            }
        }

        // ---- Logs / chat area ----
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

        // ---- Input bar ----
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
                    IconButton(
                        onClick = { vm.toggleListen() },
                        enabled = running && micOk
                    ) {
                        Text(if (listenEnabled) "🎤" else "🔇", fontSize = 18.sp)
                    }
                    IconButton(
                        onClick = {
                            val p = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            if (p == PackageManager.PERMISSION_GRANTED) {
                                launchCamera(context, cameraFile, cameraLauncher)
                            } else {
                                camPerm.launch(Manifest.permission.CAMERA)
                            }
                        },
                        enabled = running
                    ) { Text("📷", fontSize = 18.sp) }
                    IconButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = running
                    ) { Text("🖼", fontSize = 18.sp) }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask me anything...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Edge,
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
                        enabled = running && input.isNotBlank(),
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
                    ).forEach { (label, _) ->
                        TextButton(
                            onClick = { input = label },
                            enabled = running
                        ) { Text(label, color = Accent2, fontSize = 12.sp) }
                    }
                }
                Text(
                    "Always on • on-device AI • web search when needed",
                    color = Color(0xFF6B7280),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    textAlign = TextAlign.Center
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

private fun scaleDown(bmp: Bitmap?, maxDim: Int): Bitmap {
    if (bmp == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val w = bmp.width
    val h = bmp.height
    val m = maxOf(w, h)
    if (m <= maxDim) return bmp
    val scale = maxDim.toFloat() / m
    return Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
}
