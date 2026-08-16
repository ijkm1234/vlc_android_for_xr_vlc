/*
 * Copyright © 2026 XRVLC contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.videolan.vlc.bridge

import androidx.annotation.Keep

@Keep
data class SlaveDTO(
    val type: Int,
    val priority: Int,
    val uri: String
)

@Keep
data class MediaBridgeDTO(
    val uri: String,
    val index: Int
)
