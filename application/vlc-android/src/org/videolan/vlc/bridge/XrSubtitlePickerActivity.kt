/*
 * Copyright © 2026 XRVLC contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.videolan.vlc.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.videolan.vlc.gui.browser.EXTRA_MRL

class XrSubtitlePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(PlaybackServiceBridge.createSubtitleFilePickerIntent(), REQUEST_SUBTITLE_PICK)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SUBTITLE_PICK && resultCode == RESULT_OK) {
            PlaybackServiceBridge.addSubtitleTrackFromPicker(data?.getStringExtra(EXTRA_MRL))
        } else {
            Log.e(TAG, "Subtitle picker cancelled requestCode=$requestCode resultCode=$resultCode")
        }
        finish()
    }

    companion object {
        private const val TAG = "XrSubtitlePickerActivity"
        private const val REQUEST_SUBTITLE_PICK = 5107
    }
}
