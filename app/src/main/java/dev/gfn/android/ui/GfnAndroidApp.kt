package dev.gfn.android.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gfn.android.GfnAppRuntimeViewModel
import dev.gfn.android.auth.AuthUiState
import dev.gfn.android.content.ContentUiState
import dev.gfn.android.content.GameDetailUiState
import dev.gfn.android.session.PersistedSessionRecord
import dev.gfn.android.session.SessionUiState
import dev.gfn.android.settings.GfnKeyboardLayoutCatalog
import dev.gfn.android.settings.GfnStreamSettingsCatalog
import dev.gfn.android.settings.PersistentStreamSettings
import dev.gfn.android.settings.ResolvedLaunchProfile
import dev.gfn.android.stream.GfnStreamingController
import dev.gfn.core.model.GameDetail
import dev.gfn.core.model.GameSummary
import dev.gfn.core.model.GameVariant
import dev.gfn.core.model.SessionInfo
import dev.gfn.diagnostics.DiagnosticsSnapshot
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.identity.GfnLocale
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.webrtc.GfnVideoSurfaceView

private enum class AppTab(val title: String, val glyph: String) {
    Home("首页", "H"),
    Library("游戏库", "L"),
    Store("全部游戏", "G"),
    Session("会话", "Q"),
    Diagnostics("诊断", "D"),
    Settings("设置", "S"),
}

@Composable
fun GfnAndroidApp(runtime: GfnAppRuntimeViewModel) {
    var darkTheme by rememberSaveable { mutableStateOf(true) }
    var tabName by rememberSaveable { mutableStateOf(AppTab.Home.name) }
    var fullscreenStream by rememberSaveable { mutableStateOf(false) }
    val tab = AppTab.entries.firstOrNull { it.name == tabName } ?: AppTab.Home

    val authController = runtime.authController
    val contentController = runtime.contentController
    val streamSettingsController = runtime.streamSettingsController
    val sessionController = runtime.sessionController
    val streamingController = runtime.streamingController

    val authState by authController.state.collectAsState()
    val contentState by contentController.state.collectAsState()
    val searchResults by contentController.searchResults.collectAsState()
    val detailState by contentController.detailState.collectAsState()
    val sessionState by sessionController.state.collectAsState()
    val resumeRecord by sessionController.resumeRecord.collectAsState()
    val activeLaunchProfile by sessionController.activeLaunchProfile.collectAsState()
    val streamSettings by streamSettingsController.settings.collectAsState()
    val streamState by streamingController.state.collectAsState()
    val streamDiagnostics by streamingController.diagnostics.collectAsState()

    LaunchedEffect(authController) { authController.restoreOnce() }
    LaunchedEffect(sessionController) { sessionController.restoreResumeRecordOnce() }
    LaunchedEffect(authState) { contentController.onAuthStateChanged(authState) }
    LaunchedEffect(tabName, fullscreenStream, sessionState, streamState) {
        Log.i(
            "GfnNav",
            "route=$tabName fullscreen=$fullscreenStream session=${sessionState.javaClass.simpleName} stream=${streamState.javaClass.simpleName}",
        )
    }
    LaunchedEffect(sessionState, streamState) {
        if (sessionState is SessionUiState.Ended || streamState is StreamState.SessionEnded) {
            fullscreenStream = false
            tabName = AppTab.Session.name
        }
    }

    val startSession: (GameDetail, GameVariant) -> Unit = { detail, variant ->
        val ready = contentState as? ContentUiState.Ready
        if (ready != null) {
            contentController.closeGameDetail()
            sessionController.startGame(detail, variant, ready.subscription)
            tabName = AppTab.Session.name
        }
    }

    GfnTheme(darkTheme = darkTheme) {
        val claimedForFullscreen = sessionState as? SessionUiState.Claimed
        if (
            fullscreenStream && claimedForFullscreen != null &&
            streamState !is StreamState.Idle && streamState !is StreamState.Closed && streamState !is StreamState.SessionEnded
        ) {
            FullscreenStreamScreen(
                controller = streamingController,
                streamState = streamState,
                diagnostics = streamDiagnostics,
                onExitFullscreen = { fullscreenStream = false },
                onEndSession = { sessionController.endSession(cancelledByUser = false) },
            )
        } else {
            Scaffold(
                bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tabName = item.name },
                            icon = { Text(item.glyph, fontWeight = FontWeight.Bold) },
                            label = { Text(item.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    AppTab.Home -> HomeScreen(
                        authState = authState,
                        contentState = contentState,
                        sessionState = sessionState,
                        resumeRecord = resumeRecord,
                        onLogin = authController::startLogin,
                        onCancelLogin = authController::cancelLogin,
                        onLogout = authController::signOut,
                        onRefreshContent = contentController::refresh,
                        onOpenSession = { tabName = AppTab.Session.name },
                    )
                    AppTab.Library -> GameListScreen(
                        title = "游戏库",
                        subtitle = "当前 GFN 账号真实 Library",
                        authState = authState,
                        contentState = contentState,
                        games = (contentState as? ContentUiState.Ready)?.library.orEmpty(),
                        detailState = detailState,
                        onOpenGame = contentController::openGame,
                        onCloseDetail = contentController::closeGameDetail,
                        onStartSession = startSession,
                        onRetry = contentController::refresh,
                    )
                    AppTab.Store -> CatalogScreen(
                        authState = authState,
                        contentState = contentState,
                        searchResults = searchResults,
                        detailState = detailState,
                        onSearch = contentController::search,
                        onOpenGame = contentController::openGame,
                        onCloseDetail = contentController::closeGameDetail,
                        onStartSession = startSession,
                        onRetry = contentController::refresh,
                    )
                    AppTab.Session -> SessionScreen(
                        state = sessionState,
                        resumeRecord = resumeRecord,
                        streamState = streamState,
                        streamDiagnostics = streamDiagnostics,
                        launchProfile = activeLaunchProfile,
                        streamingController = streamingController,
                        onResume = sessionController::resumePersisted,
                        onClaim = sessionController::claimCurrent,
                        onConnectStream = streamingController::connectClaimedSession,
                        onDisconnectStream = streamingController::disconnect,
                        onEnterFullscreen = { fullscreenStream = true },
                        onEnd = {
                            streamingController.prepareForSessionEnd {
                                sessionController.endSession(cancelledByUser = false)
                            }
                        },
                        onCancel = {
                            streamingController.prepareForSessionEnd {
                                sessionController.endSession(cancelledByUser = true)
                            }
                        },
                        onForget = sessionController::forgetResumeRecord,
                    )
                    AppTab.Diagnostics -> DiagnosticsScreen(
                        snapshot = DiagnosticsSnapshot(),
                        contentState = contentState,
                        sessionState = sessionState,
                        resumeRecord = resumeRecord,
                        streamState = streamState,
                        streamDiagnostics = streamDiagnostics,
                    )
                    AppTab.Settings -> SettingsScreen(
                        darkTheme = darkTheme,
                        authState = authState,
                        contentState = contentState,
                        sessionState = sessionState,
                        hasFrozenLaunchProfile = activeLaunchProfile != null || resumeRecord != null,
                        streamSettings = streamSettings,
                        onKeyboardLayoutSelected = streamSettingsController::setKeyboardLayout,
                        onResolutionSelected = streamSettingsController::setResolution,
                        onFpsSelected = streamSettingsController::setFps,
                        onMaxBitrateSelected = streamSettingsController::setMaxBitrateKbps,
                        onVideoCodecSelected = streamSettingsController::setVideoCodec,
                        onAudioChannelsSelected = streamSettingsController::setAudioChannels,
                        onToggleTheme = { darkTheme = !darkTheme },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun HomeScreen(
    authState: AuthUiState,
    contentState: ContentUiState,
    sessionState: SessionUiState,
    resumeRecord: PersistedSessionRecord?,
    onLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
    onRefreshContent: () -> Unit,
    onOpenSession: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("GFN Android Lab", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text(
                "独立 Android GFN 客户端 · v6.0.1 HEVC Negotiation Compat",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { AuthCard(authState, onLogin, onCancelLogin, onLogout) }
        item { AccountContentCard(contentState, onRefreshContent) }
        item {
            SessionPreviewCard(
                state = sessionState,
                resumeRecord = resumeRecord,
                onOpenSession = onOpenSession,
            )
        }
        item { DiagnosticPreviewCard() }
    }
}

@Composable
private fun AccountContentCard(state: ContentUiState, onRefresh: () -> Unit) {
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GFN 内容服务", style = MaterialTheme.typography.titleMedium)
            when (state) {
                ContentUiState.WaitingForLogin -> Text("登录后自动加载订阅、游戏库和完整目录。")
                ContentUiState.Loading -> {
                    Text("正在连接 serverInfo / MES / Games GraphQL…")
                    CircularProgressIndicator()
                }
                is ContentUiState.Ready -> {
                    Text("会员：${state.subscription.membershipTier}", fontWeight = FontWeight.Bold)
                    Text("VPC：${state.vpcId}")
                    Text("游戏库：${state.library.size} · 目录：${state.catalog.size}")
                    val max = state.subscription.entitledResolutions.maxWithOrNull(
                        compareBy({ it.width * it.height }, { it.fps }),
                    )
                    if (max != null) Text("最高已授权档位：${max.width}×${max.height} @ ${max.fps} FPS")
                    Text("v3 真机：会员 / Library / Catalog / Search / Detail 已验证。")
                    Button(onClick = onRefresh) { Text("刷新 GFN 内容") }
                }
                is ContentUiState.Error -> {
                    Text("内容服务加载失败", color = MaterialTheme.colorScheme.error)
                    Text(state.message)
                    Button(onClick = onRefresh) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun AuthCard(
    state: AuthUiState,
    onLogin: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GeForce NOW 登录", style = MaterialTheme.typography.labelLarge)
            when (state) {
                AuthUiState.Restoring -> {
                    Text("正在恢复本地登录状态…", style = MaterialTheme.typography.titleLarge)
                    CircularProgressIndicator()
                }
                AuthUiState.SignedOut -> {
                    Text("使用 NVIDIA Device Flow 登录。凭据由 AndroidKeyStore 加密保存。")
                    Button(onClick = onLogin) { Text("登录 GeForce NOW") }
                }
                AuthUiState.RequestingCode -> {
                    Text("正在向 NVIDIA 获取登录码…", style = MaterialTheme.typography.titleLarge)
                    CircularProgressIndicator()
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
                is AuthUiState.AwaitingAuthorization -> {
                    val auth = state.authorization
                    Text("登录码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(auth.userCode, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                    Text("请在 NVIDIA 页面完成授权；客户端会按服务器要求自动轮询。")
                    Text(auth.verificationUri, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        runCatching { uriHandler.openUri(auth.verificationUriComplete ?: auth.verificationUri) }
                    }) { Text("打开 NVIDIA 登录页面") }
                    OutlinedButton(onClick = onCancel) { Text("取消登录") }
                }
                is AuthUiState.SignedIn -> {
                    Text("已登录", color = MaterialTheme.colorScheme.primary)
                    Text(state.user.displayName, style = MaterialTheme.typography.headlineSmall)
                    state.user.email?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.user.membershipTier?.let { Text("登录 token 声明等级：$it") }
                    state.session.provider?.let { Text("Provider：${it.displayName}") }
                    Text("重启恢复：已通过真机验证。")
                    OutlinedButton(onClick = onLogout) { Text("退出登录") }
                }
                is AuthUiState.Error -> {
                    Text("登录模块发生错误", style = MaterialTheme.typography.titleLarge)
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onLogin) { Text("重试") }
                        OutlinedButton(onClick = onCancel) { Text("返回") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionPreviewCard(
    state: SessionUiState,
    resumeRecord: PersistedSessionRecord?,
    onOpenSession: () -> Unit,
) {
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CloudMatch Session", style = MaterialTheme.typography.titleMedium)
            Text(sessionStateLabel(state))
            if (resumeRecord != null) Text("存在可恢复记录：${resumeRecord.gameTitle}")
            Text("v4 只验证 Create / Queue / Ready / Claim / Resume / End，不启动视频。")
            Button(onClick = onOpenSession) { Text("打开会话面板") }
        }
    }
}

@Composable
private fun DiagnosticPreviewCard() {
    val identity = GfnClientIdentity.WindowsDesktop
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GFN 协议身份", style = MaterialTheme.typography.titleMedium)
            Text("${identity.deviceOs} · ${identity.deviceType}")
            Text("${identity.clientIdentification} · ${identity.clientPlatformName}")
            Text(
                "Android 本机保持真实；GFN 内容/会话协议使用 Windows Desktop 身份。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GameListScreen(
    title: String,
    subtitle: String,
    authState: AuthUiState,
    contentState: ContentUiState,
    games: List<GameSummary>,
    detailState: GameDetailUiState,
    onOpenGame: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onStartSession: (GameDetail, GameVariant) -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { DetailPanel(detailState, onCloseDetail, onStartSession) }
        when {
            authState !is AuthUiState.SignedIn -> item { Text("请先在首页登录 GeForce NOW。") }
            contentState is ContentUiState.Loading -> item { CircularProgressIndicator() }
            contentState is ContentUiState.Error -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(contentState.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
            else -> items(games, key = { it.appId }) { game -> GameCard(game, onOpenGame) }
        }
    }
}

@Composable
private fun CatalogScreen(
    authState: AuthUiState,
    contentState: ContentUiState,
    searchResults: List<GameSummary>,
    detailState: GameDetailUiState,
    onSearch: (String) -> Unit,
    onOpenGame: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onStartSession: (GameDetail, GameVariant) -> Unit,
    onRetry: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val catalog = (contentState as? ContentUiState.Ready)?.catalog.orEmpty()
    val visibleGames = if (query.isBlank()) catalog else searchResults
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("全部游戏", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("GFN GraphQL Catalog", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索游戏") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(onClick = { onSearch(query) }) { Text("搜索") }
            }
        }
        item { DetailPanel(detailState, onCloseDetail, onStartSession) }
        when {
            authState !is AuthUiState.SignedIn -> item { Text("请先登录 GeForce NOW。") }
            contentState is ContentUiState.Loading -> item { CircularProgressIndicator() }
            contentState is ContentUiState.Error -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(contentState.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
            query.isNotBlank() && searchResults.isEmpty() -> item { Text("暂无搜索结果。点击“搜索”后显示服务端结果。") }
            else -> items(visibleGames, key = { it.appId }) { game -> GameCard(game, onOpenGame) }
        }
    }
}

@Composable
private fun GameCard(game: GameSummary, onOpenGame: (String) -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(game.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (game.genres.isNotEmpty()) {
                Text(game.genres.take(3).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                buildList {
                    if (game.supportsHdr) add("HDR")
                    if (game.supportsRtx) add("RTX")
                    if (game.supportsReflex) add("Reflex")
                    if (game.isInLibrary) add("已在游戏库")
                }.ifEmpty { listOf("GFN") }.joinToString(" · "),
                color = MaterialTheme.colorScheme.primary,
            )
            if (game.variants.isNotEmpty()) {
                Text("商店：${game.variants.map { it.appStore }.distinct().take(4).joinToString(" / ")}")
            }
            Button(onClick = { onOpenGame(game.appId) }) { Text("查看详情 / 建立会话") }
        }
    }
}

@Composable
private fun DetailPanel(
    state: GameDetailUiState,
    onClose: () -> Unit,
    onStartSession: (GameDetail, GameVariant) -> Unit,
) {
    when (state) {
        GameDetailUiState.Idle -> Unit
        GameDetailUiState.Loading -> Card(shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("正在获取游戏详情…")
            }
        }
        is GameDetailUiState.Error -> Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onClose) { Text("关闭") }
            }
        }
        is GameDetailUiState.Ready -> GameDetailCard(state.detail, onClose, onStartSession)
    }
}

@Composable
private fun GameDetailCard(
    detail: GameDetail,
    onClose: () -> Unit,
    onStartSession: (GameDetail, GameVariant) -> Unit,
) {
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(detail.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            detail.description?.let { Text(it) }
            if (detail.genres.isNotEmpty()) Text("类型：${detail.genres.joinToString(" · ")}")
            detail.developer?.let { Text("开发商：$it") }
            detail.publisher?.let { Text("发行商：$it") }
            detail.contentRating?.let { Text("评级：$it") }
            Text(
                buildList {
                    if (detail.supportsHdr) add("HDR")
                    if (detail.supportsRtx) add("RTX")
                    if (detail.supportsReflex) add("Reflex")
                }.ifEmpty { listOf("标准 GFN") }.joinToString(" · "),
                color = MaterialTheme.colorScheme.primary,
            )
            Text("v5.4 保持 H.264 / SDR8 / 1080p60；Stereo 现在显式启用 Android WebRTC ADM 2ch 输出配置，6ch 仅作为 multiopus 协商/接收实验，不宣称原生 5.1，也不预设一定能下混。")
            if (detail.variants.isEmpty()) {
                Text("当前详情没有可启动 variant。", color = MaterialTheme.colorScheme.error)
            } else {
                detail.variants.forEach { variant ->
                    Button(onClick = { onStartSession(detail, variant) }) {
                        Text(
                            buildString {
                                append("建立 ${variant.appStore} Session")
                                if (variant.isOwned) append(" · 已拥有")
                            },
                        )
                    }
                }
            }
            OutlinedButton(onClick = onClose) { Text("关闭详情") }
        }
    }
}

@Composable
private fun SessionScreen(
    state: SessionUiState,
    resumeRecord: PersistedSessionRecord?,
    streamState: StreamState,
    streamDiagnostics: StreamDiagnostics,
    launchProfile: ResolvedLaunchProfile?,
    streamingController: GfnStreamingController,
    onResume: () -> Unit,
    onClaim: () -> Unit,
    onConnectStream: (SessionInfo, ResolvedLaunchProfile) -> Unit,
    onDisconnectStream: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onForget: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("GFN 会话 / WebRTC", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("v6.0.1 在不可变 ResolvedLaunchProfile 上增加 HEVC Main pre-createAnswer codec preference 与完整 Logcat 取证；H.264 保持 fallback。")
        }

        if (resumeRecord != null) {
            item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("可恢复 Session", style = MaterialTheme.typography.titleMedium)
                        Text("${resumeRecord.gameTitle} · ${resumeRecord.appStore}")
                        Text("Session ID：${resumeRecord.sessionId}")
                        Text("Server：${resumeRecord.serverIp ?: "等待分配"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onResume) { Text("Claim / Resume") }
                            OutlinedButton(onClick = onForget) { Text("仅清除本地记录") }
                        }
                    }
                }
            }
        }

        item { SessionStateCard(state) }

        launchProfile?.let { profile ->
            item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Resolved Launch Profile", style = MaterialTheme.typography.titleMedium)
                        Text("${profile.streamConfig.width} × ${profile.streamConfig.height} · ${profile.streamConfig.fps} FPS")
                        Text("Max bitrate：${profile.streamConfig.maxBitrateKbps / 1_000} Mbps")
                        Text("Audio：${profile.streamConfig.audioChannels}ch · Codec：${profile.streamConfig.codec}")
                        Text("Keyboard：${profile.keyboardLayout} · Game language：${profile.gameLanguage}")
                        Text(
                            if (profile.entitlementVerified) "Resolution/FPS entitlement：已匹配" else "Resolution/FPS entitlement：服务端未返回可验证列表",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("该 snapshot 从 CREATE 到 CLAIM/WebRTC 保持不变。", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (state is SessionUiState.Claimed && streamState !is StreamState.Idle && streamState !is StreamState.Closed) {
            item {
                StreamingVideoCard(
                    controller = streamingController,
                    streamState = streamState,
                    diagnostics = streamDiagnostics,
                )
            }
        }

        item {
            when (state) {
                is SessionUiState.Creating,
                is SessionUiState.Queued,
                is SessionUiState.Preparing -> Button(onClick = onCancel) { Text("取消并清理 Session") }

                is SessionUiState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onClaim) { Text("Claim / Resume") }
                    OutlinedButton(onClick = onEnd) { Text("End Session") }
                }

                is SessionUiState.Claimed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (streamState) {
                        StreamState.Idle,
                        StreamState.Closed,
                        is StreamState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                enabled = launchProfile != null,
                                onClick = { launchProfile?.let { onConnectStream(state.session, it) } },
                            ) {
                                Text("连接 WebRTC H.264")
                            }
                            if (launchProfile == null) {
                                Text(
                                    "当前 Session 缺少 v5.2+ ResolvedLaunchProfile，禁止用实时 Settings 猜测 WebRTC/Reconnect 参数。",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        is StreamState.Reconnecting -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "正在重连 ${streamState.attempt}/3 · ${streamState.source}",
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "保持原 Session ID 与冻结 ResolvedLaunchProfile；不会创建第二 Session。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StreamState.SessionEnded -> Text("服务端已结束当前游戏会话。")
                        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onEnterFullscreen) { Text("进入全屏键鼠") }
                            OutlinedButton(onClick = onDisconnectStream) { Text("断开 WebRTC") }
                        }
                    }
                    OutlinedButton(onClick = onEnd) { Text("End Session") }
                }

                is SessionUiState.Error -> if (state.session != null || resumeRecord != null) {
                    OutlinedButton(onClick = onEnd) { Text("尝试 End / Cleanup") }
                }
                is SessionUiState.Claiming,
                is SessionUiState.Ending -> CircularProgressIndicator()
                SessionUiState.Idle,
                SessionUiState.Ended,
                SessionUiState.Cancelled -> Unit
            }
        }
    }
}

@Composable
private fun StreamingVideoCard(
    controller: GfnStreamingController,
    streamState: StreamState,
    diagnostics: StreamDiagnostics,
) {
    val context = LocalContext.current
    val videoView = remember { GfnVideoSurfaceView(context) }
    DisposableEffect(videoView, controller) {
        controller.bindVideoOutput(videoView)
        onDispose {
            controller.unbindVideoOutput(videoView)
            videoView.releaseRenderer()
        }
    }

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("媒体状态：${streamStateLabel(streamState)}", style = MaterialTheme.typography.titleMedium)
            AndroidView(
                factory = { videoView },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Text(
                "WSS RX/TX ${diagnostics.signaling.rxCount}/${diagnostics.signaling.txCount} · " +
                    "ICE local/remote ${diagnostics.ice.localCandidateCount}/${diagnostics.ice.remoteCandidateCount}",
            )
            if (diagnostics.reconnect.active || diagnostics.reconnect.phase == "EXHAUSTED") {
                Text(
                    "Reconnect：${diagnostics.reconnect.phase} · " +
                        "${diagnostics.reconnect.attempt}/${diagnostics.reconnect.maxAttempts} · " +
                        "sameSession=${diagnostics.reconnect.sameSessionIdVerified.asYesNo()} · " +
                        "frozenProfile=${diagnostics.reconnect.frozenProfileVerified.asYesNo()}",
                    color = if (diagnostics.reconnect.lastError == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                diagnostics.reconnect.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (diagnostics.video.firstFrameRendered) {
                Text(
                    "FIRST FRAME ${diagnostics.video.firstFrameWidth ?: "?"}x${diagnostics.video.firstFrameHeight ?: "?"}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            (streamState as? StreamState.Failed)?.let {
                Text(it.reason, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SessionStateCard(state: SessionUiState) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("状态：${sessionStateLabel(state)}", style = MaterialTheme.typography.titleMedium)
            when (state) {
                SessionUiState.Idle -> Text("在游戏详情选择一个商店 Variant 后开始。")
                is SessionUiState.Creating -> Text("${state.gameTitle} · ${state.appStore}")
                is SessionUiState.Queued -> SessionInfoRows(state.session)
                is SessionUiState.Preparing -> SessionInfoRows(state.session)
                is SessionUiState.Ready -> {
                    Text("Session 已确认可进入 Claim；媒体仍未连接。", color = MaterialTheme.colorScheme.primary)
                    SessionInfoRows(state.session)
                }
                is SessionUiState.Claiming -> {
                    Text("${state.gameTitle} · ${state.appStore}")
                    Text("Session ID：${state.sessionId}")
                }
                is SessionUiState.Claimed -> {
                    Text("RESUME PUT / Claim 已完成；可启动 v5 WebRTC。", color = MaterialTheme.colorScheme.primary)
                    SessionInfoRows(state.session)
                }
                is SessionUiState.Ending -> Text("正在发送 DELETE / 清理本地 resume 记录…")
                SessionUiState.Ended -> Text("服务端 Session 已结束。")
                SessionUiState.Cancelled -> Text("已取消并执行 best-effort cleanup。")
                is SessionUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    state.session?.let { SessionInfoRows(it) }
                }
            }
        }
    }
}

@Composable
private fun SessionInfoRows(session: SessionInfo) {
    Text("Session ID：${session.sessionId}")
    Text("status=${session.status} · queue=${session.queuePosition ?: "-"} · seatStep=${session.seatSetupStep ?: "-"}")
    session.seatSetupEtaMs?.let { Text("服务器 setup ETA：${it / 1000.0}s") }
    Text("GPU：${session.gpuType ?: "未报告"}")
    Text("Server：${session.serverIp ?: session.sessionControlIp ?: "等待分配"}")
    Text("ConnectionInfo：${session.connectionInfo.size} 条 · Server ICE：${session.iceServers.size} 条")
    session.connectionInfo.forEachIndexed { index, info ->
        Text(
            "Conn#${index + 1} usage=${info.usage} · ${info.ip ?: "host?"}:${info.port ?: "?"} · ${info.resourcePath ?: "path?"}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    session.signalingUrl?.let { Text("Signaling：$it") }
    if (session.profile.bitDepth != null || session.profile.colorMode.name != "Unknown") {
        Text("Server profile：${session.profile.bitDepth ?: "?"}-bit · ${session.profile.colorMode}")
    }
    session.adRequirement?.let {
        Text("Queue Ad：required=${it.required} paused=${it.queuePaused}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DiagnosticsScreen(
    snapshot: DiagnosticsSnapshot,
    contentState: ContentUiState,
    sessionState: SessionUiState,
    resumeRecord: PersistedSessionRecord?,
    streamState: StreamState,
    streamDiagnostics: StreamDiagnostics,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("诊断", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("v5.1.1 分开观察 Session、WSS、SDP、ICE、音频、控制通道、第一帧与键鼠输入状态。")
        }
        item {
            DiagnosticSection(
                "客户端",
                listOf(
                    "身份" to "${snapshot.identity.deviceOs} ${snapshot.identity.deviceType}",
                    "平台" to snapshot.identity.clientPlatformName,
                    "客户端" to snapshot.identity.clientIdentification,
                ),
            )
        }
        item {
            val rows = when (contentState) {
                is ContentUiState.Ready -> listOf(
                    "VPC" to contentState.vpcId,
                    "会员" to contentState.subscription.membershipTier,
                    "Library" to contentState.library.size.toString(),
                    "Catalog" to contentState.catalog.size.toString(),
                )
                ContentUiState.Loading -> listOf("状态" to "正在加载")
                ContentUiState.WaitingForLogin -> listOf("状态" to "等待登录")
                is ContentUiState.Error -> listOf("状态" to "错误", "原因" to contentState.message)
            }
            DiagnosticSection("GFN 内容服务", rows)
        }
        item {
            val info = sessionFromState(sessionState)
            DiagnosticSection(
                "CloudMatch Session",
                buildList {
                    add("状态" to sessionStateLabel(sessionState))
                    add("Resume record" to if (resumeRecord == null) "无" else "有")
                    if (info != null) {
                        add("Session ID" to info.sessionId)
                        add("Queue" to (info.queuePosition?.toString() ?: "-"))
                        add("GPU" to (info.gpuType ?: "未报告"))
                        add("Server" to (info.serverIp ?: "未报告"))
                        add("ConnectionInfo" to info.connectionInfo.size.toString())
                        add("Server ICE" to info.iceServers.size.toString())
                        info.connectionInfo.forEachIndexed { index, c ->
                            add("Conn#${index + 1}" to "usage=${c.usage} ${c.ip ?: "?"}:${c.port ?: "?"} ${c.resourcePath ?: ""}")
                        }
                    }
                },
            )
        }
        item {
            DiagnosticSection(
                "Signaling",
                listOf(
                    "媒体状态" to streamStateLabel(streamState),
                    "WSS connected" to streamDiagnostics.signaling.websocketConnected.asYesNo(),
                    "Endpoint" to (streamDiagnostics.signaling.endpointHost ?: "待连接"),
                    "RX" to streamDiagnostics.signaling.rxCount.toString(),
                    "Last RX" to (streamDiagnostics.signaling.lastRxType ?: "-"),
                    "TX" to streamDiagnostics.signaling.txCount.toString(),
                    "Last TX" to (streamDiagnostics.signaling.lastTxType ?: "-"),
                    "Close" to listOfNotNull(
                        streamDiagnostics.signaling.closeCode?.toString(),
                        streamDiagnostics.signaling.closeReason,
                    ).joinToString(" ").ifBlank { "-" },
                ),
            )
        }
        item {
            DiagnosticSection(
                "SDP",
                listOf(
                    "Offer" to streamDiagnostics.offer.offerPresent.asYesNo(),
                    "Offer codecs" to streamDiagnostics.offer.videoCodecs.joinToString().ifBlank { "-" },
                    "Offer H264 PT" to streamDiagnostics.offer.h264PayloadTypes.joinToString().ifBlank { "-" },
                    "Offer HEVC PT" to streamDiagnostics.offer.hevcPayloadTypes.joinToString().ifBlank { "-" },
                    "Offer HEVC Main PT" to streamDiagnostics.offer.hevcMainPayloadTypes.joinToString().ifBlank { "-" },
                    "Raw Answer" to streamDiagnostics.rawAnswer.answerPresent.asYesNo(),
                    "Raw Answer codecs" to streamDiagnostics.rawAnswer.videoCodecs.joinToString().ifBlank { "-" },
                    "Raw Answer H264 PT" to streamDiagnostics.rawAnswer.h264PayloadTypes.joinToString().ifBlank { "-" },
                    "Raw Answer HEVC PT" to streamDiagnostics.rawAnswer.hevcPayloadTypes.joinToString().ifBlank { "-" },
                    "Raw Answer HEVC Main PT" to streamDiagnostics.rawAnswer.hevcMainPayloadTypes.joinToString().ifBlank { "-" },
                    "Final Answer" to streamDiagnostics.answer.answerPresent.asYesNo(),
                    "Final Answer codecs" to streamDiagnostics.answer.videoCodecs.joinToString().ifBlank { "-" },
                    "Final Answer H264 PT" to streamDiagnostics.answer.h264PayloadTypes.joinToString().ifBlank { "-" },
                    "Final Answer HEVC PT" to streamDiagnostics.answer.hevcPayloadTypes.joinToString().ifBlank { "-" },
                    "Final Answer HEVC Main PT" to streamDiagnostics.answer.hevcMainPayloadTypes.joinToString().ifBlank { "-" },
                    "ICE ufrag/pwd" to "${streamDiagnostics.answer.iceUfragPresent.asYesNo()}/${streamDiagnostics.answer.icePasswordPresent.asYesNo()}",
                    "DTLS fingerprint" to streamDiagnostics.answer.dtlsFingerprintPresent.asYesNo(),
                ),
            )
        }
        item {
            DiagnosticSection(
                "ICE / PeerConnection",
                listOf(
                    "Server ICE entries" to streamDiagnostics.ice.serverIceEntries.toString(),
                    "Effective ICE servers" to streamDiagnostics.ice.effectiveIceServers.toString(),
                    "Fallback active" to streamDiagnostics.ice.fallbackActive.asYesNo(),
                    "Local candidates" to streamDiagnostics.ice.localCandidateCount.toString(),
                    "Remote signaling candidates" to streamDiagnostics.ice.remoteCandidateCount.toString(),
                    "Injected host candidates" to streamDiagnostics.ice.injectedRemoteCandidateCount.toString(),
                    "Signaling state" to streamDiagnostics.ice.signalingState,
                    "ICE gathering" to streamDiagnostics.ice.iceGatheringState,
                    "ICE connection" to streamDiagnostics.ice.iceConnectionState,
                    "Peer connection" to streamDiagnostics.ice.peerConnectionState,
                ),
            )
        }
        item {
            DiagnosticSection(
                "Audio",
                buildList {
                    add("Remote audio track" to streamDiagnostics.audio.remoteAudioTrackPresent.asYesNo())
                    add("Audio track enabled" to streamDiagnostics.audio.remoteAudioTrackEnabled.asYesNo())
                    add("First audio RTP" to streamDiagnostics.audio.firstRtpPacketReceived.asYesNo())
                    add("Requested channels" to "${streamDiagnostics.audio.requestedChannels}ch")
                    add("ADM output" to "${streamDiagnostics.audio.admConfiguredOutputChannels}ch")
                    add("ADM stereo enabled" to streamDiagnostics.audio.admStereoOutputEnabled.asYesNo())
                    add("Likely route max" to (streamDiagnostics.audio.likelyRouteMaxChannels?.let { "${it}ch" } ?: "unknown"))
                    add("Likely route" to streamDiagnostics.audio.likelyRouteSummary)
                    add("Offer audio" to listOfNotNull(
                        streamDiagnostics.audio.offerCodec,
                        streamDiagnostics.audio.offerChannels?.let { "${it}ch" },
                    ).joinToString("/").ifBlank { "-" })
                    add("Answer audio" to listOfNotNull(
                        streamDiagnostics.audio.answerCodec,
                        streamDiagnostics.audio.answerChannels?.let { "${it}ch" },
                    ).joinToString("/").ifBlank { "-" })
                    add("Opus stereo=1" to streamDiagnostics.audio.opusStereoEnabled.asYesNo())
                    add("6ch offer" to streamDiagnostics.audio.surroundOfferPresent.asYesNo())
                    add("6ch negotiated" to streamDiagnostics.audio.surroundNegotiationAccepted.asYesNo())
                    add("Native 5.1 output" to streamDiagnostics.audio.nativeSurroundOutput.asYesNo())
                    add("Output mode" to streamDiagnostics.audio.outputMode)
                    streamDiagnostics.audio.limitation?.let { add("Limitation" to it) }
                    add("Android audio usage" to streamDiagnostics.audio.androidUsage)
                    add("Android content type" to streamDiagnostics.audio.androidContentType)
                    add("Volume stream" to streamDiagnostics.audio.volumeStream)
                },
            )
        }
        item {
            DiagnosticSection(
                "Control Channel",
                listOf(
                    "control_channel" to streamDiagnostics.control.controlChannelPresent.asYesNo(),
                    "State" to streamDiagnostics.control.controlChannelState,
                    "exitMessage" to streamDiagnostics.control.exitMessageSeen.asYesNo(),
                ),
            )
        }
        item {
            DiagnosticSection(
                "键鼠输入 v5.1.9 Stable",
                listOf(
                    "Reliable channel" to if (streamDiagnostics.input.dataChannelOpen) "OPEN" else "-",
                    "Protocol ready" to streamDiagnostics.input.protocolReady.asYesNo(),
                    "Protocol version" to (streamDiagnostics.input.protocolVersion?.toString() ?: "-"),
                    "Window focus" to streamDiagnostics.input.windowFocused.asYesNo(),
                    "Pointer capture" to streamDiagnostics.input.pointerCaptured.asYesNo(),
                    "Keyboard active" to streamDiagnostics.input.keyboardActive.asYesNo(),
                    "Mouse active" to streamDiagnostics.input.mouseActive.asYesNo(),
                    "Last raw keyCode" to (streamDiagnostics.input.lastRawKeyCode?.toString() ?: "-"),
                    "Last raw metaState" to (streamDiagnostics.input.lastRawMetaState?.let { "0x${it.toString(16)}" } ?: "-"),
                    "Android modifier mask" to (streamDiagnostics.input.lastAndroidReportedModifierMask?.let { "0x${it.toString(16)}" } ?: "-"),
                    "Tracked modifier mask" to (streamDiagnostics.input.lastTrackedModifierMask?.let { "0x${it.toString(16)}" } ?: "-"),
                    "Modifier mismatches" to streamDiagnostics.input.modifierMismatchCount.toString(),
                    "Last Set-1 scan" to (streamDiagnostics.input.lastScanCode?.let { "0x${it.toString(16)}" } ?: "-"),
                    "Epoch" to streamDiagnostics.input.inputEpoch.toString(),
                    "Remote state" to streamDiagnostics.input.remoteState,
                    "Held keys local/remote" to "${streamDiagnostics.input.physicalHeldKeys}/${streamDiagnostics.input.remoteHeldKeys}",
                    "Held mouse local/remote" to "${streamDiagnostics.input.physicalHeldMouseButtons}/${streamDiagnostics.input.remoteHeldMouseButtons}",
                    "Packets accepted/submitted" to "${streamDiagnostics.input.acceptedPackets}/${streamDiagnostics.input.submittedPackets}",
                    "Dropped / stale" to "${streamDiagnostics.input.droppedPackets}/${streamDiagnostics.input.staleEventsDropped}",
                    "releaseAll" to "${streamDiagnostics.input.releaseCount} · ${streamDiagnostics.input.lastReleaseReason ?: "-"}",
                    "Last event" to (streamDiagnostics.input.lastEvent ?: "-"),
                ),
            )
        }
        item {
            DiagnosticSection(
                "视频",
                listOf(
                    "Requested codec" to streamDiagnostics.video.requestedCodec,
                    "Negotiated codec" to (streamDiagnostics.video.negotiatedCodec ?: "-"),
                    "Local decoder codecs" to streamDiagnostics.video.localDecoderCodecs.joinToString().ifBlank { "-" },
                    "Local receiver codecs" to streamDiagnostics.video.localReceiverCodecs.joinToString().ifBlank { "-" },
                    "Pre-answer preference" to when {
                        streamDiagnostics.video.preAnswerCodecPreferenceApplied -> "APPLIED"
                        streamDiagnostics.video.preAnswerCodecPreferenceAttempted -> "ATTEMPTED / NOT APPLIED"
                        else -> "NOT REQUIRED / NOT ATTEMPTED"
                    },
                    "Preference error" to (streamDiagnostics.video.preAnswerCodecPreferenceError ?: "-"),
                    "Logcat tag" to "GfnHevcCompat",
                    "Codec fallback" to streamDiagnostics.video.codecFallbackUsed.asYesNo(),
                    "Fallback reason" to (streamDiagnostics.video.codecFallbackReason ?: "-"),
                    "Color" to "SDR8（Main10/HDR 未启用）",
                    "Remote video track" to streamDiagnostics.video.remoteVideoTrackPresent.asYesNo(),
                    "First RTP packet" to streamDiagnostics.video.firstRtpPacketReceived.asYesNo(),
                    "First surface frame" to streamDiagnostics.video.firstFrameRendered.asYesNo(),
                    "Resolution" to if (streamDiagnostics.video.firstFrameWidth != null) {
                        "${streamDiagnostics.video.firstFrameWidth}x${streamDiagnostics.video.firstFrameHeight ?: "?"}"
                    } else "-",
                    "Decoder path" to streamDiagnostics.video.decoderPath,
                ),
            )
        }
        item {
            DiagnosticSection(
                "后续能力（v6.0.1 未启用）",
                listOf(
                    "显示器 HDR10" to snapshot.localVideo.displayHdr10.asYesNo(),
                    "HEVC Main10" to snapshot.localVideo.hevcMain10.asYesNo(),
                    "HDR10 解码器" to snapshot.localVideo.hdr10Decoder.asYesNo(),
                ),
            )
        }
    }
}

@Composable
private fun DiagnosticSection(title: String, rows: List<Pair<String, String>>) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    darkTheme: Boolean,
    authState: AuthUiState,
    contentState: ContentUiState,
    sessionState: SessionUiState,
    hasFrozenLaunchProfile: Boolean,
    streamSettings: PersistentStreamSettings,
    onKeyboardLayoutSelected: (String) -> Unit,
    onResolutionSelected: (String) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onMaxBitrateSelected: (Int) -> Unit,
    onVideoCodecSelected: (dev.gfn.stream.VideoCodecPreference) -> Unit,
    onAudioChannelsSelected: (Int) -> Unit,
    onToggleTheme: () -> Unit,
) {
    var keyboardLayoutMenuOpen by remember { mutableStateOf(false) }
    var resolutionMenuOpen by remember { mutableStateOf(false) }
    var fpsMenuOpen by remember { mutableStateOf(false) }
    var codecMenuOpen by remember { mutableStateOf(false) }
    var audioMenuOpen by remember { mutableStateOf(false) }

    val settings = GfnStreamSettingsCatalog.normalize(streamSettings)
    val normalizedKeyboardLayout = GfnKeyboardLayoutCatalog.normalize(settings.keyboardLayoutSelection)
    val keyboardLayoutChoice = GfnKeyboardLayoutCatalog.choice(normalizedKeyboardLayout)
    val autoDetectedLayout = GfnLocale.keyboardLayoutCode()
    val effectiveKeyboardLayout = if (normalizedKeyboardLayout == GfnKeyboardLayoutCatalog.AUTO) {
        autoDetectedLayout
    } else {
        normalizedKeyboardLayout
    }
    val resolutionChoice = GfnStreamSettingsCatalog.resolutionChoices
        .first { it.code == settings.resolutionSelection }
    val fpsChoice = GfnStreamSettingsCatalog.fpsChoices.first { it.fps == settings.fpsSelection }
    val codecChoice = GfnStreamSettingsCatalog.codecChoices.first { it.codec == settings.videoCodec }
    val audioChoice = GfnStreamSettingsCatalog.audioChoices.first { it.channels == settings.audioChannels }
    val activeOrResumable = hasFrozenLaunchProfile || (
        sessionState !is SessionUiState.Idle &&
            sessionState !is SessionUiState.Ended && sessionState !is SessionUiState.Cancelled
        )
    val entitlementSummary = (contentState as? ContentUiState.Ready)?.subscription?.entitledResolutions
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString { "${it.width}x${it.height}@${it.fps}" }
        ?: "未返回可验证列表"

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black) }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("外观", style = MaterialTheme.typography.titleMedium)
                    Text(if (darkTheme) "动态深色" else "动态浅色")
                    Button(onClick = onToggleTheme) { Text("切换主题") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("串流设置 · v5.2+ Snapshot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "这些值不会被 WebRTC 运行时直接重读。新建 Session 时先和账号 entitlement、当前 engine capability 解析成不可变 ResolvedLaunchProfile，随后 CREATE / CLAIM / WebRTC 共用同一份 snapshot。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text("串流键盘布局", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { keyboardLayoutMenuOpen = true }) {
                        Text("${keyboardLayoutChoice.label} · $effectiveKeyboardLayout")
                    }
                    DropdownMenu(
                        expanded = keyboardLayoutMenuOpen,
                        onDismissRequest = { keyboardLayoutMenuOpen = false },
                    ) {
                        GfnKeyboardLayoutCatalog.choices.forEach { choice ->
                            DropdownMenuItem(
                                text = {
                                    val suffix = if (choice.automatic) "（当前 $autoDetectedLayout）" else choice.code
                                    Text("${choice.label} · $suffix")
                                },
                                onClick = {
                                    keyboardLayoutMenuOpen = false
                                    onKeyboardLayoutSelected(choice.code)
                                },
                            )
                        }
                    }

                    Text("最大码率", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            enabled = settings.maxBitrateKbps > GfnStreamSettingsCatalog.bitrateRangeKbps.first,
                            onClick = {
                                onMaxBitrateSelected(
                                    settings.maxBitrateKbps - GfnStreamSettingsCatalog.BITRATE_STEP_KBPS,
                                )
                            },
                        ) { Text("- 5 Mbps") }
                        Text(
                            "${settings.maxBitrateKbps / 1_000} Mbps",
                            modifier = Modifier.padding(top = 12.dp),
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(
                            enabled = settings.maxBitrateKbps < GfnStreamSettingsCatalog.bitrateRangeKbps.last,
                            onClick = {
                                onMaxBitrateSelected(
                                    settings.maxBitrateKbps + GfnStreamSettingsCatalog.BITRATE_STEP_KBPS,
                                )
                            },
                        ) { Text("+ 5 Mbps") }
                    }
                    Text(
                        "该值进入 SDP bandwidth 与 NVST bitrate 字段；20 Mbps 是当前稳定默认值。5-100 Mbps 为客户端参数范围，非默认值仍需真机 A/B，不能视为已验证服务端上限。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text("视频编码", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { codecMenuOpen = true }) { Text(codecChoice.label) }
                    DropdownMenu(
                        expanded = codecMenuOpen,
                        onDismissRequest = { codecMenuOpen = false },
                    ) {
                        GfnStreamSettingsCatalog.codecChoices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    codecMenuOpen = false
                                    onVideoCodecSelected(choice.codec)
                                },
                            )
                        }
                    }
                    if (settings.videoCodec == dev.gfn.stream.VideoCodecPreference.Hevc) {
                        Text(
                            "HEVC 仅启用 Main/profile-id=1 + SDR8；若本机 decoder、GFN Offer 或 libwebrtc Answer 不接受，会在同一 Session 明确回退 H.264。",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Text("分辨率", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { resolutionMenuOpen = true }) { Text(resolutionChoice.label) }
                    DropdownMenu(
                        expanded = resolutionMenuOpen,
                        onDismissRequest = { resolutionMenuOpen = false },
                    ) {
                        GfnStreamSettingsCatalog.resolutionChoices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    resolutionMenuOpen = false
                                    onResolutionSelected(choice.code)
                                },
                            )
                        }
                    }

                    Text("帧率", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { fpsMenuOpen = true }) { Text(fpsChoice.label) }
                    DropdownMenu(
                        expanded = fpsMenuOpen,
                        onDismissRequest = { fpsMenuOpen = false },
                    ) {
                        GfnStreamSettingsCatalog.fpsChoices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    fpsMenuOpen = false
                                    onFpsSelected(choice.fps)
                                },
                            )
                        }
                    }

                    Text("音频模式", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { audioMenuOpen = true }) { Text(audioChoice.label) }
                    DropdownMenu(
                        expanded = audioMenuOpen,
                        onDismissRequest = { audioMenuOpen = false },
                    ) {
                        GfnStreamSettingsCatalog.audioChoices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    audioMenuOpen = false
                                    onAudioChannelsSelected(choice.channels)
                                },
                            )
                        }
                    }

                    Text("当前 engine capability：1920×1080 · 60 FPS · H.264 / HEVC Main SDR8 · ADM 2ch + experimental multiopus 6ch")
                    Text(
                        "5.1/6ch 为实验性 multiopus negotiation/receive probe；当前真机已确认 6ch 模式可以正常播放音频，但 upstream Android Java ADM 仍配置 2ch，因此该结果不能证明离散原生 5.1。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (settings.audioChannels >= 6) {
                        Text(
                            "已选择实验 6ch：下一新 Session 若 Offer 不含 multiopus/6 会明确停止连接；当前真机已验证协商后音频可正常播放，但离散 5.1 声道分离仍未验证。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("账号 entitlement：$entitlementSummary", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (activeOrResumable) {
                        Text(
                            "当前活动/可恢复 Session 已冻结其 ResolvedLaunchProfile；这里的修改只影响下一次新 Session。",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "下一 Session intent：keyboard=$effectiveKeyboardLayout · resolution=${resolutionChoice.code} · fps=${fpsChoice.fps} · max=${settings.maxBitrateKbps / 1_000}Mbps · codec=${settings.videoCodec} · audio=${settings.audioChannels}ch",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("第六版状态", style = MaterialTheme.typography.titleMedium)
                    Text(if (authState is AuthUiState.SignedIn) "Auth：真机已验证" else "Auth：等待登录")
                    Text(
                        when (contentState) {
                            is ContentUiState.Ready -> "Content：会员 / Library / Catalog / Search / Detail 已加载"
                            ContentUiState.Loading -> "Content：加载中"
                            is ContentUiState.Error -> "Content：错误"
                            ContentUiState.WaitingForLogin -> "Content：等待登录"
                        },
                    )
                    Text("Session：${sessionStateLabel(sessionState)}")
                    Text("Keyboard：v5.1.9 soft-freeze · en-US Cyberpunk / CS2 真机通过")
                    Text("v5.2.1：同 Session RESUME/Claim → 新 Signaling / PeerConnection / DataChannel")
                    Text("v5.3 Gamepad：已实现/离线验证；因当前无可用手柄，真机验证按决定跳过。")
                    Text("v5.4 Audio：2ch Stereo；6ch multiopus 模式真机可正常播放；离散 Native 5.1 仍未验证。")
                    Text("v6.0.1 HEVC Main negotiation compatibility：pre-answer preference + Logcat evidence；仍未开放 Main10 / HDR / 120 FPS。")
                }
            }
        }
    }
}

private fun sessionStateLabel(state: SessionUiState): String = when (state) {
    SessionUiState.Idle -> "Idle"
    is SessionUiState.Creating -> "Creating"
    is SessionUiState.Queued -> "Queued(${state.session.queuePosition ?: "?"})"
    is SessionUiState.Preparing -> "Preparing(step=${state.session.seatSetupStep ?: "?"})"
    is SessionUiState.Ready -> "Ready"
    is SessionUiState.Claiming -> "Claiming"
    is SessionUiState.Claimed -> "Claimed"
    is SessionUiState.Ending -> "Ending"
    SessionUiState.Ended -> "Ended"
    SessionUiState.Cancelled -> "Cancelled"
    is SessionUiState.Error -> "Failed"
}

private fun sessionFromState(state: SessionUiState): SessionInfo? = when (state) {
    is SessionUiState.Queued -> state.session
    is SessionUiState.Preparing -> state.session
    is SessionUiState.Ready -> state.session
    is SessionUiState.Claimed -> state.session
    is SessionUiState.Error -> state.session
    else -> null
}

private fun streamStateLabel(state: StreamState): String = when (state) {
    StreamState.Idle -> "Idle"
    StreamState.OpeningSignaling -> "OpeningSignaling"
    StreamState.AwaitingOffer -> "AwaitingOffer"
    StreamState.NegotiatingSdp -> "NegotiatingSDP"
    StreamState.IceChecking -> "ICE Checking"
    StreamState.Connected -> "Connected"
    StreamState.FirstFrame -> "FIRST FRAME"
    is StreamState.Reconnecting -> "Reconnecting ${state.attempt}/3 (${state.source})"
    StreamState.SessionEnded -> "Session Ended"
    is StreamState.Failed -> "Failed"
    StreamState.Closed -> "Closed"
}

private fun Boolean.asYesNo(): String = if (this) "是" else "否"
