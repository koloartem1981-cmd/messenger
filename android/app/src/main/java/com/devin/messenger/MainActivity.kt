package com.devin.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.devin.messenger.data.SessionStore
import com.devin.messenger.ui.AppNav
import com.devin.messenger.ui.theme.MessengerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val sessionStore = SessionStore(applicationContext)
        setContent {
            MessengerTheme {
                val token = sessionStore.tokenFlow.collectAsState(initial = null)
                val user = sessionStore.userFlow.collectAsState(initial = null)
                Surface(modifier = Modifier) {
                    AppNav(
                        sessionStore = sessionStore,
                        token = token.value,
                        currentUser = user.value,
                    )
                }
            }
        }
    }
}
