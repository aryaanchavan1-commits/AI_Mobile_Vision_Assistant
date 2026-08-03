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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            ArynoxTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
    val memories by vm.memories.collectAsState()
    var input by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(1) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val chatListState = rememberLazyListState()
    val handler = remember { Handler(Looper.getMainLooper()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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

    LaunchedEffect(Unit) {
        vm.toast.collect { t ->
            if (t != null) {
                snackbarHostState.showSnackbar(t)
                vm.toast.value = null
            }
        }
    }

    val running = phase is ChatViewModel.Phase.Running

    // Keep the screen awake while the AI is watching.
    val view = LocalView.current
    LaunchedEffect(running, visionEnabled) {
        view.keepScreenOn = running && visionEnabled
    }

    // ---- Camera: live preview + periodic capture for the vision loop ----
    val previewView = remember { PreviewView(context) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }
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
        while (isActive && cameraReady && running && visionEnabled) {
            val cap = cameraCapture
            if (cap != null) {
                cap.takePicture(ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bmp = image.toBitmap()
                                val scaled = scaleDown(bmp, 720)
                                lastFrame = scaled
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
            if (sr != null && micOk && listenEnabled && running && !speaking) {
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
        if (micOk && listenEnabled && running && !speaking) {
            handler.postDelayed(restartListen, 400)
        } else {
            recognizer?.cancel()
        }
    }

    // ---- Camera / gallery input ----
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
        else vm.showToast("Could not read that image.")
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
        else vm.showToast("Camera permission denied.")
    }
    val askAboutThis: () -> Unit = {
        val f = lastFrame
        if (f != null) {
            val stream = ByteArrayOutputStream()
            f.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            vm.send("What do you see here?", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
        } else vm.showToast("Camera not ready yet.")
    }
    val takePhoto: () -> Unit = {
        val p = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (p == PackageManager.PERMISSION_GRANTED) {
            launchCamera(context, cameraFile, cameraLauncher)
        } else {
            camPerm.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) chatListState.animateScrollToItem(messages.size - 1)
    }

    val statusText = when {
        running ->
            if (listenEnabled && visionEnabled) "● fully awake - sees, hears & remembers"
            else "● online"
        phase is ChatViewModel.Phase.Download -> "… downloading"
        phase is ChatViewModel.Phase.Start -> "… starting"
        phase is ChatViewModel.Phase.Error -> "! needs attention"
        else -> "○ offline"
    }
    val statusColor = when {
        running -> MaterialTheme.colorScheme.tertiary
        phase is ChatViewModel.Phase.Download || phase is ChatViewModel.Phase.Start ->
            MaterialTheme.colorScheme.tertiary
        phase is ChatViewModel.Phase.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val versionName = remember {
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val pkg = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getPackageInfo(context.packageName, 0)
            }
            pkg.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Header(
                statusText = statusText,
                statusColor = statusColor,
                webNote = webNote,
                tier = tier,
                installed = installed,
                ttsEnabled = ttsEnabled,
                onToggleTts = { vm.toggleTts() }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(
                    Triple(0, "👁", "Live"),
                    Triple(1, "💬", "Chat"),
                    Triple(2, "🧠", "Memory"),
                ).forEach { (i, icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon, fontSize = 18.sp) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (tab) {
                0 -> LiveScreen(
                    running = running,
                    visionEnabled = visionEnabled,
                    listenEnabled = listenEnabled,
                    speaking = speaking,
                    liveCaption = liveCaption,
                    camOk = camOk,
                    previewView = previewView,
                    progress = progress,
                    phase = phase,
                    installed = installed,
                    onToggleVision = { vm.toggleVision() },
                    onToggleListen = { vm.toggleListen() },
                    onAskAboutThis = askAboutThis,
                    onStop = { vm.stop() },
                )
                1 -> ChatScreen(
                    running = running,
                    camOk = camOk,
                    micOk = micOk,
                    lastFrame = lastFrame,
                    liveCaption = liveCaption,
                    listening = listenEnabled,
                    speaking = speaking,
                    logs = logs,
                    messages = messages,
                    typing = typing,
                    progress = progress,
                    installed = installed,
                    phase = phase,
                    versionName = versionName,
                    input = input,
                    onInput = { input = it },
                    chatListState = chatListState,
                    onToggleListen = { vm.toggleListen() },
                    onTakePhoto = takePhoto,
                    onPickImage = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onSend = {
                        vm.send(input)
                        input = ""
                    },
                )
                else -> MemoryScreen(memories = memories, onRemove = { vm.removeMemory(it) })
            }
        }
    }
}

@Composable
private fun Header(
    statusText: String,
    statusColor: Color,
    webNote: String?,
    tier: String,
    installed: Boolean,
    ttsEnabled: Boolean,
    onToggleTts: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF12151E), MaterialTheme.colorScheme.background)
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("ARYNOX", color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
            Text("tier: $tier", color = MaterialTheme.colorScheme.secondary,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = onToggleTts) {
            Text(if (ttsEnabled) "🔊" else "🔇", fontSize = 17.sp)
        }
    }
}

@Composable
private fun LiveScreen(
    running: Boolean,
    visionEnabled: Boolean,
    listenEnabled: Boolean,
    speaking: Boolean,
    liveCaption: String,
    camOk: Boolean,
    previewView: PreviewView,
    progress: Pair<String, Float>?,
    phase: ChatViewModel.Phase,
    installed: Boolean,
    onToggleVision: () -> Unit,
    onToggleListen: () -> Unit,
    onAskAboutThis: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Full camera view
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
        ) {
            if (camOk) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                // LIVE badge
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    shape = RoundedCornerShape(50),
                    color = if (running && visionEnabled) Color(0xCCE11D48) else MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        if (running && visionEnabled) "● LIVE" else "○ PAUSED",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                // bottom scrim + caption
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Text(
                            if (running && visionEnabled) {
                                if (liveCaption.isNotBlank()) "👁 AI: $liveCaption"
                                else "👁 AI is watching..."
                            } else if (running) "👁 vision paused" else "👁 camera ready",
                            color = Color.White, fontSize = 14.sp,
                            fontWeight = if (liveCaption.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 3
                        )
                        if (speaking) {
                            Spacer(Modifier.height(4.dp))
                            Text("🗣 Arynox is speaking...", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Camera permission needed\nfor 24/7 vision",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Mode toggles
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = listenEnabled,
                onClick = onToggleListen,
                enabled = running,
                label = { Text(if (listenEnabled) "👂 Always listening" else "👂 Hearing off", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            FilterChip(
                selected = visionEnabled,
                onClick = onToggleVision,
                enabled = running,
                label = { Text(if (visionEnabled) "📷 Vision 24/7" else "📷 Vision off", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        // Actions
        if (progress != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(progress.first, color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${(progress.second * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.second },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onAskAboutThis,
                enabled = running,
                modifier = Modifier.weight(1f)
            ) { Text("📸 Ask about this", fontSize = 13.sp) }
            if (running) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("■ Stop") }
            } else if (!installed) {
                Text(
                    "Installing automatically...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatScreen(
    running: Boolean,
    camOk: Boolean,
    micOk: Boolean,
    lastFrame: Bitmap?,
    liveCaption: String,
    listening: Boolean,
    speaking: Boolean,
    logs: List<String>,
    messages: List<ChatMsg>,
    typing: Boolean,
    progress: Pair<String, Float>?,
    installed: Boolean,
    phase: ChatViewModel.Phase,
    versionName: String,
    input: String,
    onInput: (String) -> Unit,
    chatListState: androidx.compose.foundation.lazy.LazyListState,
    onToggleListen: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onSend: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Compact "what AI sees" card
        if (camOk && lastFrame != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = lastFrame.asImageBitmap(),
                        contentDescription = "Latest camera frame",
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("👁 AI is watching", color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (liveCaption.isNotBlank()) liveCaption else "watching your surroundings...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.5.sp, maxLines = 2
                        )
                    }
                    if (speaking) Text("🗣", fontSize = 14.sp)
                }
            }
        }

        // Progress
        if (progress != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(progress.first, color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${(progress.second * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.second },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }

        // Chat or logs
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            LazyColumn(
                state = chatListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { "${it.role}-${System.identityHashCode(it)}" }) { m ->
                    val mine = m.role == "user"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.8f),
                            shape = RoundedCornerShape(
                                topStart = 16.dp, topEnd = 16.dp,
                                bottomStart = if (mine) 16.dp else 4.dp,
                                bottomEnd = if (mine) 4.dp else 16.dp
                            ),
                            color = if (mine) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                m.text,
                                modifier = Modifier.padding(12.dp),
                                color = if (mine) Color.White
                                else MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                if (typing) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Arynox is thinking", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                    Spacer(Modifier.width(4.dp))
                                    ThinkingDots()
                                }
                            }
                        }
                    }
                }
            }
        }

        // Input bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onToggleListen,
                        enabled = running && micOk,
                        modifier = Modifier.semantics {
                            contentDescription = if (listening) "Turn off always listening" else "Turn on always listening"
                        }
                    ) {
                        Text(
                            when {
                                !micOk -> "🚫"
                                listening -> "🎤"
                                else -> "🔇"
                            }, fontSize = 18.sp)
                    }
                    IconButton(
                        onClick = onTakePhoto,
                        enabled = running,
                        modifier = Modifier.semantics { contentDescription = "Take a photo" }
                    ) { Text("📷", fontSize = 18.sp) }
                    IconButton(
                        onClick = onPickImage,
                        enabled = running,
                        modifier = Modifier.semantics { contentDescription = "Pick an image from gallery" }
                    ) { Text("🖼", fontSize = 18.sp) }
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInput,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask me anything...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        maxLines = 2
                    )
                    Button(
                        onClick = onSend,
                        enabled = running && input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.size(48.dp)
                    ) { Text("➤", fontSize = 16.sp) }
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
                            onClick = { onInput(label) },
                            enabled = running,
                            modifier = Modifier.semantics { contentDescription = hint }
                        ) { Text(label, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp) }
                    }
                }
                Text(
                    "Always on • on-device AI • web search when needed • v$versionName",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "dot"
    )
    Text("•••", color = MaterialTheme.colorScheme.primary.copy(alpha = alpha), fontSize = 14.sp)
}

@Composable
private fun MemoryScreen(
    memories: List<MemoryItem>,
    onRemove: (String) -> Unit
) {
    if (memories.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🧠", fontSize = 44.sp)
            Spacer(Modifier.height(12.dp))
            Text("No memories yet",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Point the camera at someone and say:\n\"Remember this as Sam\"",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(memories, key = { it.name }) { m ->
                val fmt = remember {
                    SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault())
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                m.name.firstOrNull()?.uppercase() ?: "?",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(m.desc, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.5.sp, maxLines = 2)
                            Text(fmt.format(Date(m.time)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 10.5.sp)
                        }
                        IconButton(
                            onClick = { onRemove(m.name) },
                            modifier = Modifier.semantics { contentDescription = "Forget ${m.name}" }
                        ) { Text("🗑", fontSize = 16.sp) }
                    }
                }
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
