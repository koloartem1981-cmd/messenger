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

object ChatType {
    const val DM = "dm"
    const val GROUP = "group"
    const val CHANNEL = "channel"
}

data class Message(
    val id: Long,
    val senderId: Long,
    val recipientId: Long,
    val chatId: Long?,
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
            recipientId = obj.optLong("recipient_id", 0L),
            chatId = obj.optLongOrNull("chat_id"),
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

data class Chat(
    val id: Long,
    val type: String,
    val title: String,
    val ownerId: Long,
    val description: String?,
    val avatarUrl: String?,
    val isOwner: Boolean,
    val membersCount: Int?,
    val createdAt: String,
) {
    val isChannel: Boolean get() = type == ChatType.CHANNEL

    companion object {
        fun fromJson(obj: JSONObject): Chat = Chat(
            id = obj.getLong("id"),
            type = obj.optString("type", ChatType.GROUP),
            title = obj.optString("title"),
            ownerId = obj.optLong("owner_id"),
            description = obj.optString("description").takeIf { it.isNotBlank() && it != "null" },
            avatarUrl = obj.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" },
            isOwner = obj.optBoolean("is_owner", false),
            membersCount = if (obj.has("members_count") && !obj.isNull("members_count")) obj.optInt("members_count") else null,
            createdAt = obj.optString("created_at"),
        )
    }
}

data class ChatMember(
    val user: UserPublic,
    val role: String,
) {
    companion object {
        fun fromJson(obj: JSONObject): ChatMember = ChatMember(
            user = UserPublic.fromJson(obj.getJSONObject("user")),
            role = obj.optString("role", "member"),
        )
    }
}

data class ChatDetails(
    val chat: Chat,
    val myRole: String,
    val canPost: Boolean,
    val members: List<ChatMember>,
) {
    companion object {
        fun fromJson(obj: JSONObject): ChatDetails {
            val membersArr = obj.optJSONArray("members")
            val members = mutableListOf<ChatMember>()
            if (membersArr != null) {
                for (i in 0 until membersArr.length()) {
                    members += ChatMember.fromJson(membersArr.getJSONObject(i))
                }
            }
            return ChatDetails(
                chat = Chat.fromJson(obj),
                myRole = obj.optString("my_role", "member"),
                canPost = obj.optBoolean("can_post", true),
                members = members,
            )
        }
    }
}

/**
 * One entry in the unified chat list returned by GET /chats.
 * Exactly one of [peer] (DM) or [chat] (group/channel) is non-null.
 */
data class ChatEntry(
    val kind: String,
    val peer: UserPublic?,
    val chat: Chat?,
    val lastMessage: Message?,
) {
    val isDm: Boolean get() = kind == ChatType.DM
    val title: String
        get() = peer?.displayName ?: chat?.title ?: ""
    val avatarUrl: String?
        get() = peer?.avatarUrl ?: chat?.avatarUrl

    companion object {
        fun fromJson(obj: JSONObject): ChatEntry = ChatEntry(
            kind = obj.optString("kind", ChatType.DM),
            peer = obj.optJSONObject("peer")?.let { UserPublic.fromJson(it) },
            chat = obj.optJSONObject("chat")?.let { Chat.fromJson(it) },
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
