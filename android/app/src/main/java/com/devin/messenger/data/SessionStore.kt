package com.devin.messenger.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "session")

class SessionStore(private val context: Context) {

    private val tokenKey = stringPreferencesKey("access_token")
    private val userKey = stringPreferencesKey("user_json")

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[tokenKey] }

    val userFlow: Flow<UserPublic?> = context.dataStore.data.map { prefs ->
        prefs[userKey]?.let { raw ->
            runCatching { UserPublic.fromJson(JSONObject(raw)) }.getOrNull()
        }
    }

    suspend fun save(result: AuthResult) {
        val payload = JSONObject().apply {
            put("id", result.user.id)
            put("username", result.user.username)
            put("display_name", result.user.displayName)
            put("bio", result.user.bio ?: JSONObject.NULL)
            put("avatar_url", result.user.avatarUrl ?: JSONObject.NULL)
        }.toString()
        context.dataStore.edit { prefs ->
            prefs[tokenKey] = result.token
            prefs[userKey] = payload
        }
    }

    suspend fun saveUser(user: UserPublic) {
        val payload = JSONObject().apply {
            put("id", user.id)
            put("username", user.username)
            put("display_name", user.displayName)
            put("bio", user.bio ?: JSONObject.NULL)
            put("avatar_url", user.avatarUrl ?: JSONObject.NULL)
        }.toString()
        context.dataStore.edit { prefs ->
            prefs[userKey] = payload
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
