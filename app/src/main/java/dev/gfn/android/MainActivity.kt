package dev.gfn.android

import android.content.res.Configuration
import android.os.Bundle
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.gfn.android.ui.GfnAndroidApp
import dev.gfn.webrtc.GfnInputForensics
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private val runtime: GfnAppRuntimeViewModel by lazy {
        ViewModelProvider(this)[GfnAppRuntimeViewModel::class.java]
    }
    private val activityInstanceId = nextActivityInstanceId.incrementAndGet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logLifecycle("onCreate", "saved=${savedInstanceState != null}")
        GfnInputForensics.configure(BuildConfig.DEBUG && BuildConfig.INPUT_FORENSICS_ENABLED)
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        setContent {
            GfnAndroidApp(runtime)
        }
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val trace = GfnInputForensics.beginActivityDispatch(
            event = event,
            activityInstanceId = activityInstanceId,
            focusedView = currentFocus?.let { "${it.javaClass.name}@${Integer.toHexString(System.identityHashCode(it))}" } ?: "none",
        )
        var handled = false
        try {
            handled = super.dispatchKeyEvent(event)
            return handled
        } finally {
            GfnInputForensics.endActivityDispatch(trace, handled)
        }
    }

    override fun onStart() {
        super.onStart()
        logLifecycle("onStart")
    }

    override fun onResume() {
        super.onResume()
        logLifecycle("onResume")
    }

    override fun onPause() {
        logLifecycle("onPause")
        super.onPause()
    }

    override fun onStop() {
        logLifecycle("onStop")
        super.onStop()
    }

    override fun onDestroy() {
        logLifecycle("onDestroy", "changingConfig=$isChangingConfigurations finishing=$isFinishing")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        logLifecycle("onConfigurationChanged", "orientation=${orientationName(newConfig.orientation)}")
    }

    private fun logLifecycle(event: String, extra: String = "") {
        val suffix = if (extra.isBlank()) "" else " $extra"
        Log.i(
            "GfnActivity",
            "Activity#$activityInstanceId $event orientation=${orientationName(resources.configuration.orientation)} requested=$requestedOrientation$suffix",
        )
    }

    private fun orientationName(value: Int): String = when (value) {
        Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
        Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
        else -> "UNDEFINED"
    }

    private companion object {
        val nextActivityInstanceId = AtomicLong(0)
    }
}
