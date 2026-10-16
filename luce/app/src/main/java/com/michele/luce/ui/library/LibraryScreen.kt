package com.michele.luce.ui.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.michele.luce.data.EditStore
import com.michele.luce.data.Exporter
import com.michele.luce.data.MediaItem
import com.michele.luce.data.MediaRepository
import com.michele.luce.ui.components.PillRow
import com.michele.luce.ui.theme.LuceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAB_ALL = "Tutte le foto"
private const val TAB_EDITED = "Modificate"

private data class GridEntry(val uri: Uri, val thumb: String?, val edited: Boolean)

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasMediaAccess(context: Context): Boolean =
    mediaPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private fun takePersistable(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {
        // non tutti i fornitori lo consentono: l'accesso resta valido per questa sessione
    }
}

@Composable
fun LibraryScreen(refreshKey: Int, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val editStore = remember { EditStore(context) }
    var granted by remember { mutableStateOf(hasMediaAccess(context)) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var edited by remember { mutableStateOf<List<GridEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf(TAB_ALL) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMediaAccess(context)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { takePersistable(context, uri); onOpen(uri) }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { takePersistable(context, uri); onOpen(uri) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        val f = cameraFile
        if (ok && u != null) onOpen(Uri.parse(u))
        else if (f != null) File(f).let { if (it.exists() && it.length() == 0L) it.delete() }
    }

    LaunchedEffect(Unit) {
        if (!granted && !permissionAsked) {
            permissionAsked = true
            permLauncher.launch(mediaPermissions())
        }
    }

    LaunchedEffect(granted, refreshKey) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            val media = if (granted) MediaRepository.queryImages(context) else emptyList()
            val index = editStore.index().map { GridEntry(it.uri, it.thumbPath?.takeIf { p -> File(p).exists() }, true) }
            media to index
        }
        items = result.first
        edited = result.second
        loading = false
    }

    val editedMap = remember(edited) { edited.associateBy { it.uri.toString() } }
    val albums = remember(items) { MediaRepository.albums(items).map { it.name } }
    val tabs = listOf(TAB_ALL, TAB_EDITED) + albums

    val entries: List<GridEntry> = when (tab) {
        TAB_EDITED -> edited
        TAB_ALL -> items.map { m -> editedMap[m.uri.toString()] ?: GridEntry(m.uri, null, false) }
        else -> items.filter { it.album == tab }.map { m -> editedMap[m.uri.toString()] ?: GridEntry(m.uri, null, false) }
    }

    fun openCamera() {
        val (file, uri) = Exporter.cameraFile(context)
        cameraFile = file.absolutePath
        cameraUri = uri.toString()
        try {
            camera.launch(uri)
        } catch (e: Exception) {
            file.delete()
        }
    }

    Column(Modifier.fillMaxSize().background(LuceColors.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Luce", style = MaterialTheme.typography.titleLarge, color = LuceColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    if (granted) "${items.size} foto · ${edited.size} modificate" else "Editor fotografico open source",
                    style = MaterialTheme.typography.bodySmall, color = LuceColors.TextSecondary
                )
            }
            IconButton(onClick = { openCamera() }) { Icon(Icons.Filled.PhotoCamera, "Fotocamera", tint = LuceColors.TextPrimary) }
            IconButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Filled.PhotoLibrary, "Scegli dalla galleria", tint = LuceColors.TextPrimary) }
            IconButton(onClick = { documents.launch(arrayOf("image/*")) }) {
                Icon(Icons.Filled.FolderOpen, "Apri file", tint = LuceColors.TextPrimary)
            }
        }

        PillRow(tabs, tab, { it }, { tab = it })

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = LuceColors.Accent)
                !granted && tab != TAB_EDITED -> PermissionPrompt(
                    onAllow = { permLauncher.launch(mediaPermissions()) },
                    onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.align(Alignment.Center)
                )
                entries.isEmpty() -> Text(
                    if (tab == TAB_EDITED) "Nessuna foto modificata finora." else "Nessuna foto trovata.",
                    color = LuceColors.TextSecondary, modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(entries, key = { it.uri.toString() }) { e ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(3.dp))
                                .background(LuceColors.SurfaceHigh)
                                .clickable { onOpen(e.uri) }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(e.thumb?.let { File(it) } ?: e.uri)
                                    .size(360)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (e.edited) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(5.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Edit, "Modificata", tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onAllow: () -> Unit, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.PhotoLibrary, null, tint = LuceColors.Accent, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            "Consenti l'accesso alle foto per vedere la tua libreria, oppure scegli una singola immagine.",
            color = LuceColors.TextSecondary, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAllow, colors = ButtonDefaults.buttonColors(containerColor = LuceColors.Accent)) {
            Text("Consenti accesso")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPick) { Text("Scegli una foto") }
    }
}
