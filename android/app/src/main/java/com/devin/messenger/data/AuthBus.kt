package com.devin.messenger.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Global event bus for auth failures. When any HTTP request comes back with a 401
 * we emit a signal here; the navigation layer observes this and forces the user
 * back to the auth screen (clearing the stale token from disk).
 */
object AuthBus {
    private val _unauthorized = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    fun signalUnauthorized() {
        _unauthorized.tryEmit(Unit)
    }
}
