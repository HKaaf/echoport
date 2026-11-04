package io.github.hkaaf.echoport.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import io.github.hkaaf.echoport.AppSettings
import io.github.hkaaf.echoport.R
import io.github.hkaaf.echoport.engine.EqPreset
import io.github.hkaaf.echoport.engine.Hub
import io.github.hkaaf.echoport.engine.HubState
import io.github.hkaaf.echoport.engine.NetIface
import io.github.hkaaf.echoport.engine.Source
import io.github.hkaaf.echoport.engine.ipv4Interfaces
import android.media.audiofx.NoiseSuppressor
import java.net.URLEncoder
import kotlinx.coroutines.delay

@Composable
fun ServerScreen(hub: HubState, s: AppSettings) {
    val ctx = LocalContext.current
    val ep = LocalEp.current
    var ifaces by remember { mutableStateOf<List<NetIface>>(emptyList()) }
    LaunchedEffect(Unit) { while (true) { ifaces = ipv4Interfaces(); delay(4000) } }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) Hub.startServer(ctx, r.resultCode, r.data)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start(ctx, s, projectionLauncher::launch)
    }

    PageHeader(stringResource(R.string.nav_server), stringResource(R.string.server_subtitle))

    if (hub.serverRunning) {
        SecondaryButton(stringResource(R.string.server_stop), { Hub.stopServer() }, Modifier.fillMaxWidth())
    } else {
        PrimaryButton(stringResource(R.string.server_start), {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                start(ctx, s, projectionLauncher::launch)
            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }, Modifier.fillMaxWidth())
    }
    if (hub.serverError.isNotEmpty()) Hint(hub.serverError, Color(0xFFE5484D))

    EpCard {
        SectionTitle(stringResource(R.string.server_source))
        val systemOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        Segmented(
            listOf(stringResource(R.string.server_mic), stringResource(R.string.server_phone_audio)),
            if (s.source == Source.MIC) 0 else 1,
            { i -> Hub.prefs.update { it.copy(source = if (i == 0) Source.MIC else Source.SYSTEM) }; Hub.stopServer() },
            enabled = { it == 0 || systemOk },
        )
        Hint(stringResource(when {
            s.source == Source.MIC -> R.string.server_mic_hint
            systemOk -> R.string.server_system_hint
            else -> R.string.server_system_unsupported
        }))
        Text(stringResource(R.string.server_level), color = ep.muted, fontSize = 13.sp)
        val pct = if (hub.serverRunning) ((hub.levelDb + 60f) / 60f).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(ep.surface2)) {
            Box(Modifier.fillMaxWidth(pct).fillMaxHeight().background(ep.accent))
        }
    }

    EpCard {
        SectionTitle(stringResource(R.string.server_addresses))
        val payload = "echoport://${ifaces.joinToString(",") { it.ip }}:${s.port}?name=" +
            URLEncoder.encode(Hub.prefs.name, "UTF-8") + if (s.requirePin) "&pin=${s.pin}" else ""
        val qr = remember(payload) {
            if (ifaces.isEmpty()) null else runCatching { BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 480, 480) }.getOrNull()
        }
        if (qr != null) QrImage(qr)
        if (ifaces.isEmpty()) Hint(stringResource(R.string.server_no_addresses))
        ifaces.forEach { i ->
            Surface(color = ep.surface2, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${i.ip}:${s.port}", fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                        Hint(i.name)
                    }
                    Badge(stringResource(ifaceLabel(i.kind)))
                }
            }
        }
        if (s.requirePin) Text(stringResource(R.string.server_pin, s.pin), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }

    EpCard {
        val available = NoiseSuppressor.isAvailable() && s.source == Source.MIC
        ToggleRow(
            stringResource(R.string.noise_title), s.noiseSuppression && available,
            { v -> Hub.prefs.update { it.copy(noiseSuppression = v) }; Hub.restartServerIfRunning(ctx) },
            hint = stringResource(if (NoiseSuppressor.isAvailable()) R.string.noise_hint else R.string.noise_unavailable),
            enabled = available,
        )
    }

    EpCard {
        LabeledSlider(stringResource(R.string.server_gain), "${s.gainPercent}%", s.gainPercent.toFloat(), 0f..400f, { v ->
            Hub.prefs.update { it.copy(gainPercent = (v / 5).toInt() * 5) }; Hub.setEffects()
        }, hint = stringResource(R.string.server_gain_hint))
        EqPicker(s.eq) { eq -> Hub.prefs.update { it.copy(eq = eq) }; Hub.setEffects() }
    }

    EpCard {
        SectionTitle(stringResource(R.string.server_players))
        if (hub.peers.isEmpty()) Hint(stringResource(R.string.server_no_players))
        hub.peers.forEach { p ->
            Surface(color = ep.surface2, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Hint("${p.addr} · ${p.platform} · ${p.rttMs} ms")
                    }
                    SecondaryButton(stringResource(R.string.server_disconnect), { Hub.kick(p.id) })
                }
            }
        }
    }
}

private fun start(ctx: Context, s: AppSettings, askProjection: (android.content.Intent) -> Unit) {
    if (s.source == Source.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        askProjection(mpm.createScreenCaptureIntent())
    } else {
        Hub.startServer(ctx)
    }
}

@Composable
private fun QrImage(bitmap: Bitmap) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(bitmap.asImageBitmap(), contentDescription = "QR code",
            modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
    }
}

fun ifaceLabel(kind: String) = when (kind) {
    "wifi" -> R.string.iface_wifi
    "usb" -> R.string.iface_usb
    "ethernet" -> R.string.iface_ethernet
    "mobile" -> R.string.iface_mobile
    else -> R.string.iface_other
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqPicker(current: EqPreset, onPick: (EqPreset) -> Unit) {
    val names = mapOf(
        EqPreset.FLAT to stringResource(R.string.eq_flat),
        EqPreset.VOICE_CLARITY to stringResource(R.string.eq_voice),
        EqPreset.BASS_BOOST to stringResource(R.string.eq_bass),
        EqPreset.TREBLE to stringResource(R.string.eq_treble),
    )
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = names.getValue(current), onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.server_eq)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEach { (eq, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onPick(eq); open = false })
            }
        }
    }
}
