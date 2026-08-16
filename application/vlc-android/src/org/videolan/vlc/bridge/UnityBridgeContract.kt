package org.videolan.vlc.bridge

/**
 * UnitySendMessage 的通信契约，集中维护 Unity 场景对象名和回调方法名。
 */
internal object UnityBridgeContract {
    object Target {
        const val PLAYBACK_BRIDGE = "VlcPlaybackBridge"
        const val LIBRARY_LAUNCHER = "VlcLibraryLauncher"
    }

    object Method {
        const val START_PLAY = "StartPlay"
        const val SHOW_UNITY_VIEW = "ShowUnityView"
        const val VLC_ACTIVITY_READY = "OnVlcActivityReady"
        const val ON_VIDEO_SIZE_CHANGED = "OnVideoSizeChanged"
        const val ON_STATE_CHANGED = "OnStateChanged"
        const val ON_TIME_CHANGED = "OnTimeChanged"
        const val ON_POSITION_CHANGED = "OnPositionChanged"
        const val ON_LENGTH_CHANGED = "OnLengthChanged"
        const val ON_PLAYBACK_RATE_CHANGED = "OnPlaybackRateChanged"
        const val ON_BUFFERING = "OnBuffering"
        const val ON_AUDIO_TRACKS_CHANGED = "OnAudioTracksChanged"
        const val ON_SUBTITLE_TRACKS_CHANGED = "OnSubtitleTracksChanged"
        const val ON_MEDIA_PARSE_FINISHED = "OnMediaParseFinished"
        const val ON_VIDEO_OUTPUT_SWITCH_EVENT = "OnVideoOutputSwitchEvent"
        const val ON_CHROMA_KEY_COLOR_EXTRACTED = "OnChromaKeyColorExtracted"
        const val CLEAR_PLAYBACK_SURFACE = "ClearPlaybackSurface"
    }
}
