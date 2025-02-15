/*
 * Copyright (c) 2023. Proton Technologies AG
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
package com.protonvpn.android.netshield

import com.protonvpn.android.R

sealed class NetShieldViewState {
    object UpgradePlusBanner : NetShieldViewState()
    object UpgradeBusinessBanner : NetShieldViewState()
    data class NetShieldState(
        val protocol: NetShieldProtocol,
        val netShieldStats: NetShieldStats
    ) : NetShieldViewState() {
        val isDisabled = protocol == NetShieldProtocol.DISABLED
        val isGreyedOut = protocol != NetShieldProtocol.ENABLED_EXTENDED
        val iconRes = when (protocol) {
            NetShieldProtocol.DISABLED -> R.drawable.ic_proton_shield
            NetShieldProtocol.ENABLED -> R.drawable.ic_proton_shield_half_filled
            NetShieldProtocol.ENABLED_EXTENDED -> R.drawable.ic_proton_shield_filled
        }
        val titleRes = when (protocol) {
            NetShieldProtocol.DISABLED -> R.string.netshield_status_off
            NetShieldProtocol.ENABLED, NetShieldProtocol.ENABLED_EXTENDED -> R.string.netshield_status_on
        }
    }
}
