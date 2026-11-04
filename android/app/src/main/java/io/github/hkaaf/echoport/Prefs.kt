package io.github.hkaaf.echoport

import android.content.Context
import android.os.Build
import android.provider.Settings as SystemSettings
import io.github.hkaaf.echoport.engine.EqPreset
import io.github.hkaaf.echoport.engine.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlin.random.Random

data class AppSettings(
    val deviceName: String = "",
    val themeMode: String = "system", // system | light | dark
    val lightTheme: String = "daybreak",
    val darkTheme: String = "midnight",
    val source: Source = Source.MIC,
    val gainPercent: Int = 100,
    val eq: EqPreset = EqPreset.FLAT,
    val noiseSuppression: Boolean = false,
    val port: Int = 47210,
    val requirePin: Boolean = false,
    val pin: String = newPin(),
    val volume: Int = 100,
    val bufferMs: Int = 60,
    val autoReconnect: Boolean = true,
    val keepScreenOn: Boolean = false,
    val lastServerAddrs: String = "", // comma separated
    val lastServerPort: Int = 47210,
)

fun newPin(): String = (1000 + Random.nextInt(9000)).toString()

/** SharedPreferences-backed settings exposed as a StateFlow. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("echoport", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state

    /** Stable random id for this install. */
    val id: String = sp.getString("id", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("id", it).apply() }

    /** The phone's own name from system settings, else its model. */
    val defaultName: String = runCatching {
        SystemSettings.Global.getString(context.contentResolver, SystemSettings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    val name: String get() = _state.value.deviceName.trim().ifEmpty { defaultName }

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(_state.value)
        _state.value = s
        sp.edit()
            .putString("deviceName", s.deviceName)
            .putString("themeMode", s.themeMode)
            .putString("lightTheme", s.lightTheme)
            .putString("darkTheme", s.darkTheme)
            .putString("source", s.source.name)
            .putInt("gainPercent", s.gainPercent)
            .putString("eq", s.eq.name)
            .putBoolean("noiseSuppression", s.noiseSuppression)
            .putInt("port", s.port)
            .putBoolean("requirePin", s.requirePin)
            .putString("pin", s.pin)
            .putInt("volume", s.volume)
            .putInt("bufferMs", s.bufferMs)
            .putBoolean("autoReconnect", s.autoReconnect)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putString("lastServerAddrs", s.lastServerAddrs)
            .putInt("lastServerPort", s.lastServerPort)
            .apply()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            deviceName = sp.getString("deviceName", d.deviceName)!!,
            themeMode = sp.getString("themeMode", d.themeMode)!!,
            lightTheme = sp.getString("lightTheme", d.lightTheme)!!,
            darkTheme = sp.getString("darkTheme", d.darkTheme)!!,
            source = runCatching { Source.valueOf(sp.getString("source", null)!!) }.getOrDefault(d.source),
            gainPercent = sp.getInt("gainPercent", d.gainPercent),
            eq = runCatching { EqPreset.valueOf(sp.getString("eq", null)!!) }.getOrDefault(d.eq),
            noiseSuppression = sp.getBoolean("noiseSuppression", d.noiseSuppression),
            port = sp.getInt("port", d.port),
            requirePin = sp.getBoolean("requirePin", d.requirePin),
            pin = sp.getString("pin", null) ?: d.pin,
            volume = sp.getInt("volume", d.volume),
            bufferMs = sp.getInt("bufferMs", d.bufferMs),
            autoReconnect = sp.getBoolean("autoReconnect", d.autoReconnect),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            lastServerAddrs = sp.getString("lastServerAddrs", d.lastServerAddrs)!!,
            lastServerPort = sp.getInt("lastServerPort", d.lastServerPort),
        )
    }
}
