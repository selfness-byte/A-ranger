package com.mj.aranger.engine

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import com.mj.aranger.model.LogEntry
import com.mj.aranger.service.ARangerAccessibilityService
import com.mj.aranger.service.ARangerNotificationService
import com.mj.aranger.store.MacroRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 💻 A-ranger Pro v1.5 온디바이스 ADB 파워 컨트롤러
 * - PC/USB 연결 없이도 ADB(adb shell) 수준의 시스템 설정 변경, 키코드 주입, 인텐트/딥링크 호출,
 *   애니메이션 0배속 초고속 부스트, 멀티라인 ADB 스크립트 및 쉘 명령어를 스마트폰 단독으로 수행합니다.
 */
object AdbPowerController {

    data class AdbExecResult(
        val isSuccess: Boolean,
        val output: String,
        val commandCount: Int
    )

    @Volatile
    private var ttsEngine: TextToSpeech? = null

    @Volatile
    private var ttsReady: Boolean = false

    // =========================================================================
    // 1. ADB 보안/시스템 설정 원클릭 부스터 (WRITE_SECURE_SETTINGS / WRITE_SETTINGS)
    // =========================================================================

    fun isZeroAnimationBoostEnabled(context: Context): Boolean {
        return try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale <= 0.05f
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 🚀 ADB `settings put global window/transition/animator_duration_scale 0.0`과 동일:
     * 창 전환·애니메이션 지연을 0배속으로 제거하여 매크로 반응 속도를 극대화합니다.
     */
    fun setZeroAnimationBoost(context: Context, enabled: Boolean): Boolean {
        val targetScale = if (enabled) "0.0" else "1.0"
        return try {
            val cr = context.contentResolver
            Settings.Global.putString(cr, Settings.Global.WINDOW_ANIMATION_SCALE, targetScale)
            Settings.Global.putString(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, targetScale)
            Settings.Global.putString(cr, Settings.Global.ANIMATOR_DURATION_SCALE, targetScale)
            MacroRepository.addLog(
                context,
                LogEntry(
                    macroName = "🚀 ADB 애니메이션 부스트",
                    message = if (enabled) "시스템 애니메이션 0.0x (초고속 무지연 모드) 적용 완료" else "시스템 애니메이션 1.0x (기본 속도) 복원 완료",
                    isSuccess = true
                )
            )
            MacroRepository.notifyChanged()
            true
        } catch (e: Exception) {
            MacroRepository.addLog(
                context,
                LogEntry(
                    macroName = "🚀 ADB 애니메이션 부스트",
                    message = "권한 부족(WRITE_SECURE_SETTINGS): ${e.message}",
                    isSuccess = false
                )
            )
            false
        }
    }

    fun isStayAwakePluggedEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 🔌 ADB `settings put global stay_on_while_plugged_in 7`과 동일:
     * 유선/무선 충전 중일 때 개발자 옵션처럼 화면이 절대 꺼지지 않도록 유지합니다.
     */
    fun setStayAwakePlugged(context: Context, enabled: Boolean): Boolean {
        return try {
            val value = if (enabled) 7 else 0
            Settings.Global.putInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, value)
            MacroRepository.addLog(
                context,
                LogEntry(
                    macroName = "🔌 ADB 충전 중 화면유지",
                    message = if (enabled) "충전 중 화면 켜짐 유지(stay_on_while_plugged_in=7) 활성화됨" else "충전 중 화면 켜짐 유지 해제됨",
                    isSuccess = true
                )
            )
            MacroRepository.notifyChanged()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isShowTouchesEnabled(context: Context): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, "show_touches", 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 👆 ADB `settings put system show_touches 1`과 동일:
     * 화면 터치 좌표를 시각적 원형 포인트로 표시합니다.
     */
    fun setShowTouches(context: Context, enabled: Boolean): Boolean {
        return try {
            Settings.System.putInt(context.contentResolver, "show_touches", if (enabled) 1 else 0)
            MacroRepository.notifyChanged()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setScreenBrightnessPercent(context: Context, percent: Int): Boolean {
        return try {
            val cr = context.contentResolver
            val value255 = ((percent.coerceIn(1, 100) * 255) / 100).coerceIn(2, 255)
            Settings.System.putInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value255)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setRingerMode(context: Context, modeCode: Int): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = when (modeCode) {
                1 -> AudioManager.RINGER_MODE_VIBRATE
                2 -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 🧹 ADB `am kill <package>`와 동일:
     * 지정한 앱(이름 또는 패키지명)의 백그라운드 프로세스를 즉시 정리합니다.
     */
    fun killBackgroundApp(context: Context, query: String): Boolean {
        val target = query.trim()
        if (target.isEmpty()) return false
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = context.packageManager
            var resolvedPkg = target
            if (!target.contains('.')) {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val apps = pm.queryIntentActivities(mainIntent, 0)
                val matched = apps.firstOrNull { info ->
                    val p = info.activityInfo.packageName
                    val label = info.loadLabel(pm).toString()
                    p.contains(target, ignoreCase = true) || label.contains(target, ignoreCase = true)
                }
                if (matched != null) {
                    resolvedPkg = matched.activityInfo.packageName
                }
            }
            am.killBackgroundProcesses(resolvedPkg)
            MacroRepository.addLog(
                context,
                LogEntry(
                    macroName = "🧹 ADB 프로세스 정리",
                    message = "패키지 [$resolvedPkg] 백그라운드 프로세스 강제 정리 완료",
                    isSuccess = true
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 🗣️ 한국어 TTS 음성 브리핑
     */
    fun speakTts(context: Context, message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        val appCtx = context.applicationContext
        try {
            if (ttsEngine == null) {
                ttsEngine = TextToSpeech(appCtx) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsEngine?.language = Locale.KOREAN
                        ttsReady = true
                        ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aranger_tts")
                    }
                }
                return true
            }
            if (ttsReady) {
                ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aranger_tts")
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * 🔗 ADB `am start` / 딥링크 / 시스템 설정 화면 즉시 점프
     * 지원 형식:
     * 1) URL / 커스텀 스킴: https://..., kakaoopen://..., market://..., tel:010...
     * 2) intent:// URI
     * 3) 안드로이드 시스템 액션: android.settings.WIFI_SETTINGS, android.settings.APPLICATION_DEVELOPMENT_SETTINGS 등
     * 4) 컴포넌트명: com.android.settings/.Settings
     */
    fun launchDeepLinkOrIntent(context: Context, rawSpec: String): Boolean {
        val spec = rawSpec.trim()
        if (spec.isEmpty()) return false
        return try {
            val intent = when {
                spec.startsWith("intent://", ignoreCase = true) -> {
                    Intent.parseUri(spec, Intent.URI_INTENT_SCHEME)
                }
                spec.contains("://") || spec.startsWith("tel:") || spec.startsWith("mailto:") || spec.startsWith("sms:") -> {
                    Intent(Intent.ACTION_VIEW, Uri.parse(spec))
                }
                spec.startsWith("android.settings.") || spec.startsWith("android.intent.action.") -> {
                    Intent(spec)
                }
                spec.contains("/") -> {
                    val cn = ComponentName.unflattenFromString(spec)
                    if (cn != null) {
                        Intent().setComponent(cn)
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(spec))
                    }
                }
                else -> {
                    context.packageManager.getLaunchIntentForPackage(spec)
                        ?: Intent(spec)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // 2. ADB 키코드(KeyEvent) 디스패처
    // =========================================================================

    suspend fun dispatchAdbKeyEvent(context: Context, rawKeySpec: String): Boolean {
        val spec = rawKeySpec.trim().uppercase(Locale.US)
        if (spec.isEmpty()) return false

        val svc = ARangerAccessibilityService.instance
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when (spec) {
            "3", "KEYCODE_HOME", "HOME" -> {
                svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
            }
            "4", "KEYCODE_BACK", "BACK" -> {
                svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
            }
            "187", "KEYCODE_APP_SWITCH", "RECENTS" -> {
                svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
            }
            "83", "KEYCODE_NOTIFICATION", "NOTIFICATIONS" -> {
                svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) ?: false
            }
            "26", "223", "KEYCODE_POWER", "KEYCODE_SLEEP", "LOCK" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: false
                } else false
            }
            "120", "KEYCODE_SYSRQ", "SCREENSHOT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) ?: false
                } else false
            }
            "66", "KEYCODE_ENTER", "ENTER" -> {
                if (svc?.performImeEnter() == true) {
                    true
                } else {
                    runRawShellLine("input keyevent 66").first
                }
            }
            "67", "KEYCODE_DEL", "BACKSPACE", "DEL" -> {
                if (svc?.performBackspace() == true) {
                    true
                } else {
                    runRawShellLine("input keyevent 67").first
                }
            }
            "24", "KEYCODE_VOLUME_UP", "VOLUME_UP" -> {
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            "25", "KEYCODE_VOLUME_DOWN", "VOLUME_DOWN" -> {
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            "164", "KEYCODE_VOLUME_MUTE", "MUTE" -> {
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            "85", "KEYCODE_MEDIA_PLAY_PAUSE", "PLAY_PAUSE" -> {
                dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            "87", "KEYCODE_MEDIA_NEXT", "MEDIA_NEXT" -> {
                dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            "88", "KEYCODE_MEDIA_PREVIOUS", "MEDIA_PREVIOUS" -> {
                dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            "19", "KEYCODE_DPAD_UP", "DPAD_UP" -> {
                svc?.performSwipe(540, 1400, 540, 900, 180L) ?: false
            }
            "20", "KEYCODE_DPAD_DOWN", "DPAD_DOWN" -> {
                svc?.performSwipe(540, 900, 540, 1400, 180L) ?: false
            }
            "21", "KEYCODE_DPAD_LEFT", "DPAD_LEFT" -> {
                svc?.performSwipe(800, 1200, 280, 1200, 180L) ?: false
            }
            "22", "KEYCODE_DPAD_RIGHT", "DPAD_RIGHT" -> {
                svc?.performSwipe(280, 1200, 800, 1200, 180L) ?: false
            }
            else -> {
                runRawShellLine("input keyevent $spec").first
            }
        }
    }

    private fun dispatchMediaKey(am: AudioManager, keyCode: Int): Boolean {
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // 3. 멀티라인 ADB 스크립트 & 온디바이스 쉘 인터프리터
    // =========================================================================

    suspend fun executeAdbScript(context: Context, rawScript: String): AdbExecResult =
        withContext(Dispatchers.Main) {
            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
            val pickedPt = MacroRepository.getPickedPoint(context)
            val pickedColor = MacroRepository.getPickedColorHex(context)

            val expandedScript = rawScript
                .replace("{noti}", MacroRepository.lastNotificationText)
                .replace("{pkg}", MacroRepository.currentForegroundPackage)
                .replace("{time}", nowStr)
                .replace("{x}", pickedPt.first.toString())
                .replace("{y}", pickedPt.second.toString())
                .replace("{color}", pickedColor)

            val lines = expandedScript
                .split('\n', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

            if (lines.isEmpty()) {
                return@withContext AdbExecResult(false, "실행할 명령어가 없습니다.", 0)
            }

            val logSb = StringBuilder()
            var allOk = true
            var count = 0

            for (rawLine in lines) {
                count++
                // 사용자가 'adb shell input tap ...'처럼 붙여넣어도 자동 인식
                val line = rawLine
                    .removePrefix("adb shell ")
                    .removePrefix("adb ")
                    .trim()

                val tokens = line.split(Regex("\\s+"))
                val cmd = tokens.firstOrNull()?.lowercase(Locale.US).orEmpty()

                val (ok, msg) = when (cmd) {
                    "input" -> handleInputCommand(context, tokens.drop(1))
                    "settings" -> handleSettingsCommand(context, tokens.drop(1))
                    "am" -> handleAmCommand(context, tokens.drop(1), line)
                    "cmd" -> handleCmdServiceCommand(context, tokens.drop(1), line)
                    "sleep" -> {
                        val sec = tokens.getOrNull(1)?.toDoubleOrNull() ?: 1.0
                        val ms = (sec * 1000.0).toLong().coerceIn(20L, 60_000L)
                        delay(ms)
                        Pair(true, "sleep ${ms}ms 완료")
                    }
                    "wait" -> {
                        val ms = tokens.getOrNull(1)?.toLongOrNull()?.coerceIn(20L, 60_000L) ?: 1000L
                        delay(ms)
                        Pair(true, "wait ${ms}ms 완료")
                    }
                    else -> runRawShellLine(line)
                }

                if (!ok) allOk = false
                logSb.append(if (ok) "✔ " else "✖ ")
                    .append(line)
                    .append(" → ")
                    .append(msg.ifBlank { if (ok) "OK" else "FAILED" })
                    .append("\n")
            }

            val finalOutput = logSb.toString().trim()
            MacroRepository.addLog(
                context,
                LogEntry(
                    macroName = "💻 ADB 스크립트 (${count}줄)",
                    message = finalOutput.lines().joinToString(" | ").take(160),
                    isSuccess = allOk
                )
            )
            AdbExecResult(allOk, finalOutput, count)
        }

    private suspend fun handleInputCommand(
        context: Context,
        args: List<String>
    ): Pair<Boolean, String> {
        val sub = args.firstOrNull()?.lowercase(Locale.US).orEmpty()
        val svc = ARangerAccessibilityService.instance
        return when (sub) {
            "tap" -> {
                val x = args.getOrNull(1)?.toIntOrNull() ?: return Pair(false, "좌표 X 오류")
                val y = args.getOrNull(2)?.toIntOrNull() ?: return Pair(false, "좌표 Y 오류")
                val dur = args.getOrNull(3)?.toLongOrNull() ?: 95L
                val ok = svc?.performTap(x, y, dur) ?: false
                Pair(ok, "tap($x, $y, ${dur}ms)")
            }
            "swipe" -> {
                val x1 = args.getOrNull(1)?.toIntOrNull() ?: return Pair(false, "좌표 X1 오류")
                val y1 = args.getOrNull(2)?.toIntOrNull() ?: return Pair(false, "좌표 Y1 오류")
                val x2 = args.getOrNull(3)?.toIntOrNull() ?: return Pair(false, "좌표 X2 오류")
                val y2 = args.getOrNull(4)?.toIntOrNull() ?: return Pair(false, "좌표 Y2 오류")
                val dur = args.getOrNull(5)?.toLongOrNull() ?: 320L
                val ok = svc?.performSwipe(x1, y1, x2, y2, dur) ?: false
                Pair(ok, "swipe($x1,$y1 → $x2,$y2, ${dur}ms)")
            }
            "pinch" -> {
                val mode = args.getOrNull(1)?.lowercase(Locale.US) ?: "in"
                val cx = args.getOrNull(2)?.toIntOrNull() ?: 540
                val cy = args.getOrNull(3)?.toIntOrNull() ?: 1200
                val zoomIn = mode != "out"
                val ok = svc?.performPinch(cx, cy, zoomIn) ?: false
                Pair(ok, "pinch(${if (zoomIn) "확대" else "축소"} @ $cx,$cy)")
            }
            "text" -> {
                val text = args.drop(1).joinToString(" ").trim('"', '\'')
                val ok = svc?.pasteTextViaClipboard(text) ?: false
                Pair(ok, "text(\"$text\")")
            }
            "keyevent" -> {
                val keySpec = args.getOrNull(1).orEmpty()
                val ok = dispatchAdbKeyEvent(context, keySpec)
                Pair(ok, "keyevent($keySpec)")
            }
            else -> runRawShellLine("input ${args.joinToString(" ")}")
        }
    }

    private suspend fun handleSettingsCommand(
        context: Context,
        args: List<String>
    ): Pair<Boolean, String> {
        val op = args.getOrNull(0)?.lowercase(Locale.US).orEmpty()
        val ns = args.getOrNull(1)?.lowercase(Locale.US).orEmpty()
        val key = args.getOrNull(2).orEmpty()
        val cr = context.contentResolver

        if (op == "get" && key.isNotEmpty()) {
            return try {
                val v = when (ns) {
                    "system" -> Settings.System.getString(cr, key)
                    "secure" -> Settings.Secure.getString(cr, key)
                    "global" -> Settings.Global.getString(cr, key)
                    else -> null
                }
                Pair(true, "$key = ${v ?: "null"}")
            } catch (e: Exception) {
                Pair(false, e.message ?: "조회 실패")
            }
        }

        if (op == "put" && key.isNotEmpty() && args.size >= 4) {
            val value = args.drop(3).joinToString(" ")
            return try {
                val ok = when (ns) {
                    "system" -> Settings.System.putString(cr, key, value)
                    "secure" -> Settings.Secure.putString(cr, key, value)
                    "global" -> Settings.Global.putString(cr, key, value)
                    else -> false
                }
                MacroRepository.notifyChanged()
                Pair(ok, "put $ns $key = $value")
            } catch (e: Exception) {
                Pair(false, "권한 필요(WRITE_SECURE_SETTINGS/WRITE_SETTINGS): ${e.message}")
            }
        }

        return runRawShellLine("settings ${args.joinToString(" ")}")
    }

    private suspend fun handleAmCommand(
        context: Context,
        args: List<String>,
        fullLine: String
    ): Pair<Boolean, String> {
        val sub = args.firstOrNull()?.lowercase(Locale.US).orEmpty()
        if (sub == "start") {
            // -d <uri> 또는 -n <component> 또는 마지막 인자 추출
            val dIdx = args.indexOf("-d")
            val nIdx = args.indexOf("-n")
            val aIdx = args.indexOf("-a")
            val target = when {
                dIdx >= 0 && dIdx + 1 < args.size -> args[dIdx + 1]
                nIdx >= 0 && nIdx + 1 < args.size -> args[nIdx + 1]
                aIdx >= 0 && aIdx + 1 < args.size -> args[aIdx + 1]
                else -> args.lastOrNull { !it.startsWith("-") && it != "start" }.orEmpty()
            }
            if (target.isNotEmpty() && launchDeepLinkOrIntent(context, target)) {
                return Pair(true, "am start ($target)")
            }
        } else if (sub == "kill" || sub == "force-stop") {
            val pkg = args.lastOrNull { !it.startsWith("-") && it != sub }.orEmpty()
            if (pkg.isNotEmpty() && killBackgroundApp(context, pkg)) {
                return Pair(true, "am $sub ($pkg)")
            }
        }
        return runRawShellLine(fullLine)
    }

    private suspend fun handleCmdServiceCommand(
        context: Context,
        args: List<String>,
        fullLine: String
    ): Pair<Boolean, String> {
        val svcName = args.getOrNull(0)?.lowercase(Locale.US).orEmpty()
        val action = args.getOrNull(1)?.lowercase(Locale.US).orEmpty()
        val acc = ARangerAccessibilityService.instance
        if (svcName == "statusbar") {
            return when (action) {
                "expand-notifications" -> {
                    val ok = acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) ?: false
                    Pair(ok, "상단 알림창 열기")
                }
                "expand-settings" -> {
                    val ok = acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: false
                    Pair(ok, "빠른 설정창 열기")
                }
                "collapse" -> {
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) ?: false
                    } else {
                        acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
                    }
                    Pair(ok, "알림창 닫기")
                }
                else -> runRawShellLine(fullLine)
            }
        }
        if (svcName == "notification" && action == "click") {
            val keyword = args.drop(2).joinToString(" ")
            val ok = ARangerNotificationService.instance?.clickNotificationByKeyword(keyword) ?: false
            return Pair(ok, "알림 즉시 클릭($keyword)")
        }
        return runRawShellLine(fullLine)
    }

    private suspend fun runRawShellLine(command: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(6, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext Pair(false, "시간 초과 (6s)")
                }
                val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText().trim() }
                val code = proc.exitValue()
                val summary = out.lines().take(12).joinToString("\n").ifBlank { "exit=$code" }
                Pair(code == 0, summary)
            } catch (e: Exception) {
                Pair(false, e.message ?: "실행 실패")
            }
        }
}
