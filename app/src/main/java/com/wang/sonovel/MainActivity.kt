package com.wang.sonovel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wang.sonovel.ui.AppRoot
import com.wang.sonovel.ui.theme.SoNovelTheme
import com.wang.sonovel.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** 外部传入的事件：分享的链接 / 通知点击打开的页面 */
    val externalEvents = MutableStateFlow<ExternalEvent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()
            val dark = isAppInDarkTheme(settings.themeMode)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            SoNovelTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                AppRoot(externalEvents)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = Regex("https?://\\S+").find(text)?.value
            if (url != null) externalEvents.value = ExternalEvent.SharedUrl(url)
        }
        intent.getStringExtra(EXTRA_OPEN_BOOK)?.let { externalEvents.value = ExternalEvent.OpenBook(it) }
            ?: intent.getStringExtra(EXTRA_OPEN_TAB)?.let { externalEvents.value = ExternalEvent.OpenTab(it) }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_OPEN_BOOK = "open_book"
    }
}

sealed interface ExternalEvent {
    data class SharedUrl(val url: String) : ExternalEvent
    data class OpenTab(val tab: String) : ExternalEvent
    data class OpenBook(val path: String) : ExternalEvent
}
