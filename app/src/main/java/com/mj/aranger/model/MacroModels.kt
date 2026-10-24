package com.mj.aranger.model

import android.accessibilityservice.AccessibilityService
import com.mj.aranger.engine.VisionOcrEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class TriggerType(val title: String, val hint: String) {
    MANUAL_FLOATING("🎮 플로팅 리모컨 버튼 클릭", "화면 위 리모컨에서 [▶ 실행]을 누를 때 작동"),
    SCREEN_TEXT_FOUND("🔍 화면에 특정 글자 등장", "감지할 화면 텍스트 (예: 건너뛰기, 확인, 허용)"),
    PIXEL_COLOR_MATCH("🎨 화면 특정 좌표 픽셀 색상 일치 시", "X,Y,#RRGGBB 형식 입력 (예: 540,1200,#38BDF8)"),
    SHAKE_DEVICE("📳 스마트폰 흔들기 감지", "스마트폰을 강하게 흔들었을 때 즉시 작동"),
    APP_OPENED("📱 특정 앱 실행 시", "앱 이름 또는 패키지명 (예: youtube, kakao)"),
    NOTIFICATION_RECEIVED("🔔 알림 수신 시", "알림에 포함될 키워드 (비워두면 모든 알림)"),
    INTERVAL_TIMER("⏱️ 주기적 반복 타이머", "반복 주기(초) 입력 (예: 5, 10, 60)"),
    TIME_DAILY("⏰ 매일 지정 시각 (HH:mm)", "24시간 형식 시각 입력 (예: 08:30, 22:00)"),
    CHARGING_CONNECTED("⚡ 충전기 연결 시", "충전 케이블이나 무선 충전 연결 시 작동"),
    BATTERY_BELOW("🪫 배터리 잔량 N% 이하", "기준 배터리 % 숫자 입력 (예: 20, 15)");

    companion object {
        fun fromName(name: String): TriggerType =
            entries.find { it.name == name } ?: MANUAL_FLOATING
    }
}

enum class ConstraintType(val title: String, val hint: String) {
    NONE("제한 없음 (항상 허용)", ""),
    FOREGROUND_APP_IS("현재 실행 중인 앱이 일치할 때만", "앱 이름 또는 패키지명 일부 (예: youtube, chrome)"),
    SCREEN_CONTAINS_TEXT("화면에 특정 글자가 있을 때만", "화면에 반드시 있어야 하는 텍스트"),
    SCREEN_NOT_CONTAINS_TEXT("화면에 특정 글자가 없을 때만", "화면에 없어야 하는 텍스트"),
    CHARGING_ONLY("충전 중일 때만 실행", ""),
    TIME_WINDOW("특정 시간대일 때만 (HH:mm-HH:mm)", "예: 09:00-18:00 또는 23:00-07:00");

    companion object {
        fun fromName(name: String): ConstraintType =
            entries.find { it.name == name } ?: NONE
    }
}

enum class ActionType(val title: String) {
    TAP_XY("👆 좌표 터치 / 연속 광클 (X, Y)"),
    LONG_PRESS_XY("👇 좌표 길게 누르기 (Long Press)"),
    TAP_IF_COLOR("🎨 좌표 픽셀 색상이 일치할 때만 터치"),
    LOOP_UNTIL_COLOR("🎨 좌표 픽셀 색상이 뜰 때까지 대기·터치"),
    CLICK_IMAGE_TEMPLATE("🖼️ [이미지 서치] 캡처한 아이콘·버튼 이미지를 화면에서 찾아 터치"),
    LOOP_UNTIL_IMAGE("🖼️ [이미지 대기] 캡처한 아이콘 이미지가 뜰 때까지 대기·터치"),
    OCR_CLICK_TEXT("👁️ [비전 OCR] 화면 픽셀/글자 인식으로 텍스트 위치 찾아 터치"),
    OCR_LOOP_UNTIL_TEXT("👁️ [비전 OCR 대기] 화면에 글자가 인식될 때까지 반복 터치"),
    BRANCH_IF_TEXT("🔀 [조건 분기: 글자] 화면 글자 유무에 따라 진행/건너뛰기/종료"),
    BRANCH_IF_COLOR("🔀 [조건 분기: 색상] 픽셀 색상 일치 여부에 따라 진행/건너뛰기/종료"),
    ADB_SHELL_SCRIPT("💻 [ADB 스크립트] input / settings / am / 쉘 명령어 연속 실행"),
    ADB_KEY_EVENT("⌨️ [ADB 키코드] 엔터·백스페이스·미디어·볼륨·방향키 전송"),
    SEND_INTENT_DEEPLINK("🔗 [ADB 인텐트/딥링크] 앱 액티비티·URL·시스템 설정창 즉시 점프"),
    PINCH_ZOOM("🤏 [멀티터치] 두 손가락 핀치 줌인(확대) / 줌아웃(축소)"),
    CLICK_NOTIFICATION("🔔 [알림 즉시 열기] 상단바를 내리지 않고 수신 알림 바로 클릭"),
    DEVICE_CONTROL("🎛️ [시스템 제어] 애니메이션 0배속·화면밝기·벨소리·앱정리·TTS 음성"),
    CUSTOM_GESTURE_PATH("📹 녹화한 자유 곡선·패턴 제스처 재생"),
    CLICK_TEXT("🎯 화면 글자·버튼 찾아 클릭 (접근성 노드)"),
    CLICK_VIEW_ID("🧩 뷰 ID(Resource ID) 찾아 클릭"),
    LOOP_UNTIL_TEXT("🔁 특정 글자 뜰 때까지 좌표 반복 터치"),
    SWIPE("👋 화면 스와이프 / 스크롤"),
    INPUT_TEXT("⌨️ 입력창에 텍스트 자동 입력"),
    CLIPBOARD_PASTE("📋 클립보드 복사 & 즉시 붙여넣기"),
    WEBHOOK_HTTP("🌐 웹훅(HTTP URL) 요청 전송"),
    WAIT("⏳ 대기 / 딜레이 (밀리초)"),
    GLOBAL_ACTION("🔘 시스템 버튼 (뒤로/홈/잠금/캡처)"),
    MEDIA_VOLUME("🔊 미디어 볼륨 조절 (0~100%)"),
    LAUNCH_APP("🚀 앱 실행하기"),
    TOAST_VIBRATE("💬 토스트 메시지 & 진동 알림");

    companion object {
        fun fromName(name: String): ActionType =
            entries.find { it.name == name } ?: TAP_XY
    }
}

enum class BranchBehavior(val code: Int, val label: String) {
    CONTINUE_IF_MATCH(0, "✅ 조건 일치 시 계속 진행 / 불일치 시 매크로 즉시 종료"),
    ABORT_IF_MATCH(1, "🛑 조건 일치 시 매크로 즉시 종료 (무한 루프 탈출)"),
    SKIP_NEXT_IF_NOT_MATCH(2, "⏭️ IF 조건 일치 시 다음 단계 실행 / 불일치 시 1단계 건너뛰기"),
    SKIP_NEXT_IF_MATCH(3, "⏭️ 조건 일치 시 바로 다음 1단계 건너뛰기");

    companion object {
        fun fromCode(code: Int): BranchBehavior =
            entries.find { it.code == code } ?: CONTINUE_IF_MATCH
    }
}

enum class DeviceControlOption(val code: Int, val label: String) {
    ANIM_BOOST_ON(0, "🚀 애니메이션 0.0배속 초고속 부스트 켜기"),
    ANIM_BOOST_OFF(1, "🔄 애니메이션 1.0배속 기본값 복원"),
    BRIGHTNESS_SET(2, "🔆 화면 밝기 지정 (1~100%)"),
    RINGER_NORMAL(3, "🔔 벨소리 모드로 전환"),
    RINGER_VIBRATE(4, "📳 진동 모드로 전환"),
    RINGER_SILENT(5, "🔇 무음 모드로 전환"),
    KILL_BG_APP(6, "🧹 백그라운드 앱 프로세스 강제 종료 (am kill)"),
    SPEAK_TTS(7, "🗣️ 한국어 음성(TTS) 안내 읽기");

    companion object {
        fun fromCode(code: Int): DeviceControlOption =
            entries.find { it.code == code } ?: ANIM_BOOST_ON
    }
}

enum class GlobalActionOption(val label: String, val serviceAction: Int) {
    BACK("뒤로가기 (Back)", AccessibilityService.GLOBAL_ACTION_BACK),
    HOME("홈 화면 이동 (Home)", AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS("최근 앱 목록 (Recents)", AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS("상단 알림창 내리기", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS("빠른 설정창(퀵패널) 내리기", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
    LOCK_SCREEN("화면 잠그기 (Android 9+)", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
    TAKE_SCREENSHOT("화면 캡처 (Android 9+)", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);

    companion object {
        fun fromName(name: String): GlobalActionOption =
            entries.find { it.name == name } ?: BACK
    }
}

data class UiNodeItem(
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val className: String,
    val centerX: Int,
    val centerY: Int,
    val boundsStr: String,
    val isClickable: Boolean
) {
    fun displayLabel(): String {
        val main = when {
            text.isNotBlank() -> "「$text」"
            contentDesc.isNotBlank() -> "설명: 「$contentDesc」"
            viewId.isNotBlank() -> "ID: ${viewId.substringAfterLast('/')}"
            else -> className.substringAfterLast('.')
        }
        val clickTag = if (isClickable) "[클릭가능]" else ""
        return "$main $clickTag ($centerX, $centerY)"
    }
}

data class ActionStep(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType = ActionType.TAP_XY,
    val x: Int = 540,
    val y: Int = 1200,
    val x2: Int = 540,
    val y2: Int = 500,
    val durationMs: Long = 120L,
    val repeatCount: Int = 1,
    val repeatIntervalMs: Long = 250L,
    val textParam: String = "",
    val globalActionName: String = GlobalActionOption.BACK.name,
    val colorHex: String = "#38BDF8",
    val gesturePathJson: String = "",
    val templateJson: String = ""
) {
    fun toSummary(): String = when (type) {
        ActionType.TAP_XY -> {
            if (repeatCount > 1) {
                "👆 좌표 ($x, $y) 터치 × ${repeatCount}회 (${repeatIntervalMs}ms 간격)"
            } else {
                "👆 좌표 ($x, $y) 터치 (${durationMs}ms)"
            }
        }
        ActionType.LONG_PRESS_XY -> "👇 좌표 ($x, $y) 길게 누르기 (${durationMs.coerceAtLeast(500L)}ms)"
        ActionType.TAP_IF_COLOR -> "🎨 ($x, $y) 색상이 $colorHex 일 때만 터치"
        ActionType.LOOP_UNTIL_COLOR -> "🎨 ($x, $y) 색상이 $colorHex 될 때까지 대기·터치 (최대 ${repeatCount}회)"
        ActionType.CLICK_IMAGE_TEMPLATE -> "🖼️ 이미지 서치 터치 [${VisionOcrEngine.getTemplateSummary(templateJson)}]"
        ActionType.LOOP_UNTIL_IMAGE -> "🖼️ 이미지 뜰 때까지 대기·터치 (최대 ${repeatCount}회) [${VisionOcrEngine.getTemplateSummary(templateJson)}]"
        ActionType.OCR_CLICK_TEXT -> "👁️ 비전 OCR 텍스트 「$textParam」 찾아 터치"
        ActionType.OCR_LOOP_UNTIL_TEXT -> "👁️ 비전 OCR 「$textParam」 인식될 때까지 ($x, $y) 반복 터치 (최대 ${repeatCount}회)"
        ActionType.BRANCH_IF_TEXT -> "🔀 글자 분기 「$textParam」 → ${BranchBehavior.fromCode(x).label}"
        ActionType.BRANCH_IF_COLOR -> "🔀 색상 분기 ($x,$y = $colorHex) → ${BranchBehavior.fromCode(x2).label}"
        ActionType.ADB_SHELL_SCRIPT -> {
            val firstCmd = textParam.lines()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: "명령어"
            val lineCount = textParam.lines()
                .count { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
                .coerceAtLeast(1)
            "💻 ADB 스크립트: 「${firstCmd.take(36)}」 (${lineCount}줄)"
        }
        ActionType.ADB_KEY_EVENT -> "⌨️ ADB 키코드 전송: ${textParam.ifBlank { "KEYCODE_ENTER" }} × ${repeatCount}회"
        ActionType.SEND_INTENT_DEEPLINK -> "🔗 인텐트/딥링크 점프: ${textParam.take(42)}"
        ActionType.PINCH_ZOOM -> {
            val dirLabel = if (x2 == 0) "줌인(확대 🔍+)" else "줌아웃(축소 🔍-)"
            "🤏 멀티터치 $dirLabel 중심($x, $y) 폭 ${y2.coerceIn(180, 900)}px (${durationMs}ms)"
        }
        ActionType.CLICK_NOTIFICATION -> "🔔 상단바 알림 즉시 열기: 「${textParam.ifBlank { "최근 수신 알림" }}」"
        ActionType.DEVICE_CONTROL -> {
            val opt = DeviceControlOption.fromCode(x)
            when (opt) {
                DeviceControlOption.BRIGHTNESS_SET -> "🎛️ 화면 밝기 ${y.coerceIn(1, 100)}% 설정"
                DeviceControlOption.KILL_BG_APP -> "🎛️ 백그라운드 앱 종료: ${textParam.ifBlank { "대상 앱" }}"
                DeviceControlOption.SPEAK_TTS -> "🎛️ TTS 음성 안내: 「${textParam.take(28)}」"
                else -> "🎛️ 시스템 제어: ${opt.label}"
            }
        }
        ActionType.CUSTOM_GESTURE_PATH -> "📹 자유 궤적·패턴 제스처 재생 (${durationMs}ms, ${repeatCount}회)"
        ActionType.CLICK_TEXT -> "🎯 화면 글자 「$textParam」 찾아 클릭"
        ActionType.CLICK_VIEW_ID -> "🧩 뷰 ID 「$textParam」 찾아 클릭"
        ActionType.LOOP_UNTIL_TEXT -> "🔁 「$textParam」 뜰 때까지 ($x, $y) 반복 터치 (최대 ${repeatCount}회)"
        ActionType.SWIPE -> "👋 스와이프 ($x, $y) → ($x2, $y2) (${durationMs}ms, ${repeatCount}회)"
        ActionType.INPUT_TEXT -> "⌨️ 텍스트 입력: 「$textParam」"
        ActionType.CLIPBOARD_PASTE -> "📋 클립보드 복사 & 붙여넣기: 「$textParam」"
        ActionType.WEBHOOK_HTTP -> "🌐 웹훅 전송: $textParam"
        ActionType.WAIT -> "⏳ ${durationMs}ms 대기"
        ActionType.GLOBAL_ACTION -> "🔘 시스템: ${GlobalActionOption.fromName(globalActionName).label}"
        ActionType.MEDIA_VOLUME -> "🔊 미디어 볼륨 ${x.coerceIn(0, 100)}%로 설정"
        ActionType.LAUNCH_APP -> "🚀 앱 실행: $textParam"
        ActionType.TOAST_VIBRATE -> "💬 알림: 「${textParam.ifBlank { "매크로 실행됨" }}」"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("x", x)
        put("y", y)
        put("x2", x2)
        put("y2", y2)
        put("durationMs", durationMs)
        put("repeatCount", repeatCount)
        put("repeatIntervalMs", repeatIntervalMs)
        put("textParam", textParam)
        put("globalActionName", globalActionName)
        put("colorHex", colorHex)
        put("gesturePathJson", gesturePathJson)
        put("templateJson", templateJson)
    }

    companion object {
        fun fromJson(obj: JSONObject): ActionStep = ActionStep(
            id = obj.optString("id", UUID.randomUUID().toString()),
            type = ActionType.fromName(obj.optString("type", ActionType.TAP_XY.name)),
            x = obj.optInt("x", 540),
            y = obj.optInt("y", 1200),
            x2 = obj.optInt("x2", 540),
            y2 = obj.optInt("y2", 500),
            durationMs = obj.optLong("durationMs", 120L),
            repeatCount = obj.optInt("repeatCount", 1).coerceAtLeast(1),
            repeatIntervalMs = obj.optLong("repeatIntervalMs", 250L).coerceAtLeast(40L),
            textParam = obj.optString("textParam", ""),
            globalActionName = obj.optString("globalActionName", GlobalActionOption.BACK.name),
            colorHex = obj.optString("colorHex", "#38BDF8").ifBlank { "#38BDF8" },
            gesturePathJson = obj.optString("gesturePathJson", ""),
            templateJson = obj.optString("templateJson", "")
        )
    }
}

data class MacroRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val triggerType: TriggerType = TriggerType.MANUAL_FLOATING,
    val triggerParam: String = "",
    val constraintType: ConstraintType = ConstraintType.NONE,
    val constraintParam: String = "",
    val cooldownMs: Long = 1500L,
    val repeatCount: Int = 1, // 0 = 무한 반복, 1 = 1회 실행, 2+ = N회 반복
    val repeatDelayMs: Long = 500L,
    val actions: List<ActionStep> = emptyList(),
    val lastRunAt: Long = 0L,
    val runCount: Int = 0
) {
    fun triggerSummary(): String = when (triggerType) {
        TriggerType.MANUAL_FLOATING -> "⚡ 트리거: 플로팅 리모컨 [▶ 실행] 버튼"
        TriggerType.SCREEN_TEXT_FOUND -> "⚡ 트리거: 화면에 「$triggerParam」 등장 시"
        TriggerType.PIXEL_COLOR_MATCH -> "⚡ 트리거: 픽셀 색상 일치 ($triggerParam)"
        TriggerType.SHAKE_DEVICE -> "⚡ 트리거: 스마트폰 흔들기 감지"
        TriggerType.APP_OPENED -> "⚡ 트리거: 앱 「$triggerParam」 실행 시"
        TriggerType.NOTIFICATION_RECEIVED -> {
            if (triggerParam.isBlank()) "⚡ 트리거: 모든 알림 수신 시"
            else "⚡ 트리거: 알림에 「$triggerParam」 포함 시"
        }
        TriggerType.INTERVAL_TIMER -> "⚡ 트리거: ${triggerParam.ifBlank { "10" }}초마다 주기적 반복"
        TriggerType.TIME_DAILY -> "⚡ 트리거: 매일 ${triggerParam.ifBlank { "08:30" }} 정각"
        TriggerType.CHARGING_CONNECTED -> "⚡ 트리거: 충전기 연결 시"
        TriggerType.BATTERY_BELOW -> "⚡ 트리거: 배터리 ${triggerParam.ifBlank { "20" }}% 이하 시"
    }

    fun constraintSummary(): String = when (constraintType) {
        ConstraintType.NONE -> "🔍 조건: 제한 없음"
        ConstraintType.FOREGROUND_APP_IS -> "🔍 조건: 현재 앱이 「$constraintParam」일 때만"
        ConstraintType.SCREEN_CONTAINS_TEXT -> "🔍 조건: 화면에 「$constraintParam」 있을 때만"
        ConstraintType.SCREEN_NOT_CONTAINS_TEXT -> "🔍 조건: 화면에 「$constraintParam」 없을 때만"
        ConstraintType.CHARGING_ONLY -> "🔍 조건: 충전 중일 때만"
        ConstraintType.TIME_WINDOW -> "🔍 조건: 시간대 ($constraintParam)일 때만"
    }

    fun repeatSummary(): String = when {
        repeatCount == 0 -> "♾️ 전체 반복: 무한 반복 (사이클 간격 ${repeatDelayMs}ms)"
        repeatCount <= 1 -> "🔁 전체 반복: 1회 실행"
        else -> "🔁 전체 반복: ${repeatCount}회 연속 실행 (사이클 간격 ${repeatDelayMs}ms)"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("triggerType", triggerType.name)
        put("triggerParam", triggerParam)
        put("constraintType", constraintType.name)
        put("constraintParam", constraintParam)
        put("cooldownMs", cooldownMs)
        put("repeatCount", repeatCount)
        put("repeatDelayMs", repeatDelayMs)
        put("lastRunAt", lastRunAt)
        put("runCount", runCount)
        val arr = JSONArray()
        actions.forEach { arr.put(it.toJson()) }
        put("actions", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroRule {
            val arr = obj.optJSONArray("actions") ?: JSONArray()
            val stepList = mutableListOf<ActionStep>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { stepList.add(ActionStep.fromJson(it)) }
            }
            return MacroRule(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "새 매크로"),
                enabled = obj.optBoolean("enabled", true),
                triggerType = TriggerType.fromName(obj.optString("triggerType", TriggerType.MANUAL_FLOATING.name)),
                triggerParam = obj.optString("triggerParam", ""),
                constraintType = ConstraintType.fromName(obj.optString("constraintType", ConstraintType.NONE.name)),
                constraintParam = obj.optString("constraintParam", ""),
                cooldownMs = obj.optLong("cooldownMs", 1500L),
                repeatCount = obj.optInt("repeatCount", 1).coerceIn(0, 9999),
                repeatDelayMs = obj.optLong("repeatDelayMs", 500L).coerceAtLeast(100L),
                actions = stepList,
                lastRunAt = obj.optLong("lastRunAt", 0L),
                runCount = obj.optInt("runCount", 0)
            )
        }
    }
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val macroName: String,
    val message: String,
    val isSuccess: Boolean = true
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        return sdf.format(Date(timestamp))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("macroName", macroName)
        put("message", message)
        put("isSuccess", isSuccess)
    }

    companion object {
        fun fromJson(obj: JSONObject): LogEntry = LogEntry(
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            macroName = obj.optString("macroName", "시스템"),
            message = obj.optString("message", ""),
            isSuccess = obj.optBoolean("isSuccess", true)
        )
    }
}
