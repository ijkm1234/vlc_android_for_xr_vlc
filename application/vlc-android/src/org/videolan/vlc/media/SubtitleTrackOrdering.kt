/*
 * Copyright © 2026 XRVLC contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.videolan.vlc.media

import android.net.Uri

internal data class SubtitleTrackOrderEntry(
    val id: String,
    val displayName: String
)

/** AAR 侧统一字幕轨道顺序，Unity 只消费这里生成的顺序。 */
internal object SubtitleTrackOrdering {
    private const val DISABLED_TRACK_ID = "-1"

    fun compareForDisplay(
        leftName: String,
        leftId: String,
        rightName: String,
        rightId: String
    ): Int {
        val leftDisabled = leftId == DISABLED_TRACK_ID
        val rightDisabled = rightId == DISABLED_TRACK_ID
        if (leftDisabled != rightDisabled) return if (leftDisabled) -1 else 1

        val nameComparison = normalizeName(leftName).compareTo(normalizeName(rightName), ignoreCase = true)
        return if (nameComparison != 0) nameComparison else leftId.compareTo(rightId)
    }

    fun firstSelectableTrackId(tracks: Iterable<SubtitleTrackOrderEntry>): String? {
        return tracks
            .filter { it.id != DISABLED_TRACK_ID }
            .sortedWith { left, right ->
                compareForDisplay(left.displayName, left.id, right.displayName, right.id)
            }
            .firstOrNull()
            ?.id
    }

    private fun normalizeName(value: String): String {
        val withoutQuery = value.substringBefore('?')
        val decoded = runCatching { Uri.decode(withoutQuery) }.getOrDefault(withoutQuery)
        return decoded
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
    }
}
