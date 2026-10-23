package com.mj.aranger.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.mj.aranger.model.ActionStep
import com.mj.aranger.model.ActionType
import com.mj.aranger.model.ConstraintType
import com.mj.aranger.model.GlobalActionOption
import com.mj.aranger.model.LogEntry
import com.mj.aranger.model.MacroRule
import com.mj.aranger.model.TriggerType
import com.mj.aranger.service.ARangerAccessibilityService
import com.mj.aranger.store.MacroRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object MacroEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastTriggeredMap = ConcurrentHashMap<String, Long>()
    private val dailyFiredKeyMap = ConcurrentHashMap<String, String>()

    @Volatile
    var isExecuting: Boolean = false
        private set

    private var activeExecutionJob: Job? = null
    private var timerLoopJob: Job? = null
    private var lastChargingState: Boolean = false
    private var lastBatteryPercent: Int = 100
    private var tickCounter: Int = 0

    fun startTimerLoop(context: Context) {
        if (timerLoopJob?.isActive == true) return
        val appCtx = context.applicationContext
        lastChargingState = isDeviceCharging(appCtx)
        lastBatteryPercent = getBatteryPercentage(appCtx)

        timerLoopJob = scope.launch {
            while (isActive) {
                delay(1000L)
                tickCounter++

                // 0. 24/7 자가 복구 워치독: 5초마다 접근성 서비스 활성 상태 점검 및 자동 복구
                if (tickCounter % 5 == 0 && MacroRepository.isSelfHealingEnabled(appCtx)) {
                    MacroRepository.ensureSelfHealingAccessibility(appCtx)
                }

                if (!MacroRepository.isMasterEnabled(appCtx)) continue

                // 1. 충전기 연결 감지
                val chargingNow = isDeviceCharging(appCtx)
                if (chargingNow && !lastChargingState) {
                    onChargingConnected(appCtx)
                }
                lastChargingState = chargingNow

                // 2. 배터리 잔량 감지
                val batteryNow = getBatteryPercentage(appCtx)
                if (batteryNow in 1..99 && batteryNow != lastBatteryPercent) {
                    checkBatteryTriggers(appCtx, batteryNow)
                    lastBatteryPercent = batteryNow
                }

                val now = System.currentTimeMillis()
                val allEnabled = MacroRepository.getMacros(appCtx).filter { it.enabled }

                // 3. 주기적 타이머 트리거
                for (rule in allEnabled.filter { it.triggerType == TriggerType.INTERVAL_TIMER }) {
                    val intervalSec = rule.triggerParam.trim().toLongOrNull()?.coerceAtLeast(2L) ?: 10L
                    val intervalMs = intervalSec * 1000L
                    val last = lastTriggeredMap[rule.id] ?: 0L
                    if (now - last >= intervalMs) {
                        lastTriggeredMap[rule.id] = now
                        evaluateAndRun(appCtx, rule, "타이머(${intervalSec}초)")
                    }
                }

                // 4. 매일 지정 시각(HH:mm) 트리거
                val currentHm = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(now))
                val currentDayKey = SimpleDateFormat("yyyyMMdd_HH:mm", Locale.KOREA).format(Date(now))
                for (rule in allEnabled.filter { it.triggerType == TriggerType.TIME_DAILY }) {
                    val targetHm = rule.triggerParam.trim().ifBlank { "08:30" }
                    if (targetHm == currentHm && dailyFiredKeyMap[rule.id] != currentDayKey) {
                        dailyFiredKeyMap[rule.id] = currentDayKey
                        evaluateAndRun(appCtx, rule, "매일 지정 시각 ($targetHm)")
                    }
                }

                // 5. 화면 특정 좌표 픽셀 색상 일치 트리거 (2초 주기 체크)
                if (tickCounter % 2 == 0 && !isExecuting) {
                    val colorRules = allEnabled.filter { it.triggerType == TriggerType.PIXEL_COLOR_MATCH }
                    val svc = ARangerAccessibilityService.instance
                    if (svc != null && colorRules.isNotEmpty()) {
                        for (rule in colorRules) {
                            val parts = rule.triggerParam.split(",").map { it.trim() }
                            if (parts.size >= 3) {
                                val tx = parts[0].toIntOrNull() ?: continue
                                val ty = parts[1].toIntOrNull() ?: continue
                                val targetHex = parts[2]
                                val sampled = svc.samplePixelColor(tx, ty) ?: continue
                                if (ARangerAccessibilityService.colorsMatch(sampled, targetHex)) {
                                    evaluateAndRun(appCtx, rule, "픽셀 색상 일치 ($tx,$ty = $sampled)")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopAllExecutions(
        context: Context,
        reason: String = "사용자에 의해 실행 중인 매크로가 즉시 중단되었습니다."
    ) {
        activeExecutionJob?.cancel()
        activeExecutionJob = null
        isExecuting = false
        vibrateShort(context)
        showToast(context, "⏹ $reason")
        MacroRepository.addLog(
            context,
            LogEntry(
                macroName = "긴급 실행 중지",
                message = reason,
                isSuccess = true
            )
        )
    }

    fun triggerManualFloating(context: Context) {
        val appCtx = context.applicationContext
        if (!MacroRepository.isMasterEnabled(appCtx)) {
            showToast(appCtx, "A-ranger 마스터 스위치가 꺼져 있습니다.")
            return
        }
        val rules = MacroRepository.getMacros(appCtx).filter {
            it.enabled && it.triggerType == TriggerType.MANUAL_FLOATING
        }
        if (rules.isEmpty()) {
            showToast(appCtx, "활성화된 [플로팅 리모컨] 트리거 매크로가 없습니다.")
            return
        }
        rules.forEach { rule ->
            evaluateAndRun(appCtx, rule, "플로팅 리모컨 버튼", ignoreCooldown = true)
        }
    }

    fun runSingleMacroNow(context: Context, rule: MacroRule) {
        val appCtx = context.applicationContext
        evaluateAndRun(appCtx, rule, "수동 즉시 실행", ignoreCooldown = true, ignoreConstraint = true)
    }

    fun onDeviceShaken(context: Context) {
        if (!MacroRepository.isMasterEnabled(context)) return
        val rules = MacroRepository.getMacros(context).filter {
            it.enabled && it.triggerType == TriggerType.SHAKE_DEVICE
        }
        for (rule in rules) {
            evaluateAndRun(context, rule, "스마트폰 흔들기 감지")
        }
    }

    fun onScreenContentChanged(context: Context, pkg: String, screenTexts: List<String>) {
        if (!MacroRepository.isMasterEnabled(context)) return
        val rules = MacroRepository.getMacros(context).filter {
            it.enabled && it.triggerType == TriggerType.SCREEN_TEXT_FOUND && it.triggerParam.isNotBlank()
        }
        for (rule in rules) {
            val target = rule.triggerParam.trim()
            val matched = screenTexts.any { it.contains(target, ignoreCase = true) }
            if (matched) {
                evaluateAndRun(context, rule, "화면 텍스트 「$target」 감지 ($pkg)")
            }
        }
    }

    fun onAppOpened(context: Context, pkg: String) {
        if (!MacroRepository.isMasterEnabled(context)) return
        val appLabel = resolveAppLabel(context, pkg)
        val rules = MacroRepository.getMacros(context).filter {
            it.enabled && it.triggerType == TriggerType.APP_OPENED && it.triggerParam.isNotBlank()
        }
        for (rule in rules) {
            val query = rule.triggerParam.trim()
            if (pkg.contains(query, ignoreCase = true) || appLabel.contains(query, ignoreCase = true)) {
                evaluateAndRun(context, rule, "앱 실행 감지 ($appLabel)")
            }
        }
    }

    fun onNotificationReceived(context: Context, pkg: String, text: String) {
        if (!MacroRepository.isMasterEnabled(context)) return
        val rules = MacroRepository.getMacros(context).filter {
            it.enabled && it.triggerType == TriggerType.NOTIFICATION_RECEIVED
        }
        for (rule in rules) {
            val query = rule.triggerParam.trim()
            if (query.isEmpty() || text.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)) {
                evaluateAndRun(context, rule, "알림 수신 ($pkg)")
            }
        }
    }

    private fun onChargingConnected(context: Context) {
        val rules = MacroRepository.getMacros(context).filter {
            it.enabled && it.triggerType == TriggerType.CHARGING_CONNECTED
        }
        for (rule in rules) {
            evaluateAndRun(context, rule, "충전기 연결됨")
        }
    }

    private fun checkBatteryTriggers(context: Context, currentPercent: Int) {
        val rules = MacroRepository.getMacros(context).filter {
            it.enabled && it.triggerType == TriggerType.BATTERY_BELOW
        }
        for (rule in rules) {
            val threshold = rule.triggerParam.trim().toIntOrNull() ?: 20
            if (currentPercent <= threshold) {
                evaluateAndRun(context, rule, "배터리 ${currentPercent}% (기준 ${threshold}% 이하)")
            }
        }
    }

    private fun evaluateAndRun(
        context: Context,
        rule: MacroRule,
        triggerReason: String,
        ignoreCooldown: Boolean = false,
        ignoreConstraint: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!ignoreCooldown) {
            val last = lastTriggeredMap[rule.id] ?: 0L
            if (now - last < rule.cooldownMs.coerceAtLeast(300L)) {
                return
            }
        }

        if (!ignoreConstraint && !checkConstraint(context, rule)) {
            return
        }

        lastTriggeredMap[rule.id] = now
        MacroRepository.markMacroExecuted(context, rule.id)

        activeExecutionJob = scope.launch {
            isExecuting = true
            try {
                MacroRepository.addLog(
                    context,
                    LogEntry(
                        macroName = rule.name,
                        message = "발동됨 [$triggerReason] → 액션 ${rule.actions.size}개 시작",
                        isSuccess = true
                    )
                )
                for ((idx, step) in rule.actions.withIndex()) {
                    if (!isActive) break
                    val ok = executeActionStep(context, step)
                    if (!ok) {
                        MacroRepository.addLog(
                            context,
                            LogEntry(
                                macroName = rule.name,
                                message = "단계 ${idx + 1} 미수행: ${step.toSummary()}",
                                isSuccess = false
                            )
                        )
                    }
                    delay(humanizeInterval(context, 120L))
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                MacroRepository.addLog(
                    context,
                    LogEntry(
                        macroName = rule.name,
                        message = "실행 오류: ${e.message}",
                        isSuccess = false
                    )
                )
            } finally {
                isExecuting = false
            }
        }
    }

    private fun humanizeInterval(context: Context, baseMs: Long): Long {
        if (!MacroRepository.isHumanizerEnabled(context)) return baseMs
        val factor = Random.nextDouble(0.88, 1.14)
        return (baseMs * factor).toLong().coerceAtLeast(35L)
    }

    private fun checkConstraint(context: Context, rule: MacroRule): Boolean {
        return when (rule.constraintType) {
            ConstraintType.NONE -> true
            ConstraintType.FOREGROUND_APP_IS -> {
                val query = rule.constraintParam.trim()
                if (query.isEmpty()) true
                else {
                    val pkg = MacroRepository.currentForegroundPackage
                    val label = resolveAppLabel(context, pkg)
                    pkg.contains(query, ignoreCase = true) || label.contains(query, ignoreCase = true)
                }
            }
            ConstraintType.SCREEN_CONTAINS_TEXT -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                svc.containsScreenText(rule.constraintParam)
            }
            ConstraintType.SCREEN_NOT_CONTAINS_TEXT -> {
                val svc = ARangerAccessibilityService.instance ?: return true
                !svc.containsScreenText(rule.constraintParam)
            }
            ConstraintType.CHARGING_ONLY -> isDeviceCharging(context)
            ConstraintType.TIME_WINDOW -> isWithinTimeWindow(rule.constraintParam)
        }
    }

    private fun isWithinTimeWindow(rangeSpec: String): Boolean {
        val parts = rangeSpec.split("-")
        if (parts.size != 2) return true
        val startMin = parseHmToMinutes(parts[0].trim()) ?: return true
        val endMin = parseHmToMinutes(parts[1].trim()) ?: return true
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (startMin <= endMin) {
            nowMin in startMin..endMin
        } else {
            nowMin >= startMin || nowMin <= endMin
        }
    }

    private fun parseHmToMinutes(hm: String): Int? {
        val sp = hm.split(":")
        if (sp.size != 2) return null
        val h = sp[0].toIntOrNull() ?: return null
        val m = sp[1].toIntOrNull() ?: return null
        return (h.coerceIn(0, 23) * 60) + m.coerceIn(0, 59)
    }

    private suspend fun executeActionStep(context: Context, step: ActionStep): Boolean {
        return when (step.type) {
            ActionType.TAP_XY -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                var allOk = true
                val count = step.repeatCount.coerceIn(1, 500)
                for (i in 0 until count) {
                    val ok = svc.performTap(step.x, step.y, step.durationMs)
                    if (!ok) allOk = false
                    if (i < count - 1) {
                        delay(humanizeInterval(context, step.repeatIntervalMs.coerceAtLeast(40L)))
                    }
                }
                allOk
            }
            ActionType.LONG_PRESS_XY -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                svc.performTap(step.x, step.y, step.durationMs.coerceAtLeast(600L))
            }
            ActionType.TAP_IF_COLOR -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                val currentHex = svc.samplePixelColor(step.x, step.y) ?: return false
                if (ARangerAccessibilityService.colorsMatch(currentHex, step.colorHex)) {
                    svc.performTap(step.x, step.y, step.durationMs)
                } else {
                    false
                }
            }
            ActionType.LOOP_UNTIL_COLOR -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                val maxTries = step.repeatCount.coerceIn(1, 500)
                val interval = step.repeatIntervalMs.coerceAtLeast(250L)
                for (i in 0 until maxTries) {
                    val sampled = svc.samplePixelColor(step.x, step.y)
                    if (sampled != null && ARangerAccessibilityService.colorsMatch(sampled, step.colorHex)) {
                        return svc.performTap(step.x, step.y, step.durationMs)
                    }
                    delay(humanizeInterval(context, interval))
                }
                false
            }
            ActionType.CUSTOM_GESTURE_PATH -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                val pathData = step.gesturePathJson.ifBlank {
                    MacroRepository.getLastRecordedPathJson(context)
                }
                val count = step.repeatCount.coerceIn(1, 200)
                var allOk = true
                for (i in 0 until count) {
                    val ok = svc.performCustomPath(pathData, step.durationMs)
                    if (!ok) allOk = false
                    if (i < count - 1) {
                        delay(humanizeInterval(context, step.repeatIntervalMs.coerceAtLeast(200L)))
                    }
                }
                allOk
            }
            ActionType.CLICK_TEXT -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                svc.clickNodeByText(step.textParam)
            }
            ActionType.CLICK_VIEW_ID -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                svc.clickNodeByViewId(step.textParam)
            }
            ActionType.LOOP_UNTIL_TEXT -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                val targetText = step.textParam.trim()
                val maxTries = step.repeatCount.coerceIn(1, 500)
                val interval = step.repeatIntervalMs.coerceAtLeast(150L)
                for (i in 0 until maxTries) {
                    if (targetText.isNotEmpty() && svc.containsScreenText(targetText)) {
                        return true
                    }
                    svc.performTap(step.x, step.y, step.durationMs)
                    delay(humanizeInterval(context, interval))
                }
                targetText.isEmpty() || svc.containsScreenText(targetText)
            }
            ActionType.SWIPE -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                val count = step.repeatCount.coerceIn(1, 200)
                var allOk = true
                for (i in 0 until count) {
                    val ok = svc.performSwipe(step.x, step.y, step.x2, step.y2, step.durationMs)
                    if (!ok) allOk = false
                    if (i < count - 1) {
                        delay(humanizeInterval(context, step.repeatIntervalMs.coerceAtLeast(150L)))
                    }
                }
                allOk
            }
            ActionType.INPUT_TEXT -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                val expanded = expandTemplateVars(step.textParam)
                svc.inputText(expanded)
            }
            ActionType.CLIPBOARD_PASTE -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                val expanded = expandTemplateVars(step.textParam)
                svc.pasteTextViaClipboard(expanded)
            }
            ActionType.WEBHOOK_HTTP -> {
                sendWebhookRequest(context, step.textParam)
            }
            ActionType.WAIT -> {
                delay(step.durationMs.coerceIn(50L, 300_000L))
                true
            }
            ActionType.GLOBAL_ACTION -> {
                val svc = ARangerAccessibilityService.instance ?: return false
                val option = GlobalActionOption.fromName(step.globalActionName)
                svc.performGlobalAction(option.serviceAction)
            }
            ActionType.MEDIA_VOLUME -> {
                setMediaVolumePercent(context, step.x.coerceIn(0, 100))
                true
            }
            ActionType.LAUNCH_APP -> {
                launchAppByKeyword(context, step.textParam)
            }
            ActionType.TOAST_VIBRATE -> {
                val msg = expandTemplateVars(step.textParam.ifBlank { "A-ranger 매크로 실행됨" })
                showToast(context, msg)
                vibrateShort(context)
                true
            }
        }
    }

    private fun expandTemplateVars(raw: String): String {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        return raw
            .replace("{noti}", MacroRepository.lastNotificationText)
            .replace("{pkg}", MacroRepository.currentForegroundPackage)
            .replace("{time}", nowStr)
    }

    private suspend fun sendWebhookRequest(context: Context, spec: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val rawUrl = spec.trim()
                if (rawUrl.isEmpty()) return@withContext false
                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
                val resolvedUrl = rawUrl
                    .replace("{noti}", URLEncoder.encode(MacroRepository.lastNotificationText, "UTF-8"))
                    .replace("{pkg}", URLEncoder.encode(MacroRepository.currentForegroundPackage, "UTF-8"))
                    .replace("{time}", URLEncoder.encode(nowStr, "UTF-8"))

                val conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "A-ranger-Pro/1.3.0")
                }
                val code = conn.responseCode
                conn.disconnect()
                val ok = code in 200..299
                MacroRepository.addLog(
                    context,
                    LogEntry(
                        macroName = "🌐 웹훅 전송",
                        message = "HTTP $code → ${resolvedUrl.take(80)}",
                        isSuccess = ok
                    )
                )
                ok
            } catch (e: Exception) {
                MacroRepository.addLog(
                    context,
                    LogEntry(
                        macroName = "🌐 웹훅 오류",
                        message = e.message ?: "연결 실패",
                        isSuccess = false
                    )
                )
                false
            }
        }

    private fun setMediaVolumePercent(context: Context, percent: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = ((max * percent) / 100.0).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {
        }
    }

    private fun launchAppByKeyword(context: Context, query: String): Boolean {
        val target = query.trim()
        if (target.isEmpty()) return false
        val pm = context.packageManager

        pm.getLaunchIntentForPackage(target)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val matched = apps.firstOrNull { info ->
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(pm).toString()
            pkg.contains(target, ignoreCase = true) || label.contains(target, ignoreCase = true)
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    private fun resolveAppLabel(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun isDeviceCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getBatteryPercentage(context: Context): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 100
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 100
        return (level * 100) / scale
    }

    private fun vibrateShort(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(70L)
            }
        } catch (_: Exception) {
        }
    }

    private fun showToast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
