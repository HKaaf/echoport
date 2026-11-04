package io.github.hkaaf.echoport.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import io.github.hkaaf.echoport.AppSettings
import io.github.hkaaf.echoport.R
import io.github.hkaaf.echoport.engine.Hub
import io.github.hkaaf.echoport.newPin

private const val REPO = "https://github.com/HKaaf/echoport"

@SuppressLint("BatteryLife") // Distributed outside Play; staying alive in the background is the app's job.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(s: AppSettings) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(s.deviceName) }

    PageHeader(stringResource(R.string.nav_settings), "")

    EpCard {
        SectionTitle(stringResource(R.string.settings_device))
        OutlinedTextField(
            value = name,
            onValueChange = { v -> name = v.take(40); Hub.prefs.update { it.copy(deviceName = name) } },
            label = { Text(stringResource(R.string.settings_device_name)) },
            placeholder = { Text(Hub.prefs.defaultName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(stringResource(R.string.settings_device_name_hint, Hub.prefs.defaultName))
        SecondaryButton(stringResource(R.string.settings_reset), { name = ""; Hub.prefs.update { it.copy(deviceName = "") } })
    }

    EpCard {
        SectionTitle(stringResource(R.string.settings_appearance))
        val modes = listOf("system", "light", "dark")
        Segmented(
            listOf(stringResource(R.string.settings_mode_system), stringResource(R.string.settings_mode_light), stringResource(R.string.settings_mode_dark)),
            modes.indexOf(s.themeMode).coerceAtLeast(0),
            { i -> Hub.prefs.update { it.copy(themeMode = modes[i]) } },
        )
        Hint(stringResource(R.string.settings_light_themes))
        ThemeGrid(THEMES.filter { !it.dark }, s.lightTheme) { id -> Hub.prefs.update { it.copy(lightTheme = id, themeMode = "light") } }
        Hint(stringResource(R.string.settings_dark_themes))
        ThemeGrid(THEMES.filter { it.dark }, s.darkTheme) { id -> Hub.prefs.update { it.copy(darkTheme = id, themeMode = "dark") } }

        SectionTitle(stringResource(R.string.settings_language))
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        Segmented(listOf("English", "فارسی"), if (current.startsWith("fa")) 1 else 0, { i ->
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (i == 1) "fa" else "en"))
        })
    }

    EpCard {
        SectionTitle(stringResource(R.string.settings_connection))
        ToggleRow(stringResource(R.string.settings_require_pin), s.requirePin, { v ->
            Hub.prefs.update { it.copy(requirePin = v) }; Hub.restartServerIfRunning(ctx)
        }, hint = stringResource(R.string.settings_require_pin_hint))
        if (s.requirePin) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.pin, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
                SecondaryButton(stringResource(R.string.settings_new_pin), {
                    Hub.prefs.update { it.copy(pin = newPin()) }; Hub.restartServerIfRunning(ctx)
                })
            }
        }
        var portText by remember { mutableStateOf(s.port.toString()) }
        OutlinedTextField(portText, { v ->
            portText = v.filter(Char::isDigit).take(5)
            portText.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> Hub.prefs.update { it.copy(port = p) } }
        }, label = { Text(stringResource(R.string.settings_port)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(160.dp))
        ToggleRow(stringResource(R.string.settings_auto_reconnect), s.autoReconnect, { v -> Hub.prefs.update { it.copy(autoReconnect = v) } })
    }

    EpCard {
        SectionTitle(stringResource(R.string.settings_android))
        Hint(stringResource(R.string.settings_battery_hint))
        SecondaryButton(stringResource(R.string.settings_battery), {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }, Modifier.fillMaxWidth())
        ToggleRow(stringResource(R.string.settings_keep_screen_on), s.keepScreenOn, { v -> Hub.prefs.update { it.copy(keepScreenOn = v) } })
    }

    EpCard {
        SectionTitle(stringResource(R.string.settings_about))
        val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
        Text("Echoport · " + stringResource(R.string.settings_version, version))
        Hint(stringResource(R.string.settings_license))
        SecondaryButton(stringResource(R.string.settings_github), {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO)))
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeGrid(list: List<EpTheme>, selected: String, onPick: (String) -> Unit) {
    val ep = LocalEp.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { th ->
            val on = th.id == selected
            Surface(
                onClick = { onPick(th.id) },
                color = th.surface,
                contentColor = th.text,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) ep.accent else th.border),
                modifier = Modifier.width(100.dp),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(th.bg, th.accent, th.accent2).forEach { c ->
                            Box(Modifier.size(18.dp).background(c, RoundedCornerShape(5.dp)).border(1.dp, th.border, RoundedCornerShape(5.dp)))
                        }
                    }
                    Text(th.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}
