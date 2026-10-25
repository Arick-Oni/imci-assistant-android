package org.imci.transcriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // test hook: adb shell am start -n org.imci.transcriber/.MainActivity --es replay <file in Android/data/.../files>
        intent?.getStringExtra("replay")?.let { vm.replayAsLive(File(getExternalFilesDir(null), it)) }
        setContent {
            ImciTheme {
                // keep the CPU at full speed: transcription and form filling stall if the screen turns off
                val working = vm.busy || vm.fill != null
                LaunchedEffect(working) {
                    if (working) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                App(vm)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ theme
private val Teal = Color(0xFF00796B)
private val PinkC = Color(0xFFD81B60)
private val YellowC = Color(0xFFF9A825)
private val GreenC = Color(0xFF2E7D32)
private val GreyC = Color(0xFF78909C)
private val HeroA = Color(0xFF00897B)
private val HeroB = Color(0xFF004D40)
private val RecRed = Color(0xFFE53935)

@Composable
private fun ImciTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF4DB6AC), onPrimary = Color(0xFF003731), primaryContainer = Color(0xFF00504A),
        onPrimaryContainer = Color(0xFF9EF2E6), secondary = Color(0xFFFFCA28), secondaryContainer = Color(0xFF4A3B00),
        tertiary = Color(0xFF90CAF9), background = Color(0xFF0F1514), surface = Color(0xFF0F1514), surfaceVariant = Color(0xFF26302E),
        surfaceContainerLow = Color(0xFF171E1D), surfaceContainer = Color(0xFF1B2221), surfaceContainerHigh = Color(0xFF252C2B))
    else lightColorScheme(primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFB2DFDB),
        onPrimaryContainer = Color(0xFF00201C), secondary = Color(0xFF8D6E00), secondaryContainer = Color(0xFFFFF0C2),
        tertiary = Color(0xFF1565C0), background = Color(0xFFF4F7F6), surface = Color(0xFFF4F7F6),
        surfaceVariant = Color(0xFFE3EBE9), surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFEAF0EE))
    val type = Typography().let { t ->
        t.copy(headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold), titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
    MaterialTheme(colorScheme = scheme, typography = type,
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)), content = content)
}

private fun colourOf(c: String) = when (c) { "Pink" -> PinkC; "Yellow" -> YellowC; "Green" -> GreenC; else -> GreyC }

private sealed interface Screen {
    data object Home : Screen
    data class Visit(val id: String) : Screen
    data object Settings : Screen
    data object Live : Screen
}

@Composable
private fun App(vm: MainViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.error) { vm.error?.let { snack.showSnackbar(it); vm.error = null } }
    BackHandler(screen != Screen.Home) { screen = Screen.Home }
    // a live recording opens its form; after Stop the saved visit takes its place
    var lastLive by remember { mutableStateOf<String?>(null) }
    // a finished recording opens once; Back from it must land on Home and stay there
    var opened by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.liveId) {
        val id = vm.liveId
        if (id != null) { lastLive = id; screen = Screen.Live }
        else if (screen == Screen.Live) {
            screen = lastLive?.let { l -> Screen.Visit(l).takeIf { vm.history.any { it.id == l } } } ?: Screen.Home
            opened = lastLive
        }
    }
    // the home header is dark teal under the status bar: light icons there, dark icons on the light screens
    val act = LocalContext.current as ComponentActivity
    val lightIcons = screen == Screen.Home || isSystemInDarkTheme()
    LaunchedEffect(lightIcons) {
        val clear = android.graphics.Color.TRANSPARENT
        act.enableEdgeToEdge(statusBarStyle = if (lightIcons) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear))
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(vm, snack, opened, onOpen = { opened = it; screen = Screen.Visit(it) },
                onSettings = { screen = Screen.Settings }, onLive = { screen = Screen.Live })
            is Screen.Visit -> vm.history.firstOrNull { it.id == s.id }?.let { VisitScreen(vm, it, snack) { screen = Screen.Home } }
                ?: run { screen = Screen.Home }
            Screen.Settings -> SettingsScreen(vm) { screen = Screen.Home }
            Screen.Live -> vm.liveId?.let { LiveScreen(vm, it, snack) { screen = Screen.Home } }   // null: the effect above moves on
        }
    }
}

// ------------------------------------------------------------------------------------------------ home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: MainViewModel, snack: SnackbarHostState, opened: String?, onOpen: (String) -> Unit,
                       onSettings: () -> Unit, onLive: () -> Unit) {
    val ctx = LocalContext.current
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.transcribeFile(it) } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggleRecording() else vm.error = "Microphone permission is needed to record"
    }
    // a visit that just finished transcribing opens by itself
    LaunchedEffect(vm.lastResult?.id, vm.status is Status.Idle) {
        vm.lastResult?.let { if (vm.status is Status.Idle && it.id != opened) onOpen(it.id) }
    }

    var query by remember { mutableStateOf("") }
    val visits = vm.history.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    val side = Modifier.padding(horizontal = 16.dp)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val recording = vm.status is Status.Recording
                Hero(vm, recording, canRecord = vm.ready && (recording || !vm.busy) && vm.fill == null, onSettings = onSettings,
                    onMic = {
                        if (recording || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED) vm.toggleRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onPick = { pickAudio.launch(arrayOf("audio/*", "video/*")) })
            }
            if (vm.status.let { it !is Status.Idle && it !is Status.Preparing && it !is Status.Recording }) item {
                Card(side.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Box(Modifier.padding(16.dp, 8.dp)) { StatusLine(vm) }
                }
            }
            vm.liveId?.let { id -> item {
                val f = vm.formOf(id)
                Card(side.fillMaxWidth().clickable(onClick = onLive), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Filled.Checklist, 40.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Live form", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("${f.values.size} answered so far · tap to check and correct", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            } }
            if (vm.status is Status.Recording && vm.liveChunks.isNotEmpty()) item {
                Card(side.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.GraphicEq, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("Live transcript", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        vm.liveChunks.takeLast(4).forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            vm.fill?.let { f -> item { Box(side) { FillCard(vm, f, vm.history.firstOrNull { it.id == f.visitId }?.title) } } }
            if (!vm.canFill) item {
                Card(side.fillMaxWidth().clickable(onClick = onSettings), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null); Spacer(Modifier.width(12.dp))
                        Text("Add the form models in Settings to fill the IMCI form automatically.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            }
            item {
                Row(side.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent visits", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (vm.history.isNotEmpty()) Text("${vm.history.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }
            if (vm.history.size > 3) item {
                TextField(query, { query = it }, side.fillMaxWidth(), singleLine = true, shape = CircleShape,
                    placeholder = { Text("Search visits") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            }
            if (vm.history.isEmpty()) item {
                Column(side.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBubble(Icons.Filled.ChildCare, 72.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("No visits yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap the microphone at the start of a home visit.", color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                }
            } else if (visits.isEmpty()) item {
                Text("No visit matches \"$query\"", color = MaterialTheme.colorScheme.outline,
                    modifier = side.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
            }
            items(visits, key = { it.id }) { t -> Box(side) { VisitCard(vm, t) { onOpen(t.id) } } }
        }
    }
}

/** Gradient header: title, visit counts and the big record button. */
@Composable
private fun Hero(vm: MainViewModel, recording: Boolean, canRecord: Boolean, onSettings: () -> Unit, onMic: () -> Unit, onPick: () -> Unit) {
    val forms = vm.history.map { vm.formOf(it.id) }
    val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val header = @Composable {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("IMCI Assistant", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Children 2–59 months · works offline", style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f))
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Color.White) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Visits", vm.history.size, Icons.Filled.Folder)
            StatTile("To check", forms.count { it.filled && it.reviewedMs == null }, Icons.Filled.FactCheck)
            StatTile("Urgent", forms.count { f -> f.classes.any { it.colour == "Pink" } }, Icons.Filled.Warning, alert = true)
        }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
        .background(Brush.linearGradient(listOf(HeroA, HeroB)))) {
        // landscape: title and counts on the left, the record controls on the right, so the list stays visible
        if (wide) Row(Modifier.statusBarsPadding().displayCutoutPadding().padding(20.dp, 4.dp, 20.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) { header() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { RecordControls(vm, recording, canRecord, onMic, onPick) }
        } else Column(Modifier.statusBarsPadding().padding(20.dp, 4.dp, 20.dp, 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            header()
            Spacer(Modifier.height(20.dp))
            RecordControls(vm, recording, canRecord, onMic, onPick)
        }
    }
}

@Composable
private fun RecordControls(vm: MainViewModel, recording: Boolean, canRecord: Boolean, onMic: () -> Unit, onPick: () -> Unit) {
    run {
            RecordButton(recording, canRecord, onMic)
            Spacer(Modifier.height(8.dp))
            val s = vm.status
            Text(when {
                s is Status.Recording -> (if (s.paused) "❚❚ " else "● ") + Transcript.fmt(s.seconds)
                !vm.ready -> "Getting ready…"
                else -> "Start a visit"
            }, style = MaterialTheme.typography.titleLarge, color = Color.White)
            if (recording) VoiceWave(vm::micLevel, Color.White, Modifier.padding(vertical = 6.dp).width(220.dp).height(36.dp), bars = 28)
            Text(when {
                s is Status.Recording -> if (s.live) "Transcribing as you go · tap to stop" else "Tap to stop"
                else -> "Record the conversation · the form fills itself"
            }, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            if (recording) {
                val paused = (s as? Status.Recording)?.paused == true
                Spacer(Modifier.height(14.dp))
                Surface(onClick = { vm.togglePause() }, shape = CircleShape, color = Color.White.copy(alpha = 0.16f), contentColor = Color.White) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (paused) "Resume" else "Pause", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (!recording) {
                Spacer(Modifier.height(14.dp))
                Surface(onClick = onPick, enabled = vm.ready && !vm.busy && vm.fill == null, shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f), contentColor = Color.White) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text("Use a recording", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
    }
}

@Composable
private fun RowScope.StatTile(label: String, value: Int, icon: ImageVector, alert: Boolean = false) {
    val hot = alert && value > 0
    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
        .background(if (hot) RecRed.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.13f)).padding(12.dp, 10.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.85f))
        Spacer(Modifier.height(4.dp))
        Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
    }
}

/** Round mic button; a ring pulses out of it while recording. */
@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val grow by pulse.animateFloat(1f, 1.45f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "grow")
    val fade by pulse.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "fade")
    Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(100.dp).graphicsLayer {
            if (recording) { scaleX = grow; scaleY = grow; alpha = fade } else { scaleX = 1.22f; scaleY = 1.22f; alpha = 0.15f }
        }.clip(CircleShape).background(if (recording) RecRed else Color.White))
        Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = if (recording) RecRed else Color.White,
            shadowElevation = 10.dp, modifier = Modifier.size(100.dp).alpha(if (enabled) 1f else 0.55f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, if (recording) "Stop" else "Record",
                    Modifier.size(44.dp), tint = if (recording) Color.White else HeroB)
            }
        }
    }
}

@Composable
private fun IconBubble(icon: ImageVector, size: androidx.compose.ui.unit.Dp, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = tint)
    }
}

@Composable
private fun StatusLine(vm: MainViewModel) {
    when (val s = vm.status) {
        Status.Idle, Status.Preparing, is Status.Recording -> {}
        is Status.Finishing -> ProgressRow(if (s.waiting > 0) "Finishing the transcript · ${s.waiting} part(s) left" else "Finishing…", null) { vm.cancel() }
        is Status.Decoding -> ProgressRow("Opening audio…", s.progress) { vm.cancel() }
        is Status.Transcribing -> ProgressRow(if (s.total == 0) "Listening for speech…" else "Transcribing · ${Transcript.fmt(s.elapsed)}",
            if (s.total > 0) s.done.toFloat() / s.total else null) { vm.cancel() }
    }
}

@Composable
private fun ProgressRow(label: String, frac: Float?, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (frac == null) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        else LinearProgressIndicator({ frac }, Modifier.fillMaxWidth().clip(CircleShape))
    }
}

@Composable
private fun FillCard(vm: MainViewModel, f: MainViewModel.FillProgress, title: String?) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Filled.AutoAwesome, 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (f.loading) "Loading the form model…" else "Filling the IMCI form" + (title?.let { " · $it" } ?: ""),
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { vm.cancelFill() }) { Text("Stop") }
            }
            if (f.total > 0) LinearProgressIndicator({ f.done.toFloat() / f.total }, Modifier.fillMaxWidth().clip(CircleShape))
            else LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            if (!f.loading) Text("${f.done} of ~${maxOf(f.total, f.done)} questions · ${Transcript.fmt(f.seconds)} · ${f.question}", maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisitCard(vm: MainViewModel, t: Transcript, onClick: () -> Unit) {
    val f = vm.formOf(t.id)
    val main = f.classes.filter { it.colour != "Green" }.sortedBy { listOf("Pink", "Yellow", "Grey").indexOf(it.colour) }
    val filling = vm.fill?.visitId == t.id
    val worst = main.firstOrNull()?.colour ?: if (f.filled) "Green" else null
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(0.5.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            IconBubble(when {
                filling -> Icons.Filled.AutoAwesome
                worst == "Pink" -> Icons.Filled.Warning
                f.reviewedMs != null -> Icons.Filled.TaskAlt
                f.filled -> Icons.Filled.ChildCare
                else -> Icons.Filled.GraphicEq
            }, 46.dp, worst?.let { if (it == "Yellow") Color(0xFFB27C00) else colourOf(it) } ?: MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    StatusPill(f, filling)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(4.dp))
                    Text("${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.createdMs))} · ${Transcript.fmt(t.audioSeconds)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (f.filled) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (main.isEmpty()) ClassChip("No danger signs found", "Green")
                    main.take(3).forEach { ClassChip(it.name, it.colour) }
                    if (main.size > 3) ClassChip("+${main.size - 3} more", "Grey")
                }
            }
        }
    }
}

@Composable
private fun StatusPill(f: VisitForm, filling: Boolean) {
    val (label, c) = when {
        filling -> "Filling…" to MaterialTheme.colorScheme.primary
        f.reviewedMs != null -> "Confirmed" to GreenC
        f.filled -> "Check" to Color(0xFFB27C00)
        else -> "New" to GreyC
    }
    Text(label, color = c, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun ClassChip(name: String, colour: String) {
    val c = colourOf(colour)
    Row(Modifier.clip(CircleShape).background(c.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = if (colour == "Yellow") Color(0xFF7A5B00) else c)
    }
}

// ------------------------------------------------------------------------------------------------ visit
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisitScreen(vm: MainViewModel, t: Transcript, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val f = vm.formOf(t.id)
    var tab by remember { mutableIntStateOf(0) }
    var highlight by remember { mutableStateOf<IntRange?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { rename = true }) },
                actions = {
                    IconButton(onClick = {
                        val files = listOf(vm.textFile(t), vm.formFile(t.id)).filter { it.exists() }
                        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
                        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            .putExtra(Intent.EXTRA_SUBJECT, t.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share visit"))
                    }) { Icon(Icons.Filled.Share, "Share") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) })
                            DropdownMenuItem(text = { Text("Fill form again") }, enabled = vm.fill == null && vm.canFill,
                                onClick = { menu = false; vm.fillForm(t) }, leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) })
                            DropdownMenuItem(text = { Text("Delete visit", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (f.filled) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val ai = f.sources.count { it.value == "ai" && f.values.containsKey(it.key) }
                        val ed = f.sources.count { it.value == "worker" }
                        Text("$ai by AI · $ed changed by you", style = MaterialTheme.typography.bodySmall)
                        Text(if (f.reviewedMs != null) "Confirmed" else "Please check, then confirm",
                            style = MaterialTheme.typography.labelMedium, color = if (f.reviewedMs != null) GreenC else MaterialTheme.colorScheme.outline)
                    }
                    Button(onClick = { vm.confirmForm(t.id) }, enabled = f.reviewedMs == null) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (f.reviewedMs != null) "Saved" else "Confirm")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            vm.fill?.takeIf { it.visitId == t.id }?.let { Box(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp)) { FillCard(vm, it, null) } }
            if (f.filled && vm.fill?.visitId != t.id) VisitSummary(vm, t, f) { tab = 1 }
            PillTabs(tab, listOf("Form" to Icons.Filled.Checklist, "Result" to Icons.Filled.LocalHospital,
                "Transcript" to Icons.Filled.Notes)) { tab = it }
            when (tab) {
                0 -> FormTab(vm, t, f) { r -> highlight = r; tab = 2 }
                1 -> ResultTab(f)
                else -> TranscriptTab(if (f.lines.isNotEmpty()) f.lines else FormFiller.lines(t.chunks.map { it.text }), highlight)
            }
        }
    }
    if (rename) {
        var name by remember { mutableStateOf(t.title) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Visit name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("e.g. child's name") }) },
            confirmButton = { TextButton(onClick = { vm.rename(t, name); rename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this visit?") },
        text = { Text("The transcript and the form are removed from this phone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onBack(); vm.delete(t) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

// ------------------------------------------------------------------------------------------------ live visit
/** The visit being recorded: the form fills as things are discussed and the worker can correct it on the spot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveScreen(vm: MainViewModel, id: String, snack: SnackbarHostState, onBack: () -> Unit) {
    val f = vm.formOf(id)
    var tab by remember { mutableIntStateOf(0) }
    var highlight by remember { mutableStateOf<IntRange?>(null) }
    val t = Transcript(id, "Live visit", 0L, 0f, 0f, vm.liveChunks.sortedBy { it.start })
    val s = vm.status
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text("Live visit", maxLines = 1)
                        Text(when (s) {
                            is Status.Recording -> (if (s.paused) "❚❚ Paused " else "● Recording ") + Transcript.fmt(s.seconds) +
                                if (s.waiting > 0) " · ${s.waiting} part(s) to transcribe" else ""
                            is Status.Finishing -> "Finishing the transcript…"
                            else -> ""
                        }, style = MaterialTheme.typography.labelMedium, color = RecRed)
                    }
                },
                actions = {
                    if (s is Status.Recording) FilledTonalIconButton(onClick = { vm.togglePause() }) {
                        Icon(if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (s.paused) "Resume" else "Pause")
                    }
                    if (s is Status.Recording) Button(onClick = { vm.toggleRecording() }, modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RecRed)) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (s is Status.Recording) VoicePanel(vm)
            if (vm.liveRecent.isNotEmpty()) JustAdded(vm, id, f)
            PillTabs(tab, listOf("Form" to Icons.Filled.Checklist, "Transcript" to Icons.Filled.Notes)) { tab = it }
            when (tab) {
                0 -> FormTab(vm, t, f, live = true) { r -> highlight = r; tab = 1 }
                else -> TranscriptTab(if (f.lines.isNotEmpty()) f.lines else FormFiller.lines(t.chunks.map { it.text }), highlight)
            }
        }
    }
}

/**
 * Voice animation: rounded bars scroll in from the right, each one the (smoothed) microphone loudness of the moment
 * it was drawn; [onLevel] gets the smoothed level every frame (for the pulsing mic dot).
 */
@Composable
private fun VoiceWave(level: () -> Float, color: Color, modifier: Modifier, bars: Int = 36, onLevel: (Float) -> Unit = {}) {
    val hist = remember(bars) { FloatArray(bars) }
    var shift by remember { mutableFloatStateOf(0f) }          // 0..1 of the way to the next bar: smooth scrolling
    LaunchedEffect(Unit) {
        var last = 0L; var acc = 0L; var smooth = 0f
        val step = 70_000_000L                                  // a new bar every 70 ms
        while (true) withFrameNanos { now ->
            acc += if (last == 0L) 0L else now - last; last = now
            val target = level()
            smooth += (target - smooth) * if (target > smooth) 0.45f else 0.10f   // fast attack, gentle fall
            onLevel(smooth)
            while (acc >= step) { acc -= step; System.arraycopy(hist, 1, hist, 0, bars - 1); hist[bars - 1] = smooth }
            shift = acc / step.toFloat()
        }
    }
    Canvas(modifier) {
        val w = size.width / bars
        val cy = size.height / 2
        for (i in 0 until bars) {
            val v = hist[i]
            val h = maxOf(w * 0.55f, (0.10f + 0.90f * v * v.coerceAtLeast(0.35f)) * size.height)
            val x = (i - shift) * w + w / 2
            if (x < 0) continue
            val age = (i + 1f) / bars                           // older bars fade out to the left
            drawLine(color.copy(alpha = 0.25f + 0.75f * age), androidx.compose.ui.geometry.Offset(x, cy - h / 2),
                androidx.compose.ui.geometry.Offset(x, cy + h / 2), strokeWidth = w * 0.55f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Live screen header while recording: a mic dot that swells with the voice and the scrolling wave. */
@Composable
private fun VoicePanel(vm: MainViewModel) {
    var lv by remember { mutableFloatStateOf(0f) }
    Card(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).graphicsLayer { val s = 0.7f + 0.6f * lv; scaleX = s; scaleY = s; alpha = 0.25f + 0.35f * lv }
                    .clip(CircleShape).background(RecRed))
                Box(Modifier.size(30.dp).clip(CircleShape).background(RecRed), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mic, null, Modifier.size(18.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            VoiceWave(vm::micLevel, MaterialTheme.colorScheme.primary, Modifier.weight(1f).height(44.dp)) { lv = it }
        }
    }
}

/** The newest AI answers with one-tap correction, so a wrong one is fixed while the mother is still there. */
@Composable
private fun JustAdded(vm: MainViewModel, id: String, f: VisitForm) {
    Card(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(14.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Just added - tap to correct", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            vm.liveRecent.take(3).forEach { (k, _) ->
                val item = vm.form.items[k] ?: return@forEach
                val v = f.values[k]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.textBn.ifBlank { item.text }, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    listOf("yes" to "Yes", "no" to "No").forEach { (o, label) ->
                        val sel = v == o
                        val c = if (o == "yes") PinkC else GreenC
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = if (sel) Color.White else c,
                            modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(if (sel) c else c.copy(alpha = 0.12f))
                                .clickable { if (!sel) vm.setAnswer(id, k, o) }.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

/** Segmented pill switcher used in place of a tab row. */
@Composable
private fun PillTabs(tab: Int, tabs: List<Pair<String, ImageVector>>, onTab: (Int) -> Unit) {
    Row(Modifier.padding(16.dp, 4.dp, 16.dp, 8.dp).fillMaxWidth().clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp)) {
        tabs.forEachIndexed { i, (label, icon) ->
            val sel = i == tab
            Row(Modifier.weight(1f).clip(CircleShape).background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onTab(i) }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                val c = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(icon, null, Modifier.size(18.dp), tint = c); Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = c, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

/** Top of a filled visit: most serious classification and how much of the form is answered. */
@Composable
private fun VisitSummary(vm: MainViewModel, t: Transcript, f: VisitForm, onResult: () -> Unit) {
    val shown = vm.form.sections.flatMap { s -> s.keys.filter { it in vm.form.items && vm.form.shown(it, f.values) } }.distinct()
    val answered = shown.count { f.values.containsKey(it) }
    val top = f.classes.sortedBy { listOf("Pink", "Yellow", "Grey", "Green").indexOf(it.colour) }.firstOrNull()
    val colour = top?.colour ?: "Green"
    val c = if (colour == "Yellow") Color(0xFFB27C00) else colourOf(colour)
    Card(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp).fillMaxWidth().clickable(onClick = onResult), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.10f))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(if (colour == "Pink") Icons.Filled.Warning else if (colour == "Green") Icons.Filled.TaskAlt else Icons.Filled.Info, 42.dp, c)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(top?.name ?: "No danger signs found", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$answered of ${shown.size} questions answered" + if (f.classes.size > 1) " · ${f.classes.size} classifications" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator({ if (shown.isEmpty()) 0f else answered.toFloat() / shown.size },
                    Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = c, trackColor = c.copy(alpha = 0.18f))
            }
            Icon(Icons.Filled.ChevronRight, null, tint = c)
        }
    }
}

@Composable
private fun FormTab(vm: MainViewModel, t: Transcript, f: VisitForm, live: Boolean = false, onShowLines: (IntRange) -> Unit) {
    val filling = vm.fill?.visitId == t.id
    if (live && !f.filled) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val paused = (vm.status as? Status.Recording)?.paused == true
            EqBubble(vm::micLevel, paused, 120.dp)
            Spacer(Modifier.height(20.dp))
            Text(if (paused) "Paused" else "Listening…", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Answers appear here as soon as they are discussed. You can correct any of them right away.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        return
    }
    if (!f.filled && !filling) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            IconBubble(Icons.Filled.AutoAwesome, 96.dp)
            Spacer(Modifier.height(20.dp))
            Text("Ready to fill the form", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("The AI reads the transcript and answers each IMCI question. Everything stays on this phone.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { vm.fillForm(t) }, enabled = vm.fill == null && vm.canFill,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Fill the form with AI")
            }
            if (!vm.canFill) Text("Add the form models in Settings first", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    var onlyOpen by remember { mutableStateOf(false) }
    val shown = vm.form.sections.flatMap { s -> s.keys.filter { it in vm.form.items && vm.form.shown(it, f.values) } }.distinct()
    val enter = shown.filter { it in FormFiller.MEASURE_KEYS && !f.values.containsKey(it) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.count { f.values.containsKey(it) }} answered · ${shown.count { !f.values.containsKey(it) }} not discussed yet",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                FilterChip(onlyOpen, { onlyOpen = !onlyOpen }, label = { Text("Only not discussed") },
                    leadingIcon = if (onlyOpen) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null)
            }
        }
        if (enter.isNotEmpty()) item(key = "enter") {
            // measurements the worker took: entered by hand (Gemma is off by default)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Straighten, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Please enter", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Measurements and checks you did yourself", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                enter.forEach { k -> ItemRow(vm, t.id, f, vm.form.items.getValue(k), false, onShowLines) }
            }
        }
        val visible = vm.form.sections.filter { s -> !onlyOpen || s.keys.any { it in vm.form.items && vm.form.shown(it, f.values) && !f.values.containsKey(it) } }
        itemsIndexed(visible, key = { _, s -> s.area }) { i, s ->
            if (i > 0) SectionBar()
            SectionCard(vm, t.id, f, s, open, onShowLines, onlyOpen)
        }
    }
}

@Composable
private fun SectionCard(vm: MainViewModel, id: String, f: VisitForm, s: Section, open: MutableMap<String, Boolean>,
                        onShowLines: (IntRange) -> Unit, onlyOpen: Boolean = false) {
    val keys = s.keys.filter { it in vm.form.items && vm.form.shown(it, f.values) && !(onlyOpen && f.values.containsKey(it)) }
    if (keys.isEmpty() && onlyOpen) return
    val anyAnswer = keys.any { f.values.containsKey(it) }
    val isOpen = open[s.area] ?: (!s.collapsed || anyAnswer)
    val gateValue = s.gate?.let { f.values[it] }
    val accent = when (gateValue) { "yes" -> PinkC; "no" -> GreenC; else -> if (anyAnswer) MaterialTheme.colorScheme.primary else GreyC.copy(alpha = 0.5f) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), elevation = CardDefaults.cardElevation(0.5.dp)) {
      Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(accent))    // colour strip: where this section stands
      Column(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth().clickable { open[s.area] = !isOpen }.padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, fontWeight = FontWeight.SemiBold)
                if (s.titleBn.isNotBlank()) Text(s.titleBn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            when {
                s.gate != null && gateValue == "yes" -> ClassChip("Yes", "Pink")
                s.gate != null && gateValue == "no" -> ClassChip("No", "Green")
                s.gate != null -> ClassChip("Not discussed", "Grey")
                else -> Text("${keys.count { f.values.containsKey(it) }}/${keys.size}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline)
            }
            Icon(if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.padding(start = 6.dp))
        }
        AnimatedVisibility(isOpen) {
            Column {
                keys.forEachIndexed { i, k ->
                    if (i > 0 && keys[i - 1] == s.gate) FollowUpBar()
                    else if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                    ItemRow(vm, id, f, vm.form.items.getValue(k), k == s.gate, onShowLines)
                }
            }
        }
      }
      }
    }
}

/** Small bar between two form sections. */
@Composable
private fun SectionBar() {
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(44.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** Bar between a gate question and the follow-ups it opens. */
@Composable
private fun FollowUpBar() {
    Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(18.dp).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
        Text("Follow-up questions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
    }
}

/** Round equaliser for the live screen: five bars dance with the voice and keep a gentle sway while listening. */
@Composable
private fun EqBubble(level: () -> Float, paused: Boolean, size: androidx.compose.ui.unit.Dp) {
    var t by remember { mutableFloatStateOf(0f) }
    var lv by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(paused) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else (now - last) / 1e9f; last = now
            if (!paused) t += dt
            val target = if (paused) 0f else level()
            lv += (target - lv) * if (target > lv) 0.4f else 0.08f
        }
    }
    val c = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.55f)) {
            val n = 5
            val w = this.size.width / (n * 1.6f)
            for (i in 0 until n) {
                val sway = if (paused) 0f else 0.5f + 0.5f * kotlin.math.sin(t * 5f + i * 1.3f)
                val h = this.size.height * (0.14f + if (paused) 0f else (0.18f * sway + 0.68f * lv * (0.6f + 0.4f * sway)))
                val x = w * 0.8f + i * w * 1.6f + w / 2
                drawLine(c, androidx.compose.ui.geometry.Offset(x, center.y - h / 2), androidx.compose.ui.geometry.Offset(x, center.y + h / 2),
                    strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(vm: MainViewModel, id: String, f: VisitForm, it: FormItem, gate: Boolean, onShowLines: (IntRange) -> Unit) {
    val v = f.values[it.key]
    val src = f.sources[it.key]
    val ai = f.ai[it.key]
    Column(Modifier.fillMaxWidth().background(if (gate) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else Color.Transparent)
        .padding(16.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(if (it.isQuestion) "ASK" else "LOOK", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = if (it.isQuestion) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp))
            Column {
                Text(it.textBn.ifBlank { it.text }, style = MaterialTheme.typography.bodyLarge, fontWeight = if (gate) FontWeight.SemiBold else FontWeight.Normal)
                if (it.textBn.isNotBlank()) Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        when {
            it.isYesNo -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("yes" to "Yes", "no" to "No").forEachIndexed { i, (o, label) ->
                    SegmentedButton(selected = v == o, onClick = { vm.setAnswer(id, it.key, o) }, shape = SegmentedButtonDefaults.itemShape(i, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = if (o == "yes") PinkC else GreenC,
                            activeContentColor = Color.White, activeBorderColor = if (o == "yes") PinkC else GreenC)) {
                        Text(label, fontWeight = if (v == o) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            it.options.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                it.options.forEach { o -> FilterChip(selected = v == o, onClick = { vm.setAnswer(id, it.key, o) }, label = { Text(o) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary)) }
            }
            else -> {
                var text by remember(v) { mutableStateOf(v ?: "") }
                OutlinedTextField(text, { s -> text = s }, singleLine = it.answerType == "number", modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (it.answerType == "number") "days" else "write here") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (it.answerType == "number") KeyboardType.Number else KeyboardType.Text),
                    trailingIcon = { if (text != (v ?: "")) IconButton(onClick = { vm.setAnswer(id, it.key, text.trim().ifEmpty { null }) }) { Icon(Icons.Filled.Check, "Save") } })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                src == "worker" -> Badge("Changed by you", MaterialTheme.colorScheme.tertiary)
                v != null && ai?.value == v -> {
                    Badge("AI ${(ai.confidence * 100).toInt()}%", MaterialTheme.colorScheme.primary)
                    ai.lines?.let { r -> Text("from L${r.first + 1}–L${r.last + 1}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onShowLines(r) }) }
                }
                v == null -> Badge(if (it.key in FormFiller.MEASURE_KEYS) "Please enter" else "Not discussed", YellowC)
            }
        }
    }
}

@Composable
private fun Badge(text: String, c: Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = c,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun ResultTab(f: VisitForm) {
    val classes = f.classes.sortedBy { listOf("Pink", "Yellow", "Grey", "Green").indexOf(it.colour) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (classes.any { it.colour == "Pink" }) item {
            Card(colors = CardDefaults.cardColors(containerColor = PinkC)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = Color.White); Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Refer urgently to hospital", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("A pink classification was found - check the answers that led to it.", color = Color.White,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (classes.isEmpty()) item {
            Text("No classification yet - answer the form questions first.", color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp))
        }
        items(classes) { c ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(6.dp).fillMaxHeight().background(colourOf(c.colour)))
                    Column(Modifier.padding(14.dp)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        Text("${c.colour} · based on ${c.because.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        item {
            Text("Classifications follow the IMCI Chart Booklet 2019 (2-59 months) and update as you change answers. " +
                "They support, not replace, the health worker's judgement.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun TranscriptTab(lines: List<String>, highlight: IntRange?) {
    val state = rememberLazyListState()
    LaunchedEffect(highlight) { highlight?.let { state.animateScrollToItem(maxOf(0, it.first - 1)) } }
    if (lines.isEmpty()) {
        Text("No speech was found in this recording.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.outline)
        return
    }
    LazyColumn(state = state, contentPadding = PaddingValues(12.dp)) {
        itemsIndexed(lines) { i, l ->
            val hl = highlight?.contains(i) == true
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (hl) YellowC.copy(alpha = 0.22f) else Color.Transparent).padding(8.dp, 6.dp)) {
                Text("L${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(40.dp).padding(top = 3.dp))
                Text(l, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ settings
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val pickLlm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importLlm(it) } }
    val pickBert = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importBert(uris) }
    LaunchedEffect(Unit) { vm.refreshLlmModels() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard("Form filling (AI)", Icons.Filled.AutoAwesome) {
                Text(if (vm.bertFiles > 0) "Yes/no questions: BanglaBERT, average of ${vm.bertFiles} models (fast, also live while recording)"
                     else "Yes/no questions: no BanglaBERT models - only Gemma can fill (slow)",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { pickBert.launch(arrayOf("*/*")) }, enabled = !vm.busy) { Text("Add BanglaBERT files") }
                Hint("Select all at once: bb*.onnx, vocab.txt, tokenizer_config.json, tokenizer_cases.json")
                ToggleRow("Fill the form automatically", "Live while recording, and after each recording or import", vm.autoFill) { vm.changeAutoFill(it) }
                ToggleRow("Gemma for measurement questions", "MUAC, palm, breaths… Slow (minutes) and ~2.4 GB. Off: you enter them",
                    vm.useGemma) { vm.changeUseGemma(it) }
                if (vm.useGemma) {
                    if (vm.llmModels.isEmpty()) Text("No Gemma model yet. Add the fine-tuned Gemma .gguf file.", style = MaterialTheme.typography.bodyMedium)
                    vm.llmModels.forEach { m ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.selectLlm(m) }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = vm.llmPath == m.absolutePath, onClick = { vm.selectLlm(m) })
                            Column {
                                Text(m.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${m.length() / 1_000_000} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    OutlinedButton(onClick = { pickLlm.launch(arrayOf("*/*")) }, enabled = !vm.busy) { Text("Add form model (.gguf)") }
                    Hint("Or copy the .gguf to Android/data/org.imci.transcriber/files/models/llm/")
                }
            }
            SettingsCard("Speech to text", Icons.Filled.RecordVoiceOver) {
                SpeechModelPicker(vm)
                ToggleRow("Live transcription", "Text appears while you record", vm.liveMode) { vm.setLive(it) }
            }
            SettingsCard("About", Icons.Filled.Info) {
                Text("Speech recognition and form filling run entirely on this phone - no internet is needed and no audio leaves the device.",
                    style = MaterialTheme.typography.bodyMedium)
                Hint("IMCI form: children 2-59 months (Bangladesh IMCI Chart Booklet 2019).")
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

/** Speech model choice: the built-in model, .bin files or CTC folders in the models folder ("Add" copies a .bin there). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeechModelPicker(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModel(it) } }
    val current = vm.models.firstOrNull { it.id == vm.modelId }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (!vm.busy) open = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(value = current?.label ?: "—", onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text("Speech model") }, enabled = !vm.busy,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                vm.models.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { open = false; vm.selectModel(m.id) }) }
            }
        }
        OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }, enabled = !vm.busy) { Text("Add") }
    }
    Hint("Fast CTC models (Srotoswini, Bhatiyali) are folders with tokens.txt in Android/data/org.imci.transcriber/files/models/")
}
