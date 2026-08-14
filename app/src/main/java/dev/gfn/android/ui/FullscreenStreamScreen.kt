package dev.gfn.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.gfn.android.stream.GfnStreamingController
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.webrtc.GfnVideoSurfaceView
import kotlinx.coroutines.delay

/** v5.1：全屏 H.264 画面 + 硬件键盘/相对鼠标。Back 打开本地薄 Overlay，Esc 继续发给远端。 */
@Composable
fun FullscreenStreamScreen(
    controller: GfnStreamingController,
    streamState: StreamState,
    diagnostics: StreamDiagnostics,
    onExitFullscreen: () -> Unit,
    onEndSession: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val videoView = remember { GfnVideoSurfaceView(context) }
    var overlayOpen by remember { mutableStateOf(false) }

    DisposableEffect(videoView, controller) {
        videoView.setKeyboardMouseInputEnabled(true)
        controller.bindVideoOutput(videoView)
        onDispose {
            controller.onFullscreenExit()
            controller.setOverlayOpen(false)
            controller.unbindVideoOutput(videoView)
            videoView.setKeyboardMouseInputEnabled(false)
            videoView.releaseRenderer()
        }
    }

    DisposableEffect(activity, controller) {
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onActivityResumed()
                Lifecycle.Event.ON_PAUSE -> controller.onActivityPaused()
                Lifecycle.Event.ON_DESTROY -> controller.onActivityDestroy()
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val decor = window?.decorView
        val oldVisibility = decor?.systemUiVisibility
        if (activity != null && activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            runCatching { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        }
        if (window != null && decor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.apply {
                    hide(WindowInsets.Type.systemBars())
                    systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                run {
                    decor.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }
            }
        }
        onDispose {
            if (window != null && decor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(WindowInsets.Type.systemBars())
                } else if (oldVisibility != null) {
                    @Suppress("DEPRECATION")
                    run { decor.systemUiVisibility = oldVisibility }
                }
            }
            if (activity != null && !activity.isChangingConfigurations) {
                runCatching { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    fun setLocalOverlay(open: Boolean) {
        // Overlay 打开前同步冻结输入，避免 Compose side-effect 调度留下短暂的输入窗口。
        controller.setOverlayOpen(open)
        if (open) videoView.releaseKeyboardMouseCapture()
        overlayOpen = open
    }

    LaunchedEffect(overlayOpen) {
        if (!overlayOpen) {
            // Overlay 关闭后等 View 重新进入 focusable 树，再请求 capture；真正启用鼠标仍等 callback。
            delay(120)
            videoView.requestKeyboardMouseCapture()
        }
    }

    BackHandler {
        setLocalOverlay(!overlayOpen)
    }

    val videoAspectRatio = diagnostics.video.firstFrameWidth
        ?.takeIf { it > 0 }
        ?.let { width ->
            diagnostics.video.firstFrameHeight
                ?.takeIf { it > 0 }
                ?.let { height -> width.toFloat() / height.toFloat() }
        }
        ?: (16f / 9f)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { videoView },
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(videoAspectRatio)
                .fillMaxSize(),
            update = { view ->
                if (!overlayOpen && !view.hasPointerCapture()) {
                    view.requestFocus()
                }
            },
        )

        if (overlayOpen) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("串流菜单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("打开 Overlay 已执行 releaseAll；Esc 不被占用，仍发送给远端游戏。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { setLocalOverlay(false) }) { Text("返回游戏") }
                    OutlinedButton(
                        onClick = {
                            controller.onFullscreenExit()
                            onExitFullscreen()
                        },
                    ) { Text("退出全屏") }
                }
                OutlinedButton(
                    onClick = {
                        controller.prepareForSessionEnd {
                            onEndSession()
                            onExitFullscreen()
                        }
                    },
                ) { Text("End Session") }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
