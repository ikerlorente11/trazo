package dev.pistation.trazo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import dev.pistation.trazo.data.ArtworkMeta
import dev.pistation.trazo.data.ArtworkStore
import dev.pistation.trazo.data.BrushStore
import dev.pistation.trazo.ui.EditorScreen
import dev.pistation.trazo.ui.EditorState
import dev.pistation.trazo.ui.GalleryScreen
import dev.pistation.trazo.ui.TrazoColors

class MainActivity : ComponentActivity() {
    private lateinit var store: ArtworkStore
    private lateinit var brushes: BrushStore
    private var editor by mutableStateOf<EditorState?>(null)
    private var galleryKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferHighRefreshRate()
        store = ArtworkStore(this)
        brushes = BrushStore(this)
        setContent {
            MaterialTheme(colorScheme = TrazoColors) {
                val ed = editor
                if (ed == null) key(galleryKey) { GalleryScreen(store) { open(it) } }
                else key(ed) { EditorScreen(ed) { close() } }
            }
        }
    }

    private fun open(m: ArtworkMeta) { editor = EditorState(this, store, brushes, m) { reopened -> close(); open(reopened) } }

    private fun close() {
        editor?.dispose()
        editor = null
        galleryKey++
    }

    /** The Mi Pad 5 panel does 120 Hz: ask for the fastest mode at the current resolution. */
    private fun preferHighRefreshRate() {
        val d = display ?: return
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    override fun onPause() {
        super.onPause()
        editor?.let { e -> if (e.isDirty()) e.save() }
        editor?.view?.onPause()
    }

    override fun onResume() {
        super.onResume()
        editor?.view?.onResume()
    }
}
