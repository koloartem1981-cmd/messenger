package com.devin.messenger.data

import android.content.Context
import android.net.Uri
import com.devin.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, val errorMessage: String) :
    IOException("HTTP $code: $errorMessage")

class Api(
    private val context: Context,
    private val baseUrl: String = BuildConfig.BACKEND_URL.trimEnd('/'),
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val httpClient: OkHttpClient get() = client
    val httpBaseUrl: String get() = baseUrl

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun buildRequest(
        path: String,
        method: String,
        token: String?,
        body: RequestBody?,
    ): Request {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .method(method, body)
            .header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }

    private suspend fun executeRaw(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching {
                    JSONObject(raw).optString("detail", raw)
                }.getOrDefault(raw)
                throw ApiException(resp.code, detail.ifBlank { "Request failed" })
            }
            raw
        }
    }

    private suspend fun executeJson(request: Request): JSONObject =
        JSONObject(executeRaw(request))

    private suspend fun executeJsonArray(request: Request): JSONArray =
        JSONArray(executeRaw(request))

    private fun jsonBody(payload: JSONObject): RequestBody =
        payload.toString().toRequestBody(jsonMedia)

    // ---------- Auth ----------

    suspend fun register(username: String, displayName: String, password: String): AuthResult {
        val payload = JSONObject().apply {
            put("username", username)
            put("display_name", displayName)
            put("password", password)
        }
        val req = buildRequest("/auth/register", "POST", null, jsonBody(payload))
        return AuthResult.fromJson(executeJson(req))
    }

    suspend fun login(username: String, password: String): AuthResult {
        val payload = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        val req = buildRequest("/auth/login", "POST", null, jsonBody(payload))
        return AuthResult.fromJson(executeJson(req))
    }

    suspend fun me(token: String): UserPublic {
        val req = buildRequest("/me", "GET", token, null)
        return UserPublic.fromJson(executeJson(req))
    }

    suspend fun updateProfile(
        token: String,
        displayName: String?,
        bio: String?,
    ): UserPublic {
        val payload = JSONObject()
        if (displayName != null) payload.put("display_name", displayName)
        if (bio != null) payload.put("bio", bio)
        val req = Request.Builder()
            .url("$baseUrl/me")
            .method("PATCH", jsonBody(payload))
            .header("Authorization", "Bearer $token")
            .build()
        return UserPublic.fromJson(executeJson(req))
    }

    suspend fun uploadAvatar(token: String, uri: Uri): UserPublic = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val bytes = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open image" }.readBytes()
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = "avatar.${if (mime.endsWith("png")) "png" else "jpg"}",
                body = bytes.toRequestBody(mime.toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("$baseUrl/me/avatar")
            .method("POST", body)
            .header("Authorization", "Bearer $token")
            .build()
        UserPublic.fromJson(executeJson(req))
    }

    // ---------- Users / Contacts ----------

    suspend fun searchUsers(token: String, q: String): List<UserPublic> {
        val url = "$baseUrl/users/search?q=${Uri.encode(q)}"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val arr = executeJsonArray(req)
        return (0 until arr.length()).map { UserPublic.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun listContacts(token: String): List<UserPublic> {
        val req = buildRequest("/contacts", "GET", token, null)
        val arr = executeJsonArray(req)
        return (0 until arr.length()).map { UserPublic.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun addContact(token: String, username: String): UserPublic {
        val payload = JSONObject().put("username", username)
        val req = buildRequest("/contacts", "POST", token, jsonBody(payload))
        return UserPublic.fromJson(executeJson(req))
    }

    suspend fun removeContact(token: String, userId: Long) {
        val req = buildRequest("/contacts/$userId", "DELETE", token, "".toRequestBody(jsonMedia))
        executeRaw(req)
    }

    suspend fun getUser(token: String, userId: Long): UserPublic {
        val req = buildRequest("/users/$userId", "GET", token, null)
        return UserPublic.fromJson(executeJson(req))
    }

    // ---------- Chats ----------

    suspend fun listChats(token: String): List<ChatPreview> {
        val req = buildRequest("/chats", "GET", token, null)
        val arr = executeJsonArray(req)
        return (0 until arr.length()).map { ChatPreview.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun listMessages(token: String, peerId: Long): List<Message> {
        val req = buildRequest("/messages/$peerId", "GET", token, null)
        val arr = executeJsonArray(req)
        return (0 until arr.length()).map { Message.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun sendMessage(token: String, recipientId: Long, content: String): Message {
        val payload = JSONObject().apply {
            put("recipient_id", recipientId)
            put("content", content)
        }
        val req = buildRequest("/messages", "POST", token, jsonBody(payload))
        return Message.fromJson(executeJson(req))
    }

    fun avatarUrlFor(user: UserPublic): String? =
        user.avatarUrl?.let { path ->
            if (path.startsWith("http")) path else "$baseUrl$path"
        }

    fun webSocketUrl(token: String): String {
        val httpToWs = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$httpToWs/ws?token=${Uri.encode(token)}"
    }
}
