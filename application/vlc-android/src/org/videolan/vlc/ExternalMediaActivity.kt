/*
 * *************************************************************************
 *  ExternalMediaActivity.kt
 * **************************************************************************
 *
 *  Thin XR entry point for media opened from Android file managers/choosers.
 *  It forwards the original ACTION_VIEW intent to Unity and never plays media
 *  in the native VLC UI.
 *
 *  Copyright © 2026 XRVLC contributors
 *  SPDX-License-Identifier: GPL-2.0-or-later
 *  ***************************************************************************
 */

package org.videolan.vlc

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log

private const val TAG = "VLC/ExternalMediaActivity"
private const val EXTRA_EXTERNAL_MEDIA = "org.videolan.vlc.extra.XR_EXTERNAL_MEDIA"
private const val EXTRA_EXTERNAL_MEDIA_TOKEN = "org.videolan.vlc.extra.XR_EXTERNAL_MEDIA_TOKEN"
private const val EXTRA_EXTERNAL_MEDIA_TITLE = "org.videolan.vlc.extra.XR_EXTERNAL_MEDIA_TITLE"

class ExternalMediaActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardToUnity(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        forwardToUnity(intent)
        finish()
    }

    private fun forwardToUnity(sourceIntent: Intent?) {
        if (sourceIntent?.action != Intent.ACTION_VIEW) {
            Log.w(TAG, "Ignoring non-view external media intent: ${sourceIntent?.action}")
            return
        }

        val resolvedType = sourceIntent.resolveType(this)
        if (resolvedType?.startsWith("video/", ignoreCase = true) != true) {
            Log.w(TAG, "Ignoring external media intent with non-video type: $resolvedType")
            return
        }

        val dataString = sourceIntent.dataString
        if (dataString.isNullOrEmpty()) {
            Log.w(TAG, "Ignoring external media intent without data")
            return
        }
        val displayName = sourceIntent.data?.let(::resolveDisplayName)
            ?: sourceIntent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()

        val unityIntent = Intent(sourceIntent).apply {
            component = ComponentName(packageName, "com.unity3d.player.UnityPlayerActivity")
            addFlags(sourceIntent.flags and grantUriPermissionFlags)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(EXTRA_EXTERNAL_MEDIA, true)
            putExtra(EXTRA_EXTERNAL_MEDIA_TOKEN, SystemClock.elapsedRealtime())
            if (!displayName.isNullOrBlank())
                putExtra(EXTRA_EXTERNAL_MEDIA_TITLE, displayName)
        }

        try {
            Log.i(TAG, "ExternalMediaActivity -> UnityPlayerActivity: $dataString")
            startActivity(unityIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward external media intent to Unity", e)
        }
    }

    private fun resolveDisplayName(uri: android.net.Uri): String? {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0) cursor.getString(columnIndex) else null
            }
        }.onFailure {
            Log.w(TAG, "Failed to resolve external media display name: $uri", it)
        }.getOrNull()
    }

    companion object {
        private val grantUriPermissionFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
