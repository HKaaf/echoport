package io.github.hkaaf.echoport.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hkaaf.echoport.R
import io.github.hkaaf.echoport.engine.Hub

class MainActivity : AppCompatActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Hub.init(this)
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        askPermissions.launch(wanted.toTypedArray())

        // Reconnect to the last server on a fresh launch.
        val s = Hub.prefs.state.value
        if (savedInstanceState == null && s.autoReconnect && s.lastServerAddrs.isNotEmpty() && Hub.state.value.player == null) {
            Hub.connect(this, s.lastServerAddrs.split(','), s.lastServerPort, null)
        }

        setContent { App() }
    }

    override fun onStart() {
        super.onStart()
        Hub.discovery.startBrowsing()
    }

    override fun onStop() {
        Hub.discovery.stopBrowsing()
        super.onStop()
    }

    @Composable
    private fun App() {
        val settings by Hub.prefs.state.collectAsStateWithLifecycle()
        val hub by Hub.state.collectAsStateWithLifecycle()
        val dark = settings.themeMode == "dark" || (settings.themeMode == "system" && isSystemInDarkTheme())
        val theme = themeById(if (dark) settings.darkTheme else settings.lightTheme)

        val streaming = hub.serverRunning || hub.player != null
        LaunchedEffect(streaming, settings.keepScreenOn) {
            if (streaming && settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        EchoportTheme(theme) {
            var tab by rememberSaveable { mutableIntStateOf(if (Hub.prefs.state.value.lastServerAddrs.isNotEmpty()) 1 else 0) }
            val labels = listOf(R.string.nav_server, R.string.nav_player, R.string.nav_settings)
            Scaffold(
                containerColor = theme.bg,
                topBar = {
                    Row(
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Logo(30.dp)
                        Text("Echoport", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = theme.surface) {
                        labels.forEachIndexed { i, res ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = {},
                                label = { Text(stringResource(res), fontSize = 14.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedTextColor = theme.accent, unselectedTextColor = theme.muted,
                                    indicatorColor = theme.surface2,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (tab) {
                        0 -> ServerScreen(hub, settings)
                        1 -> PlayerScreen(hub, settings)
                        else -> SettingsScreen(settings)
                    }
                }
            }
        }
    }
}
