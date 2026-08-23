/*
 * *************************************************************************
 *  PreferencesXRController.kt
 * **************************************************************************
 *  Copyright © 2026 XRVLC contributors
 *  SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.videolan.vlc.gui.preferences

import android.os.Bundle
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.Preference
import org.json.JSONObject
import org.videolan.tools.KEY_XR_BUTTON_MAPPINGS
import org.videolan.tools.Settings
import org.videolan.vlc.R

private const val ACTION_NONE = "none"
private const val ACTION_TOGGLE_2X_SPEED = "toggle_2x_speed"
private const val ACTION_TOGGLE_SUBTITLE = "toggle_subtitle"
private const val ACTION_TOGGLE_PASSTHROUGH_BACKGROUND = "toggle_passthrough_background"
private const val ACTION_RESET_SCREEN_TRANSFORM = "reset_screen_transform"

private const val BUTTON_RIGHT_STICK_CLICK = "right_stick_click"
private const val BUTTON_LEFT_STICK_CLICK = "left_stick_click"
private const val BUTTON_B = "button_b"
private const val BUTTON_Y = "button_y"

private const val PREF_RIGHT_STICK_CLICK = "xr_pref_right_stick_click"
private const val PREF_LEFT_STICK_CLICK = "xr_pref_left_stick_click"
private const val PREF_BUTTON_B = "xr_pref_button_b"
private const val PREF_BUTTON_Y = "xr_pref_button_y"

class PreferencesXRController : BasePreferenceFragment() {

    private val mappings = LinkedHashMap<String, String>()

    /** 返回包含 XR 手柄快捷键配置项的偏好设置页面。 */
    override fun getXml() = R.xml.preferences_xr_controller

    /** 返回当前设置页在工具栏上显示的标题。 */
    override fun getTitleId() = R.string.xr_controller_shortcuts

    /** 加载已保存的映射，并把每个 ListPreference 绑定到对应的 JSON 字段。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mappings.putAll(readMappings())

        bindPreference(PREF_LEFT_STICK_CLICK, BUTTON_LEFT_STICK_CLICK)
        bindPreference(PREF_RIGHT_STICK_CLICK, BUTTON_RIGHT_STICK_CLICK)
        bindPreference(PREF_BUTTON_Y, BUTTON_Y)
        bindPreference(PREF_BUTTON_B, BUTTON_B)
    }

    /** 将单个 ListPreference 连接到一个手柄按键 ID，并在用户改动后立即保存。 */
    private fun bindPreference(preferenceKey: String, buttonId: String) {
        val pref = findPreference<ListPreference>(preferenceKey) ?: return
        pref.value = mappings[buttonId] ?: ACTION_NONE
        pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            mappings[buttonId] = normalizeAction(newValue as? String)
            saveMappings()
            true
        }
    }

    /** 读取共享的 xr_button_mappings JSON；首次运行或数据损坏时回退到默认映射。 */
    private fun readMappings(): Map<String, String> {
        val settings = Settings.getInstance(requireActivity())
        val json = settings.getString(KEY_XR_BUTTON_MAPPINGS, null)
        if (json.isNullOrBlank()) return defaultMappings()

        return try {
            val obj = JSONObject(json)
            linkedMapOf(
                BUTTON_RIGHT_STICK_CLICK to normalizeAction(obj.optString(BUTTON_RIGHT_STICK_CLICK, ACTION_NONE)),
                BUTTON_LEFT_STICK_CLICK to normalizeAction(obj.optString(BUTTON_LEFT_STICK_CLICK, ACTION_NONE)),
                BUTTON_B to normalizeAction(obj.optString(BUTTON_B, ACTION_NONE)),
                BUTTON_Y to normalizeAction(obj.optString(BUTTON_Y, ACTION_NONE))
            )
        } catch (e: Exception) {
            defaultMappings()
        }
    }

    /** 写入完整按键映射 JSON，让 Unity 与 VLC 设置页共享同一份配置源。 */
    private fun saveMappings() {
        val obj = JSONObject()
        mappings.forEach { (buttonId, action) -> obj.put(buttonId, normalizeAction(action)) }
        Settings.getInstance(requireActivity()).edit {
            putString(KEY_XR_BUTTON_MAPPINGS, obj.toString())
        }
    }

    /** 返回偏好项键名尚未创建时使用的默认映射。 */
    private fun defaultMappings() = linkedMapOf(
        BUTTON_RIGHT_STICK_CLICK to ACTION_RESET_SCREEN_TRANSFORM,
        BUTTON_LEFT_STICK_CLICK to ACTION_RESET_SCREEN_TRANSFORM,
        BUTTON_B to ACTION_TOGGLE_PASSTHROUGH_BACKGROUND,
        BUTTON_Y to ACTION_TOGGLE_PASSTHROUGH_BACKGROUND
    )

    /** 拒绝未知操作字符串，让旧版本或损坏 JSON 变成安全的无操作。 */
    private fun normalizeAction(action: String?) = when (action) {
        ACTION_TOGGLE_2X_SPEED,
        ACTION_TOGGLE_SUBTITLE,
        ACTION_TOGGLE_PASSTHROUGH_BACKGROUND,
        ACTION_RESET_SCREEN_TRANSFORM -> action
        else -> ACTION_NONE
    }
}
