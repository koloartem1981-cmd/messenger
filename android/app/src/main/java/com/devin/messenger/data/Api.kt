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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
                if (resp.code == 401) {
                    AuthBus.signalUnauthorized()
                }
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
        val rawMime = resolver.getType(uri) ?: "image/jpeg"
        val mime = when (rawMime.lowercase()) {
            "image/jpeg", "image/jpg", "image/png", "image/webp" -> rawMime
            else -> "image/jpeg"
        }
        val bytes = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open image" }.readBytes()
        }
        val ext = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = "avatar.$ext",
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

    suspend fun listAllUsers(token: String): List<UserPublic> {
        val req = buildRequest("/users", "GET", token, null)
        val arr = executeJsonArray(req)
        return (0 until arr.length()).map { UserPublic.fromJson(arr.getJSONObject(it)) }
    }

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

    // ---------- Unified chat list ----------

    suspend fun listChats(token: String): List<ChatEntry> {
        val req = buildRequest("/chats", "GET", token, null)
        val arr = executeJsonArray(req)
        return (0 until arr.length()).map { ChatEntry.fromJson(arr.getJSONObject(it)) }
    }

    // ---------- DM messages ----------

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

    suspend fun sendMediaMessage(
        token: String,
        recipientId: Long,
        kind: String,
        file: File,
        mime: String,
        durationMs: Int? = null,
        caption: String? = null,
    ): Message = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = file.name,
                body = file.asRequestBody(mime.toMediaType()),
            )
            .addFormDataPart("recipient_id", recipientId.toString())
            .addFormDataPart("kind", kind)
        if (durationMs != null && durationMs > 0) {
            builder.addFormDataPart("duration_ms", durationMs.toString())
        }
        if (!caption.isNullOrBlank()) {
            builder.addFormDataPart("caption", caption)
        }
        val req = Request.Builder()
            .url("$baseUrl/messages/media")
            .post(builder.build())
            .header("Authorization", "Bearer $token")
            .build()
        Message.fromJson(executeJson(req))
    }

    // ---------- Group / Channel chats ----------

    suspend fun createChat(
        token: String,
        type: String,
        title: String,
        description: String? = null,
        memberIds: List<Long> = emptyList(),
    ): Chat {
        val payload = JSONObject().apply {
            put("type", type)
            put("title", title)
            if (!description.isNullOrBlank()) put("description", description)
            val arr = JSONArray()
            memberIds.forEach { arr.put(it) }
            put("member_ids", arr)
        }
        val req = buildRequest("/chats", "POST", token, jsonBody(payload))
        return Chat.fromJson(executeJson(req))
    }

    suspend fun getChatDetails(token: String, chatId: Long): ChatDetails {
        val req = buildRequest("/chats/$chatId", "GET", token, null)
        return ChatDetails.fromJson(executeJson(req))
    }

    suspend fun updateChat(
        token: String,
        chatId: Long,
        title: String? = null,
        description: String? = null,
    ): Chat {
        val payload = JSONObject()
        if (title != null) payload.put("title", title)
        if (description != null) payload.put("description", description)
        val req = buildRequest("/chats/$chatId", "PATCH", token, jsonBody(payload))
        return Chat.fromJson(executeJson(req))
    }

    suspend fun deleteChat(token: String, chatId: Long) {
        val req = buildRequest("/chats/$chatId", "DELETE", token, "".toRequestBody(jsonMedia))
        executeRaw(req)
    }

    suspend fun addChatMember(
        token: String,
        chatId: Long,
        userId: Long,
        role: String = "member",
    ): JSONObject {
        val payload = JSONObject().apply {
            put("user_id", userId)
            put("role", role)
        }
        val req = buildRequest("/chats/$chatId/members", "POST", token, jsonBody(payload))
        return executeJson(req)
    }

    suspend fun removeChatMember(token: String, chatId: Long, userId: Long) {
        val req = buildRequest(
            "/chats/$chatId/members/$userId",
            "DELETE",
            token,
            "".toRequestBody(jsonMedia),
        )
        executeRaw(req)
    }

    suspend fun listChatMessages(token: String, chatId: Long): List<Message> {
        val req = buildRequest("/chats/$chatId/messages", "GET", token, null)
        val arr = executeJsonArray(req)
        return (0 until arr.length()).map { Message.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun sendChatMessage(token: String, chatId: Long, content: String): Message {
        val payload = JSONObject().put("content", content)
        val req = buildRequest("/chats/$chatId/messages", "POST", token, jsonBody(payload))
        return Message.fromJson(executeJson(req))
    }

    suspend fun sendChatMediaMessage(
        token: String,
        chatId: Long,
        kind: String,
        file: File,
        mime: String,
        durationMs: Int? = null,
        caption: String? = null,
    ): Message = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = file.name,
                body = file.asRequestBody(mime.toMediaType()),
            )
            .addFormDataPart("kind", kind)
        if (durationMs != null && durationMs > 0) {
            builder.addFormDataPart("duration_ms", durationMs.toString())
        }
        if (!caption.isNullOrBlank()) {
            builder.addFormDataPart("caption", caption)
        }
        val req = Request.Builder()
            .url("$baseUrl/chats/$chatId/messages/media")
            .post(builder.build())
            .header("Authorization", "Bearer $token")
            .build()
        Message.fromJson(executeJson(req))
    }

    fun avatarUrlFor(user: UserPublic): String? =
        user.avatarUrl?.let { path ->
            if (path.startsWith("http")) path else "$baseUrl$path"
        }

    fun chatAvatarUrlFor(chat: Chat): String? =
        chat.avatarUrl?.let { path ->
            if (path.startsWith("http")) path else "$baseUrl$path"
        }

    fun mediaUrlFor(message: Message): String? =
        message.mediaUrl?.let { path ->
            if (path.startsWith("http")) path else "$baseUrl$path"
        }

    fun webSocketUrl(token: String): String {
        val httpToWs = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$httpToWs/ws?token=${Uri.encode(token)}"
    }
}
