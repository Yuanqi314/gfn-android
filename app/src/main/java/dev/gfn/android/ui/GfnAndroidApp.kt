package dev.gfn.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gfn.core.model.GameSummary
import dev.gfn.diagnostics.DiagnosticsSnapshot
import dev.gfn.identity.GfnClientIdentity

private enum class AppTab(val title: String, val glyph: String) {
    Home("Home", "H"),
    Library("Library", "L"),
    Diagnostics("Diagnostics", "D"),
    Settings("Settings", "S"),
}

@Composable
fun GfnAndroidApp() {
    var darkTheme by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(AppTab.Home) }

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
                    AppTab.Home -> HomeScreen()
                    AppTab.Library -> LibraryScreen()
                    AppTab.Diagnostics -> DiagnosticsScreen(DiagnosticsSnapshot())
                    AppTab.Settings -> SettingsScreen(
                        darkTheme = darkTheme,
                        onToggleTheme = { darkTheme = !darkTheme },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
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
                text = "Independent Android client · protocol-first bring-up",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(shape = RoundedCornerShape(28.dp)) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Phase 0 / 1", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Protocol boundaries are ready. Real OAuth and CloudMatch are intentionally not wired yet.",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Button(onClick = { }) {
                        Text("Run fixture in protocol-cli")
                    }
                }
            }
        }
        item {
            DiagnosticPreviewCard()
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
            Text("Protocol identity", style = MaterialTheme.typography.titleMedium)
            Text("${identity.deviceOs} · ${identity.deviceType}")
            Text("${identity.clientIdentification} · ${identity.clientPlatformName}")
            Text(
                "Android runtime remains real; only the GFN protocol identity is modeled as Windows Desktop.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryScreen() {
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
            Text("Library", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("Mock data until the catalog module is connected.")
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
                    Text("Open")
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
            Text("Diagnostics", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("Designed as a product feature, not a temporary debug overlay.")
        }
        item {
            DiagnosticSection(
                title = "CLIENT",
                rows = listOf(
                    "Identity" to "${snapshot.identity.deviceOs} ${snapshot.identity.deviceType}",
                    "Platform" to snapshot.identity.clientPlatformName,
                    "Client" to snapshot.identity.clientIdentification,
                ),
            )
        }
        item {
            DiagnosticSection(
                title = "LOCAL",
                rows = listOf(
                    "Display HDR10" to snapshot.localVideo.displayHdr10.asYesNo(),
                    "HEVC Main10" to snapshot.localVideo.hevcMain10.asYesNo(),
                    "HDR10 decoder" to snapshot.localVideo.hdr10Decoder.asYesNo(),
                ),
            )
        }
        item {
            DiagnosticSection(
                title = "SERVER / DECODER",
                rows = listOf(
                    "Negotiated" to snapshot.negotiatedColorMode.name,
                    "Codec" to (snapshot.decoder.codec ?: "Not connected"),
                    "Profile" to (snapshot.decoder.profile ?: "Unknown"),
                    "Bit depth" to (snapshot.decoder.bitDepth?.toString() ?: "Unknown"),
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
private fun SettingsScreen(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Text(if (darkTheme) "Dynamic dark" else "Dynamic light")
                    Button(onClick = onToggleTheme) {
                        Text("Toggle theme")
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
                    Text("Streaming bring-up", style = MaterialTheme.typography.titleMedium)
                    Text("Milestone 1: H.264 SDR 1080p60")
                    Text("Milestone 2: HEVC Main SDR8")
                    Text("Milestone 3: HEVC Main10 SDR10")
                    Text("Milestone 4: HDR10 · BT.2020 · ST2084")
                }
            }
        }
    }
}

private fun Boolean.asYesNo(): String = if (this) "Yes" else "No"
