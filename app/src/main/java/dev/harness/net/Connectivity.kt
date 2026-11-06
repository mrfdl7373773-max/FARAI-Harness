package dev.harness.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Whether the phone has internet right now. Model calls and web tools wait on [awaitOnline]
 * instead of failing, so a run pauses while the connection is gone and carries on when it is back.
 * Starts as online; [start] wires it to Android's default network. Tests flip [set] by hand.
 */
object Connectivity {
    private val state = MutableStateFlow(true)
    val online: StateFlow<Boolean> = state
    val isOnline: Boolean get() = state.value

    fun set(value: Boolean) { state.value = value }

    /** Returns at once when online; otherwise suspends until the connection is back (cancellable). */
    suspend fun awaitOnline() { if (!state.value) state.first { it } }

    /** Suspends until the connection drops. */
    suspend fun awaitOffline() { state.first { !it } }

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        fun check(caps: NetworkCapabilities?) =
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        state.value = runCatching { check(cm.getNetworkCapabilities(cm.activeNetwork)) }.getOrDefault(true)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    state.value = runCatching { check(cm.getNetworkCapabilities(network)) }.getOrDefault(true)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { state.value = check(caps) }
                override fun onLost(network: Network) {
                    state.value = runCatching { check(cm.getNetworkCapabilities(cm.activeNetwork)) }.getOrDefault(false)
                }
                override fun onUnavailable() { state.value = false }
            })
        }
    }
}
