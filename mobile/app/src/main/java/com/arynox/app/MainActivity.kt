package com.arynox.app

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
            )) {
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
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val listState = rememberLazyListState()
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && !busy) {
            busy = true
            val bytes = try {
                val stream = ByteArrayOutputStream()
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(stream) }
                stream.toByteArray()
            } catch (_: Exception) {
                null
            }
            if (bytes != null) {
                vm.send(input.ifEmpty { "What is in this image?" },
                    Base64.encodeToString(bytes, Base64.NO_WRAP))
                input = ""
            }
            busy = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("ARYNOX", color = Color(0xFF8AB4F8), fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        Text("tier: $tier | ${if (installed) "installed" else "not installed"}",
            color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress!!.second },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Text("${progress!!.first}", color = Color.Gray, fontSize = 11.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Button(enabled = !installed && phase != ChatViewModel.Phase.Error,
                onClick = { vm.install() }) { Text("Install models") }
            Button(enabled = installed && phase != ChatViewModel.Phase.Running,
                onClick = { vm.start() }) { Text("Start") }
            OutlinedButton(enabled = phase == ChatViewModel.Phase.Running,
                onClick = { vm.stop() }) { Text("Stop") }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (messages.isEmpty()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logs) { line ->
                        Text(line, color = Color(0xFF9CD9A0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(messages) { m ->
                        Text(if (m.role == "user") "You: ${m.text}" else "Arynox: ${m.text}",
                            color = if (m.role == "user") Color.White else Color(0xFF8AB4F8),
                            fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("📷") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Arynox...") },
                maxLines = 3,
            )
            Button(enabled = phase == ChatViewModel.Phase.Running && !busy,
                onClick = {
                    vm.send(input)
                    input = ""
                }) {
                if (busy) CircularProgressIndicator(modifier = Modifier.heightIn(max = 18.dp))
                else Text("Send")
            }
        }
    }
}
