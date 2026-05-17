package com.devin.messenger.data

import org.json.JSONObject

data class UserPublic(
    val id: Long,
    val username: String,
    val displayName: String,
    val bio: String?,
    val avatarUrl: String?,
) {
    companion object {
        fun fromJson(obj: JSONObject): UserPublic = UserPublic(
            id = obj.getLong("id"),
            username = obj.getString("username"),
            displayName = obj.optString("display_name", obj.getString("username")),
            bio = obj.optString("bio").takeIf { it.isNotBlank() && it != "null" },
            avatarUrl = obj.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

object MessageKind {
    const val TEXT = "text"
    const val VOICE = "voice"
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val FILE = "file"
    const val VIDEO_CIRCLE = "video_circle"
}

data class Message(
    val id: Long,
    val senderId: Long,
    val recipientId: Long,
    val kind: String,
    val content: String,
    val createdAt: String,
    val mediaUrl: String? = null,
    val mediaMime: String? = null,
    val mediaSize: Long? = null,
    val mediaDurationMs: Int? = null,
    val mediaFilename: String? = null,
) {
    val hasMedia: Boolean get() = !mediaUrl.isNullOrBlank()

    companion object {
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (!has(key) || isNull(key)) null else optLong(key)

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (!has(key) || isNull(key)) null else optInt(key)

        fun fromJson(obj: JSONObject): Message = Message(
            id = obj.getLong("id"),
            senderId = obj.getLong("sender_id"),
            recipientId = obj.getLong("recipient_id"),
            kind = obj.optString("kind").takeIf { it.isNotBlank() } ?: MessageKind.TEXT,
            content = obj.optString("content", ""),
            createdAt = obj.getString("created_at"),
            mediaUrl = obj.optStringOrNull("media_url"),
            mediaMime = obj.optStringOrNull("media_mime"),
            mediaSize = obj.optLongOrNull("media_size"),
            mediaDurationMs = obj.optIntOrNull("media_duration_ms"),
            mediaFilename = obj.optStringOrNull("media_filename"),
        )
    }
}

data class ChatPreview(
    val peer: UserPublic,
    val lastMessage: Message?,
) {
    companion object {
        fun fromJson(obj: JSONObject): ChatPreview = ChatPreview(
            peer = UserPublic.fromJson(obj.getJSONObject("peer")),
            lastMessage = obj.optJSONObject("last_message")?.let { Message.fromJson(it) },
        )
    }
}

data class AuthResult(
    val token: String,
    val user: UserPublic,
) {
    companion object {
        fun fromJson(obj: JSONObject): AuthResult = AuthResult(
            token = obj.getString("access_token"),
            user = UserPublic.fromJson(obj.getJSONObject("user")),
        )
    }
}
