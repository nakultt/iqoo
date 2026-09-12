package com.veritransit.inspector.ui.screens

import android.Manifest as AndroidPermission
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.veritransit.inspector.ai.ChatSession
import com.veritransit.inspector.ai.ChatTurn
import com.veritransit.inspector.ai.EvidenceCamera
import com.veritransit.inspector.ai.EvidenceViewfinder
import com.veritransit.inspector.ai.LlmGateway
import com.veritransit.inspector.ai.NpuEngine
import com.veritransit.inspector.ai.OpenRouterClient
import com.veritransit.inspector.ui.components.FlowHeader
import com.veritransit.inspector.ui.components.NoticeStrip
import com.veritransit.inspector.ui.components.PulseDot
import com.veritransit.inspector.ui.components.VTCard
import com.veritransit.inspector.ui.theme.Mono
import com.veritransit.inspector.ui.theme.VT
import kotlinx.coroutines.launch
import java.io.File

/**
 * Free-form conversation with the model — the same pipeline the inspection
 * steps use ([LlmGateway]: NPU first, cloud fallback), without the structured
 * prompts wrapped around it. Useful for asking the model about a load
 * directly, and for checking it is actually alive before starting an
 * inspection.
 */
@Composable
fun ChatScreen(session: ChatSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var draft by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<File?>(null) }
    var cameraOpen by remember { mutableStateOf(false) }
    val camera = remember { EvidenceCamera() }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, AndroidPermission.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
        cameraOpen = it
    }

    // Follow the conversation as it grows, including while tokens stream in.
    LaunchedEffect(session.turns.size, session.pending) {
        val last = session.turns.size + if (session.pending != null) 1 else 0
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() && attachment == null) return
        val image = attachment
        draft = ""
        attachment = null
        cameraOpen = false
        scope.launch {
            session.send(
                text = text.ifEmpty { "What do you see in this photo?" },
                imagePath = image?.absolutePath,
            )
        }
    }

    Column(Modifier.fillMaxSize().background(VT.Alabaster)) {
        FlowHeader(
            title = "Chat",
            subtitle = LlmGateway.activeModelLabel,
            onBack = onBack,
            trailing = {
                if (session.turns.isNotEmpty()) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { session.clear() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, "Clear", tint = VT.Muted, modifier = Modifier.size(21.dp))
                    }
                }
            },
        )

        if (!NpuEngine.isReady) {
            Box(Modifier.padding(16.dp)) {
                NoticeStrip(
                    if (LlmGateway.cloudReady) {
                        "The on-device model is not loaded — replies come from " +
                            OpenRouterClient.DISPLAY_NAME + " via OpenRouter until it is."
                    } else {
                        "The model is not loaded and no OpenRouter API key is " +
                            "configured — set openrouter.api.key in local.properties."
                    },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session.turns.isEmpty() && session.pending == null) {
                item { EmptyState() }
            }
            items(session.turns) { turn -> Bubble(turn) }
            session.pending?.let { partial ->
                item { StreamingBubble(partial) }
            }
        }

        session.error?.let {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { NoticeStrip(it) }
        }

        AnimatedVisibility(cameraOpen, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF141A22)),
                ) {
                    EvidenceViewfinder(camera, Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(enabled = camera.ready) {
                                scope.launch {
                                    attachment = camera.capture(context)
                                    cameraOpen = false
                                }
                            },
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        attachment?.let { file ->
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(file, 44.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Photo attached",
                    style = TextStyle(fontFamily = Mono, fontSize = 11.5.sp),
                    color = VT.Muted,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable { attachment = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, "Remove", tint = VT.Muted, modifier = Modifier.size(17.dp))
                }
            }
        }

        Composer(
            draft = draft,
            onDraft = { draft = it },
            enabled = LlmGateway.isAvailable && !session.streaming,
            streaming = session.streaming,
            onAttach = {
                if (cameraGranted) cameraOpen = !cameraOpen
                else askCamera.launch(AndroidPermission.permission.CAMERA)
            },
            onSend = ::send,
            onStop = { LlmGateway.stop() },
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    streaming: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(VT.Surface)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(VT.Hairline))
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, VT.Border, RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled, onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.AddAPhoto,
                    "Attach photo",
                    tint = if (enabled) VT.Slate else VT.Faint,
                    modifier = Modifier.size(20.dp),
                )
            }

            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = VT.Ink),
                cursorBrush = SolidColor(VT.Primary),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 128.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(VT.Inset)
                    .padding(horizontal = 12.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(
                            "Ask the model anything…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VT.Faint,
                        )
                    }
                    inner()
                },
            )

            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (streaming) VT.Crimson else if (enabled) VT.Primary else Color(0xFFD7DCE3))
                    .clickable(enabled = streaming || enabled) { if (streaming) onStop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                    if (streaming) "Stop" else "Send",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun Bubble(turn: ChatTurn) {
    val user = turn.role == ChatTurn.Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (user) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            turn.imagePath?.let { path ->
                Thumbnail(File(path), 128.dp)
                Spacer(Modifier.height(6.dp))
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (user) VT.Primary else VT.Surface)
                    .then(
                        if (user) Modifier
                        else Modifier.border(1.dp, VT.Hairline, RoundedCornerShape(10.dp)),
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(
                    turn.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (user) VT.OnPrimary else VT.Ink,
                    lineHeight = 21.sp,
                )
            }
            turn.stats?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = TextStyle(fontFamily = Mono, fontSize = 10.sp),
                    color = VT.Faint,
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(partial: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.86f)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(VT.Surface)
                    .border(1.dp, VT.Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                if (partial.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PulseDot(VT.Azure, 7.dp)
                        Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = VT.Muted)
                    }
                } else {
                    Text(partial, style = MaterialTheme.typography.bodyMedium, color = VT.Ink, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(file: File, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(file.absolutePath) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun EmptyState() {
    VTCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Talk to the model", style = MaterialTheme.typography.titleMedium, color = VT.Ink)
            Text(
                "By default every prompt, photo and reply runs through the " +
                    "Hexagon NPU and never leaves the handset. When the on-device " +
                    "model is not loaded, the conversation falls back to " +
                    OpenRouterClient.DISPLAY_NAME + " on OpenRouter — that turn's " +
                    "prompt and photo are sent to it.",
                style = MaterialTheme.typography.bodySmall,
                color = VT.Muted,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Attach a photo to ask about it.",
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                color = VT.Azure,
            )
        }
    }
}
