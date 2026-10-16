package com.michele.luce

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.michele.luce.ui.editor.EditorScreen
import com.michele.luce.ui.editor.EditorViewModel
import com.michele.luce.ui.library.LibraryScreen
import com.michele.luce.ui.theme.LuceColors
import com.michele.luce.ui.theme.LuceTheme

class MainActivity : ComponentActivity() {

    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming.value = extractUri(intent)
        setContent {
            LuceTheme {
                LuceApp(incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUri(intent)?.let { incoming.value = it }
    }

    @Suppress("DEPRECATION")
    private fun legacyStream(intent: Intent): Uri? = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else legacyStream(intent)
            }
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            else -> null
        }
    }
}

@Composable
fun LuceApp(incoming: MutableState<Uri?>) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(incoming.value) {
        incoming.value?.let {
            editing = it.toString()
            incoming.value = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(LuceColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val current = editing
        if (current == null) {
            LibraryScreen(refresh, onOpen = { editing = it.toString() })
        } else {
            key(current) {
                EditorHost(Uri.parse(current)) {
                    editing = null
                    refresh++
                }
            }
        }
    }
}

/** Ogni apertura dell'editor ha il proprio ViewModel, liberato all'uscita. */
@Composable
private fun EditorHost(uri: Uri, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val owner = remember(uri) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    val vm: EditorViewModel = viewModel(viewModelStoreOwner = owner, key = uri.toString()) {
        EditorViewModel(app, uri)
    }
    EditorScreen(vm, onBack)
}
