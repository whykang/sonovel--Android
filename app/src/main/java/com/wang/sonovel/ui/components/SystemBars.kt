package com.wang.sonovel.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** 让窗口内容延伸到手势导航条（小白条）下方：导航栏透明、关闭系统的对比度底色 */
@Suppress("DEPRECATION")
private fun Window.makeNavigationBarImmersive(lightBackground: Boolean) {
    navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isNavigationBarContrastEnforced = false
    WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars = lightBackground
}

/**
 * 在 ModalBottomSheet 的内容里调用：底部面板位于独立窗口，默认会保留系统的导航栏底色，
 * 这里让面板背景一直铺到屏幕底部，小白条悬浮在面板之上，颜色随应用主题深浅变化。
 */
@Composable
fun ImmersiveSheetEffect() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    SideEffect {
        val window = generateSequence(view as View?) { it.parent as? View }
            .firstNotNullOfOrNull { (it as? DialogWindowProvider)?.window }
        window?.makeNavigationBarImmersive(light)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 阅读界面使用：状态栏图标和小白条的深浅跟随阅读背景（纸黄 / 护眼 / 夜间），离开时恢复原样。
 */
@Composable
fun ReaderSystemBars(lightBackground: Boolean) {
    val context = LocalContext.current
    DisposableEffect(lightBackground) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNav = controller?.isAppearanceLightNavigationBars
        window?.makeNavigationBarImmersive(lightBackground)
        controller?.isAppearanceLightStatusBars = lightBackground
        onDispose {
            if (oldStatus != null) controller.isAppearanceLightStatusBars = oldStatus
            if (oldNav != null) controller.isAppearanceLightNavigationBars = oldNav
        }
    }
}
