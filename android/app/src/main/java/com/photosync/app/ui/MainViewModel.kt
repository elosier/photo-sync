package com.photosync.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photosync.app.data.AppDatabase
import com.photosync.app.data.DeviceInfo
import com.photosync.app.data.SettingsStore
import com.photosync.app.net.UploadClient
import com.photosync.app.sync.MediaWatchScheduler
import com.photosync.app.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val serverUrl: String = "",
    val token: String = "",
    val deviceFolder: String = "",
    val wifiOnly: Boolean = true,
    val cameraOnly: Boolean = true,
    val syncEnabled: Boolean = false,
    val uploadedCount: Int = 0,
    val lastRun: String = "never",
    val lastResult: String = "",
    val syncState: String = "Idle",
    val message: String = "",
    val busy: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val dao = AppDatabase.get(app).uploadedDao()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
        observeUploadCount()
        observeWork()
    }

    /** Live upload count straight from Room — updates as rows are inserted. */
    private fun observeUploadCount() {
        viewModelScope.launch {
            dao.countFlow().collect { c -> _state.update { it.copy(uploadedCount = c) } }
        }
    }

    /** Reflect WorkManager state (Running/Scheduled/Idle) and, when a run
     *  finishes, pull the freshly persisted last-run/last-result values. */
    private fun observeWork() {
        val wm = WorkManager.getInstance(getApplication())
        viewModelScope.launch {
            combine(
                wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.ONESHOT),
                wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.PERIODIC),
            ) { oneshot, periodic -> oneshot + periodic }.collect { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING }
                val enqueued = infos.any { it.state == WorkInfo.State.ENQUEUED }
                val syncState = when {
                    running -> "Running…"
                    enqueued -> "Scheduled (waiting for network)"
                    else -> "Idle"
                }
                _state.update {
                    // Clear the transient "Sync…" confirmation once idle again.
                    val msg = if (syncState == "Idle" && it.message.startsWith("Sync")) "" else it.message
                    it.copy(
                        syncState = syncState,
                        lastRun = formatTime(settings.lastRunMillis),
                        lastResult = settings.lastResult,
                        message = msg,
                    )
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { runCatching { dao.count() }.getOrDefault(0) }
            _state.value = _state.value.copy(
                serverUrl = settings.serverBaseUrl,
                token = settings.token,
                // Pre-fill an auto-detected suggestion on first run (nothing saved
                // yet). It stays editable and is only persisted on Save.
                deviceFolder = settings.deviceFolder.ifBlank { DeviceInfo.suggestedFolder(getApplication()) },
                wifiOnly = settings.wifiOnly,
                cameraOnly = settings.cameraOnly,
                syncEnabled = settings.syncEnabled,
                uploadedCount = count,
                lastRun = formatTime(settings.lastRunMillis),
                lastResult = settings.lastResult,
            )
        }
    }

    fun onServerUrl(v: String) = _state.update { it.copy(serverUrl = v) }
    fun onToken(v: String) = _state.update { it.copy(token = v) }
    fun onDeviceFolder(v: String) =
        _state.update { it.copy(deviceFolder = SettingsStore.sanitizeDevice(v)) }
    fun onWifiOnly(v: Boolean) = _state.update { it.copy(wifiOnly = v) }
    fun onCameraOnly(v: Boolean) = _state.update { it.copy(cameraOnly = v) }

    /** Re-fill the device folder from the phone's detected name/model. */
    fun detectDeviceFolder() {
        val suggestion = DeviceInfo.suggestedFolder(getApplication())
        _state.update {
            it.copy(
                deviceFolder = suggestion,
                message = if (suggestion.isBlank()) {
                    "Couldn't detect a device name — enter it manually"
                } else {
                    "Filled from this phone — edit the model part if needed"
                },
            )
        }
    }

    fun save() {
        val s = _state.value
        settings.serverBaseUrl = s.serverUrl
        settings.token = s.token
        settings.deviceFolder = s.deviceFolder
        settings.wifiOnly = s.wifiOnly
        settings.cameraOnly = s.cameraOnly
        _state.update { it.copy(message = "Saved", deviceFolder = settings.deviceFolder) }
        // Reschedule with any new constraints.
        if (settings.isConfigured() && settings.syncEnabled) {
            SyncScheduler.schedulePeriodic(getApplication())
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        save()
        settings.syncEnabled = enabled
        _state.update { it.copy(syncEnabled = enabled) }
        val ctx = getApplication<Application>()
        if (enabled && settings.isConfigured()) {
            SyncScheduler.schedulePeriodic(ctx)
            MediaWatchScheduler.schedule(ctx)
            SyncScheduler.syncNow(ctx)
        } else {
            SyncScheduler.cancelPeriodic(ctx)
            MediaWatchScheduler.cancel(ctx)
        }
    }

    fun syncNow() {
        save()
        if (!settings.isConfigured()) {
            _state.update { it.copy(message = "Fill in server URL, token and device folder first") }
            return
        }
        SyncScheduler.syncNow(getApplication())
        _state.update { it.copy(message = "Sync started") }
    }

    fun testConnection() {
        save()
        _state.update { it.copy(busy = true, message = "Testing…") }
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                UploadClient(
                    settings.serverBaseUrl,
                    settings.token,
                    getApplication<Application>().contentResolver,
                ).testConnection()
            }
            _state.update {
                it.copy(busy = false, message = err ?: "Connection OK — server reachable and token accepted")
            }
        }
    }

    fun refresh() = load()

    private fun formatTime(millis: Long): String =
        if (millis <= 0) "never"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}
