package com.mj.aranger.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mj.aranger.model.ActionStep
import com.mj.aranger.model.ActionType
import com.mj.aranger.model.ConstraintType
import com.mj.aranger.model.LogEntry
import com.mj.aranger.model.MacroRule
import com.mj.aranger.model.TriggerType
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
    private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update_startup"
    private const val KEY_PICKED_X = "picked_x"
    private const val KEY_PICKED_Y = "picked_y"
    private const val KEY_PICKED_TEXT = "picked_text"
    private const val KEY_PICKED_VIEW_ID = "picked_view_id"
    private const val KEY_INITIALIZED = "initialized_defaults"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var currentForegroundPackage: String = ""

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
        nodeViewId: String = ""
    ) {
        val editor = prefs(context).edit()
            .putInt(KEY_PICKED_X, x)
            .putInt(KEY_PICKED_Y, y)
        if (nodeText.isNotBlank()) editor.putString(KEY_PICKED_TEXT, nodeText)
        if (nodeViewId.isNotBlank()) editor.putString(KEY_PICKED_VIEW_ID, nodeViewId)
        editor.apply()

        val extra = buildString {
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
