package org.videolan.vlc.bridge

import android.util.Log

/**
 * 统一封装 Android 到 Unity 的 UnitySendMessage 调用，避免各业务类重复反射和硬编码对象名。
 */
internal object UnityMessageDispatcher {
    private const val TAG = "UnityMessageDispatcher"
    private const val UNITY_PLAYER_CLASS = "com.unity3d.player.UnityPlayer"
    private const val UNITY_SEND_MESSAGE = "UnitySendMessage"

    private val unityPlayerClass: Class<*>? by lazy {
        try {
            Class.forName(UNITY_PLAYER_CLASS)
        } catch (e: Exception) {
            Log.e(TAG, "UnityPlayer class not found, are we running in Unity?", e)
            null
        }
    }

    /**
     * 向指定 Unity GameObject 发送消息。
     */
    fun send(target: String, methodName: String, message: String = "") {
        try {
            val method = unityPlayerClass?.getMethod(
                    UNITY_SEND_MESSAGE,
                    String::class.java,
                    String::class.java,
                    String::class.java
            )
            method?.invoke(null, target, methodName, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Unity message: $target.$methodName", e)
        }
    }

    /**
     * 向 Unity 播放桥接对象发送消息。
     */
    fun sendToPlayback(methodName: String, message: String = "") {
        send(UnityBridgeContract.Target.PLAYBACK_BRIDGE, methodName, message)
    }

    fun sendToLibraryLauncher(methodName: String, message: String = "") {
        send(UnityBridgeContract.Target.LIBRARY_LAUNCHER, methodName, message)
    }
}
