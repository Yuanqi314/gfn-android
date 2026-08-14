package dev.gfn.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.gfn.account.GfnAccountClient
import dev.gfn.android.auth.AndroidKeystoreTokenStore
import dev.gfn.android.auth.AuthController
import dev.gfn.android.content.GfnContentController
import dev.gfn.android.session.AndroidSessionRecordStore
import dev.gfn.android.session.AndroidStableDeviceId
import dev.gfn.android.session.GfnSessionController
import dev.gfn.android.stream.GfnStreamingController
import dev.gfn.auth.AuthSessionService
import dev.gfn.auth.NvidiaAuthApi
import dev.gfn.auth.NvidiaAuthReferenceDefaults
import dev.gfn.cloudmatch.GfnCloudMatchClient
import dev.gfn.games.GfnGamesClient
import dev.gfn.network.UrlConnectionHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

class GfnAppRuntimeViewModel(application: Application) : AndroidViewModel(application) {
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transport = UrlConnectionHttpTransport()
    private val appContext = application.applicationContext

    val authController: AuthController
    val contentController: GfnContentController
    val sessionController: GfnSessionController
    val streamingController: GfnStreamingController

    init {
        val authApi = NvidiaAuthApi(
            transport = transport,
            config = NvidiaAuthReferenceDefaults.config.copy(displayName = "Android"),
            sleepSeconds = { seconds -> delay(seconds * 1_000L) },
        )
        authController = AuthController(
            service = AuthSessionService(authApi, AndroidKeystoreTokenStore(appContext)),
            scope = runtimeScope,
        )
        contentController = GfnContentController(
            authController = authController,
            accountClient = GfnAccountClient(transport),
            gamesClient = GfnGamesClient(transport),
            scope = runtimeScope,
        )
        val stableDeviceId = AndroidStableDeviceId(appContext)
        sessionController = GfnSessionController(
            authController = authController,
            cloudMatchClient = GfnCloudMatchClient(
                transport = transport,
                deviceId = stableDeviceId::getOrCreate,
            ),
            recordStore = AndroidSessionRecordStore(appContext),
            scope = runtimeScope,
        )
        streamingController = GfnStreamingController(
            context = appContext,
            serverSessionEndedSink = sessionController::onServerSessionEnded,
            transportReconcileSink = sessionController::reconcileAfterStreamDisconnect,
        )
    }

    override fun onCleared() {
        streamingController.disconnect()
        runtimeScope.cancel()
        super.onCleared()
    }
}
