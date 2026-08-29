/*
 * Copyright © 2026 XRVLC contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.videolan.medialibrary.interfaces.media;

import org.videolan.libvlc.interfaces.IMedia;

/** Package-level access to MediaWrapper's slave list for playback preparation. */
public final class MediaWrapperSlavesAccessor {
    private MediaWrapperSlavesAccessor() {}

    public static void setSlaves(MediaWrapper media, IMedia.Slave[] slaves) {
        media.mSlaves = slaves;
    }
}
