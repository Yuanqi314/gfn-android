package dev.gfn.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gfn.account.GfnAccountClient
import dev.gfn.android.auth.AndroidKeystoreTokenStore
import dev.gfn.android.auth.AuthController
import dev.gfn.android.auth.AuthUiState
import dev.gfn.android.content.ContentUiState
import dev.gfn.android.content.GameDetailUiState
import dev.gfn.android.content.GfnContentController
import dev.gfn.auth.AuthSessionService
import dev.gfn.auth.NvidiaAuthApi
import dev.gfn.auth.NvidiaAuthReferenceDefaults
import dev.gfn.core.model.GameDetail
import dev.gfn.core.model.GameSummary
import dev.gfn.diagnostics.DiagnosticsSnapshot
import dev.gfn.games.GfnGamesClient
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.network.UrlConnectionHttpTransport
import kotlinx.coroutines.delay

private enum class AppTab(val title: String, val glyph: String) {
    Home("首页", "H"),
    Library("游戏库", "L"),
    Store("全部游戏", "G"),
    Diagnostics("诊断", "D"),
    Settings("设置", "S"),
}

@Composable
fun GfnAndroidApp() {
    var darkTheme by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(AppTab.Home) }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val transport = remember { UrlConnectionHttpTransport() }
    val authController = remember(context, scope, transport) {
        val api = NvidiaAuthApi(
            transport = transport,
            config = NvidiaAuthReferenceDefaults.config.copy(displayName = "Android"),
            sleepSeconds = { seconds -> delay(seconds * 1_000L) },
        )
        AuthController(
            service = AuthSessionService(api, AndroidKeystoreTokenStore(context)),
            scope = scope,
        )
    }
    val contentController = remember(authController, scope, transport) {
        GfnContentController(
            authController = authController,
            accountClient = GfnAccountClient(transport),
            gamesClient = GfnGamesClient(transport),
            scope = scope,
        )
    }

    val authState by authController.state.collectAsState()
    val contentState by contentController.state.collectAsState()
    val searchResults by contentController.searchResults.collectAsState()
    val detailState by contentController.detailState.collectAsState()

    LaunchedEffect(authController) { authController.restoreOnce() }
    LaunchedEffect(authState) { contentController.onAuthStateChanged(authState) }

    GfnTheme(darkTheme = darkTheme) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.glyph, fontWeight = FontWeight.Bold) },
                            label = { Text(item.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                when (tab) {
                    AppTab.Home -> HomeScreen(
                        authState = authState,
                        contentState = contentState,
                        onLogin = authController::startLogin,
                        onCancelLogin = authController::cancelLogin,
                        onLogout = authController::signOut,
                        onRefreshContent = contentController::refresh,
                    )
                    AppTab.Library -> GameListScreen(
                        title = "游戏库",
                        subtitle = "来自当前 GFN 账号的真实 Library",
                        authState = authState,
                        contentState = contentState,
                        games = (contentState as? ContentUiState.Ready)?.library.orEmpty(),
                        detailState = detailState,
                        onOpenGame = contentController::openGame,
                        onCloseDetail = contentController::closeGameDetail,
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
                        onRetry = contentController::refresh,
                    )
                    AppTab.Diagnostics -> DiagnosticsScreen(
                        snapshot = DiagnosticsSnapshot(),
                        contentState = contentState,
                    )
                    AppTab.Settings -> SettingsScreen(
                        darkTheme = darkTheme,
                        authState = authState,
                        contentState = contentState,
                        onToggleTheme = { darkTheme = !darkTheme },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    authState: AuthUiState,
    contentState: ContentUiState,
    onLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
    onRefreshContent: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("GFN Android Lab", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text(
                "独立 Android GFN 客户端 · 第三版",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            AuthCard(
                state = authState,
                onLogin = onLogin,
                onCancel = onCancelLogin,
                onLogout = onLogout,
            )
        }
        item { AccountContentCard(contentState, onRefreshContent) }
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
private fun DiagnosticPreviewCard() {
    val identity = GfnClientIdentity.WindowsDesktop
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GFN 协议身份", style = MaterialTheme.typography.titleMedium)
            Text("${identity.deviceOs} · ${identity.deviceType}")
            Text("${identity.clientIdentification} · ${identity.clientPlatformName}")
            Text(
                "Android 本机运行环境保持真实；GFN 内容/会话协议使用 Windows Desktop 身份。",
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
        item { DetailPanel(detailState, onCloseDetail) }
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
        item { DetailPanel(detailState, onCloseDetail) }
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
            Button(onClick = { onOpenGame(game.appId) }) { Text("查看详情") }
        }
    }
}

@Composable
private fun DetailPanel(state: GameDetailUiState, onClose: () -> Unit) {
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
        is GameDetailUiState.Ready -> GameDetailCard(state.detail, onClose)
    }
}

@Composable
private fun GameDetailCard(detail: GameDetail, onClose: () -> Unit) {
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
            Text("可用商店：${detail.variants.map { it.appStore }.distinct().joinToString(" / ").ifBlank { "未知" }}")
            Text("启动串流：第 4 版开始接 CloudMatch / Queue", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClose) { Text("关闭详情") }
        }
    }
}

@Composable
private fun DiagnosticsScreen(snapshot: DiagnosticsSnapshot, contentState: ContentUiState) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("诊断", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("第 3 版开始同时显示协议身份、内容服务和未来串流状态。")
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
            DiagnosticSection(
                "本地能力",
                listOf(
                    "显示器 HDR10" to snapshot.localVideo.displayHdr10.asYesNo(),
                    "HEVC Main10" to snapshot.localVideo.hevcMain10.asYesNo(),
                    "HDR10 解码器" to snapshot.localVideo.hdr10Decoder.asYesNo(),
                ),
            )
        }
        item {
            DiagnosticSection(
                "串流（尚未连接）",
                listOf(
                    "协商色彩模式" to snapshot.negotiatedColorMode.name,
                    "Codec" to (snapshot.decoder.codec ?: "未连接"),
                    "Profile" to (snapshot.decoder.profile ?: "未知"),
                    "Bit depth" to (snapshot.decoder.bitDepth?.toString() ?: "未知"),
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
    onToggleTheme: () -> Unit,
) {
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
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("第三版状态", style = MaterialTheme.typography.titleMedium)
                    Text(if (authState is AuthUiState.SignedIn) "真实 Device Flow：已连接" else "真实 Device Flow：等待登录")
                    Text(
                        when (contentState) {
                            is ContentUiState.Ready -> "Account / Subscription / Catalog / Library：已加载"
                            ContentUiState.Loading -> "Account / Subscription / Catalog / Library：加载中"
                            is ContentUiState.Error -> "Account / Subscription / Catalog / Library：错误"
                            ContentUiState.WaitingForLogin -> "Account / Subscription / Catalog / Library：等待登录"
                        },
                    )
                    Text("下一阶段：Regions / CloudMatch Create / Queue / Ready")
                    Text("串流里程碑 1：H.264 SDR 1080p60")
                    Text("串流里程碑 2：HEVC Main SDR8")
                    Text("串流里程碑 3：HEVC Main10 SDR10")
                    Text("串流里程碑 4：HDR10 · BT.2020 · ST2084")
                }
            }
        }
    }
}

private fun Boolean.asYesNo(): String = if (this) "是" else "否"
