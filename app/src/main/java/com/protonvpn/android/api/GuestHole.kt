/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.protonvpn.android.api

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import com.protonvpn.android.ProtonApplication
import com.protonvpn.android.R
import com.protonvpn.android.components.NotificationHelper
import com.protonvpn.android.logging.ProtonLogger
import com.protonvpn.android.models.config.VpnProtocol
import com.protonvpn.android.models.profiles.Profile
import com.protonvpn.android.models.vpn.Server
import com.protonvpn.android.ui.vpn.VpnPermissionActivityDelegate
import com.protonvpn.android.utils.Constants
import com.protonvpn.android.utils.FileUtils
import com.protonvpn.android.utils.ServerManager
import com.protonvpn.android.vpn.VpnConnectionManager
import com.protonvpn.android.vpn.VpnPermissionDelegate
import com.protonvpn.android.vpn.VpnState
import com.protonvpn.android.vpn.VpnStateMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.proton.core.network.domain.serverconnection.ApiConnectionListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import me.proton.core.network.domain.ApiResult
import me.proton.core.util.kotlin.DispatcherProvider
import javax.inject.Inject
import kotlin.coroutines.resume

class GuestHole @Inject constructor(
    @ApplicationContext val appContext: Context,
    private val scope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val serverManager: dagger.Lazy<ServerManager>,
    private val vpnMonitor: VpnStateMonitor,
    private val vpnConnectionManager: dagger.Lazy<VpnConnectionManager>,
    private val notificationHelper: NotificationHelper
) : ApiConnectionListener {

    private var lastGuestHoleServer: Server? = null

    private fun getGuestHoleServers(): List<Server> {
        lastGuestHoleServer?.let {
            return arrayListOf(it)
        }

        val servers =
            FileUtils.getObjectFromAssets(ListSerializer(Server.serializer()), GUEST_HOLE_SERVERS_ASSET)
        val shuffledServers = servers.shuffled().take(GUEST_HOLE_SERVER_COUNT)
        serverManager.get().setGuestHoleServers(shuffledServers)
        return shuffledServers
    }

    private suspend fun <T> executeConnected(
        vpnPermissionDelegate: VpnPermissionDelegate,
        server: Server,
        block: suspend () -> T
    ): Boolean {
        var connected = vpnMonitor.isConnected
        if (!connected) {
            val vpnStatus = vpnMonitor.status
            connected = withTimeoutOrNull(GUEST_HOLE_SERVER_TIMEOUT) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val profile = Profile.getTempProfile(server, serverManager.get())
                        .apply {
                            setProtocol(VpnProtocol.OpenVPN)
                            setGuestHole(true)
                        }
                    vpnConnectionManager.get().connect(vpnPermissionDelegate, profile, "Guest hole")
                    val observerJob = scope.launch {
                        vpnStatus.collect { newState ->
                            if (newState.state.let { it is VpnState.Connected || it is VpnState.Error }) {
                                coroutineContext.cancel()
                                continuation.resume(newState.state == VpnState.Connected)
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        observerJob.cancel()
                    }
                }
            } == true
        }
        if (connected) {
            block()
        }
        return connected
    }

    @VisibleForTesting
    fun getCurrentActivity(): Activity? = (appContext as ProtonApplication).foregroundActivity

    override suspend fun <T> onPotentiallyBlocked(
        path: String?,
        query: String?,
        backendCall: suspend () -> ApiResult<T>
    ): ApiResult<T>? {
        ProtonLogger.log("Guesthole for call: " + path + " with query: " + query)

        // Do not execute guesthole for calls running in background, due to inability to call permission intent
        val currentActivity: Activity = getCurrentActivity() ?: return null

        if (!isEligibleForGuestHole(path, query)) {
            ProtonLogger.log("Guesthole not available for this call: " + path)
            return null
        }
        val delegate = VpnPermissionActivityDelegate(currentActivity as ComponentActivity)

        val intent = vpnConnectionManager.get().prepare(delegate.getContext())

        // Ask for permissions and if granted execute original method and return it back to core
        return if (delegate.suspendForPermissions(intent)) {
            withTimeoutOrNull(GUEST_HOLE_ATTEMPT_TIMEOUT) {
                return@withTimeoutOrNull unblockCall(path, delegate, backendCall)
            }
        }
        else null
    }

    private suspend fun <T> unblockCall(
        path: String?,
        delegate: VpnPermissionActivityDelegate,
        backendCall: suspend () -> ApiResult<T>
    ): ApiResult<T>? {
        var result: ApiResult<T>? = null
        try {
            notificationHelper.showInformationNotification(
                appContext,
                appContext.getString(R.string.guestHoleNotificationContent),
                notificationId = Constants.NOTIFICATION_GUESTHOLE_ID
            )
            ProtonLogger.log("Guesthole Establishing hole for call: " + path)
            getGuestHoleServers().any { server ->
                executeConnected(delegate, server) {
                    // Add slight delay before retrying original call to avoid network timeout right after connection
                    delay(500)
                    lastGuestHoleServer = server
                    result = backendCall()
                    ProtonLogger.log("Guesthole succesful for call: " + path)
                    ProtonLogger.log("Guesthole result: " + result?.valueOrNull.toString())
                }
            }
        } finally {
            if (!vpnMonitor.isDisabled) {
                withContext(dispatcherProvider.Main) {
                    vpnConnectionManager.get().disconnectSync()
                }
            }
        }
        return result
    }

    private fun isEligibleForGuestHole(path: String?, query: String?): Boolean {
        // Only trigger guesthole for server list if it hasn't been downloaded before
        if (path == ONE_TIME_LOGICAL_CALL && serverManager.get().isDownloadedAtLeastOnce) return false

        // Do not run guesthole for domain call with type, as that is not necessary for the flow
        if (path == DOMAINS_CALL && query != null) return false

        return CORE_GUESTHOLE_CALLS.contains(path)
    }

    companion object {

        private const val GUEST_HOLE_SERVER_COUNT = 5
        private const val GUEST_HOLE_SERVER_TIMEOUT = 10_000L
        private const val GUEST_HOLE_ATTEMPT_TIMEOUT = 50_000L
        private const val GUEST_HOLE_SERVERS_ASSET = "GuestHoleServers.json"

        private const val ONE_TIME_LOGICAL_CALL = "/vpn/logicals"
        private const val DOMAINS_CALL = "/domains/available"
        private val CORE_GUESTHOLE_CALLS = listOf(
            "/auth/info",
            "/auth/modulus",
            "/auth/scopes",
            "/auth",
            "/auth/2fa",
            "/core/v4/validate/email",
            "/core/v4/validate/phone",
            "/users",
            "/users/available",
            "/v4/users",
            "/v4/users/external",
            "/addresses",
            "/addresses/setup",
            "/payments/v4/plans",
            "/payments/v4/tokens",
            "/payments/v4/methods",
            "/payments/v4/subscription",
            "/payments/v4/subscription/check",
            "/v4/users/code",
            "/v4/users/check",
            DOMAINS_CALL,

            // VPN specific calls

            "/vpn",
            ONE_TIME_LOGICAL_CALL
        )
    }
}
