package com.windy.phonemouse.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val update: AppUpdateInfo) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

internal data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String,
    val releasePage: String,
    val notes: String,
)

enum class UpdateCheckFrequency(val code: String, val label: String, internal val intervalMillis: Long?) {
    Daily("daily", "每天", 24 * 60 * 60 * 1000L),
    Weekly("weekly", "每周", 7 * 24 * 60 * 60 * 1000L),
    Off("off", "关闭", null);

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Daily
    }
}

internal const val MAX_UPDATE_MANIFEST_BYTES = 128 * 1024L
internal const val APP_PROJECT_URL = "https://github.com/windyy0/phone-mouse"
internal const val APP_UPDATE_MANIFEST_URL = "$APP_PROJECT_URL/releases/latest/download/latest.json"

internal fun shouldCheckForUpdate(lastCheckAt: Long?, now: Long, frequency: UpdateCheckFrequency): Boolean {
    val interval = frequency.intervalMillis ?: return false
    return lastCheckAt == null || now < lastCheckAt || now - lastCheckAt >= interval
}

/** Wall-clock timestamps survive process restarts and device reboots. */
internal class AppUpdateSchedule {
    private var loaded = false
    private var frequency = UpdateCheckFrequency.Daily
    private var lastCheckAt: Long? = null
    internal var promptGeneration = 0
        private set

    internal fun restore(frequency: UpdateCheckFrequency, lastCheckAt: Long?) {
        this.lastCheckAt = lastCheckAt
        setFrequency(frequency)
        loaded = true
    }

    internal fun setFrequency(value: UpdateCheckFrequency) {
        frequency = value
        if (value == UpdateCheckFrequency.Off) promptGeneration++
    }

    internal fun isDue(now: Long): Boolean = loaded && shouldCheckForUpdate(lastCheckAt, now, frequency)
    internal fun recordAttempt(now: Long) { lastCheckAt = now }
    internal fun mayPrompt(generation: Int): Boolean =
        loaded && frequency != UpdateCheckFrequency.Off && generation == promptGeneration
}

internal fun isSecureUpdateUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

internal fun parseAppUpdate(body: String, currentVersionCode: Long): AppUpdateInfo? {
    val root = JSONObject(body)
    require(root.get("schema") == 1) { "Unsupported update manifest schema" }
    val android = root.getJSONObject("android")
    val versionName = android.getString("version_name")
    val code = android.get("version_code")
    require(code is Number && Regex("[1-9][0-9]*").matches(code.toString())) { "Invalid version code" }
    val versionCode = code.toString().toLong()
    val downloadUrl = android.getString("url")
    val releasePage = root.getString("release_page")
    require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(versionName) &&
        versionName == root.getString("version") && versionCode <= 2_100_000_000L &&
        isSecureUpdateUrl(downloadUrl) && isSecureUpdateUrl(releasePage)) {
        "Invalid update manifest"
    }
    if (versionCode <= currentVersionCode) return null
    return AppUpdateInfo(versionName, versionCode, downloadUrl, releasePage, root.optString("notes").take(4000))
}

/** Reads the static release manifest rather than calling the GitHub API. */
internal class AppUpdateChecker(private val store: SettingsStore) {
    private companion object {
        // Keep this list in the app so a future mirror can be used when GitHub is unreachable.
        val ENDPOINTS = listOf(APP_UPDATE_MANIFEST_URL)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val checking = AtomicBoolean(false)
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    internal val state: StateFlow<AppUpdateState> = _state.asStateFlow()
    private val _prompt = MutableStateFlow<AppUpdateInfo?>(null)
    internal val prompt: StateFlow<AppUpdateInfo?> = _prompt.asStateFlow()
    private var promptedVersionCode: Long? = null
    private val schedule = AppUpdateSchedule()

    internal fun restoreSchedule(frequency: UpdateCheckFrequency, lastCheckAt: Long?) {
        schedule.restore(frequency, lastCheckAt)
    }

    internal fun setFrequency(frequency: UpdateCheckFrequency) {
        schedule.setFrequency(frequency)
        if (frequency == UpdateCheckFrequency.Off) _prompt.value = null
    }

    internal fun maybeCheck(scope: CoroutineScope) {
        if (!schedule.isDue(System.currentTimeMillis())) return
        check(scope, manual = false)
    }

    internal fun check(scope: CoroutineScope, manual: Boolean) {
        if (!checking.compareAndSet(false, true)) return
        val checkedAt = System.currentTimeMillis()
        schedule.recordAttempt(checkedAt)
        val promptGeneration = schedule.promptGeneration
        if (manual) _prompt.value = null
        scope.launch {
            val previousState = _state.value
            try {
                _state.value = AppUpdateState.Checking
                val result = try {
                    // Record failures too, and keep this write separate from control settings.
                    store.recordUpdateCheck(checkedAt)
                    if (!manual && !schedule.mayPrompt(promptGeneration)) return@launch
                    withContext(Dispatchers.IO) { fetch() }
                } catch (error: java.io.IOException) {
                    Result.failure(error)
                }
                _state.value = when {
                    result.isSuccess -> result.getOrThrow()?.let {
                        if (!manual && schedule.mayPrompt(promptGeneration) && promptedVersionCode != it.versionCode) {
                            promptedVersionCode = it.versionCode
                            _prompt.value = it
                        }
                        AppUpdateState.Available(it)
                    }
                        ?: run {
                            _prompt.value = null
                            if (manual) AppUpdateState.UpToDate else AppUpdateState.Idle
                        }
                    manual -> AppUpdateState.Error("检查更新失败。请检查网络，或稍后重试。")
                    else -> previousState
                }
            } finally {
                if (_state.value == AppUpdateState.Checking) _state.value = previousState
            }
        }.invokeOnCompletion { checking.set(false) }
    }

    internal fun dismissPrompt() {
        _prompt.value = null
    }

    internal fun dismissError() {
        _state.update { if (it is AppUpdateState.Error) AppUpdateState.Idle else it }
    }

    private fun fetch(): Result<AppUpdateInfo?> = runCatching {
        var lastError: Throwable? = null
        for (endpoint in ENDPOINTS) {
            try {
                val request = Request.Builder().url(endpoint)
                    .header("Accept", "application/json")
                    .header("User-Agent", "phone-mouse-android/${BuildConfig.VERSION_NAME}")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body
                    require(body.contentLength() <= MAX_UPDATE_MANIFEST_BYTES) { "Update manifest too large" }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    val input = body.byteStream()
                    while (output.size() <= MAX_UPDATE_MANIFEST_BYTES) {
                        val size = input.read(buffer, 0, minOf(buffer.size,
                            MAX_UPDATE_MANIFEST_BYTES.toInt() + 1 - output.size()))
                        if (size == -1) break
                        output.write(buffer, 0, size)
                    }
                    val bytes = output.toByteArray()
                    require(bytes.size <= MAX_UPDATE_MANIFEST_BYTES) { "Update manifest too large" }
                    return@runCatching parseAppUpdate(bytes.toString(Charsets.UTF_8), BuildConfig.VERSION_CODE.toLong())
                }
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("没有可用的更新地址")
    }

}
