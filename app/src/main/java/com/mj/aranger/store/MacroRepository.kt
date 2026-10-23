package com.mj.aranger.store

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.mj.aranger.model.ActionStep
import com.mj.aranger.model.ActionType
import com.mj.aranger.model.ConstraintType
import com.mj.aranger.model.LogEntry
import com.mj.aranger.model.MacroRule
import com.mj.aranger.model.TriggerType
import com.mj.aranger.service.ARangerAccessibilityService
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object MacroRepository {
    private const val PREF_NAME = "aranger_store"
    private const val KEY_MACROS = "macros_json"
    private const val KEY_LOGS = "logs_json"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_AUTO_ACCEPT_DIALOG = "auto_accept_system_dialog"
    private const val KEY_AUTO_SKIP_AD = "auto_skip_ad"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_HUMANIZER = "humanizer_enabled"
    private const val KEY_VOLUME_PANIC = "volume_panic_enabled"
    private const val KEY_SELF_HEALING = "self_healing_enabled"
    private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update_startup"
    private const val KEY_PICKED_X = "picked_x"
    private const val KEY_PICKED_Y = "picked_y"
    private const val KEY_PICKED_TEXT = "picked_text"
    private const val KEY_PICKED_VIEW_ID = "picked_view_id"
    private const val KEY_PICKED_COLOR = "picked_color_hex"
    private const val KEY_RECORDED_PATH = "recorded_gesture_path_json"
    private const val KEY_INITIALIZED = "initialized_defaults"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var currentForegroundPackage: String = ""

    @Volatile
    var lastNotificationText: String = ""

    fun addChangeListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        mainHandler.post {
            listeners.forEach { it.invoke() }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER_ENABLED, true)

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
        addLog(
            context,
            LogEntry(
                macroName = "엔진 상태",
                message = if (enabled) "A-ranger 자동화 엔진이 활성화되었습니다." else "A-ranger 자동화 엔진이 일시정지되었습니다.",
                isSuccess = true
            )
        )
        notifyChanged()
    }

    fun isAutoAcceptDialogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ACCEPT_DIALOG, true)

    fun setAutoAcceptDialogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ACCEPT_DIALOG, enabled).apply()
        addLog(
            context,
            LogEntry(
                macroName = "스마트 가드",
                message = if (enabled) "🔌 USB/ADB/시스템 권한 수락 팝업 자동 클릭이 켜졌습니다." else "시스템 수락 팝업 자동 클릭이 꺼졌습니다.",
                isSuccess = true
            )
        )
        notifyChanged()
    }

    fun isAutoSkipAdEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SKIP_AD, false)

    fun setAutoSkipAdEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SKIP_AD, enabled).apply()
        addLog(
            context,
            LogEntry(
                macroName = "스마트 가드",
                message = if (enabled) "⏭️ 광고 '건너뛰기' 자동 스킵 가드가 켜졌습니다." else "광고 자동 스킵 가드가 꺼졌습니다.",
                isSuccess = true
            )
        )
        notifyChanged()
    }

    fun isKeepScreenOnEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOnEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        notifyChanged()
    }

    fun isHumanizerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HUMANIZER, true)

    fun setHumanizerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HUMANIZER, enabled).apply()
        addLog(
            context,
            LogEntry(
                macroName = "🛡️ 안티 매크로 우회",
                message = if (enabled) "Humanizer 모드(±7px 랜덤 좌표·미세 간격 변조)가 활성화되었습니다." else "Humanizer 모드가 해제되었습니다.",
                isSuccess = true
            )
        )
        notifyChanged()
    }

    fun isVolumePanicEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_PANIC, true)

    fun setVolumePanicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_PANIC, enabled).apply()
        addLog(
            context,
            LogEntry(
                macroName = "🔊 긴급 정지 가드",
                message = if (enabled) "매크로 실행 중 볼륨[↓] 버튼 또는 흔들기 시 즉시 정지 기능이 켜졌습니다." else "하드웨어 긴급 정지 기능이 꺼졌습니다.",
                isSuccess = true
            )
        )
        notifyChanged()
    }

    fun isSelfHealingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SELF_HEALING, true)

    fun setSelfHealingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SELF_HEALING, enabled).apply()
        if (enabled) {
            ensureSelfHealingAccessibility(context)
        }
        notifyChanged()
    }

    /**
     * ♻️ WRITE_SECURE_SETTINGS 권한이 부여된 경우,
     * OS에 의해 접근성 서비스가 풀렸을 때 자동으로 다시 활성화합니다.
     */
    fun ensureSelfHealingAccessibility(context: Context): Boolean {
        if (!isSelfHealingEnabled(context)) return false
        if (ARangerAccessibilityService.isRunning()) return true
        return try {
            val cr = context.contentResolver
            val targetFlat = ComponentName(context, ARangerAccessibilityService::class.java).flattenToString()
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val alreadyInList = current.split(':').any {
                ComponentName.unflattenFromString(it) == ComponentName(context, ARangerAccessibilityService::class.java)
            }
            if (!alreadyInList) {
                val updated = if (current.isBlank() || current == "null") {
                    targetFlat
                } else {
                    "$current:$targetFlat"
                }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: SecurityException) {
            android.util.Log.w("ARangerSelfHeal", "WRITE_SECURE_SETTINGS 거부", e)
            false
        } catch (e: Exception) {
            android.util.Log.w("ARangerSelfHeal", "접근성 자가복구 실패", e)
            false
        }
    }

    fun isAutoCheckUpdateOnStartup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHECK_UPDATE, true)

    fun setAutoCheckUpdateOnStartup(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK_UPDATE, enabled).apply()
        notifyChanged()
    }

    fun getPickedPoint(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return Pair(p.getInt(KEY_PICKED_X, 540), p.getInt(KEY_PICKED_Y, 1200))
    }

    fun getPickedColorHex(context: Context): String =
        prefs(context).getString(KEY_PICKED_COLOR, "#38BDF8") ?: "#38BDF8"

    fun savePickedColorHex(context: Context, colorHex: String) {
        prefs(context).edit().putString(KEY_PICKED_COLOR, colorHex).apply()
        notifyChanged()
    }

    fun getLastRecordedPathJson(context: Context): String =
        prefs(context).getString(KEY_RECORDED_PATH, "").orEmpty()

    fun saveRecordedPathJson(context: Context, pathJson: String, pointCount: Int, durationMs: Long) {
        prefs(context).edit().putString(KEY_RECORDED_PATH, pathJson).apply()
        addLog(
            context,
            LogEntry(
                macroName = "📹 자유 궤적 녹화",
                message = "곡선·패턴 포인트 ${pointCount}개 (${durationMs}ms) 저장 완료",
                isSuccess = true
            )
        )
        notifyChanged()
    }

    fun getPickedNodeInfo(context: Context): Pair<String, String> {
        val p = prefs(context)
        return Pair(
            p.getString(KEY_PICKED_TEXT, "").orEmpty(),
            p.getString(KEY_PICKED_VIEW_ID, "").orEmpty()
        )
    }

    fun savePickedPoint(
        context: Context,
        x: Int,
        y: Int,
        nodeText: String = "",
        nodeViewId: String = "",
        colorHex: String = ""
    ) {
        val editor = prefs(context).edit()
            .putInt(KEY_PICKED_X, x)
            .putInt(KEY_PICKED_Y, y)
        if (nodeText.isNotBlank()) editor.putString(KEY_PICKED_TEXT, nodeText)
        if (nodeViewId.isNotBlank()) editor.putString(KEY_PICKED_VIEW_ID, nodeViewId)
        if (colorHex.isNotBlank()) editor.putString(KEY_PICKED_COLOR, colorHex)
        editor.apply()

        val extra = buildString {
            if (colorHex.isNotBlank()) append(" 색상=$colorHex")
            if (nodeText.isNotBlank()) append(" 텍스트=「$nodeText」")
            if (nodeViewId.isNotBlank()) append(" ID=${nodeViewId.substringAfterLast('/')}")
        }
        addLog(
            context,
            LogEntry(
                macroName = "화면 타겟 저장",
                message = "좌표 ($x, $y)$extra 저장됨",
                isSuccess = true
            )
        )
        notifyChanged()
    }

    fun getMacros(context: Context): List<MacroRule> {
        ensureDefaults(context)
        val raw = prefs(context).getString(KEY_MACROS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<MacroRule>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(MacroRule.fromJson(it)) }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMacros(context: Context, macros: List<MacroRule>) {
        val arr = JSONArray()
        macros.forEach { arr.put(it.toJson()) }
        prefs(context).edit()
            .putString(KEY_MACROS, arr.toString())
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
        notifyChanged()
    }

    fun upsertMacro(context: Context, rule: MacroRule) {
        val current = getMacros(context).toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            current[idx] = rule
        } else {
            current.add(0, rule)
        }
        saveMacros(context, current)
    }

    fun duplicateMacro(context: Context, rule: MacroRule) {
        val copyRule = rule.copy(
            id = UUID.randomUUID().toString(),
            name = "${rule.name} (복사본)",
            runCount = 0,
            lastRunAt = 0L,
            actions = rule.actions.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        upsertMacro(context, copyRule)
    }

    fun deleteMacro(context: Context, ruleId: String) {
        val current = getMacros(context).filterNot { it.id == ruleId }
        saveMacros(context, current)
    }

    fun toggleMacro(context: Context, ruleId: String, enabled: Boolean) {
        val current = getMacros(context).map {
            if (it.id == ruleId) it.copy(enabled = enabled) else it
        }
        saveMacros(context, current)
    }

    fun markMacroExecuted(context: Context, ruleId: String) {
        val now = System.currentTimeMillis()
        val current = getMacros(context).map {
            if (it.id == ruleId) {
                it.copy(lastRunAt = now, runCount = it.runCount + 1)
            } else {
                it
            }
        }
        saveMacros(context, current)
    }

    fun exportMacrosJson(context: Context): String {
        val arr = JSONArray()
        getMacros(context).forEach { arr.put(it.toJson()) }
        return arr.toString(2)
    }

    fun importMacrosJson(context: Context, jsonText: String): Int {
        val arr = JSONArray(jsonText.trim())
        val imported = mutableListOf<MacroRule>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { imported.add(MacroRule.fromJson(it)) }
        }
        if (imported.isNotEmpty()) {
            val current = getMacros(context).toMutableList()
            imported.reversed().forEach { rule ->
                val existingIdx = current.indexOfFirst { it.id == rule.id }
                if (existingIdx >= 0) current[existingIdx] = rule else current.add(0, rule)
            }
            saveMacros(context, current)
        }
        return imported.size
    }

    fun getLogs(context: Context): List<LogEntry> {
        val raw = prefs(context).getString(KEY_LOGS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<LogEntry>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(LogEntry.fromJson(it)) }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addLog(context: Context, entry: LogEntry) {
        val current = getLogs(context).toMutableList()
        current.add(0, entry)
        val trimmed = current.take(100)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_LOGS, arr.toString()).apply()
        notifyChanged()
    }

    fun clearLogs(context: Context) {
        prefs(context).edit().putString(KEY_LOGS, "[]").apply()
        notifyChanged()
    }

    private fun ensureDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_INITIALIZED, false)) return

        val defaults = listOf(
            MacroRule(
                name = "🎯 화면 '건너뛰기' 자동 클릭",
                enabled = false,
                triggerType = TriggerType.SCREEN_TEXT_FOUND,
                triggerParam = "건너뛰기",
                constraintType = ConstraintType.NONE,
                cooldownMs = 2000L,
                actions = listOf(
                    ActionStep(
                        type = ActionType.CLICK_TEXT,
                        textParam = "건너뛰기"
                    ),
                    ActionStep(
                        type = ActionType.TOAST_VIBRATE,
                        textParam = "건너뛰기 버튼을 자동 클릭했습니다"
                    )
                )
            ),
            MacroRule(
                name = "👆 리모컨 5회 연속 좌표 터치",
                enabled = true,
                triggerType = TriggerType.MANUAL_FLOATING,
                triggerParam = "",
                constraintType = ConstraintType.NONE,
                cooldownMs = 1000L,
                actions = listOf(
                    ActionStep(
                        type = ActionType.TAP_XY,
                        x = 540,
                        y = 1200,
                        durationMs = 80L,
                        repeatCount = 5,
                        repeatIntervalMs = 200L
                    ),
                    ActionStep(
                        type = ActionType.TOAST_VIBRATE,
                        textParam = "지정 좌표 5회 터치 완료!"
                    )
                )
            ),
            MacroRule(
                name = "👋 리모컨 자동 위로 스와이프 (피드 넘기기)",
                enabled = false,
                triggerType = TriggerType.MANUAL_FLOATING,
                triggerParam = "",
                constraintType = ConstraintType.NONE,
                cooldownMs = 1000L,
                actions = listOf(
                    ActionStep(
                        type = ActionType.SWIPE,
                        x = 540,
                        y = 1750,
                        x2 = 540,
                        y2 = 450,
                        durationMs = 320L
                    )
                )
            )
        )

        val arr = JSONArray()
        defaults.forEach { arr.put(it.toJson()) }
        p.edit()
            .putString(KEY_MACROS, arr.toString())
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }
}
