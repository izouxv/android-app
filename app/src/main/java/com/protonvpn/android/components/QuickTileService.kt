/*
 * Copyright (c) 2018 Proton Technologies AG
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
package com.protonvpn.android.components

import android.graphics.drawable.Icon
import android.os.Build.VERSION_CODES
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.protonvpn.android.R
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.logging.ProtonLogger
import com.protonvpn.android.logging.UiConnect
import com.protonvpn.android.logging.UiDisconnect
import com.protonvpn.android.notifications.NotificationHelper
import com.protonvpn.android.tv.IsTvCheck
import com.protonvpn.android.vpn.ConnectTrigger
import com.protonvpn.android.vpn.DefaultAvailableConnection
import com.protonvpn.android.vpn.DisconnectTrigger
import com.protonvpn.android.vpn.VpnConnectionManager
import com.protonvpn.android.vpn.VpnState
import com.protonvpn.android.vpn.VpnStateMonitor
import com.protonvpn.android.vpn.VpnStatusProviderUI
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
@RequiresApi(VERSION_CODES.N)
class QuickTileService : TileService() {

    @Inject lateinit var defaultAvailableConnection: DefaultAvailableConnection
    @Inject lateinit var vpnStatusProviderUI: VpnStatusProviderUI
    @Inject lateinit var vpnConnectionManager: VpnConnectionManager
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var isTv: IsTvCheck
    @Inject lateinit var mainScope: CoroutineScope

    private var listeningScope : CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningScope = CoroutineScope(Job())

        val tile = qsTile
        if (tile != null) {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_status_information)
            bindToListener()
        }
    }

    override fun onStopListening() {
        listeningScope?.cancel()
        super.onStopListening()
    }

    private fun bindToListener() {
        listeningScope?.let { scope ->
            vpnStatusProviderUI.status.onEach {
                stateChanged(it)
            }.launchIn(scope)
        }
    }

    override fun onClick() {
        unlockAndRun {
            val isInactive = qsTile.state == Tile.STATE_INACTIVE
            mainScope.launch {
                if (isInactive) {
                    if (currentUser.isLoggedIn()) {
                        ProtonLogger.log(UiConnect, "quick tile")
                        vpnConnectionManager.connectInBackground(
                            defaultAvailableConnection(),
                            ConnectTrigger.QuickTile
                        )
                    } else {
                        startActivity(NotificationHelper.createMainActivityIntent(applicationContext, isTv()))
                    }
                } else {
                    ProtonLogger.log(UiDisconnect, "quick tile")
                    vpnConnectionManager.disconnect(DisconnectTrigger.QuickTile)
                }
            }
        }
    }

    private suspend fun stateChanged(vpnStatus: VpnStateMonitor.Status) {
        when (vpnStatus.state) {
            VpnState.Disabled -> {
                qsTile.label = getString(if (currentUser.isLoggedIn()) R.string.quickConnect else R.string.login)
                qsTile.state = Tile.STATE_INACTIVE
            }
            VpnState.CheckingAvailability,
            VpnState.ScanningPorts,
            VpnState.Reconnecting,
            VpnState.Connecting -> {
                qsTile.label = getString(R.string.state_connecting)
                qsTile.state = Tile.STATE_UNAVAILABLE
            }
            VpnState.WaitingForNetwork -> {
                qsTile.label = getString(R.string.state_nonetwork)
                qsTile.state = Tile.STATE_ACTIVE
            }
            is VpnState.Error -> {
                qsTile.label = getString(R.string.state_error)
                qsTile.state = Tile.STATE_UNAVAILABLE
            }
            VpnState.Connected -> {
                val server = vpnStatus.server
                val serverName = server!!.serverName
                qsTile.label = getString(R.string.tileConnected, serverName)
                qsTile.state = Tile.STATE_ACTIVE
            }
            VpnState.Disconnecting -> {
                qsTile.label = getString(R.string.state_disconnecting)
                qsTile.state = Tile.STATE_UNAVAILABLE
            }
        }
        qsTile.updateTile()
    }
}
