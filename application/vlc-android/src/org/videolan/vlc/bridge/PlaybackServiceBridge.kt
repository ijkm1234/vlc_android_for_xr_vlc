/*
 * Copyright © 2026 XRVLC contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.videolan.vlc.bridge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaWrapperImpl
import org.videolan.resources.AndroidDevices
import org.videolan.resources.AppContextProvider
import org.videolan.resources.VLCInstance
import org.videolan.tools.KEY_AUDIO_BOOST
import org.videolan.tools.KEY_SUBTITLES_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_SIZE
import org.videolan.tools.Settings
import org.videolan.tools.VIDEO_RATIO
import org.videolan.tools.putSingle
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.gui.browser.FilePickerActivity
import org.videolan.vlc.gui.browser.KEY_MEDIA
import org.videolan.vlc.repository.SlaveRepository
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.isSchemeFile
import org.videolan.vlc.util.isSchemeNetwork
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

object PlaybackServiceBridge : PlaybackService.Callback, IVLCVout.Callback, IVLCVout.OnNewVideoLayoutListener {
    private const val TAG = "PlaybackServiceBridge"
    private const val SURFACE_DEBUG_TAG = "XR_SURFACE_DEBUG"
    private const val SUBTITLE_RENDER_NATIVE = 0
    private const val SUBTITLE_RENDER_SPATIAL = 1
    private const val SUBTITLE_RENDER_OFF = 2
    private const val DEFAULT_SUBTITLE_FONT_SIZE = "16"
    private const val DEFAULT_SUBTITLE_OPACITY = 255
    private const val MIN_SUBTITLE_OPACITY = 50
    private const val SUBTITLE_STYLE_RESTART_DEBOUNCE_MS = 200L
    private const val COLOR_EXTRACTION_TIMEOUT_MS = 2_500L
    private const val VOUT_DETACH_TIMEOUT_MS = 1_500L
    private const val VOUT_ATTACH_TIMEOUT_MS = 5_000L
    private const val VOUT_POLL_INTERVAL_MS = 50L
    private const val PLAYBACK_SELECTION_UNKNOWN = -1
    private const val PLAYBACK_SELECTION_EMPTY = 0
    private const val PLAYBACK_SELECTION_ACTIVE = 1
    private const val PLAYBACK_SELECTION_QUERY_TIMEOUT_MS = 1_500L
    private const val PLAYBACK_SOURCE_EXTERNAL = "external"
    private const val MEDIA_TYPE_VIDEO = "video"
    private const val VLC_TASK_UNKNOWN = -1
    private const val VLC_TASK_HIDDEN = 0
    private const val VLC_TASK_VISIBLE = 1
    private const val VLC_RESTORE_COOLDOWN_MS = 500L
    private val supportedSubtitleFontSizes = setOf("40", "32", "25", "19", "16", "13", "10")
    private val subtitleStyleRestartRevision = AtomicLong(0L)
    private val subtitleStyleRestartMutex = Mutex()
    private data class VideoLayoutSize(
        val width: Int,
        val height: Int,
        val visibleWidth: Int,
        val visibleHeight: Int
    )

    private enum class VideoLayerOperation {
        RebuildLayer,
        ChangeLayer
    }

    private enum class VideoOutputSwitchPhase {
        AwaitVoutZero,
        AwaitAttach,
        AwaitVoutPositive,
        StartingMedia,
        AwaitMediaOpening
    }

    private data class VideoOutputSwitch(
        val token: Long,
        val operation: VideoLayerOperation,
        val mediaRequestId: Long = 0L,
        var phase: VideoOutputSwitchPhase,
        val rebuildInput: Boolean = false,
        val rebuildOutput: Boolean = false,
        val targetMediaUri: String? = null,
        val openingObservedWhileStarting: Boolean = false
    )

    private data class PendingMediaRequest(
        val mediaRequestId: Long,
        val uri: Uri,
        val dto: MediaBridgeDTO?,
        val forceUnmarkedAac4Ambisonics: Boolean,
        var flatVideo: Boolean = false,
        var parseResult: String? = null
    )

    private var playbackService: PlaybackService? = null
    @Volatile
    private var lastUnityVolumePercent = 100

    @Volatile
    private var videoSurface: Surface? = null

    @Volatile
    private var videoSurfaceFisheyeMappingEnabled = false

    @Volatile
    private var videoSurfaceChromaKeyEnabled = false

    @Volatile
    private var videoSurfaceColorExtractionActive = false

    @Volatile
    private var videoSurfaceMappingStereo = 0

    @Volatile
    private var videoSurfaceMappingContentWidth = 0

    @Volatile
    private var videoSurfaceMappingContentHeight = 0

    @Volatile
    private var videoSurfaceRotationDegrees = 0

    @Volatile
    private var videoSurfaceFisheyeProjectionFormula = 0

    @Volatile
    private var videoSurfaceChromaKeyRed = 0x2B / 255f

    @Volatile
    private var videoSurfaceChromaKeyGreen = 0xE6 / 255f

    @Volatile
    private var videoSurfaceChromaKeyBlue = 0x40 / 255f

    @Volatile
    private var videoSurfaceChromaKeyRange = 0.125f

    @Volatile
    private var videoSurfaceChromaKeyEdgeSmooth = 0.125f

    @Volatile
    private var videoSurfaceChromaKeyDespillStrength = 0.05f

    private var surfaceMapper: XrSurfaceMapper? = null

    @Volatile
    private var boundVideoInputSurface: Surface? = null

    @Volatile
    private var subtitleSurface: Surface? = null

    @Volatile
    private var lastAppliedRate = 1.0f

    @Volatile
    private var lastKnownPlaybackRate = 1.0f
    @Volatile
    private var subtitleRenderMode = SUBTITLE_RENDER_SPATIAL

    @Volatile
    private var subtitleStackOutside = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeVideoOutputSwitch: VideoOutputSwitch? = null
    private val queuedVideoOutputSwitches = ArrayDeque<VideoOutputSwitch>()
    private var nextMediaRequestId = 0L
    private val pendingMediaRequests = ArrayDeque<PendingMediaRequest>()
    private var activePendingMediaRequest: PendingMediaRequest? = null

    private fun surfaceDebug(message: String) {
        Log.e(SURFACE_DEBUG_TAG, "android_bridge $message")
    }

    private fun describeSurface(label: String, surface: Surface?): String {
        return "$label=$surface ${label}Valid=${surface?.isValid} " +
            "${label}Identity=${surface?.let { System.identityHashCode(it) }}"
    }

    private fun describePlaybackState(service: PlaybackService?): String {
        if (service == null) return "service=null pendingPlay=$pendingPlay"
        val player = service.mediaplayer
        val time = runCatching { service.getTime() }.getOrDefault(-1L)
        val length = runCatching { service.length }.getOrDefault(-1L)
        val rate = runCatching { service.rate }.getOrDefault(-1.0f)
        val hasMedia = runCatching { player.hasMedia() }.getOrDefault(false)
        val released = runCatching { player.isReleased }.getOrDefault(true)
        return "service=${System.identityHashCode(service)} isPlaying=${service.isPlaying} " +
            "isPaused=${service.isPaused} time=$time length=$length rate=$rate " +
            "hasMedia=$hasMedia released=$released pendingPlay=$pendingPlay"
    }

    fun bindService(service: PlaybackService) {
        this.playbackService = service
        service.addCallback(this)
        service.mediaplayer.vlcVout.addCallback(this)
        applyXrSubtitlePlayerConfiguration(service.mediaplayer, "bind-service")
        android.util.Log.e(TAG, "PlaybackService bound to Bridge")
        surfaceDebug(
            "bind_service service=${System.identityHashCode(service)} " +
                "voutAttached=${service.mediaplayer.vlcVout.areViewsAttached()} " +
                "voutCount=${service.mediaplayer.getVoutCount()}"
        )
    }

    private fun sendToUnity(methodName: String, message: String) {
        UnityMessageDispatcher.sendToPlayback(methodName, message)
    }

    private fun sendVideoOutputSwitchEvent(token: Long, state: String, reason: String? = null) {
        val payload = if (reason.isNullOrEmpty()) "$token|$state" else "$token|$state|$reason"
        val voutCount = playbackService?.mediaplayer?.getVoutCount() ?: 0
        surfaceDebug("video_output_switch event=$payload voutCount=$voutCount")
        sendToUnity(UnityBridgeContract.Method.ON_VIDEO_OUTPUT_SWITCH_EVENT, payload)
    }

    private fun failVideoOutputSwitch(token: Long, reason: String) {
        val active = activeVideoOutputSwitch
        if (active == null || active.token != token) return
        if (activePendingMediaRequest?.mediaRequestId == active.mediaRequestId)
            activePendingMediaRequest = null
        activeVideoOutputSwitch = null
        sendVideoOutputSwitchEvent(token, "failed", reason)
        startNextVideoOutputSwitch()
    }

    private fun scheduleVideoOutputSwitchTimeout(
        token: Long,
        phase: VideoOutputSwitchPhase,
        timeoutMs: Long,
        reason: String
    ) {
        mainHandler.postDelayed({
            val active = activeVideoOutputSwitch
            if (active?.token == token && active.phase == phase)
                failVideoOutputSwitch(token, reason)
        }, timeoutMs)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun sendPlaybackRateToUnity(rate: Float, reason: String) {
        val safeRate = if (!rate.isNaN() && !rate.isInfinite() && rate > 0f) rate else 1.0f
        lastKnownPlaybackRate = safeRate
        val message = String.format(Locale.US, "%.4f", safeRate)
        android.util.Log.e(TAG, "Playback rate changed reason=$reason rate=$message")
        sendToUnity(UnityBridgeContract.Method.ON_PLAYBACK_RATE_CHANGED, message)
    }

    private var pendingPlay = false
    private var pendingPickedSubtitleMrl: String? = null
    private var pendingPickedSubtitleBaselineCount = -1
    @Volatile
    private var pendingFlatVideo = false
    @Volatile
    private var pendingForceUnmarkedAac4Ambisonics = false
    @Volatile
    private var pendingForceUnmarkedAac4AmbisonicsUri: String? = null
    @Volatile
    private var restoreVlcAfterSystemPanelPending = false
    @Volatile
    private var restoreVlcNotBeforeElapsedRealtimeMs = 0L

    @JvmStatic
    fun restoreVlcTask(context: Context?): Boolean {
        val remainingCooldownMs = getVlcRestoreCooldownRemainingMs()
        if (remainingCooldownMs > 0L) {
            Log.i(TAG, "restoreVlcTask blocked by cooldown remainingMs=$remainingCooldownMs")
            return false
        }

        val baseContext = context ?: AppContextProvider.currentActivity ?: AppContextProvider.appContext
        val activityManager = baseContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false

        return runCatching {
            for (appTask in activityManager.appTasks) {
                val taskInfo = appTask.taskInfo
                if (!isVlcTask(taskInfo)) continue

                Log.e(TAG, "restoreVlcTask moving task to front top=${taskInfo.topActivity} base=${taskInfo.baseActivity}")
                appTask.moveToFront()
                synchronized(this) {
                    restoreVlcAfterSystemPanelPending = false
                    restoreVlcNotBeforeElapsedRealtimeMs = 0L
                }
                return true
            }

            Log.e(TAG, "restoreVlcTask found no existing VLC task")
            false
        }.getOrElse {
            Log.e(TAG, "restoreVlcTask failed", it)
            false
        }
    }

    /**
     * Records that the visible VLC task was temporarily moved behind Unity so the
     * PICO Home panel can be shown without overlapping the VLC 2D panel.
     */
    @JvmStatic
    fun markVlcTemporarilyHiddenForSystemPanel() {
        val restoreNotBefore = SystemClock.elapsedRealtime() + VLC_RESTORE_COOLDOWN_MS
        synchronized(this) {
            restoreVlcAfterSystemPanelPending = true
            restoreVlcNotBeforeElapsedRealtimeMs = restoreNotBefore
        }
        Log.i(
            TAG,
            "VLC task marked for restore after system panel; cooldownMs=$VLC_RESTORE_COOLDOWN_MS"
        )
    }

    @JvmStatic
    fun cancelVlcRestoreAfterSystemPanel() {
        synchronized(this) {
            restoreVlcAfterSystemPanelPending = false
            restoreVlcNotBeforeElapsedRealtimeMs = 0L
        }
        Log.i(TAG, "VLC task restore after system panel cancelled")
    }

    @JvmStatic
    fun getVlcRestoreCooldownRemainingMs(): Long {
        return synchronized(this) {
            (restoreVlcNotBeforeElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
    }

    /**
     * One-shot handoff to Unity. Unity keeps its own copy while the AAR cooldown
     * and XR focus state determine when restoration is allowed.
     */
    @JvmStatic
    fun consumeVlcRestoreAfterSystemPanel(): Boolean {
        val pending = synchronized(this) {
            val value = restoreVlcAfterSystemPanelPending
            restoreVlcAfterSystemPanelPending = false
            value
        }
        if (pending) Log.i(TAG, "VLC task restore after system panel consumed by Unity")
        return pending
    }

    /**
     * Returns the visibility of this application's VLC task.
     *
     * - 1: visible
     * - 0: absent or not visible
     * - -1: query failed
     */
    @JvmStatic
    fun getVlcTaskVisibilityState(context: Context?): Int {
        val baseContext = context ?: AppContextProvider.currentActivity ?: AppContextProvider.appContext
        val activityManager = baseContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return VLC_TASK_UNKNOWN

        return runCatching {
            val taskInfo = activityManager.appTasks
                .asSequence()
                .map { it.taskInfo }
                .firstOrNull(::isVlcTask)
                ?: return VLC_TASK_HIDDEN

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && taskInfo.isVisible) {
                VLC_TASK_VISIBLE
            } else {
                VLC_TASK_HIDDEN
            }
        }.getOrElse {
            Log.e(TAG, "getVlcTaskVisibilityState failed", it)
            VLC_TASK_UNKNOWN
        }
    }

    /**
     * Returns whether VLC owns a valid selected video playlist entry.
     *
     * - 1: mediaListSize > 0, playlist currentIndex is valid, and the selected item is video
     * - 0: no playback service exists, no entry is selected, or the selected item is not video
     * - -1: the main-thread query timed out or failed
     */
    @JvmStatic
    fun getPlaybackSelectionState(): Int {
        // The service is created lazily. Its absence is the normal initial state before
        // the user selects media, so it authoritatively means there is no active entry.
        val service = playbackService ?: return PLAYBACK_SELECTION_EMPTY
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return resolvePlaybackSelectionState(service)
        }

        var result = PLAYBACK_SELECTION_UNKNOWN
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = resolvePlaybackSelectionState(service)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(PLAYBACK_SELECTION_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Timed out waiting for playback selection state")
            return PLAYBACK_SELECTION_UNKNOWN
        }

        return result
    }

    private fun resolvePlaybackSelectionState(service: PlaybackService): Int {
        return runCatching {
            val playlistManager = service.playlistManager
            val currentIndex = playlistManager.currentIndex
            val currentMedia = if (currentIndex >= 0) {
                playlistManager.getMedia(currentIndex)
            } else {
                null
            }
            if (service.mediaListSize > 0 &&
                currentIndex != -1 &&
                currentMedia?.type == MediaWrapper.TYPE_VIDEO
            ) {
                PLAYBACK_SELECTION_ACTIVE
            } else {
                PLAYBACK_SELECTION_EMPTY
            }
        }.getOrElse {
            Log.e(TAG, "Failed to query playback selection state", it)
            PLAYBACK_SELECTION_UNKNOWN
        }
    }

    private fun isVlcTask(taskInfo: ActivityManager.RecentTaskInfo): Boolean {
        return isVlcActivityComponent(taskInfo.topActivity?.className) ||
                isVlcActivityComponent(taskInfo.baseActivity?.className)
    }

    private fun isVlcActivityComponent(className: String?): Boolean {
        return className?.startsWith("org.videolan.vlc.") == true
    }

    @JvmStatic
    fun preloadLocation(url: String): Long {
        android.util.Log.e(TAG, "[VLC-AAR-TRACE] [PlaybackServiceBridge] preloadLocation IN! Payload: $url")
        android.util.Log.e(TAG, "preloadLocation called with: $url")
        val payloadIsJson = url.trimStart().startsWith("{")
        surfaceDebug(
            "preload_location enter payloadLength=${url.length} payloadIsJson=$payloadIsJson " +
                "serviceNull=${playbackService == null}"
        )
        // Allocate the request at the JNI boundary, before a service-bind wait can
        // reorder two rapid Unity preload calls.
        val mediaRequestId = synchronized(PlaybackServiceBridge) { ++nextMediaRequestId }
        
        CoroutineScope(Dispatchers.Main).launch {
            var service = playbackService
            if (service == null) {
                android.util.Log.e(TAG, "PlaybackService is null, starting service...")
                surfaceDebug("preload_location service_null_starting_service")
                PlaybackService.start(AppContextProvider.appContext)
                // Wait until service is bound
                android.util.Log.e(TAG, "Waiting for PlaybackService to be bound...")
                service = PlaybackService.serviceFlow.first { it != null }
                android.util.Log.e(TAG, "PlaybackService bound successfully.")
                surfaceDebug("preload_location service_bound_after_start")
                playbackService = service
            }

            if (service == null) {
                android.util.Log.e(TAG, "Failed to start PlaybackService")
                surfaceDebug("preload_location abort service_still_null")
                return@launch
            }

            try {
                var urlStr = url
                var dto: MediaBridgeDTO? = null
                
                android.util.Log.e(TAG, "Parsing payload: $url")
                
                // 尝试解析 JSON
                if (url.startsWith("{")) {
                    try {
                        val json = org.json.JSONObject(url)
                        var uriStr = json.optString("uri", "")
                        if (uriStr.isNotEmpty()) {
                            val decodedUriStr = android.net.Uri.decode(uriStr)
                            android.util.Log.e("VLC-AAR-TRACE", "[PlaybackServiceBridge] Original URI: $uriStr")
                            android.util.Log.e("VLC-AAR-TRACE", "[PlaybackServiceBridge] Decoded URI: $decodedUriStr")
                            
                            urlStr = uriStr
                            android.util.Log.e(TAG, "Successfully parsed JSON. Extracted URI: $urlStr")
                            
                            // Unity only echoes media identity. Playback metadata stays in VLC.
                            dto = MediaBridgeDTO(
                                uri = uriStr,
                                index = json.optInt("index", 0),
                                source = json.optString("source", ""),
                                mediaType = json.optString("mediaType", "")
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to parse JSON payload", e)
                    }
                } else {
                    android.util.Log.e(TAG, "Payload does not start with '{', treating as plain URI.")
                }
                
                var uri = Uri.parse(urlStr)
                android.util.Log.e(TAG, "Parsed Uri object: $uri")
                if (uri.scheme == null && urlStr.startsWith("/")) {
                    uri = Uri.fromFile(java.io.File(urlStr))
                    android.util.Log.e(TAG, "Converted local path to URI with scheme: $uri")
                }
                val pendingRequest = PendingMediaRequest(
                    mediaRequestId = mediaRequestId,
                    uri = uri,
                    dto = dto,
                    forceUnmarkedAac4Ambisonics = isPanoramicUri(uri.toString())
                )
                pendingMediaRequests.addLast(pendingRequest)
                surfaceDebug(
                    "preload_location queued request=$mediaRequestId uri=$uri " +
                        "dtoUri=${dto?.uri} source=${dto?.source} mediaType=${dto?.mediaType} " +
                        "queued=${pendingMediaRequests.size}"
                )
                
                // 使用 IO 线程进行异步解析，避免阻塞主线程（特别是针对 SMB 网络流）
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val libVlc = org.videolan.resources.VLCInstance.getInstance(AppContextProvider.appContext)
                    val parseUri = if (uri.scheme == "content") FileUtils.getUri(uri) ?: uri else uri
                    surfaceDebug("preload_parse source original=$uri resolved=$parseUri")
                    val media = org.videolan.libvlc.Media(libVlc, parseUri)
                    
                    // 首先尝试传统的静态解析，并尝试获取本地字幕(FetchLocal)
                    val parsed = media.parse(IMedia.Parse.ParseNetwork or IMedia.Parse.FetchLocal)
                    android.util.Log.e(TAG, "Static Media parse returned: $parsed")
                    surfaceDebug("preload_parse static_result=$parsed uri=$uri")
                    
                    var width = 0
                    var height = 0
                    var visibleWidth = 0
                    var visibleHeight = 0
                    var hasParsedSize = false
                    // projection: 0=Rectangular(flat), 1=EquiRectangular(360), 2=Cubemap
                    var projectionInt = 0
                    var duration = 0L

                    for (i in 0 until media.trackCount) {
                        val track = media.getTrack(i)
                        if (track is IMedia.VideoTrack) {
                            if (track.width > 0 && track.height > 0) {
                                width = track.width
                                height = track.height
                                visibleWidth = track.width
                                visibleHeight = track.height
                                projectionInt = track.projection
                                hasParsedSize = true
                                break
                            }
                        }
                    }

                    duration = media.duration

                    if (!hasParsedSize) {
                        android.util.Log.e(TAG, "Static parse failed to get video size, attempting dummy surface decoder extraction...")
                        surfaceDebug("preload_parse static_size_missing_try_dummy_surface")
                        
                        // 创建一个临时的 MediaPlayer 和一个假的 Surface (1x1)
                        media.addOption(":no-audio")
                        media.addOption(":no-spu")
                        val dummyMediaPlayer = MediaPlayer(libVlc)
                        dummyMediaPlayer.media = media
                        
                        val dummySurfaceTexture = SurfaceTexture(0)
                        dummySurfaceTexture.setDefaultBufferSize(1, 1)
                        val dummySurface = Surface(dummySurfaceTexture)
                        
                        // 绑定假画布
                        dummyMediaPlayer.vlcVout.setVideoSurface(dummySurface, null)
                        
                        // 准备一个信号量来等待回调
                        val sizeDeferred = kotlinx.coroutines.CompletableDeferred<VideoLayoutSize>()
                        
                        val layoutListener = object : IVLCVout.OnNewVideoLayoutListener {
                            override fun onNewVideoLayout(vout: IVLCVout?, w: Int, h: Int, vw: Int, vh: Int, sarNum: Int, sarDen: Int) {
                                if (w > 0 && h > 0 && !sizeDeferred.isCompleted) {
                                    android.util.Log.e(TAG, "Dummy decoder extracted real size: ${w}x${h}, visible=${vw}x${vh}")
                                    surfaceDebug("preload_parse dummy_layout raw=${w}x${h} visible=${vw}x${vh}")
                                    sizeDeferred.complete(VideoLayoutSize(w, h, vw, vh))
                                }
                            }
                        }
                        
                        dummyMediaPlayer.vlcVout.attachViews(layoutListener)
                        
                        // 开始硬解，但不发声
                        dummyMediaPlayer.setVolume(0)
                        dummyMediaPlayer.play()
                        
                        // 给定 5 秒超时等待解码器吐出尺寸
                        val realSize = withTimeoutOrNull(5000L) {
                            sizeDeferred.await()
                        }
                        
                        // 清理现场：停播放、解绑、销毁
                        dummyMediaPlayer.stop()
                        dummyMediaPlayer.vlcVout.detachViews()
                        dummyMediaPlayer.release()
                        dummySurface.release()
                        dummySurfaceTexture.release()
                        
                        if (realSize != null) {
                            width = realSize.width
                            height = realSize.height
                            visibleWidth = realSize.visibleWidth
                            visibleHeight = realSize.visibleHeight
                            hasParsedSize = true
                            android.util.Log.e(TAG, "Successfully extracted size via dummy surface: ${width}x${height}, visible=${visibleWidth}x${visibleHeight}")
                            surfaceDebug("preload_parse dummy_success raw=${width}x${height} visible=${visibleWidth}x${visibleHeight}")
                        } else {
                            android.util.Log.e(TAG, "Timeout waiting for dummy decoder to extract size")
                            surfaceDebug("preload_parse dummy_timeout")
                        }
                    }
                    
                    media.release()
                    
                    if (!hasParsedSize) {
                        android.util.Log.e(TAG, "All parse methods failed; reporting invalid 0x0 size")
                        surfaceDebug("preload_parse failed_reporting_invalid_zero_size")
                    }
                    if (visibleWidth <= 0) visibleWidth = width
                    if (visibleHeight <= 0) visibleHeight = height

                    // 将解析结果通知 Unity（包含尺寸、投影类型、时长）
                    val projectionStr = when (projectionInt) {
                        1 -> "360"   // IMedia.VideoTrack.Projection.EquiRectangular
                        else -> "flat"
                    }
                    val parseResultJson = """{"uri":${org.json.JSONObject.quote(uri.toString())},"width":$width,"height":$height,"visibleWidth":$visibleWidth,"visibleHeight":$visibleHeight,"projection":"$projectionStr","duration":$duration,"mediaRequestId":$mediaRequestId}"""
                    surfaceDebug(
                        "preload_parse result uri=$uri raw=${width}x${height} visible=${visibleWidth}x${visibleHeight} " +
                            "projection=$projectionStr duration=$duration hasParsedSize=$hasParsedSize"
                    )

                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        val queuedRequest = pendingMediaRequests.firstOrNull {
                            it.mediaRequestId == mediaRequestId
                        }
                        if (queuedRequest == null) {
                            surfaceDebug("preload_parse ignored missing_request request=$mediaRequestId")
                            return@withContext
                        }
                        queuedRequest.flatVideo = projectionStr == "flat"
                        queuedRequest.parseResult = parseResultJson
                        android.util.Log.e(TAG, "OnMediaParseFinished: $parseResultJson")
                        surfaceDebug("preload_parse send_unity_parse_finished request=$mediaRequestId")
                        sendToUnity(UnityBridgeContract.Method.ON_MEDIA_PARSE_FINISHED, parseResultJson)
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error in preloadLocation", e)
                surfaceDebug("preload_location exception=${e.message}")
            }
        }
        return mediaRequestId
    }

    @JvmStatic
    fun shouldForceUnmarkedAac4Ambisonics(uri: Uri?): Boolean {
        val uriString = uri?.toString() ?: return false
        if (pendingForceUnmarkedAac4Ambisonics &&
            sameMediaUri(pendingForceUnmarkedAac4AmbisonicsUri, uriString))
            return true

        return isPanoramicUri(uriString)
    }

    private fun sameMediaUri(left: String?, right: String?): Boolean {
        if (left.isNullOrEmpty() || right.isNullOrEmpty()) return false
        return Uri.decode(left) == Uri.decode(right)
    }

    private fun isPanoramicUri(uriString: String?): Boolean {
        val name = fileNameLower(uriString)
        if (name.isEmpty()) return false

        return name.contains("equirect") ||
            name.contains("vr360") ||
            name.contains("360vr") ||
            name.contains("3d360") ||
            containsToken(name, "360") ||
            name.contains("fisheye") ||
            name.contains("vr180") ||
            name.contains("180vr") ||
            name.contains("3d180") ||
            containsToken(name, "180")
    }

    private fun fileNameLower(uriString: String?): String {
        if (uriString.isNullOrEmpty()) return ""
        val decoded = Uri.decode(uriString)
        val slash = maxOf(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\'))
        return (if (slash >= 0) decoded.substring(slash + 1) else decoded)
            .lowercase(Locale.US)
    }

    private fun containsToken(name: String, token: String): Boolean {
        val idx = name.indexOf(token)
        if (idx < 0) return false
        val leftOk = idx == 0 || !name[idx - 1].isLetterOrDigit()
        val right = idx + token.length
        val rightOk = right >= name.length || !name[right].isLetterOrDigit()
        return leftOk && rightOk
    }

    @JvmStatic
    fun beginRebuildLayer(token: Long, mediaRequestId: Long, rebuildInput: Boolean, rebuildOutput: Boolean) {
        queueVideoLayerTransaction(
            VideoOutputSwitch(
                token = token,
                operation = VideoLayerOperation.RebuildLayer,
                mediaRequestId = mediaRequestId,
                phase = VideoOutputSwitchPhase.AwaitAttach,
                rebuildInput = rebuildInput,
                rebuildOutput = rebuildOutput
            )
        )
    }

    @JvmStatic
    fun beginChangeLayer(token: Long, rebuildOutput: Boolean) {
        queueVideoLayerTransaction(
            VideoOutputSwitch(
                token = token,
                operation = VideoLayerOperation.ChangeLayer,
                phase = VideoOutputSwitchPhase.AwaitAttach,
                rebuildOutput = rebuildOutput
            )
        )
    }

    private fun queueVideoLayerTransaction(transaction: VideoOutputSwitch) {
        runOnMain {
            queuedVideoOutputSwitches.addLast(transaction)
            surfaceDebug(
                "video_layer queued token=${transaction.token} operation=${transaction.operation} " +
                    "mediaRequest=${transaction.mediaRequestId} rebuildInput=${transaction.rebuildInput} " +
                    "rebuildOutput=${transaction.rebuildOutput} queued=${queuedVideoOutputSwitches.size}"
            )
            startNextVideoOutputSwitch()
        }
    }

    @JvmStatic
    fun attachRebuildLayer(
        token: Long,
        mediaRequestId: Long,
        surface: Surface?,
        fisheyeMappingEnabled: Boolean,
        chromaKeyEnabled: Boolean,
        resumeCurrentMedia: Boolean,
        stereo: Int,
        contentWidth: Int,
        contentHeight: Int
    ) {
        attachVideoLayer(
            token,
            VideoLayerOperation.RebuildLayer,
            mediaRequestId,
            surface,
            fisheyeMappingEnabled,
            chromaKeyEnabled,
            resumeCurrentMedia,
            stereo,
            contentWidth,
            contentHeight
        )
    }

    @JvmStatic
    fun attachChangeLayer(
        token: Long,
        surface: Surface?,
        fisheyeMappingEnabled: Boolean,
        chromaKeyEnabled: Boolean,
        stereo: Int,
        contentWidth: Int,
        contentHeight: Int
    ) {
        attachVideoLayer(
            token,
            VideoLayerOperation.ChangeLayer,
            0L,
            surface,
            fisheyeMappingEnabled,
            chromaKeyEnabled,
            false,
            stereo,
            contentWidth,
            contentHeight
        )
    }

    private fun attachVideoLayer(
        token: Long,
        operation: VideoLayerOperation,
        mediaRequestId: Long,
        surface: Surface?,
        fisheyeMappingEnabled: Boolean,
        chromaKeyEnabled: Boolean,
        resumeCurrentMedia: Boolean,
        stereo: Int,
        contentWidth: Int,
        contentHeight: Int
    ) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            attachVideoLayerOnMain(
                token,
                operation,
                mediaRequestId,
                surface,
                fisheyeMappingEnabled,
                chromaKeyEnabled,
                resumeCurrentMedia,
                stereo,
                contentWidth,
                contentHeight
            )
        }
    }

    private suspend fun attachVideoLayerOnMain(
        token: Long,
        operation: VideoLayerOperation,
        mediaRequestId: Long,
        surface: Surface?,
        fisheyeMappingEnabled: Boolean,
        chromaKeyEnabled: Boolean,
        resumeCurrentMedia: Boolean,
        stereo: Int,
        contentWidth: Int,
        contentHeight: Int
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Layer attach must run on Android main thread" }
        val active = activeVideoOutputSwitch
        if (active == null || active.token != token || active.operation != operation ||
            active.phase != VideoOutputSwitchPhase.AwaitAttach || active.mediaRequestId != mediaRequestId) {
            sendVideoOutputSwitchEvent(token, "failed", "attach-without-detach")
            return
        }
        if (surface == null || !surface.isValid) {
            failVideoOutputSwitch(token, "invalid-output-surface")
            return
        }

        val attachingSwitch = active.copy(phase = VideoOutputSwitchPhase.AwaitVoutPositive)
        activeVideoOutputSwitch = attachingSwitch
        videoSurface = surface
        videoSurfaceFisheyeMappingEnabled = fisheyeMappingEnabled
        videoSurfaceChromaKeyEnabled = chromaKeyEnabled
        videoSurfaceMappingStereo = stereo
        videoSurfaceMappingContentWidth = contentWidth
        videoSurfaceMappingContentHeight = contentHeight
        surfaceDebug(
            "video_layer attach token=$token operation=$operation mediaRequest=$mediaRequestId " +
                "${describeSurface("video", surface)} fisheye=$fisheyeMappingEnabled " +
                "chromaKey=$chromaKeyEnabled stereo=$stereo content=${contentWidth}x$contentHeight " +
                "rebuildInput=${active.rebuildInput} rebuildOutput=${active.rebuildOutput}"
        )

        val mapperInput = try {
            configurePersistentSurfaceMapper(surface)
        } catch (firstError: Throwable) {
            Log.e(TAG, "mapper attach failed token=$token operation=$operation", firstError)
            if (!active.rebuildInput) {
                surfaceDebug(
                    "video_layer mapper_rebuild_requires_input token=$token operation=$operation " +
                        "${describeSurface("boundInput", boundVideoInputSurface)}"
                )
                failVideoOutputSwitch(token, "input-rebuild-required")
                return
            }

            try {
                releaseSurfaceMapper("mapper-attach-recovery-$token")
                configurePersistentSurfaceMapper(surface)
            } catch (recoveryError: Throwable) {
                Log.e(TAG, "mapper recovery failed token=$token operation=$operation", recoveryError)
                failVideoOutputSwitch(token, "mapper-rebuild-exception")
                return
            }
        }

        val vout = playbackService?.mediaplayer?.vlcVout
        if (active.rebuildInput) {
            runCatching {
                configureVoutSurfaces("${operation.name.lowercase()}-$token")
            }.onFailure {
                Log.e(TAG, "input layer attach failed token=$token operation=$operation", it)
                failVideoOutputSwitch(token, "input-attach-exception")
                return
            }
            if (mapperInput !== boundVideoInputSurface || !mapperInput.isValid || vout?.areViewsAttached() != true) {
                surfaceDebug(
                    "video_layer input_attach_invalid token=$token operation=$operation " +
                        "attached=${vout?.areViewsAttached()} " +
                        "bound=${describeSurface("boundInput", boundVideoInputSurface)} " +
                        "resolved=${describeSurface("resolvedInput", mapperInput)}"
                )
                failVideoOutputSwitch(token, "input-attach-invalid")
                return
            }
        } else if (mapperInput !== boundVideoInputSurface || !mapperInput.isValid || vout?.areViewsAttached() != true) {
            surfaceDebug(
                "video_layer input_identity_changed token=$token operation=$operation " +
                    "attached=${vout?.areViewsAttached()} " +
                    "bound=${describeSurface("boundInput", boundVideoInputSurface)} " +
                    "resolved=${describeSurface("resolvedInput", mapperInput)}"
            )
            failVideoOutputSwitch(token, "input-rebuild-required")
            return
        }

        if (operation == VideoLayerOperation.ChangeLayer) {
            completeVideoOutputAttach(attachingSwitch)
            return
        }

        if (mediaRequestId > 0L) {
            scheduleVideoOutputSwitchTimeout(
                token,
                VideoOutputSwitchPhase.StartingMedia,
                VOUT_ATTACH_TIMEOUT_MS,
                "media-start-timeout"
            )
            startPendingMediaForVideoOutput(token, mediaRequestId)
            scheduleVideoOutputSwitchTimeout(
                token,
                VideoOutputSwitchPhase.AwaitMediaOpening,
                VOUT_ATTACH_TIMEOUT_MS,
                "media-opening-timeout"
            )
        } else if (active.rebuildInput && resumeCurrentMedia) {
            surfaceDebug("video_layer resume_current_media token=$token before_vout_wait")
            playbackService?.play()
            advanceVideoOutputSwitchFromVlc("attach-immediate")
            scheduleVideoOutputSwitchTimeout(
                token,
                VideoOutputSwitchPhase.AwaitVoutPositive,
                VOUT_ATTACH_TIMEOUT_MS,
                "vout-positive-timeout"
            )
        } else {
            completeVideoOutputAttach(attachingSwitch)
        }
    }

    @JvmStatic
    fun cancelVideoOutputSwitch(token: Long) {
        runOnMain {
            if (activeVideoOutputSwitch?.token == token) {
                surfaceDebug("video_output_switch cancel token=$token")
                activeVideoOutputSwitch = null
                startNextVideoOutputSwitch()
                return@runOnMain
            }
            queuedVideoOutputSwitches.removeAll { it.token == token }
        }
    }

    @JvmStatic
    fun cancelPendingMediaRequests() {
        runOnMain {
            surfaceDebug(
                "media_requests cancel_all pending=${pendingMediaRequests.size} " +
                    "active=${activePendingMediaRequest?.mediaRequestId}"
            )
            pendingMediaRequests.clear()
            activePendingMediaRequest = null
            pendingFlatVideo = false
            pendingForceUnmarkedAac4Ambisonics = false
            pendingForceUnmarkedAac4AmbisonicsUri = null
        }
    }

    @JvmStatic
    fun cancelPendingMediaRequest(mediaRequestId: Long) {
        if (mediaRequestId <= 0L) return
        runOnMain {
            val removed = pendingMediaRequests.removeAll { it.mediaRequestId == mediaRequestId }
            if (activePendingMediaRequest?.mediaRequestId == mediaRequestId)
                activePendingMediaRequest = null
            surfaceDebug(
                "media_requests cancel request=$mediaRequestId removed=$removed " +
                    "remaining=${pendingMediaRequests.size}"
            )
        }
    }

    private fun completeVideoOutputDetach(token: Long) {
        val active = activeVideoOutputSwitch
        if (active == null || active.token != token || active.phase != VideoOutputSwitchPhase.AwaitVoutZero) return
        boundVideoInputSurface = null
        runCatching { surfaceMapper?.releaseInputLayer() }
            .onFailure {
                Log.e(TAG, "input layer release failed token=$token", it)
                failVideoOutputSwitch(token, "input-release-exception")
                return
            }
        if (active.rebuildOutput) {
            runCatching { surfaceMapper?.detachOutput() }
                .onFailure {
                    Log.e(TAG, "mapper output detach failed token=$token", it)
                    failVideoOutputSwitch(token, "mapper-output-detach-exception")
                    return
                }
            videoSurface = null
        }
        activeVideoOutputSwitch = active.copy(phase = VideoOutputSwitchPhase.AwaitAttach)
        sendVideoOutputSwitchEvent(token, "detached")
    }

    private fun startNextVideoOutputSwitch() {
        if (activeVideoOutputSwitch != null) return
        val next = queuedVideoOutputSwitches.pollFirst() ?: return
        val vout = playbackService?.mediaplayer?.vlcVout
        if (vout == null) {
            sendVideoOutputSwitchEvent(next.token, "failed", "vout-null")
            startNextVideoOutputSwitch()
            return
        }

        val mapper = surfaceMapper
        val mapperInput = mapper?.inputSurface
        val voutCount = currentVoutCount()
        val activeSwitch = next.copy(
            phase = if (next.rebuildInput) VideoOutputSwitchPhase.AwaitVoutZero else VideoOutputSwitchPhase.AwaitAttach
        )
        activeVideoOutputSwitch = activeSwitch
        surfaceDebug(
            "video_layer begin token=${next.token} operation=${next.operation} attached=${vout.areViewsAttached()} " +
                "mediaRequest=${next.mediaRequestId} voutCount=$voutCount rebuildInput=${next.rebuildInput} " +
                "rebuildOutput=${next.rebuildOutput} " +
                "${describeSurface("video", videoSurface)} ${describeSurface("mapperInput", mapperInput)}"
        )

        if (!next.rebuildInput &&
            (mapperInput == null || !mapperInput.isValid || mapperInput !== boundVideoInputSurface || !vout.areViewsAttached())) {
            failVideoOutputSwitch(next.token, "input-rebuild-required")
            return
        }

        if (!next.rebuildInput && next.rebuildOutput) {
            runCatching {
                checkNotNull(mapper) { "Persistent mapper disappeared before output detach" }
                    .detachOutput()
            }.onFailure {
                Log.e(TAG, "mapper output detach failed token=${next.token}", it)
                failVideoOutputSwitch(next.token, "mapper-output-detach-exception")
                return
            }
            videoSurface = null
            activeVideoOutputSwitch = activeSwitch.copy(phase = VideoOutputSwitchPhase.AwaitAttach)
            surfaceDebug(
                "video_layer mapper_output_detached token=${next.token} operation=${next.operation} " +
                    "voutCount=${currentVoutCount()} ${describeSurface("mapperInput", mapperInput)}"
            )
            sendVideoOutputSwitchEvent(next.token, "detached")
            return
        }

        if (!next.rebuildInput) {
            sendVideoOutputSwitchEvent(next.token, "detached")
            return
        }

        if (vout.areViewsAttached()) vout.detachViews()

        if (voutCount == 0) {
            completeVideoOutputDetach(next.token)
            return
        }

        pollVideoOutputSwitch(next.token, VideoOutputSwitchPhase.AwaitVoutZero)
        scheduleVideoOutputSwitchTimeout(
            next.token,
            VideoOutputSwitchPhase.AwaitVoutZero,
            VOUT_DETACH_TIMEOUT_MS,
            "vout-zero-timeout"
        )
    }

    private fun currentVoutCount(): Int = playbackService?.mediaplayer?.getVoutCount() ?: 0

    private fun pollVideoOutputSwitch(token: Long, phase: VideoOutputSwitchPhase) {
        mainHandler.postDelayed({
            val active = activeVideoOutputSwitch
            if (active?.token != token || active.phase != phase) return@postDelayed
            advanceVideoOutputSwitchFromVlc("poll")
            val current = activeVideoOutputSwitch
            if (current?.token == token && current.phase == phase)
                pollVideoOutputSwitch(token, phase)
        }, VOUT_POLL_INTERVAL_MS)
    }

    private fun advanceVideoOutputSwitchFromVlc(source: String) {
        val active = activeVideoOutputSwitch ?: return
        val voutCount = currentVoutCount()
        surfaceDebug(
            "video_output_switch vout source=$source token=${active.token} operation=${active.operation} " +
                "phase=${active.phase} count=$voutCount mediaRequest=${active.mediaRequestId}"
        )
        when {
            active.phase == VideoOutputSwitchPhase.AwaitVoutZero && voutCount == 0 ->
                completeVideoOutputDetach(active.token)
            active.phase == VideoOutputSwitchPhase.AwaitVoutPositive && voutCount > 0 ->
                completeVideoOutputAttach(active)
        }
    }

    private fun completeVideoOutputAttach(active: VideoOutputSwitch) {
        if (activeVideoOutputSwitch?.token != active.token) return
        surfaceDebug(
            "video_layer complete token=${active.token} operation=${active.operation} " +
                "mediaRequest=${active.mediaRequestId} phase=${active.phase} " +
                "${describeSurface("input", boundVideoInputSurface)} ${describeSurface("output", videoSurface)}"
        )
        activeVideoOutputSwitch = null
        if (active.rebuildOutput && !active.rebuildInput)
            updateSubtitleSurfaceSafely("${active.operation.name.lowercase()}-complete-${active.token}")
        if (active.mediaRequestId > 0L)
            activePendingMediaRequest = null
        sendVideoOutputSwitchEvent(active.token, "ready")
        startNextVideoOutputSwitch()
    }

    private suspend fun startPendingMediaForVideoOutput(token: Long, mediaRequestId: Long) {
        if (mediaRequestId <= 0L)
            return
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media start must run on Android main thread" }
        val active = activeVideoOutputSwitch
        if (active?.token != token || active.mediaRequestId != mediaRequestId) return

        val pm = playbackService?.playlistManager
        val pendingRequest = pendingMediaRequests.peekFirst()
        if (pendingRequest == null || pendingRequest.mediaRequestId != mediaRequestId) {
            failVideoOutputSwitch(token, "request-not-head")
            return
        }
        if (pm == null) {
            failVideoOutputSwitch(token, "playlist-null")
            return
        }

        pendingMediaRequests.removeFirst()
        activePendingMediaRequest = pendingRequest
        pendingFlatVideo = pendingRequest.flatVideo
        pendingForceUnmarkedAac4Ambisonics = pendingRequest.forceUnmarkedAac4Ambisonics
        pendingForceUnmarkedAac4AmbisonicsUri = pendingRequest.uri.toString()
        activeVideoOutputSwitch = active.copy(
            phase = VideoOutputSwitchPhase.StartingMedia,
            targetMediaUri = pendingRequest.uri.toString()
        )

        try {
            var startMethod = "load"
            val dto = pendingRequest.dto
            if (dto != null) {
                val resolvedIndex = resolvePlaylistIndex(pm, dto)
                if (resolvedIndex >= 0) {
                    if (dto.isExternalVideo())
                        pm.getMedia(resolvedIndex)?.type = MediaWrapper.TYPE_VIDEO
                    startMethod = "playIndex"
                    surfaceDebug(
                        "video_layer media_start_serial token=$token request=$mediaRequestId " +
                            "method=playIndex index=$resolvedIndex forcePlay=true"
                    )
                    pm.playIndex(resolvedIndex, 0, false, false, true)
                } else {
                    val uri = pendingRequest.uri
                    surfaceDebug("video_layer media_start_serial token=$token request=$mediaRequestId method=load uri=$uri")
                    val media = MLServiceLocator.getAbstractMediaWrapper(uri).apply {
                        if (dto.isExternalVideo()) {
                            type = MediaWrapper.TYPE_VIDEO
                            surfaceDebug(
                                "video_layer external_media_type_applied token=$token request=$mediaRequestId type=video"
                            )
                        }
                    }
                    pm.load(listOf(media), 0, avoidErasingStop = true, forcePlay = true)
                }
            } else {
                val uri = pendingRequest.uri
                surfaceDebug("video_layer media_start_serial token=$token request=$mediaRequestId method=load uri=$uri")
                val media = MLServiceLocator.getAbstractMediaWrapper(uri)
                pm.load(listOf(media), 0, avoidErasingStop = true, forcePlay = true)
            }

            val starting = activeVideoOutputSwitch
            if (starting?.token != token || starting.mediaRequestId != mediaRequestId ||
                starting.phase != VideoOutputSwitchPhase.StartingMedia)
                return
            val awaiting = starting.copy(phase = VideoOutputSwitchPhase.AwaitMediaOpening)
            activeVideoOutputSwitch = awaiting
            surfaceDebug(
                "video_layer media_start_committed token=$token request=$mediaRequestId method=$startMethod " +
                    "phase=${awaiting.phase} openingObserved=${awaiting.openingObservedWhileStarting}"
            )
            if (awaiting.openingObservedWhileStarting)
                completeRebuildLayerOnOpening()
        } catch (error: Throwable) {
            Log.e(TAG, "serial media start failed token=$token request=$mediaRequestId", error)
            failVideoOutputSwitch(token, "media-start-exception")
        }
    }

    @JvmStatic
    fun setVideoSurface(surface: Surface?) {
        android.util.Log.e(TAG, "setVideoSurface called with surface: $surface")
        surfaceDebug(
            "set_video_surface enter ${describeSurface("video", surface)} " +
                "thread=${Thread.currentThread().name} serviceNull=${playbackService == null} " +
                "previous=${describeSurface("previousVideo", videoSurface)} " +
                "subtitle=${describeSurface("subtitle", subtitleSurface)}"
        )
        videoSurface = surface
        if (surface == null) {
            subtitleSurface = null
            videoSurfaceFisheyeMappingEnabled = false
            videoSurfaceChromaKeyEnabled = false
            videoSurfaceColorExtractionActive = false
        }
        // The legacy setter is retained only for release-only detaches. New video
        // output attachment must use attachRebuildLayer/attachChangeLayer with a token.
        if (surface == null) {
            configureVoutSurfacesOnMainBlocking("video-surface-release")
            releaseSurfaceMapper("video-surface-null")
        }
        surfaceDebug(
            "set_video_surface after_configure ${describeSurface("video", videoSurface)} " +
                describeSurface("subtitle", subtitleSurface)
        )
    }

    @JvmStatic
    fun setVideoSurfaceMapping(fisheyeMappingEnabled: Boolean, chromaKeyEnabled: Boolean, stereo: Int, contentWidth: Int, contentHeight: Int) {
        android.util.Log.e(
            TAG,
            "setVideoSurfaceMapping fisheye=$fisheyeMappingEnabled chromaKey=$chromaKeyEnabled " +
                "stereo=$stereo content=${contentWidth}x$contentHeight " +
                "video=$videoSurface videoValid=${videoSurface?.isValid}"
        )
        surfaceDebug(
            "set_video_surface_mapping enter fisheye=$fisheyeMappingEnabled chromaKey=$chromaKeyEnabled " +
                "stereo=$stereo content=${contentWidth}x$contentHeight " +
                "${describeSurface("video", videoSurface)}"
        )
        videoSurfaceFisheyeMappingEnabled = fisheyeMappingEnabled
        videoSurfaceChromaKeyEnabled = chromaKeyEnabled
        videoSurfaceMappingStereo = stereo
        videoSurfaceMappingContentWidth = contentWidth
        videoSurfaceMappingContentHeight = contentHeight

        val currentOutput = videoSurface
        if (currentOutput?.isValid == true && activeVideoOutputSwitch == null) {
            runCatching { configurePersistentSurfaceMapper(currentOutput) }
                .onFailure { handleSurfaceMapperConfigurationFailure("mapping-update", it) }
        }
    }

    @JvmStatic
    fun setVideoSurfaceRotation(degrees: Int) {
        var normalized = degrees % 360
        if (normalized > 180) normalized -= 360
        if (normalized < -180) normalized += 360
        val steps = if (normalized >= 0) {
            (normalized + 45) / 90
        } else {
            (normalized - 45) / 90
        }
        videoSurfaceRotationDegrees = steps * 90
        surfaceMapper?.updateRotation(videoSurfaceRotationDegrees)
        surfaceDebug("set_video_surface_rotation degrees=$videoSurfaceRotationDegrees mapper=${surfaceMapper != null}")
    }

    @JvmStatic
    fun setVideoSurfaceProcessingParameters(
        fisheyeProjectionFormula: Int,
        keyRed: Float,
        keyGreen: Float,
        keyBlue: Float,
        colorRange: Float,
        edgeSmooth: Float,
        despillStrength: Float
    ) {
        videoSurfaceFisheyeProjectionFormula = fisheyeProjectionFormula.coerceIn(0, 3)
        videoSurfaceChromaKeyRed = keyRed.coerceIn(0f, 1f)
        videoSurfaceChromaKeyGreen = keyGreen.coerceIn(0f, 1f)
        videoSurfaceChromaKeyBlue = keyBlue.coerceIn(0f, 1f)
        videoSurfaceChromaKeyRange = colorRange.coerceIn(0f, 0.25f)
        videoSurfaceChromaKeyEdgeSmooth = edgeSmooth.coerceIn(0f, 0.25f)
        videoSurfaceChromaKeyDespillStrength = despillStrength.coerceIn(0f, 0.1f)
        surfaceMapper?.updateProcessingParameters(
            videoSurfaceFisheyeProjectionFormula,
            videoSurfaceChromaKeyRed,
            videoSurfaceChromaKeyGreen,
            videoSurfaceChromaKeyBlue,
            videoSurfaceChromaKeyRange,
            videoSurfaceChromaKeyEdgeSmooth,
            videoSurfaceChromaKeyDespillStrength
        )
        surfaceDebug(
            "set_video_surface_processing formula=$videoSurfaceFisheyeProjectionFormula " +
                "key=($videoSurfaceChromaKeyRed,$videoSurfaceChromaKeyGreen,$videoSurfaceChromaKeyBlue) " +
                "range=$videoSurfaceChromaKeyRange edgeSmooth=$videoSurfaceChromaKeyEdgeSmooth " +
                "despill=$videoSurfaceChromaKeyDespillStrength " +
                "mapper=${surfaceMapper != null} surfaceRebuild=false"
        )
    }

    @JvmStatic
    fun requestVideoSurfaceChromaKeyColorExtraction() {
        mainHandler.post {
            val mapper = surfaceMapper
            if (mapper == null) {
                UnityMessageDispatcher.sendToPlayback(
                    UnityBridgeContract.Method.ON_CHROMA_KEY_COLOR_EXTRACTED,
                    "error|mapper-unavailable-enable-chroma-first"
                )
                return@post
            }

            val completed = AtomicBoolean(false)
            mapper.requestDominantColor { color ->
                mainHandler.post {
                    finishVideoSurfaceColorExtraction(color, completed)
                }
            }
            mainHandler.postDelayed(
                { finishVideoSurfaceColorExtraction(null, completed) },
                COLOR_EXTRACTION_TIMEOUT_MS
            )
        }
    }

    private fun finishVideoSurfaceColorExtraction(
        color: Int?,
        completed: AtomicBoolean
    ) {
        if (!completed.compareAndSet(false, true)) return
        val payload = if (color == null) {
            "error|insufficient-edge-color"
        } else {
            String.format(Locale.US, "ok|%06X", color and 0xFFFFFF)
        }
        UnityMessageDispatcher.sendToPlayback(
            UnityBridgeContract.Method.ON_CHROMA_KEY_COLOR_EXTRACTED,
            payload
        )
    }

    @JvmStatic
    fun setSubtitleSurface(surface: Surface?) {
        android.util.Log.e(
            TAG,
            "XR_SUB_SURFACE setSubtitleSurface called with surface=$surface valid=${surface?.isValid} " +
                "identity=${surface?.let { System.identityHashCode(it) }} " +
                "videoSurface=$videoSurface videoValid=${videoSurface?.isValid} " +
                "mode=$subtitleRenderMode pendingFlatVideo=$pendingFlatVideo"
        )
        surfaceDebug(
            "set_subtitle_surface enter ${describeSurface("subtitle", surface)} " +
                "thread=${Thread.currentThread().name} serviceNull=${playbackService == null} " +
                "video=${describeSurface("video", videoSurface)} mode=$subtitleRenderMode pendingFlatVideo=$pendingFlatVideo"
        )
        subtitleSurface = surface
        if (surface == null) applyXrSubtitleSurfaceEnabled(false, "subtitle-surface-clear")
        updateSubtitleSurfaceOnMainBlocking("subtitle_live_update")
        if (surface != null) applyXrSubtitleSurfaceEnabled(shouldEnableXrSubtitleSurface(), "subtitle-surface-set")
        surfaceDebug(
            "set_subtitle_surface after_update ${describeSurface("subtitle", subtitleSurface)} " +
                "video=${describeSurface("video", videoSurface)} enabled=${shouldEnableXrSubtitleSurface()}"
        )
    }

    @JvmStatic
    fun setSubtitleSurfacePolicy(stackOutside: Boolean) {
        android.util.Log.e(TAG, "setSubtitleSurfacePolicy stackOutside=$stackOutside")
        subtitleStackOutside = stackOutside
        playbackService?.mediaplayer?.setXrSubtitleStackOutside(stackOutside)
    }

    @JvmStatic
    fun setSubtitleSurfaceEnabled(enabled: Boolean) {
        applyXrSubtitleSurfaceEnabled(enabled, "unity-explicit")
    }

    private fun shouldEnableXrSubtitleSurface(): Boolean {
        return subtitleRenderMode == SUBTITLE_RENDER_SPATIAL && subtitleSurface?.isValid == true
    }

    private fun applyXrSubtitlePlayerConfiguration(player: MediaPlayer, reason: String) {
        val surfaceEnabled = shouldEnableXrSubtitleSurface()
        val stackApplied = player.setXrSubtitleStackOutside(subtitleStackOutside)
        val surfaceApplied = player.setXrSubtitleSurfaceEnabled(surfaceEnabled)
        Log.e(
            TAG,
            "XR_SUB_PLAYER_CONFIG reason=$reason player=${System.identityHashCode(player)} " +
                "stackOutside=$subtitleStackOutside stackApplied=$stackApplied " +
                "surfaceEnabled=$surfaceEnabled surfaceApplied=$surfaceApplied"
        )
    }

    private fun applyXrSubtitleSurfaceEnabled(enabled: Boolean, reason: String) {
        Log.e(
            TAG,
            "XR_SUB_SURFACE_ENABLED reason=$reason enabled=$enabled mode=$subtitleRenderMode " +
                "subtitle=$subtitleSurface subtitleValid=${subtitleSurface?.isValid}"
        )
        playbackService?.mediaplayer?.setXrSubtitleSurfaceEnabled(enabled)
    }

    @JvmStatic
    fun setSubtitleDelay(delayUs: Long) {
        android.util.Log.e(TAG, "setSubtitleDelay delayUs=$delayUs")
        CoroutineScope(Dispatchers.Main).launch {
            playbackService?.setSpuDelay(delayUs)
        }
    }

    @JvmStatic
    fun getSubtitleDelay(): Long {
        val delayUs = playbackService?.spuDelay ?: 0L
        android.util.Log.e(TAG, "getSubtitleDelay delayUs=$delayUs")
        return delayUs
    }

    @JvmStatic
    fun setAudioDelay(delayUs: Long) {
        android.util.Log.e(TAG, "setAudioDelay delayUs=$delayUs")
        CoroutineScope(Dispatchers.Main).launch {
            playbackService?.setAudioDelay(delayUs)
        }
    }

    @JvmStatic
    fun getAudioDelay(): Long {
        val delayUs = playbackService?.audioDelay ?: 0L
        android.util.Log.e(TAG, "getAudioDelay delayUs=$delayUs")
        return delayUs
    }

    @JvmStatic
    fun openSubtitlePicker() {
        android.util.Log.e(TAG, "openSubtitlePicker")
        val intent = Intent(AppContextProvider.appContext, XrSubtitlePickerActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            AppContextProvider.appContext.startActivity(intent)
        }.onFailure {
            Log.e(TAG, "openSubtitlePicker failed", it)
        }
    }

    internal fun createSubtitleFilePickerIntent(): Intent {
        val intent = Intent(AppContextProvider.appContext, FilePickerActivity::class.java)
        intent.putExtra(KEY_MEDIA, createSubtitlePickerParentMedia())
        return intent
    }

    private fun createSubtitlePickerParentMedia(): MediaWrapper {
        val location = playbackService?.currentMediaLocation
            ?: playbackService?.currentMediaWrapper?.uri?.toString()
            ?: activePendingMediaRequest?.dto?.uri
            ?: pendingMediaRequests.peekFirst()?.dto?.uri
            ?: return createInternalStorageMediaWrapper()
        val uri = Uri.parse(location)
        if (!uri.scheme.isSchemeFile() && !uri.scheme.isSchemeNetwork())
            return createInternalStorageMediaWrapper()
        val parent = FileUtils.getParent(uri.toString()) ?: return createInternalStorageMediaWrapper()
        return MediaWrapperImpl(parent.toUri())
    }

    private fun createInternalStorageMediaWrapper() = MediaWrapperImpl("file://${AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY}".toUri())

    internal fun addSubtitleTrackFromPicker(mrl: String?) {
        if (mrl.isNullOrEmpty()) {
            Log.e(TAG, "addSubtitleTrackFromPicker ignored empty mrl")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            if (service == null) {
                Log.e(TAG, "addSubtitleTrackFromPicker ignored because PlaybackService is null mrl=$mrl")
                return@launch
            }

            val subtitleUri = mrl.toUri()
            val resolvedUri = kotlinx.coroutines.withContext(Dispatchers.IO) {
                FileUtils.getUri(subtitleUri)
            } ?: subtitleUri
            pendingPickedSubtitleMrl = mrl
            pendingPickedSubtitleBaselineCount = service.spuTracks?.size ?: 0
            Log.e(
                TAG,
                "addSubtitleTrackFromPicker mrl=$mrl resolvedUri=$resolvedUri " +
                    "select=false baseline=$pendingPickedSubtitleBaselineCount"
            )
            service.addSubtitleTrack(resolvedUri, false)
            service.currentMediaWrapper?.let {
                SlaveRepository.getInstance(AppContextProvider.appContext)
                    .saveSlave(it.location, IMedia.Slave.Type.Subtitle, 2, mrl)
            }
            mainHandler.postDelayed({ selectPendingPickedSubtitleTrack("picker-fallback") }, 350)
        }
    }

    private fun configureVoutSurfacesOnMainBlocking(reason: String) {
        Log.e(TAG, "configureVoutSurfacesOnMainBlocking entry reason=$reason onMain=${Looper.myLooper() == Looper.getMainLooper()}")
        surfaceDebug(
            "configure_blocking enter reason=$reason onMain=${Looper.myLooper() == Looper.getMainLooper()} " +
                "serviceNull=${playbackService == null} ${describeSurface("video", videoSurface)} " +
                "${describeSurface("subtitle", subtitleSurface)}"
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            configureVoutSurfacesSafely(reason)
            return
        }

        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                configureVoutSurfacesSafely(reason)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(1500L, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Timed out waiting for configureVoutSurfaces reason=$reason")
            surfaceDebug("configure_blocking timeout reason=$reason")
        }
    }

    private fun configureVoutSurfacesSafely(reason: String) {
        runCatching {
            configureVoutSurfaces(reason)
        }.onFailure {
            Log.e(TAG, "configureVoutSurfaces failed reason=$reason", it)
        }
    }

    private fun configureVoutSurfaces(reason: String) {
        val player = playbackService?.mediaplayer
        if (player != null)
            applyXrSubtitlePlayerConfiguration(player, "configure-vout-$reason")
        val vout = player?.vlcVout
        if (vout != null) {
            val currentVideoSurface = videoSurface
            val currentSubtitleSurface = subtitleSurface
            val videoSurfaceForVlc = resolveVideoSurfaceForVlc(currentVideoSurface)
            surfaceDebug(
                "configure_vout enter reason=$reason attachedBefore=${vout.areViewsAttached()} " +
                    "${describeSurface("videoOutput", currentVideoSurface)} ${describeSurface("videoInput", videoSurfaceForVlc)} " +
                    "${describeSurface("subtitle", currentSubtitleSurface)} fisheye=$videoSurfaceFisheyeMappingEnabled " +
                    "chromaKey=$videoSurfaceChromaKeyEnabled " +
                    "switch=${activeVideoOutputSwitch?.token}:${activeVideoOutputSwitch?.phase}"
            )
            android.util.Log.e(
                TAG,
                "video_rebind configureVoutSurfaces reason=$reason video=$currentVideoSurface videoValid=${currentVideoSurface?.isValid} " +
                    "videoForVlc=$videoSurfaceForVlc videoForVlcValid=${videoSurfaceForVlc?.isValid} " +
                    "fisheye=$videoSurfaceFisheyeMappingEnabled chromaKey=$videoSurfaceChromaKeyEnabled " +
                    "subtitle=$currentSubtitleSurface subtitleValid=${currentSubtitleSurface?.isValid} " +
                    "subtitleIdentity=${currentSubtitleSurface?.let { System.identityHashCode(it) }} " +
                    "mode=$subtitleRenderMode pendingFlatVideo=$pendingFlatVideo attachedBefore=${vout.areViewsAttached()}"
            )

            if (vout.areViewsAttached()) {
                android.util.Log.e(TAG, "video_rebind configureVoutSurfaces detaching existing VLCVout views before rebinding reason=$reason")
                surfaceDebug("configure_vout detach_existing reason=$reason")
                vout.detachViews()
                boundVideoInputSurface = null
            }

            if (videoSurfaceForVlc == null) {
                android.util.Log.e(
                    TAG,
                    "Surface detached from VLCVout; subtitleSurface=$currentSubtitleSurface " +
                        "subtitleValid=${currentSubtitleSurface?.isValid}"
                )
                boundVideoInputSurface = null
                surfaceDebug("configure_vout abort video_null reason=$reason")
                return
            }

            surfaceDebug("configure_vout before_set_video ${describeSurface("videoInput", videoSurfaceForVlc)}")
            vout.setVideoSurface(videoSurfaceForVlc, null)
            boundVideoInputSurface = videoSurfaceForVlc
            surfaceDebug("configure_vout after_set_video attached=${vout.areViewsAttached()}")
            if (currentSubtitleSurface != null) {
                android.util.Log.e(
                    TAG,
                    "XR_SUB_SURFACE Calling vout.setSubtitlesSurface with subtitle=$currentSubtitleSurface " +
                        "identity=${System.identityHashCode(currentSubtitleSurface)} " +
                        "subtitleValid=${currentSubtitleSurface.isValid}"
                )
                surfaceDebug("configure_vout before_set_subtitle ${describeSurface("subtitle", currentSubtitleSurface)}")
                vout.setSubtitlesSurface(currentSubtitleSurface, null)
                android.util.Log.e(
                    TAG,
                    "XR_SUB_SURFACE Subtitle surface configured for VLCVout; " +
                        "identity=${System.identityHashCode(currentSubtitleSurface)}"
                )
                surfaceDebug("configure_vout after_set_subtitle")
            } else {
                android.util.Log.e(TAG, "No subtitle surface provided for VLCVout binding")
                surfaceDebug("configure_vout no_subtitle_surface")
            }

            surfaceDebug("configure_vout before_attach_views")
            vout.attachViews(this)
            android.util.Log.e(TAG, "Surfaces attached to VLCVout video=$videoSurfaceForVlc output=$currentVideoSurface subtitle=$currentSubtitleSurface")
            surfaceDebug("configure_vout after_attach attached=${vout.areViewsAttached()}")

        } else {
            android.util.Log.e(
                TAG,
                "VLCVout is null; playbackServiceNull=${playbackService == null} " +
                    "mediaPlayerNull=${playbackService?.mediaplayer == null} " +
                    "videoSurface=$videoSurface videoValid=${videoSurface?.isValid} " +
                    "subtitleSurface=$subtitleSurface subtitleValid=${subtitleSurface?.isValid}"
            )
            surfaceDebug(
                "configure_vout abort vout_null serviceNull=${playbackService == null} " +
                    "${describeSurface("video", videoSurface)} ${describeSurface("subtitle", subtitleSurface)}"
            )
        }
    }

    private fun resolveVideoSurfaceForVlc(outputSurface: Surface?): Surface? {
        if (outputSurface == null) {
            surfaceDebug("surface_mapper resolve output_null")
            return null
        }
        return configurePersistentSurfaceMapper(outputSurface)
    }

    private fun configurePersistentSurfaceMapper(outputSurface: Surface): Surface {
        check(outputSurface.isValid) { "Invalid mapper output surface" }
        val mapper = surfaceMapper ?: XrSurfaceMapper(::handleSurfaceMapperFailure).also {
            surfaceMapper = it
            surfaceDebug("surface_mapper created persistentInput=true")
        }
        mapper.configure(
            outputSurface,
            videoSurfaceFisheyeMappingEnabled,
            videoSurfaceChromaKeyEnabled,
            videoSurfaceMappingStereo,
            videoSurfaceMappingContentWidth,
            videoSurfaceMappingContentHeight
        )
        mapper.updateProcessingParameters(
            videoSurfaceFisheyeProjectionFormula,
            videoSurfaceChromaKeyRed,
            videoSurfaceChromaKeyGreen,
            videoSurfaceChromaKeyBlue,
            videoSurfaceChromaKeyRange,
            videoSurfaceChromaKeyEdgeSmooth,
            videoSurfaceChromaKeyDespillStrength
        )
        mapper.updateRotation(videoSurfaceRotationDegrees)
        val inputSurface = checkNotNull(mapper.inputSurface) { "Mapper input surface was not created" }
        check(inputSurface.isValid) { "Mapper input surface is invalid" }
        surfaceDebug(
            "surface_mapper resolved persistentInput=true ${describeSurface("output", outputSurface)} " +
                "${describeSurface("input", inputSurface)} fisheye=$videoSurfaceFisheyeMappingEnabled " +
                "chromaKey=$videoSurfaceChromaKeyEnabled stereo=$videoSurfaceMappingStereo " +
                "content=${videoSurfaceMappingContentWidth}x$videoSurfaceMappingContentHeight " +
                "rotation=$videoSurfaceRotationDegrees " +
                "formula=$videoSurfaceFisheyeProjectionFormula " +
                "key=($videoSurfaceChromaKeyRed,$videoSurfaceChromaKeyGreen,$videoSurfaceChromaKeyBlue) " +
                "range=$videoSurfaceChromaKeyRange edgeSmooth=$videoSurfaceChromaKeyEdgeSmooth " +
                "despill=$videoSurfaceChromaKeyDespillStrength"
        )
        return inputSurface
    }

    private fun handleSurfaceMapperConfigurationFailure(reason: String, error: Throwable) {
        Log.e(TAG, "surface mapper configuration failed reason=$reason", error)
        surfaceDebug("surface_mapper configuration_failed reason=$reason exception=$error")
    }

    private fun handleSurfaceMapperFailure(mapper: XrSurfaceMapper, error: Throwable) {
        Log.e(TAG, "surface mapper runtime render failure", error)
        mainHandler.post {
            if (surfaceMapper !== mapper) return@post
            val inputStillBound = mapper.inputSurface === boundVideoInputSurface
            surfaceDebug("surface_mapper runtime_failure inputStillBound=$inputStillBound exception=$error")
            if (inputStillBound) {
                playbackService?.pause()
            } else {
                releaseSurfaceMapper("runtime-render-error")
            }
            activeVideoOutputSwitch?.let { failVideoOutputSwitch(it.token, "surface-mapper-runtime-error") }
        }
    }

    private fun releaseSurfaceMapper(reason: String) {
        if (surfaceMapper == null) return
        surfaceDebug("surface_mapper release reason=$reason")
        surfaceMapper?.release()
        surfaceMapper = null
    }

    private fun updateSubtitleSurfaceOnMainBlocking(reason: String) {
        Log.e(TAG, "updateSubtitleSurfaceOnMainBlocking entry reason=$reason onMain=${Looper.myLooper() == Looper.getMainLooper()}")
        surfaceDebug(
            "subtitle_update_blocking enter reason=$reason onMain=${Looper.myLooper() == Looper.getMainLooper()} " +
                "${describeSurface("video", videoSurface)} ${describeSurface("subtitle", subtitleSurface)}"
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateSubtitleSurfaceSafely(reason)
            return
        }

        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                updateSubtitleSurfaceSafely(reason)
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(1500L, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Timed out waiting for updateSubtitleSurface reason=$reason")
            surfaceDebug("subtitle_update_blocking timeout reason=$reason")
        }
    }

    private fun updateSubtitleSurfaceSafely(reason: String) {
        runCatching {
            updateSubtitleSurface(reason)
        }.onFailure {
            Log.e(TAG, "updateSubtitleSurface failed reason=$reason", it)
        }
    }

    private fun updateSubtitleSurface(reason: String) {
        if (activeVideoOutputSwitch != null) {
            surfaceDebug(
                "subtitle_update cached_during_video_output_switch reason=$reason " +
                    "token=${activeVideoOutputSwitch?.token} ${describeSurface("subtitle", subtitleSurface)}"
            )
            return
        }
        val vout = playbackService?.mediaplayer?.vlcVout
        if (vout == null) {
            Log.e(TAG, "subtitle_live_update skipped reason=$reason because VLCVout is null subtitle=$subtitleSurface")
            surfaceDebug("subtitle_update skipped_vout_null reason=$reason ${describeSurface("subtitle", subtitleSurface)}")
            return
        }

        val currentSubtitleSurface = subtitleSurface
        Log.e(
            TAG,
            "subtitle_live_update reason=$reason subtitle=$currentSubtitleSurface " +
                "subtitleValid=${currentSubtitleSurface?.isValid} " +
                "subtitleIdentity=${currentSubtitleSurface?.let { System.identityHashCode(it) }} " +
                "video=$videoSurface videoValid=${videoSurface?.isValid} " +
                "attached=${vout.areViewsAttached()} mode=$subtitleRenderMode"
        )
        surfaceDebug(
            "subtitle_update enter reason=$reason attached=${vout.areViewsAttached()} " +
                "${describeSurface("video", videoSurface)} ${describeSurface("subtitle", currentSubtitleSurface)}"
        )

        if (!vout.areViewsAttached()) {
            Log.e(TAG, "subtitle_live_update cached because video output is not attached")
            surfaceDebug("subtitle_update cached_video_not_attached")
            return
        }

        surfaceDebug("subtitle_update before_replace ${describeSurface("subtitle", currentSubtitleSurface)}")
        vout.replaceSubtitlesSurface(currentSubtitleSurface, null)
        surfaceDebug("subtitle_update after_replace attached=${vout.areViewsAttached()}")

    }

    @JvmStatic
    fun play() {
        android.util.Log.e(TAG, "play called")
        surfaceDebug("control_play enter ${describePlaybackState(playbackService)}")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            surfaceDebug("control_play main_before ${describePlaybackState(service)}")
            if (service == null) {
                surfaceDebug("control_play abort service_null")
                return@launch
            }
            val playlistManager = service.playlistManager
            val currentMedia = playlistManager.getCurrentMedia()
            if (playlistManager.hasCurrentMedia()
                && currentMedia?.hasFlag(MediaWrapper.MEDIA_FROM_START) == true
            ) {
                surfaceDebug("control_play replay_from_start index=${playlistManager.currentIndex}")
                playlistManager.playIndex(playlistManager.currentIndex, forcePlay = true)
            } else {
                service.play()
            }
            surfaceDebug("control_play main_after ${describePlaybackState(service)}")
        }
    }

    @JvmStatic
    fun isPlaying(): Boolean = playbackService?.mediaplayer?.isPlaying() == true

    @JvmStatic
    fun getPlayerState(): Int = playbackService?.mediaplayer?.getPlayerState() ?: -1

    @JvmStatic
    fun getTime(): Long = playbackService?.mediaplayer?.getTime() ?: 0L

    @JvmStatic
    fun getLength(): Long = playbackService?.mediaplayer?.getLength() ?: 0L

    @JvmStatic
    fun replayFromStart() {
        surfaceDebug("control_replay_from_start enter ${describePlaybackState(playbackService)}")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            if (service == null) {
                surfaceDebug("control_replay_from_start abort service_null")
                return@launch
            }
            if (!service.playlistManager.hasCurrentMedia()) {
                surfaceDebug("control_replay_from_start abort no_current_playlist_item")
                return@launch
            }
            service.playlistManager.playIndex(service.playlistManager.currentIndex, forcePlay = true)
            surfaceDebug("control_replay_from_start main_after ${describePlaybackState(service)}")
        }
    }

    @JvmStatic
    fun pause() {
        android.util.Log.e(TAG, "pause called")
        surfaceDebug("control_pause enter ${describePlaybackState(playbackService)}")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            surfaceDebug("control_pause main_before ${describePlaybackState(service)}")
            if (service == null) {
                surfaceDebug("control_pause abort service_null")
                return@launch
            }
            service.pause()
            surfaceDebug("control_pause main_after ${describePlaybackState(service)}")
        }
    }

    @JvmStatic
    fun stop() {
        android.util.Log.e(TAG, "stop called")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            service?.stop()
        }
    }

    @JvmStatic
    fun next() {
        android.util.Log.e(TAG, "next called")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService ?: return@launch
            if (!service.hasNext()) {
                android.util.Log.w(TAG, "next ignored: no next media")
                return@launch
            }
            service.next()
        }
    }

    @JvmStatic
    fun previous() {
        android.util.Log.e(TAG, "previous called")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService ?: return@launch
            if (!service.hasPrevious()) {
                android.util.Log.w(TAG, "previous ignored: no previous media")
                return@launch
            }
            service.previous(false)
        }
    }

    @JvmStatic
    fun setRepeatMode(mode: Int) {
        android.util.Log.e(TAG, "setRepeatMode called with mode: $mode")
        CoroutineScope(Dispatchers.Main).launch {
            playbackService?.repeatType = mode
        }
    }

    @JvmStatic
    fun setShuffle(shuffle: Boolean) {
        android.util.Log.e(TAG, "setShuffle called with shuffle: $shuffle")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            if (service != null && service.isShuffling != shuffle) {
                service.shuffle()
            }
        }
    }
    
    @JvmStatic
    fun seek(position: Float) {
        surfaceDebug("control_seek enter requested=$position ${describePlaybackState(playbackService)}")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            if (service == null) {
                surfaceDebug("control_seek abort service_null requested=$position")
                return@launch
            }
            val safePosition = position.coerceIn(0F, 1F)
            val length = service.length
            surfaceDebug(
                "control_seek main_before requested=$position safe=$safePosition length=$length " +
                    describePlaybackState(service)
            )
            if (length > 0L) {
                val targetTime = (length.toDouble() * safePosition.toDouble()).toLong()
                surfaceDebug("control_seek target_time=$targetTime length=$length safe=$safePosition")
                service.seek(targetTime, length.toDouble(), fromUser = true)
            } else {
                surfaceDebug("control_seek direct_position safe=$safePosition length=$length")
                service.mediaplayer.position = safePosition
            }
            surfaceDebug("control_seek main_after ${describePlaybackState(service)}")
        }
    }

    @JvmStatic
    fun setTime(timeMs: Long) {
        surfaceDebug("control_set_time enter timeMs=$timeMs ${describePlaybackState(playbackService)}")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            if (service == null) {
                surfaceDebug("control_set_time abort service_null timeMs=$timeMs")
                return@launch
            }
            surfaceDebug("control_set_time main_before timeMs=$timeMs ${describePlaybackState(service)}")
            service.seek(timeMs, fromUser = true)
            surfaceDebug("control_set_time main_after timeMs=$timeMs ${describePlaybackState(service)}")
        }
    }

    @JvmStatic
    fun setAudioTrack(trackId: String) {
        android.util.Log.e(TAG, "setAudioTrack called with trackId: $trackId")
        CoroutineScope(Dispatchers.Main).launch {
            val service = playbackService
            val switched = service?.setAudioTrack(trackId) == true
            if (service != null && switched) saveSelectedAudioTrack(service, trackId)
            getTrackInfo()
        }
    }

    @JvmStatic
    fun setSpuTrack(trackId: String) {
        android.util.Log.e(TAG, "setSpuTrack called with trackId: $trackId")
        CoroutineScope(Dispatchers.Main).launch {
            playbackService?.setSpuTrack(trackId)
            getTrackInfo()
        }
    }

    @JvmStatic
    fun getTrackSnapshot(): String {
        return runTrackSnapshotBlocking("getTrackSnapshot") { service ->
            buildTrackSnapshotJson(service, "getTrackSnapshot")
        }
    }

    @JvmStatic
    fun getAudioTrackSnapshot(): String {
        return runTrackSnapshotBlocking("getAudioTrackSnapshot") { service ->
            buildAudioTrackSnapshotJson(service, "getAudioTrackSnapshot")
        }
    }

    @JvmStatic
    fun getSubtitleTrackSnapshot(): String {
        return runTrackSnapshotBlocking("getSubtitleTrackSnapshot") { service ->
            buildSubtitleTrackSnapshotJson(service, "getSubtitleTrackSnapshot")
        }
    }

    @JvmStatic
    fun setAudioTrackAndGetSnapshot(trackId: String): String {
        android.util.Log.e(TAG, "setAudioTrackAndGetSnapshot called with trackId: $trackId")
        return runTrackSnapshotBlocking("setAudioTrackAndGetSnapshot") { service ->
            val switched = service.setAudioTrack(trackId)
            if (switched) saveSelectedAudioTrack(service, trackId)
            buildAudioTrackSnapshotJson(service, "setAudioTrackAndGetSnapshot")
        }
    }

    @JvmStatic
    fun setSpuTrackAndGetSnapshot(trackId: String): String {
        android.util.Log.e(TAG, "setSpuTrackAndGetSnapshot called with trackId: $trackId")
        return runTrackSnapshotBlocking("setSpuTrackAndGetSnapshot") { service ->
            service.setSpuTrack(trackId)
            buildSubtitleTrackSnapshotJson(service, "setSpuTrackAndGetSnapshot")
        }
    }

    @JvmStatic
    fun setSubtitleRenderMode(mode: Int) {
        val normalizedMode = mode.coerceIn(SUBTITLE_RENDER_NATIVE, SUBTITLE_RENDER_OFF)
        android.util.Log.e(TAG, "setSubtitleRenderMode called with mode: $normalizedMode")

        subtitleRenderMode = normalizedMode
        applyXrSubtitleSurfaceEnabled(shouldEnableXrSubtitleSurface(), "render-mode")

        if (normalizedMode == SUBTITLE_RENDER_OFF) {
            CoroutineScope(Dispatchers.Main).launch {
                playbackService?.setSpuTrack("-1")
            }
        }
    }

    @JvmStatic
    fun getSubtitleFontSize(): String {
        val value = Settings.getInstance(AppContextProvider.appContext)
            .getString(KEY_SUBTITLES_SIZE, DEFAULT_SUBTITLE_FONT_SIZE)
        return value?.takeIf(supportedSubtitleFontSizes::contains) ?: DEFAULT_SUBTITLE_FONT_SIZE
    }

    @JvmStatic
    fun setSubtitleFontSize(value: String) {
        val normalizedValue = value.takeIf(supportedSubtitleFontSizes::contains)
            ?: DEFAULT_SUBTITLE_FONT_SIZE
        val settings = Settings.getInstance(AppContextProvider.appContext)
        if (settings.getString(KEY_SUBTITLES_SIZE, DEFAULT_SUBTITLE_FONT_SIZE) == normalizedValue) return

        settings.putSingle(KEY_SUBTITLES_SIZE, normalizedValue)
        scheduleSubtitleStyleRestart("font-size=$normalizedValue")
    }

    @JvmStatic
    fun getSubtitleOpacity(): Int {
        return Settings.getInstance(AppContextProvider.appContext)
            .getInt(KEY_SUBTITLES_COLOR_OPACITY, DEFAULT_SUBTITLE_OPACITY)
            .coerceIn(MIN_SUBTITLE_OPACITY, DEFAULT_SUBTITLE_OPACITY)
    }

    @JvmStatic
    fun setSubtitleOpacity(value: Int) {
        val normalizedValue = value.coerceIn(MIN_SUBTITLE_OPACITY, DEFAULT_SUBTITLE_OPACITY)
        val settings = Settings.getInstance(AppContextProvider.appContext)
        if (settings.getInt(KEY_SUBTITLES_COLOR_OPACITY, DEFAULT_SUBTITLE_OPACITY) == normalizedValue) return

        settings.putSingle(KEY_SUBTITLES_COLOR_OPACITY, normalizedValue)
        scheduleSubtitleStyleRestart("opacity=$normalizedValue")
    }

    private fun scheduleSubtitleStyleRestart(reason: String) {
        val revision = subtitleStyleRestartRevision.incrementAndGet()
        android.util.Log.e(TAG, "scheduleSubtitleStyleRestart revision=$revision reason=$reason")
        CoroutineScope(Dispatchers.Main).launch {
            delay(SUBTITLE_STYLE_RESTART_DEBOUNCE_MS)
            subtitleStyleRestartMutex.withLock {
                if (revision != subtitleStyleRestartRevision.get()) return@withLock

                val service = playbackService
                if (service == null) {
                    android.util.Log.e(TAG, "applySubtitleStyleRestart deferred until playback service starts revision=$revision reason=$reason")
                    return@withLock
                }

                android.util.Log.e(TAG, "applySubtitleStyleRestart revision=$revision reason=$reason")
                VLCInstance.restart()
                service.playlistManager.restart()
            }
        }
    }

    @JvmStatic
    fun setVideoScale(scaleOrdinal: Int) {
        val scales = MediaPlayer.ScaleType.entries
        val safeOrdinal = scaleOrdinal.coerceIn(0, scales.size - 1)
        val scale = scales[safeOrdinal]
        android.util.Log.e(TAG, "setVideoScale called with ordinal: $safeOrdinal scale=$scale")

        Settings.getInstance(AppContextProvider.appContext).putSingle(VIDEO_RATIO, safeOrdinal)
        CoroutineScope(Dispatchers.Main).launch {
            playbackService?.mediaplayer?.videoScale = scale
        }
    }

    @JvmStatic
    fun isAudioBoostEnabled(): Boolean {
        return Settings.getInstance(AppContextProvider.appContext).getBoolean(KEY_AUDIO_BOOST, true)
    }

    @JvmStatic
    fun setAudioBoostEnabled(enabled: Boolean) {
        android.util.Log.e(TAG, "setAudioBoostEnabled enabled=$enabled")
        Settings.getInstance(AppContextProvider.appContext).putSingle(KEY_AUDIO_BOOST, enabled)
        if (!enabled) lastUnityVolumePercent = lastUnityVolumePercent.coerceAtMost(100)
        if (!enabled && (playbackService?.volume ?: 100) > 100) {
            CoroutineScope(Dispatchers.Main).launch {
                playbackService?.setVolume(100)
            }
        }
    }

    @JvmStatic
    fun getVolumePercent(): Int {
        val serviceVolume = playbackService?.volume ?: 100
        if (isAudioBoostEnabled() && serviceVolume > 100) {
            lastUnityVolumePercent = serviceVolume.coerceIn(0, 200)
            return lastUnityVolumePercent
        }

        if (isAudioBoostEnabled() && lastUnityVolumePercent > 100) {
            CoroutineScope(Dispatchers.Main).launch {
                playbackService?.setVolume(lastUnityVolumePercent.coerceIn(101, 200))
            }
            return lastUnityVolumePercent.coerceIn(101, 200)
        }

        lastUnityVolumePercent = readSystemVolumePercent()
        return lastUnityVolumePercent
    }

    @JvmStatic
    fun setVolumePercent(percent: Int) {
        val safePercent = percent.coerceIn(0, if (isAudioBoostEnabled()) 200 else 100)
        lastUnityVolumePercent = safePercent
        android.util.Log.e(TAG, "setVolumePercent percent=$percent safePercent=$safePercent boost=${isAudioBoostEnabled()}")

        if (safePercent <= 100) {
            CoroutineScope(Dispatchers.Main).launch {
                playbackService?.setVolume(100)
            }
            setSystemVolumePercent(safePercent)
            return
        }

        setSystemVolumePercent(100)
        CoroutineScope(Dispatchers.Main).launch {
            playbackService?.setVolume(safePercent)
        }
    }

    private fun getAudioManager(): AudioManager? {
        return AppContextProvider.appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private fun readSystemVolumePercent(): Int {
        val audioManager = getAudioManager() ?: return 100
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
        return ((currentVolume.toFloat() / maxVolume.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun setSystemVolumePercent(percent: Int) {
        val audioManager = getAudioManager() ?: return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = ((percent.coerceIn(0, 100).toFloat() / 100f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        }.onFailure {
            Log.w(TAG, "setSystemVolumePercent failed percent=$percent target=$targetVolume", it)
        }
    }

    /** 应用 Unity 手柄快捷键发来的播放倍速变更。 */
    @JvmStatic
    fun setRate(rate: Float) {
        android.util.Log.e(TAG, "setRate called with rate: $rate")
        val service = playbackService
        if (service == null) {
            android.util.Log.e(TAG, "Ignoring setRate because PlaybackService is not bound")
            return
        }
        if (kotlin.math.abs(lastAppliedRate - rate) < 0.001f) {
            android.util.Log.e(TAG, "Skipping duplicate setRate request: $rate")
            CoroutineScope(Dispatchers.Main).launch {
                sendPlaybackRateToUnity(service.rate, "duplicate-setRate")
            }
            return
        }
        lastAppliedRate = rate
        CoroutineScope(Dispatchers.Main).launch {
            service.setRate(rate, false)
            sendPlaybackRateToUnity(service.rate, "setRate")
        }
    }

    @JvmStatic
    fun getRate(): Float {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val rate = playbackService?.rate ?: lastKnownPlaybackRate
            lastKnownPlaybackRate = if (!rate.isNaN() && !rate.isInfinite() && rate > 0f) rate else 1.0f
        }
        return lastKnownPlaybackRate
    }

    @JvmStatic
    fun getPlaylist(): String {
        val pm = playbackService?.playlistManager ?: return "[]"
        val mediaList = pm.getMediaList()
        val currentIdx = pm.currentIndex
        val playlist = org.json.JSONArray()
        mediaList.forEachIndexed { i, mw ->
            val rawTitle = mw.title ?: mw.uri?.lastPathSegment ?: "未知"
            playlist.put(
                org.json.JSONObject()
                    .put("index", i)
                    .put("title", rawTitle)
                    .put("length", mw.length)
                    .put("uri", mw.uri?.toString() ?: "")
                    .put("isCurrent", i == currentIdx)
            )
        }
        android.util.Log.e(TAG, "getPlaylist: returning ${mediaList.size} items")
        return playlist.toString()
    }

    @JvmStatic
    fun skipToIndex(index: Int) {
        android.util.Log.e(TAG, "skipToIndex called with index: $index")
        CoroutineScope(Dispatchers.Main).launch {
            val pm = playbackService?.playlistManager
            if (pm != null && pm.isValidPosition(index)) {
                val target = pm.getMedia(index)
                android.util.Log.e(TAG, "==== [XR_FROM_START] skipToIndex dispatch index=$index currentIndex=${pm.currentIndex} uri=${target?.uri} time=${target?.time} hasFromStart=${target?.hasFlag(MediaWrapper.MEDIA_FROM_START)} forceRestart=false ====")
                pm.playIndex(index)
            } else {
                android.util.Log.e(TAG, "skipToIndex: invalid index $index (size=${pm?.getMediaListSize() ?: 0})")
            }
        }
    }

    // --- IVLCVout.Callback ---
    override fun onSurfacesCreated(vout: IVLCVout?) {
        surfaceDebug(
            "on_surfaces_created framework_only pendingDto=${activePendingMediaRequest?.dto?.uri} " +
                "voutNull=${vout == null} attached=${vout?.areViewsAttached()} " +
                "${describeSurface("video", videoSurface)} ${describeSurface("subtitle", subtitleSurface)}"
        )
    }

    override fun onSurfacesDestroyed(vout: IVLCVout?) {
        android.util.Log.e(TAG, "onSurfacesDestroyed")
        boundVideoInputSurface = null
        surfaceDebug("on_surfaces_destroyed voutNull=${vout == null} attached=${vout?.areViewsAttached()}")
    }

    // --- IVLCVout.OnNewVideoLayoutListener ---
    override fun onNewVideoLayout(vout: IVLCVout?, width: Int, height: Int, visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int) {
        android.util.Log.e(TAG, "onNewVideoLayout: ${width}x${height}, visible=${visibleWidth}x${visibleHeight}")
        surfaceDebug(
            "layout_from_vlc raw=${width}x${height} visible=${visibleWidth}x${visibleHeight} " +
                "sar=${sarNum}/${sarDen} attached=${vout?.areViewsAttached()} " +
                "sendUri=false"
        )
        sendToUnity(UnityBridgeContract.Method.ON_VIDEO_SIZE_CHANGED, "$width|$height|$visibleWidth|$visibleHeight")
    }

    // --- PlaybackService.Callback ---
    override fun update() {
        playbackService?.let {
            sendPlaybackRateToUnity(it.rate, "update")
        }
    }

    override fun onMediaEvent(event: IMedia.Event) {
        // Not used
    }

    private fun handleVideoOutputVoutCount(voutCount: Int) {
        surfaceDebug("video_output_switch vout_event reported=$voutCount actual=${currentVoutCount()}")
        advanceVideoOutputSwitchFromVlc("vout-event")
    }

    private fun completeRebuildLayerOnOpening() {
        val active = activeVideoOutputSwitch ?: return
        if (active.operation != VideoLayerOperation.RebuildLayer || active.mediaRequestId <= 0L)
            return

        if (active.phase == VideoOutputSwitchPhase.StartingMedia) {
            val currentUri = playbackService?.currentMediaLocation
                ?: playbackService?.currentMediaWrapper?.uri?.toString()
            if (!sameMediaUri(active.targetMediaUri, currentUri)) {
                surfaceDebug(
                    "video_layer opening_ignored_while_starting token=${active.token} " +
                        "request=${active.mediaRequestId} target=${active.targetMediaUri} current=$currentUri"
                )
                return
            }
            activeVideoOutputSwitch = active.copy(openingObservedWhileStarting = true)
            surfaceDebug(
                "video_layer opening_deferred_until_start_returns token=${active.token} " +
                    "request=${active.mediaRequestId} target=${active.targetMediaUri} current=$currentUri"
            )
            return
        }
        if (active.phase != VideoOutputSwitchPhase.AwaitMediaOpening) return

        val currentUri = playbackService?.currentMediaLocation
            ?: playbackService?.currentMediaWrapper?.uri?.toString()
        if (!sameMediaUri(active.targetMediaUri, currentUri)) {
            surfaceDebug(
                "video_layer opening_ignored token=${active.token} request=${active.mediaRequestId} " +
                    "target=${active.targetMediaUri} current=$currentUri"
            )
            return
        }

        surfaceDebug(
            "video_layer opening_matched token=${active.token} request=${active.mediaRequestId} " +
                "target=${active.targetMediaUri} ${describeSurface("input", boundVideoInputSurface)} " +
                describeSurface("output", videoSurface)
        )
        completeVideoOutputAttach(active)
    }

    override fun onMediaPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.TimeChanged -> {
                sendToUnity(UnityBridgeContract.Method.ON_TIME_CHANGED, event.timeChanged.toString())
            }
            MediaPlayer.Event.PositionChanged -> {
                surfaceDebug("event_position_changed position=${event.positionChanged} ${describePlaybackState(playbackService)}")
                sendToUnity(UnityBridgeContract.Method.ON_POSITION_CHANGED, event.positionChanged.toString())
            }
            MediaPlayer.Event.LengthChanged -> {
                surfaceDebug("event_length_changed length=${event.lengthChanged} ${describePlaybackState(playbackService)}")
                sendToUnity(UnityBridgeContract.Method.ON_LENGTH_CHANGED, event.lengthChanged.toString())
            }
            MediaPlayer.Event.Vout -> handleVideoOutputVoutCount(event.voutCount)
            MediaPlayer.Event.Opening -> {
                surfaceDebug("event_opening ${describePlaybackState(playbackService)}")
                completeRebuildLayerOnOpening()
                sendToUnity(UnityBridgeContract.Method.ON_STATE_CHANGED, "Opening")
            }
            MediaPlayer.Event.Playing -> {
                surfaceDebug("event_playing ${describePlaybackState(playbackService)}")
                sendToUnity(UnityBridgeContract.Method.ON_STATE_CHANGED, "Playing")
                getTrackInfo()
            }
            MediaPlayer.Event.Paused -> {
                surfaceDebug("event_paused pendingPlay=$pendingPlay ${describePlaybackState(playbackService)}")
                sendToUnity(UnityBridgeContract.Method.ON_STATE_CHANGED, "Paused")
                // 如果有待处理的播放请求，说明底层已经解出第一帧并挂起，现在可以安全播放了
                if (pendingPlay) {
                    android.util.Log.e(TAG, "Media reached Paused state, executing pending play command.")
                    pendingPlay = false
                    CoroutineScope(Dispatchers.Main).launch {
                        playbackService?.play()
                    }
                }
            }
            MediaPlayer.Event.Stopped -> {
                surfaceDebug("event_stopped ${describePlaybackState(playbackService)}")
                advanceVideoOutputSwitchFromVlc("stopped")
                sendToUnity(UnityBridgeContract.Method.ON_STATE_CHANGED, "Stopped")
                pendingPlay = false
            }
            MediaPlayer.Event.EndReached -> {
                surfaceDebug("event_end_reached ${describePlaybackState(playbackService)}")
                advanceVideoOutputSwitchFromVlc("end-reached")
                sendToUnity(UnityBridgeContract.Method.ON_TIME_CHANGED, "0")
                sendToUnity(UnityBridgeContract.Method.ON_STATE_CHANGED, "Ended")
                pendingPlay = false
            }
            MediaPlayer.Event.EncounteredError -> {
                surfaceDebug("event_error ${describePlaybackState(playbackService)}")
                activeVideoOutputSwitch?.let { active ->
                    if (active.phase == VideoOutputSwitchPhase.StartingMedia ||
                        active.phase == VideoOutputSwitchPhase.AwaitMediaOpening)
                        failVideoOutputSwitch(active.token, "media-opening-error")
                }
                sendToUnity(UnityBridgeContract.Method.ON_STATE_CHANGED, "Error")
                pendingPlay = false
            }
            MediaPlayer.Event.Buffering -> {
                sendToUnity(UnityBridgeContract.Method.ON_BUFFERING, event.buffering.toString())
            }
            MediaPlayer.Event.ESAdded -> {
                if (event.esChangedType == IMedia.Track.Type.Text)
                    selectPendingPickedSubtitleTrack("es-added")
                else
                    getTrackInfo()
            }
            MediaPlayer.Event.ESDeleted -> getTrackInfo()
        }
    }

    private fun selectPendingPickedSubtitleTrack(reason: String) {
        val service = playbackService ?: return
        CoroutineScope(Dispatchers.Main).launch {
            val pendingMrl = pendingPickedSubtitleMrl
            if (pendingMrl.isNullOrEmpty()) {
                getTrackInfo()
                return@launch
            }

            val tracks = service.spuTracks ?: emptyArray()
            val startIndex = pendingPickedSubtitleBaselineCount.coerceIn(0, tracks.size)
            val pickedTrack = tracks
                .drop(startIndex)
                .lastOrNull { it.getId() != "-1" }
                ?: tracks.lastOrNull { it.getId() != "-1" }

            if (pickedTrack == null) {
                Log.e(
                    TAG,
                    "selectPendingPickedSubtitleTrack no selectable track reason=$reason " +
                        "pendingMrl=$pendingMrl baseline=$pendingPickedSubtitleBaselineCount count=${tracks.size}"
                )
                getTrackInfo()
                return@launch
            }

            Log.e(
                TAG,
                "selectPendingPickedSubtitleTrack reason=$reason pendingMrl=$pendingMrl " +
                    "baseline=$pendingPickedSubtitleBaselineCount count=${tracks.size} " +
                    "selectedId=${pickedTrack.getId()} selectedName=${pickedTrack.getName()}"
            )
            service.setSpuTrack(pickedTrack.getId())
            pendingPickedSubtitleMrl = null
            pendingPickedSubtitleBaselineCount = -1
            getTrackInfo()
        }
    }

    private fun getTrackInfo() {
        sendToUnity(UnityBridgeContract.Method.ON_SUBTITLE_TRACKS_CHANGED, "")
        sendToUnity(UnityBridgeContract.Method.ON_AUDIO_TRACKS_CHANGED, "")
        android.util.Log.e("Unity", "==== [DEBUG_VLC_TRACKS] track dirty notification sent to Unity ====")
    }

    private fun saveSelectedAudioTrack(service: PlaybackService, trackId: String) {
        val currentMedia = service.currentMediaWrapper ?: return
        CoroutineScope(Dispatchers.IO).launch {
            var media = currentMedia
            if (media.id == 0L) {
                media = Medialibrary.getInstance().findMedia(media) ?: media
            }
            if (media.id != 0L) media.setStringMeta(MediaWrapper.META_AUDIOTRACK, trackId)
        }
    }

    private fun runTrackSnapshotBlocking(reason: String, block: suspend (PlaybackService) -> String): String {
        val service = playbackService ?: return emptyTrackSnapshotJson(reason)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching {
                kotlinx.coroutines.runBlocking { block(service) }
            }.getOrElse {
                Log.e(TAG, "runTrackSnapshotBlocking failed on main reason=$reason", it)
                emptyTrackSnapshotJson(reason)
            }
        }

        var result: String? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    result = block(service)
                } catch (throwable: Throwable) {
                    Log.e(TAG, "runTrackSnapshotBlocking failed reason=$reason", throwable)
                    result = emptyTrackSnapshotJson(reason)
                } finally {
                    latch.countDown()
                }
            }
        }

        if (!latch.await(1500L, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Timed out waiting for track snapshot reason=$reason")
        }

        return result ?: emptyTrackSnapshotJson(reason)
    }

    private fun emptyTrackSnapshotJson(reason: String): String {
        Log.e(TAG, "Returning empty track snapshot reason=$reason serviceNull=${playbackService == null}")
        return org.json.JSONObject()
            .put("audioTracks", org.json.JSONArray())
            .put("subtitleTracks", org.json.JSONArray())
            .toString()
    }

    private suspend fun buildTrackSnapshotJson(service: PlaybackService, reason: String): String {
        val audioTracksArray = buildAudioTracksArray(service)
        val subtitleTracksArray = buildSubtitleTracksArray(service)
        android.util.Log.e(
            "Unity",
            "==== [DEBUG_VLC_TRACKS] buildTrackSnapshotJson reason=$reason " +
                "audio=${audioTracksArray.length()} subtitle=${subtitleTracksArray.length()} ===="
        )

        return org.json.JSONObject()
            .put("audioTracks", audioTracksArray)
            .put("subtitleTracks", subtitleTracksArray)
            .toString()
    }

    private fun buildAudioTrackSnapshotJson(service: PlaybackService, reason: String): String {
        val audioTracksArray = buildAudioTracksArray(service)
        android.util.Log.e(
            "Unity",
            "==== [DEBUG_VLC_TRACKS] buildAudioTrackSnapshotJson reason=$reason audio=${audioTracksArray.length()} ===="
        )

        return org.json.JSONObject()
            .put("audioTracks", audioTracksArray)
            .toString()
    }

    private suspend fun buildSubtitleTrackSnapshotJson(service: PlaybackService, reason: String): String {
        val subtitleTracksArray = buildSubtitleTracksArray(service)
        android.util.Log.e(
            "Unity",
            "==== [DEBUG_VLC_TRACKS] buildSubtitleTrackSnapshotJson reason=$reason subtitle=${subtitleTracksArray.length()} ===="
        )

        return org.json.JSONObject()
            .put("subtitleTracks", subtitleTracksArray)
            .toString()
    }

    private fun buildAudioTracksArray(service: PlaybackService): org.json.JSONArray {
        val audioTracks = service.audioTracks ?: emptyArray()
        val selectedAudioTrack = service.audioTrack
        val audioTracksArray = org.json.JSONArray()
        audioTracks.forEach {
            val trackId = it.getId()
            audioTracksArray.put(
                org.json.JSONObject()
                    .put("id", trackId)
                    .put("name", it.getName())
                    .put("selected", trackId == selectedAudioTrack)
            )
        }

        return audioTracksArray
    }

    private suspend fun buildSubtitleTracksArray(service: PlaybackService): org.json.JSONArray {
        val spuTracks = service.spuTracks ?: emptyArray()
        val selectedSpuTrack = service.spuTrack
        val subtitleSlaves = withTimeoutOrNull(500L) { resolveSubtitleSlaves(service) } ?: emptyList()
        var subtitleSlaveIndex = 0
        val subtitleTracksArray = org.json.JSONArray()
        spuTracks.forEach {
            val trackId = it.getId()
            val slave = if (trackId != "-1") {
                val currentSlave = subtitleSlaves.getOrNull(subtitleSlaveIndex)
                subtitleSlaveIndex += 1
                currentSlave
            } else {
                null
            }
            val trackJson = org.json.JSONObject()
                .put("id", trackId)
                .put("name", it.getName())
                .put("selected", trackId == selectedSpuTrack)
            if (slave != null) {
                trackJson.put(
                    "slave",
                    org.json.JSONObject()
                        .put("type", slave.type)
                        .put("priority", slave.priority)
                        .put("uri", slave.uri)
                )
            }
            subtitleTracksArray.put(trackJson)
        }

        return subtitleTracksArray
    }

    private suspend fun resolveSubtitleSlaves(service: PlaybackService): List<IMedia.Slave> {
        val mediaSlaves = service.currentMediaWrapper?.slaves
            ?.filter { it.type == IMedia.Slave.Type.Subtitle }
            ?: emptyList()
        if (mediaSlaves.isNotEmpty()) {
            android.util.Log.e("Unity", "==== [DEBUG_VLC_TRACKS] subtitleSlaves source=currentMedia count=${mediaSlaves.size} ====")
            return mediaSlaves
        }

        val mediaLocation = service.currentMediaLocation
            ?: activePendingMediaRequest?.dto?.uri
            ?: pendingMediaRequests.peekFirst()?.dto?.uri
        if (mediaLocation.isNullOrEmpty()) {
            android.util.Log.e("Unity", "==== [DEBUG_VLC_TRACKS] subtitleSlaves source=none reason=no-media-location ====")
            return emptyList()
        }

        val dbSlaves = SlaveRepository.getInstance(AppContextProvider.appContext)
            .getSlaves(mediaLocation)
            .filter { it.type == IMedia.Slave.Type.Subtitle }
        android.util.Log.e("Unity", "==== [DEBUG_VLC_TRACKS] subtitleSlaves source=db location=$mediaLocation count=${dbSlaves.size} ====")
        return dbSlaves
    }

    private fun formatTrackPayload(id: String, name: String?, selected: Boolean): String {
        val marker = if (selected) "*" else ""
        val safeName = (name ?: "")
            .replace("|", " ")
            .replace(":", " ")
        return "$marker$id:$safeName"
    }

    private fun resolvePlaylistIndex(pm: org.videolan.vlc.media.PlaylistManager, dto: MediaBridgeDTO): Int {
        if (dto.index >= 0 && sameMediaUri(pm.getMedia(dto.index)?.uri?.toString(), dto.uri)) {
            return dto.index
        }

        val currentIndex = pm.currentIndex
        if (currentIndex >= 0 && sameMediaUri(pm.getCurrentMedia()?.uri?.toString(), dto.uri)) {
            return currentIndex
        }

        for (index in 0 until pm.getMediaListSize()) {
            if (sameMediaUri(pm.getMedia(index)?.uri?.toString(), dto.uri)) return index
        }

        return -1
    }

    private fun MediaBridgeDTO.isExternalVideo(): Boolean {
        return source.equals(PLAYBACK_SOURCE_EXTERNAL, ignoreCase = true) &&
                mediaType.equals(MEDIA_TYPE_VIDEO, ignoreCase = true)
    }

    // 内部 DTO 用于暂存解析出的数据
    data class MediaBridgeDTO(
        val uri: String,
        val index: Int,
        val source: String,
        val mediaType: String
    )
}
