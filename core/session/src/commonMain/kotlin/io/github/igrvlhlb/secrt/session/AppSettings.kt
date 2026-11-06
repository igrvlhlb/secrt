package io.github.igrvlhlb.secrt.session

import io.github.igrvlhlb.secrt.model.SecrtJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Preferences that apply while the diary is locked, so they can't live in the encrypted
 * database. Nothing here is sensitive.
 */
@Serializable
data class AppSettings(
    val autoLock: AutoLockTimeout = AutoLockTimeout.OneMinute,
)

/** How long the app may stay in the background before the diary locks. */
@Serializable
enum class AutoLockTimeout(
    /** `null`: never while the app is running; the diary only locks when the app restarts. */
    val millis: Long?,
) {
    @SerialName("immediately")
    Immediately(0),

    @SerialName("1m")
    OneMinute(60_000),

    @SerialName("5m")
    FiveMinutes(5 * 60_000),

    @SerialName("15m")
    FifteenMinutes(15 * 60_000),

    @SerialName("1h")
    OneHour(60 * 60_000),

    @SerialName("restart")
    OnAppRestart(null),
}

/**
 * [AppSettings] kept as a small plaintext JSON file. A missing or unreadable file means defaults,
 * and a value this version doesn't know (written by a newer one) falls back to that setting's
 * default. Never an error: settings must not stand between the user and their diary.
 */
class AppSettingsStore(private val fileSystem: FileSystem, private val file: Path) {

    private val json = Json(SecrtJson) { coerceInputValues = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Applies [transform] and saves the result. Blocking, but the file is tiny. */
    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = _settings.updateAndGet(transform)
        val directory = requireNotNull(file.parent)
        fileSystem.createDirectories(directory)
        val temp = directory / "${file.name}.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(AppSettings.serializer(), updated)) }
        fileSystem.atomicMove(temp, file)
    }

    private fun load(): AppSettings = try {
        if (fileSystem.exists(file)) json.decodeFromString(AppSettings.serializer(), fileSystem.read(file) { readUtf8() }) else AppSettings()
    } catch (e: IllegalArgumentException) { // includes SerializationException
        AppSettings()
    } catch (e: IOException) {
        AppSettings()
    }
}
