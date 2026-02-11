package com.example.testwebapp

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val SETTINGS_NAME = "testwebapp_settings"

val Context.appDataStore by preferencesDataStore(name = SETTINGS_NAME)

object PrefKeys {
    val lastUrl = stringPreferencesKey("last_url")
    val lastUa = stringPreferencesKey("last_ua")
    val urlHistory = stringPreferencesKey("url_history")
    val uaHistory = stringPreferencesKey("ua_history")
    val jsHistory = stringPreferencesKey("js_history")
}

data class StoredSettings(
    val lastUrl: String,
    val lastUa: String,
    val urlHistory: List<String>,
    val uaHistory: List<String>,
    val jsHistory: List<String>,
)

fun Context.settingsFlow(
    defaultUrl: String,
    defaultUa: String,
): Flow<StoredSettings> {
    return appDataStore.data.map { prefs ->
        StoredSettings(
            lastUrl = prefs[PrefKeys.lastUrl] ?: defaultUrl,
            lastUa = prefs[PrefKeys.lastUa] ?: defaultUa,
            urlHistory = decodeList(prefs[PrefKeys.urlHistory]),
            uaHistory = decodeList(prefs[PrefKeys.uaHistory]),
            jsHistory = decodeList(prefs[PrefKeys.jsHistory]),
        )
    }
}

fun encodeList(values: List<String>): String {
    return values.joinToString("\n")
}

fun decodeList(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    return value.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}
