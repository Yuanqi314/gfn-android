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
import dev.gfn.android.auth.AndroidKeystoreTokenStore
import dev.gfn.android.auth.AuthController
import dev.gfn.android.auth.AuthUiState
import dev.gfn.auth.AuthSessionService
import dev.gfn.auth.NvidiaAuthApi
import dev.gfn.core.model.GameSummary
import dev.gfn.diagnostics.DiagnosticsSnapshot
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.network.UrlConnectionHttpTransport
import kotlinx.coroutines.delay

private enum class AppTab(val title: String, val glyph: String) {
    Home("首页", "H"),
    Library("游戏库", "L"),
    Diagnostics("诊断", "D"),
    Settings("设置", "S"),
}

@Composable
fun GfnAndroidApp() {
    var darkTheme by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(AppTab.Home) }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val authController = remember(context, scope) {
        val transport = UrlConnectionHttpTransport()
        val api = NvidiaAuthApi(transport, sleepSeconds = { seconds -> delay(seconds * 1_000L) })
        val tokenStore = AndroidKeystoreTokenStore(context)
        AuthController(
            service = AuthSessionService(api, tokenStore),
            scope = scope,
        )
    }
    val authState by authController.state.collectAsState()

    LaunchedEffect(authController) {
        authController.restoreOnce()
    }

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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (tab) {
                    AppTab.Home -> HomeScreen(
                        authState = authState,
                        onLogin = authController::startLogin,
                        onCancelLogin = authController::cancelLogin,
                        onLogout = authController::signOut,
                    )
                    AppTab.Library -> LibraryScreen(authState)
                    AppTab.Diagnostics -> DiagnosticsScreen(DiagnosticsSnapshot())
                    AppTab.Settings -> SettingsScreen(
                        darkTheme = darkTheme,
                        authState = authState,
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
    onLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "GFN Android Lab",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "独立 Android GFN 客户端 · 第二版",
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
        item {
            DiagnosticPreviewCard()
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
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("GeForce NOW 登录", style = MaterialTheme.typography.labelLarge)
            when (state) {
                AuthUiState.Restoring -> {
                    Text("正在恢复本地登录状态…", style = MaterialTheme.typography.titleLarge)
                    CircularProgressIndicator()
                }
                AuthUiState.SignedOut -> {
                    Text(
                        "使用 NVIDIA Device Flow 登录。登录完成后 token 会通过 AndroidKeyStore 加密保存。",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = onLogin) {
                        Text("登录 GeForce NOW")
                    }
                }
                AuthUiState.RequestingCode -> {
                    Text("正在向 NVIDIA 获取登录码…", style = MaterialTheme.typography.titleLarge)
                    CircularProgressIndicator()
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
                is AuthUiState.AwaitingAuthorization -> {
                    val auth = state.authorization
                    Text("登录码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        auth.userCode,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                    )
                    Text("请在 NVIDIA 页面完成授权；本客户端会按服务器要求自动轮询。")
                    Text(auth.verificationUri, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            runCatching { uriHandler.openUri(auth.verificationUriComplete ?: auth.verificationUri) }
                        },
                    ) {
                        Text("打开 NVIDIA 登录页面")
                    }
                    OutlinedButton(onClick = onCancel) { Text("取消登录") }
                }
                is AuthUiState.SignedIn -> {
                    Text("已登录", color = MaterialTheme.colorScheme.primary)
                    Text(state.user.displayName, style = MaterialTheme.typography.headlineSmall)
                    state.user.email?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.user.membershipTier?.let { Text("会员等级：$it") }
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
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("GFN 协议身份", style = MaterialTheme.typography.titleMedium)
            Text("${identity.deviceOs} · ${identity.deviceType}")
            Text("${identity.clientIdentification} · ${identity.clientPlatformName}")
            Text(
                "Android 本机运行环境保持真实，仅向 GFN 协议层建模为 Windows Desktop。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryScreen(authState: AuthUiState) {
    val games = remember {
        listOf(
            GameSummary("fixture-1", "Cyberpunk fixture", supportsHdr = true, supportsRtx = true),
            GameSummary("fixture-2", "Racing fixture", supportsHdr = true),
            GameSummary("fixture-3", "Strategy fixture"),
        )
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("游戏库", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text(
                when (authState) {
                    is AuthUiState.SignedIn -> "登录已接通。真实 Catalog / Library API 将在下一阶段替换当前 fixture。"
                    else -> "请先在首页登录 GeForce NOW；当前列表仍是 fixture。"
                },
            )
        }
        items(games, key = { it.appId }) { game ->
            Card(shape = RoundedCornerShape(24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(game.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                if (game.supportsHdr) append("HDR ")
                                if (game.supportsRtx) append("RTX")
                                if (!game.supportsHdr && !game.supportsRtx) append("SDR")
                            }.trim(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text("真实 API 待接入", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(snapshot: DiagnosticsSnapshot) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("诊断", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("诊断模块作为正式功能保留，不是临时日志页面。")
        }
        item {
            DiagnosticSection(
                title = "客户端",
                rows = listOf(
                    "身份" to "${snapshot.identity.deviceOs} ${snapshot.identity.deviceType}",
                    "平台" to snapshot.identity.clientPlatformName,
                    "客户端" to snapshot.identity.clientIdentification,
                ),
            )
        }
        item {
            DiagnosticSection(
                title = "本地能力",
                rows = listOf(
                    "显示器 HDR10" to snapshot.localVideo.displayHdr10.asYesNo(),
                    "HEVC Main10" to snapshot.localVideo.hevcMain10.asYesNo(),
                    "HDR10 解码器" to snapshot.localVideo.hdr10Decoder.asYesNo(),
                ),
            )
        }
        item {
            DiagnosticSection(
                title = "服务端 / 解码器",
                rows = listOf(
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
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
    onToggleTheme: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("外观", style = MaterialTheme.typography.titleMedium)
                    Text(if (darkTheme) "动态深色" else "动态浅色")
                    Button(onClick = onToggleTheme) {
                        Text("切换主题")
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("第二版状态", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (authState) {
                            is AuthUiState.SignedIn -> "真实 Device Flow 登录：已连接"
                            else -> "真实 Device Flow 登录：等待登录"
                        },
                    )
                    Text("下一阶段：Catalog / Library / CloudMatch")
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
