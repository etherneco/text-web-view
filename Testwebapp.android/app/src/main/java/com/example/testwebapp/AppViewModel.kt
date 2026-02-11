package com.example.testwebapp

import android.app.Application
import android.webkit.WebSettings
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_URL = "https://etherneco.co.uk"
private const val LOG_LIMIT = 500
private const val HISTORY_LIMIT = 10
private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/121.0.0.0 Safari/537.36"

data class UiState(
    val url: String = DEFAULT_URL,
    val ua: String = "",
    val urlHistory: List<String> = emptyList(),
    val uaHistory: List<String> = emptyList(),
    val jsHistory: List<String> = emptyList(),
    val consoleEntries: List<ConsoleEntry> = emptyList(),
    val requestEntries: List<RequestEntry> = emptyList(),
    val consoleFilters: Set<ConsoleLevel> = setOf(ConsoleLevel.LOG, ConsoleLevel.WARN, ConsoleLevel.ERROR),
    val requestFilters: Set<RequestKind> = setOf(
        RequestKind.FETCH,
        RequestKind.XHR,
        RequestKind.RESOURCE,
        RequestKind.NAVIGATION
    ),
    val consoleSearch: String = "",
    val requestSearch: String = "",
    val requestsOnlyErrors: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val context = getApplication<Application>()
        val defaultUa = if (isTablet(context)) DESKTOP_UA else WebSettings.getDefaultUserAgent(context)
        viewModelScope.launch(Dispatchers.IO) {
            context.settingsFlow(DEFAULT_URL, defaultUa).collect { stored ->
                _state.update { current ->
                    current.copy(
                        url = stored.lastUrl,
                        ua = stored.lastUa,
                        urlHistory = stored.urlHistory,
                        uaHistory = stored.uaHistory,
                        jsHistory = stored.jsHistory,
                    )
                }
            }
        }
    }

    fun setNavigationState(canBack: Boolean, canForward: Boolean) {
        _state.update { it.copy(canGoBack = canBack, canGoForward = canForward) }
    }

    fun setUrl(value: String) {
        val normalized = normalizeQuotes(value).trim()
        if (normalized.isEmpty()) return
        _state.update { it.copy(url = normalized) }
        persistUrlAndHistory(normalized)
    }

    fun setUserAgent(value: String) {
        val normalized = normalizeQuotes(value).trim()
        if (normalized.isEmpty()) return
        _state.update { it.copy(ua = normalized) }
        persistUaAndHistory(normalized)
    }

    fun addJsHistory(script: String) {
        val normalized = normalizeQuotes(script).trim()
        if (normalized.isEmpty()) return
        _state.update { current ->
            current.copy(jsHistory = updateHistory(current.jsHistory, normalized))
        }
        persistJsHistory()
    }

    fun updateConsoleSearch(value: String) {
        _state.update { it.copy(consoleSearch = normalizeQuotes(value)) }
    }

    fun updateRequestSearch(value: String) {
        _state.update { it.copy(requestSearch = normalizeQuotes(value)) }
    }

    fun toggleConsoleFilter(level: ConsoleLevel) {
        _state.update { current ->
            val updated = current.consoleFilters.toMutableSet()
            if (updated.contains(level)) updated.remove(level) else updated.add(level)
            current.copy(consoleFilters = updated)
        }
    }

    fun toggleRequestFilter(kind: RequestKind) {
        _state.update { current ->
            val updated = current.requestFilters.toMutableSet()
            if (updated.contains(kind)) updated.remove(kind) else updated.add(kind)
            current.copy(requestFilters = updated)
        }
    }

    fun toggleOnlyErrors() {
        _state.update { it.copy(requestsOnlyErrors = !it.requestsOnlyErrors) }
    }

    fun logConsole(level: ConsoleLevel, message: String) {
        val entry = ConsoleEntry(System.currentTimeMillis(), level, message)
        _state.update { current ->
            val updated = (current.consoleEntries + entry).takeLast(LOG_LIMIT)
            current.copy(consoleEntries = updated)
        }
    }

    fun logRequest(kind: RequestKind, method: String, url: String, status: Int?) {
        val entry = RequestEntry(System.currentTimeMillis(), kind, method, url, status)
        _state.update { current ->
            val updated = (current.requestEntries + entry).takeLast(LOG_LIMIT)
            current.copy(requestEntries = updated)
        }
    }

    fun buildExportText(): String {
        val current = _state.value
        val sb = StringBuilder()
        sb.appendLine("== Console ==")
        current.consoleEntries.forEach { entry ->
            sb.appendLine("${formatTime(entry.timeMs)} [${entry.level}] ${entry.message}")
        }
        sb.appendLine()
        sb.appendLine("== Requests ==")
        current.requestEntries.forEach { entry ->
            val status = entry.status?.toString() ?: "-"
            sb.appendLine("${formatTime(entry.timeMs)} [${entry.kind}] ${entry.method} ${entry.url} ($status)")
        }
        return sb.toString()
    }

    private fun persistUrlAndHistory(url: String) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            context.appDataStore.edit { prefs ->
                prefs[PrefKeys.lastUrl] = url
                val updated = updateHistory(_state.value.urlHistory, url)
                prefs[PrefKeys.urlHistory] = encodeList(updated)
            }
        }
    }

    private fun persistUaAndHistory(ua: String) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            context.appDataStore.edit { prefs ->
                prefs[PrefKeys.lastUa] = ua
                val updated = updateHistory(_state.value.uaHistory, ua)
                prefs[PrefKeys.uaHistory] = encodeList(updated)
            }
        }
    }

    private fun persistJsHistory() {
        val context = getApplication<Application>()
        val snapshot = _state.value.jsHistory
        viewModelScope.launch(Dispatchers.IO) {
            context.appDataStore.edit { prefs ->
                prefs[PrefKeys.jsHistory] = encodeList(snapshot)
            }
        }
    }

    private fun updateHistory(values: List<String>, value: String): List<String> {
        val trimmed = value.trim()
        val updated = values.filter { it != trimmed }.toMutableList()
        updated.add(0, trimmed)
        return updated.take(HISTORY_LIMIT)
    }
}

fun normalizeQuotes(value: String): String {
    return value
        .replace('\u201C', '"')
        .replace('\u201D', '"')
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
}

fun formatTime(timeMs: Long): String {
    val seconds = timeMs / 1000
    val mins = seconds / 60
    val hrs = mins / 60
    val remMins = mins % 60
    val remSecs = seconds % 60
    return String.format("%02d:%02d:%02d", hrs % 24, remMins, remSecs)
}

fun isTablet(context: android.content.Context): Boolean {
    return context.resources.configuration.smallestScreenWidthDp >= 600
}
