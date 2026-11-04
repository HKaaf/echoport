package io.github.hkaaf.echoport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import io.github.hkaaf.echoport.AppSettings
import io.github.hkaaf.echoport.R
import io.github.hkaaf.echoport.engine.Hub
import io.github.hkaaf.echoport.engine.HubState

@Composable
fun PlayerScreen(hub: HubState, s: AppSettings) {
    val ctx = LocalContext.current
    val ep = LocalEp.current
    val servers by Hub.discovery.servers.collectAsStateWithLifecycle()
    var ip by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val parsed = result.contents?.let { parseEchoportUri(it) } ?: return@rememberLauncherForActivityResult
        val (addrs, port, qrPin) = parsed
        if (qrPin != null) pin = qrPin
        ip = addrs.first()
        Hub.connect(ctx, addrs, port, qrPin ?: pin.ifBlank { null })
    }

    PageHeader(stringResource(R.string.nav_player), stringResource(R.string.player_subtitle))

    val connected = hub.player?.connected == true
    EpCard {
        SectionTitle(stringResource(R.string.player_servers))
        if (servers.isEmpty()) Hint(stringResource(R.string.player_searching))
        servers.forEach { srv ->
            Surface(color = ep.surface2, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(srv.name, fontWeight = FontWeight.SemiBold)
                        Hint("${srv.addrs.joinToString()} · " + stringResource(if (srv.source == "system") R.string.server_phone_audio else R.string.server_mic))
                    }
                    if (connected && hub.player?.serverId == srv.id) {
                        SecondaryButton(stringResource(R.string.player_disconnect), { Hub.disconnect() })
                    } else {
                        PrimaryButton(stringResource(if (hub.connecting) R.string.player_connecting else R.string.player_connect),
                            { Hub.connect(ctx, srv.addrs, srv.port, pin.ifBlank { null }) }, enabled = !hub.connecting)
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.player_manual))
        OutlinedTextField(ip, { ip = it.trim() }, label = { Text(stringResource(R.string.player_ip)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.player_pin)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(if (hub.connecting) R.string.player_connecting else R.string.player_connect), {
                val host = ip.substringBefore(':')
                val port = ip.substringAfter(':', "").toIntOrNull() ?: s.port
                Hub.connect(ctx, listOf(host), port, pin.ifBlank { null })
            }, Modifier.weight(1f), enabled = ip.isNotBlank() && !hub.connecting)
            SecondaryButton(stringResource(R.string.player_scan), {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false))
            }, Modifier.weight(1f))
        }
        if (connected) SecondaryButton(stringResource(R.string.player_disconnect), { Hub.disconnect() }, Modifier.fillMaxWidth())

        val error = when (hub.playerError) {
            "" -> ""
            "pin_required" -> stringResource(R.string.player_pin_needed)
            "bad_pin" -> stringResource(R.string.player_bad_pin)
            "lost" -> stringResource(R.string.player_lost)
            else -> hub.playerError
        }
        if (error.isNotEmpty()) Hint(error, Color(0xFFE5484D))
        hub.player?.takeIf { it.connected }?.let {
            Hint(stringResource(R.string.player_stats, it.rttMs, it.bufferedMs, it.lost.toInt()))
        }
    }

    EpCard {
        SectionTitle(stringResource(R.string.player_play_into))
        Segmented(listOf(stringResource(R.string.player_speaker), stringResource(R.string.player_virtual_mic)), 0, {},
            enabled = { it == 0 })
        Hint(stringResource(R.string.player_vmic_note))
    }

    EpCard {
        LabeledSlider(stringResource(R.string.player_volume), "${s.volume}%", s.volume.toFloat(), 0f..200f, { v ->
            val pct = (v / 5).toInt() * 5
            Hub.prefs.update { it.copy(volume = pct) }; Hub.setVolume(pct)
        })
        LabeledSlider(stringResource(R.string.player_buffer), "${s.bufferMs} ms", s.bufferMs.toFloat(), 20f..300f, { v ->
            val ms = (v / 5).toInt() * 5
            Hub.prefs.update { it.copy(bufferMs = ms) }; Hub.setBuffer(ms)
        }, hint = stringResource(R.string.player_buffer_hint))
    }
}
