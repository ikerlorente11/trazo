package dev.pistation.trazo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pistation.trazo.data.ArtworkMeta
import dev.pistation.trazo.data.ArtworkStore
import dev.pistation.trazo.data.Release
import dev.pistation.trazo.data.Updater
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class CanvasPreset(val name: String, val w: Int, val h: Int)

val PRESETS = listOf(
    CanvasPreset("Pantalla (Mi Pad 5)", 2560, 1600),
    CanvasPreset("Pantalla vertical", 1600, 2560),
    CanvasPreset("Cuadrado", 2048, 2048),
    CanvasPreset("Cuadrado grande", 4096, 4096),
    CanvasPreset("4K", 3840, 2160),
    CanvasPreset("A4 · 300 ppp", 2480, 3508),
    CanvasPreset("A5 · 300 ppp", 1748, 2480),
    CanvasPreset("Cómic (US) · 300 ppp", 1988, 3075),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(store: ArtworkStore, onOpen: (ArtworkMeta) -> Unit) {
    var items by remember { mutableStateOf(store.list()) }
    var creating by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<ArtworkMeta?>(null) }
    var renaming by remember { mutableStateOf<ArtworkMeta?>(null) }
    var deleting by remember { mutableStateOf<ArtworkMeta?>(null) }
    fun refresh() { items = store.list() }
    val fmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "ES")) }

    Column(Modifier.fillMaxSize().background(Ui.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Galería", color = Ui.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("  v${Updater.current}", color = Ui.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            FilledTonalButton({ creating = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Nuevo lienzo") }
        }
        UpdateBanner()
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Crea tu primer lienzo con «Nuevo lienzo»", color = Ui.dim, fontSize = 16.sp)
        }
        LazyVerticalGrid(GridCells.Adaptive(200.dp), contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(items, key = { it.id }) { m ->
                val thumb = remember(m.id, m.modified) { store.thumbnail(m.id) }
                Column(Modifier.combinedClickable(onLongClick = { menu = m }) { onOpen(m) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        val ratio = m.width.toFloat() / m.height
                        Box(Modifier.fillMaxSize(if (ratio >= 1f) 1f else ratio).aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
                            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        DropdownMenu(menu?.id == m.id, { menu = null }) {
                            DropdownMenuItem({ Text("Renombrar") }, { menu = null; renaming = m })
                            DropdownMenuItem({ Text("Duplicar") }, { menu = null; store.duplicate(m.id); refresh() })
                            DropdownMenuItem({ Text("Eliminar", color = Color(0xFFFF6B6B)) }, { menu = null; deleting = m })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(m.name, color = Ui.text, fontSize = 15.sp, maxLines = 1)
                    Text("${m.width}×${m.height} · ${fmt.format(Date(m.modified))}", color = Ui.dim, fontSize = 12.sp)
                }
            }
        }
    }

    if (creating) NewCanvasDialog({ creating = false }) { name, w, h ->
        creating = false
        onOpen(store.create(name, w, h))
    }
    renaming?.let { m ->
        var name by remember(m.id) { mutableStateOf(m.name) }
        AlertDialog({ renaming = null }, confirmButton = { TextButton({ store.rename(m.id, name.ifBlank { m.name }); renaming = null; refresh() }) { Text("Aceptar") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancelar") } }, title = { Text("Renombrar") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) })
    }
    deleting?.let { m ->
        AlertDialog({ deleting = null }, confirmButton = { TextButton({ store.delete(m.id); deleting = null; refresh() }) { Text("Eliminar", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancelar") } }, title = { Text("¿Eliminar «${m.name}»?") },
            text = { Text("No se puede deshacer.") })
    }
}

@Composable
private fun NewCanvasDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int) -> Unit) {
    var w by remember { mutableStateOf("2560") }
    var h by remember { mutableStateOf("1600") }
    var name by remember { mutableStateOf("Sin título") }
    val wi = w.toIntOrNull() ?: 0; val hi = h.toIntOrNull() ?: 0
    val valid = wi in 64..4096 && hi in 64..4096
    val maxLayers = if (valid) ((1400L shl 20) / (wi.toLong() * hi * 4) - 8).coerceAtLeast(1) else 0
    AlertDialog(onDismiss,
        confirmButton = { TextButton({ onCreate(name.ifBlank { "Sin título" }, wi, hi) }, enabled = valid) { Text("Crear") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
        title = { Text("Nuevo lienzo") },
        text = {
            Column {
                PRESETS.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { w = p.w.toString(); h = p.h.toString() }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                        Text(p.name, modifier = Modifier.weight(1f))
                        Text("${p.w} × ${p.h}", color = Ui.dim)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, label = { Text("Ancho") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, label = { Text("Alto") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Text(if (valid) "Capas recomendadas como máximo: $maxLayers" else "Tamaño entre 64 y 4096 px", color = Ui.dim, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        })
}

/** "New version" strip: download and install the release APK from GitHub. */
@Composable
private fun UpdateBanner() {
    val ctx = LocalContext.current
    val updater = remember { Updater(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { release = withContext(Dispatchers.IO) { updater.check() } }
    val r = release ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(12.dp)).background(Ui.accent.copy(alpha = 0.18f))
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Trazo ${r.version} disponible", color = Ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(error ?: r.notes.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "Tienes la ${Updater.current}",
                color = if (error != null) Color(0xFFFF6B6B) else Ui.dim, fontSize = 12.sp, maxLines = 1)
            progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(top = 6.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Button(enabled = progress == null, onClick = {
            if (!updater.canInstall) {
                error = "Permite a Trazo instalar apps y vuelve a pulsar"
                ctx.startActivity(updater.permissionIntent())
                return@Button
            }
            error = null; progress = 0f
            scope.launch {
                val f = withContext(Dispatchers.IO) { runCatching { updater.download(r) { p -> progress = p } } }
                progress = null
                f.onSuccess { updater.install(it) }.onFailure { error = "Error al descargar: ${it.message}" }
            }
        }) { Text("Actualizar") }
    }
    Spacer(Modifier.height(8.dp))
}
