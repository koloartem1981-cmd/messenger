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

data class Message(
    val id: Long,
    val senderId: Long,
    val recipientId: Long,
    val content: String,
    val createdAt: String,
) {
    companion object {
        fun fromJson(obj: JSONObject): Message = Message(
            id = obj.getLong("id"),
            senderId = obj.getLong("sender_id"),
            recipientId = obj.getLong("recipient_id"),
            content = obj.getString("content"),
            createdAt = obj.getString("created_at"),
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
