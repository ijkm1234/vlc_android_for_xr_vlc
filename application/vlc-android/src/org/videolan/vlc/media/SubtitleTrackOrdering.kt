/*
 * Copyright © 2026 XRVLC contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.videolan.vlc.media

import android.net.Uri
import org.videolan.libvlc.interfaces.IMedia

/** Sort external subtitle slaves before they are added to VLC. */
internal object SubtitleTrackOrdering {
    fun sortExternalSubtitlesInPlace(slaves: Array<IMedia.Slave>) {
        val sortedSubtitles = slaves
            .filter { it.type == IMedia.Slave.Type.Subtitle }
            .sortedWith { left, right -> compareNames(left.uri, right.uri) }
            .iterator()

        slaves.indices.forEach { index ->
            if (slaves[index].type == IMedia.Slave.Type.Subtitle)
                slaves[index] = sortedSubtitles.next()
        }
    }

    private fun compareNames(left: String, right: String): Int {
        val nameComparison = normalizeName(left).compareTo(normalizeName(right), ignoreCase = true)
        return if (nameComparison != 0) nameComparison else left.compareTo(right)
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
