package com.devin.messenger.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

object RealtimeBus {
    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Message> = _incoming.asSharedFlow()

    private var socket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun connect(api: Api, token: String) {
        disconnect()
        val request = Request.Builder().url(api.webSocketUrl(token)).build()
        socket = api.httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = JSONObject(text)
                    if (obj.optString("type") == "message") {
                        val data = obj.getJSONObject("data")
                        val msg = Message.fromJson(data)
                        scope.launch { _incoming.emit(msg) }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // swallow; reconnect handled at higher level
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
    }
}
