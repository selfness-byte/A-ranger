package com.mj.aranger.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.mj.aranger.engine.AdbPowerController
import com.mj.aranger.engine.MacroEngine
import com.mj.aranger.model.ActionStep
import com.mj.aranger.model.ActionType
import com.mj.aranger.model.MacroRule
import com.mj.aranger.model.TriggerType
import com.mj.aranger.service.ARangerAccessibilityService
import com.mj.aranger.service.ARangerNotificationService
import com.mj.aranger.service.FloatingOverlayService
import com.mj.aranger.store.MacroRepository
import com.mj.aranger.update.AppUpdater
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var contentContainer: LinearLayout
    private var currentTab = 0 // 0 = 매크로 목록, 1 = 실행 로그, 2 = 설정·자동업데이트
    private var updateStatusMessage: String = ""

    private val repoListener: () -> Unit = {
        renderScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        MacroEngine.startTimerLoop(applicationContext)

        val curName = AppUpdater.installedVersionName(this)
        val curCode = AppUpdater.installedVersionCode(this)
        updateStatusMessage = "현재 설치 버전: v$curName (build $curCode) · 전 버전 무관 자동 업데이트 지원"

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F141C"))
            isFillViewport = true
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }

        rootScroll.addView(contentContainer)
        setContentView(rootScroll)

        // 앱 시작 시 최신 업데이트 자동 확인
        AppUpdater.checkOnStartup(this, lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        MacroRepository.addChangeListener(repoListener)
        AppUpdater.resumePendingInstallIfAllowed(this)
        renderScreen()
    }

    override fun onPause() {
        MacroRepository.removeChangeListener(repoListener)
        super.onPause()
    }

    private fun renderScreen() {
        contentContainer.removeAllViews()

        // 1. 상단 타이틀 & 설정/마스터 스위치 헤더
        contentContainer.addView(buildHeaderSection())

        // 2. 권한 상태 & 플로팅 리모컨 제어 카드
        contentContainer.addView(buildControlDashboardCard())

        // 3. 스마트 자동 가드 (AutoGuard) 원클릭 스위치 카드
        contentContainer.addView(buildAutoGuardCard())

        // 4. 탭 바 (매크로 / 로그 / 설정·업데이트) & 새 매크로 추가 버튼
        contentContainer.addView(buildTabAndActionRow())

        // 5. 탭 컨텐츠
        when (currentTab) {
            0 -> buildMacroListSection()
            1 -> buildLogsSection()
            else -> buildSettingsAndUpdateSection()
        }
    }

    private fun buildHeaderSection(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val curName = AppUpdater.installedVersionName(this)
        val titleTv = TextView(this).apply {
            text = "⚡ A-ranger Pro"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val subTv = TextView(this).apply {
            text = "v$curName · ADB파워엔진 · 이미지서치 · 비전OCR · 무한루프 · 스포이드"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        titleCol.addView(titleTv)
        titleCol.addView(subTv)
        row.addView(titleCol)

        val settingsShortcutBtn = TextView(this).apply {
            text = "⚙️ 업데이트"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#7C3AED"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                currentTab = 2
                renderScreen()
            }
        }
        row.addView(settingsShortcutBtn)

        val masterOn = MacroRepository.isMasterEnabled(this)
        val masterBtn = TextView(this).apply {
            text = if (masterOn) "엔진 ON" else "엔진 OFF"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor(if (masterOn) "#16A34A" else "#475569"))
            }
            setOnClickListener {
                MacroRepository.setMasterEnabled(this@MainActivity, !masterOn)
            }
        }
        row.addView(masterBtn)

        return row
    }

    private fun buildControlDashboardCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#18202C"))
                setStroke(dp(1), Color.parseColor("#2A364B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val accEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)
        val notifEnabled = isNotificationListenerEnabled()

        val statusTitle = TextView(this).apply {
            text = "🛡️ 핵심 권한 및 화면 제어 리모컨"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(statusTitle)

        card.addView(
            buildPermissionRow(
                title = "1. 화면 제어 (접근성 서비스)",
                subtitle = if (accEnabled) "활성화됨 · 터치/스와이프/화면 UI 분석 준비 완료" else "비활성 · 클릭하여 [A-ranger 화면 제어 엔진]을 켜주세요",
                isGranted = accEnabled,
                actionLabel = if (accEnabled) "설정됨" else "권한 켜기"
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        card.addView(
            buildPermissionRow(
                title = "2. 다른 앱 위에 표시 (플로팅 리모컨)",
                subtitle = if (overlayEnabled) "허용됨 · 리모컨/조준기/화면분석기 사용 가능" else "비허용 · 화면 위 리모컨과 조준기를 쓰려면 허용해 주세요",
                isGranted = overlayEnabled,
                actionLabel = if (overlayEnabled) "허용됨" else "허용하기"
            ) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        )

        card.addView(
            buildPermissionRow(
                title = "3. 알림 읽기 권한 (알림 수신 트리거용)",
                subtitle = if (notifEnabled) "허용됨 · 상단바 알림 키워드 트리거 작동 가능" else "선택 사항 · 알림 수신 트리거를 쓰려면 클릭하여 허용해 주세요",
                isGranted = notifEnabled,
                actionLabel = if (notifEnabled) "허용됨" else "설정"
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        val picked = MacroRepository.getPickedPoint(this)
        val pickedColor = MacroRepository.getPickedColorHex(this)
        val pickedNode = MacroRepository.getPickedNodeInfo(this)
        val hasPath = MacroRepository.getLastRecordedPathJson(this).isNotBlank()
        val hasTemplate = MacroRepository.getLastCapturedTemplateJson(this).isNotBlank()
        val extraInfo = buildString {
            append("🎯 최근 타겟: (${picked.first}, ${picked.second}) · 🎨 색상: $pickedColor")
            if (pickedNode.first.isNotBlank()) append(" · 글자: 「${pickedNode.first}」")
            if (pickedNode.second.isNotBlank()) append(" · ID: ${pickedNode.second.substringAfterLast('/')}")
            if (hasPath) append(" · 📹 궤적녹화됨")
            if (hasTemplate) append(" · 🖼️ 조각캡처됨")
        }
        val pickedInfo = TextView(this).apply {
            text = extraInfo
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(10))
        }
        card.addView(pickedInfo)

        val floatingOn = FloatingOverlayService.isRunning
        val floatingBtn = TextView(this).apply {
            text = if (floatingOn) {
                "⏹ 화면 위 플로팅 리모컨 · 스포이드 · 궤적녹화 끄기"
            } else {
                "🎮 화면 위 플로팅 리모컨 · 스포이드 · 궤적녹화 · UI분석기 띄우기"
            }
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(if (floatingOn) "#DC2626" else "#2563EB"))
            }
            setOnClickListener {
                if (FloatingOverlayService.isRunning) {
                    FloatingOverlayService.stop(this@MainActivity)
                    contentContainer.postDelayed({ renderScreen() }, 250L)
                } else {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        Toast.makeText(this@MainActivity, "먼저 [2. 다른 앱 위에 표시] 권한을 허용해 주세요.", Toast.LENGTH_SHORT).show()
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } else {
                        FloatingOverlayService.start(this@MainActivity)
                        contentContainer.postDelayed({ renderScreen() }, 250L)
                    }
                }
            }
        }
        card.addView(floatingBtn)

        return card
    }

    private fun buildAutoGuardCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#18202C"))
                setStroke(dp(1), Color.parseColor("#2A364B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val titleTv = TextView(this).apply {
            text = "⚡ 원클릭 스마트 가드 (백그라운드 자동 처리)"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(titleTv)

        card.addView(
            buildGuardToggleRow(
                title = "🔌 USB 디버깅 / 데이터 접근 수락 팝업 자동 허용",
                subtitle = "케이블 연결·화면 공유 시 뜨는 시스템 [항상 허용] 및 [허용/지금 시작]을 자동 클릭",
                checked = MacroRepository.isAutoAcceptDialogEnabled(this)
            ) { isChecked ->
                MacroRepository.setAutoAcceptDialogEnabled(this, isChecked)
            }
        )

        card.addView(
            buildGuardToggleRow(
                title = "⏭️ 영상 · 앱 광고 '건너뛰기' 자동 스킵",
                subtitle = "화면에 [광고 건너뛰기], [건너뛰기], [Skip Ad] 버튼이 뜨면 즉시 자동 클릭",
                checked = MacroRepository.isAutoSkipAdEnabled(this)
            ) { isChecked ->
                MacroRepository.setAutoSkipAdEnabled(this, isChecked)
            }
        )

        card.addView(
            buildGuardToggleRow(
                title = "🛡️ 안티 매크로 우회 (Humanizer 랜덤 좌표·간격)",
                subtitle = "터치 좌표를 ±7px, 속도와 간격을 ±15% 범위에서 사람 손처럼 미세하게 변조",
                checked = MacroRepository.isHumanizerEnabled(this)
            ) { isChecked ->
                MacroRepository.setHumanizerEnabled(this, isChecked)
            }
        )

        card.addView(
            buildGuardToggleRow(
                title = "🔊 볼륨[↓] 버튼 & 스마트폰 흔들기 긴급 정지",
                subtitle = "매크로 실행 중 볼륨 다운 키를 누르거나 폰을 흔들면 진행 중인 동작을 즉시 중단",
                checked = MacroRepository.isVolumePanicEnabled(this)
            ) { isChecked ->
                MacroRepository.setVolumePanicEnabled(this, isChecked)
            }
        )

        card.addView(
            buildGuardToggleRow(
                title = "♻️ 24시간 무중단 자가 복구 (Self-Healing 워치독)",
                subtitle = "OS 절전이나 업데이트로 접근성 서비스가 풀려도 5초 내 자동으로 다시 활성화",
                checked = MacroRepository.isSelfHealingEnabled(this)
            ) { isChecked ->
                MacroRepository.setSelfHealingEnabled(this, isChecked)
            }
        )

        card.addView(
            buildGuardToggleRow(
                title = "☀️ 리모컨 실행 중 화면 꺼짐 방지 (Keep Awake)",
                subtitle = "플로팅 리모컨이 떠 있는 동안 스마트폰 화면이 자동으로 꺼지지 않게 유지",
                checked = MacroRepository.isKeepScreenOnEnabled(this)
            ) { isChecked ->
                MacroRepository.setKeepScreenOnEnabled(this, isChecked)
                if (FloatingOverlayService.isRunning) {
                    FloatingOverlayService.start(this)
                }
            }
        )

        card.addView(
            buildGuardToggleRow(
                title = "🚀 [ADB] 시스템 애니메이션 0.0배속 초고속 부스트",
                subtitle = "창·전환·애니메이터 배율을 0.0x로 설정해 앱 전환 및 매크로 인식 속도를 극대화",
                checked = AdbPowerController.isZeroAnimationBoostEnabled(this)
            ) { isChecked ->
                val ok = AdbPowerController.setZeroAnimationBoost(this, isChecked)
                if (!ok) {
                    Toast.makeText(this, "WRITE_SECURE_SETTINGS 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                }
                renderScreen()
            }
        )

        card.addView(
            buildGuardToggleRow(
                title = "🔌 [ADB] 충전 중 화면 절대 안 꺼짐 (Stay Awake)",
                subtitle = "시스템 Global stay_on_while_plugged_in=7 설정으로 충전 시 24시간 화면 유지",
                checked = AdbPowerController.isStayAwakePluggedEnabled(this)
            ) { isChecked ->
                AdbPowerController.setStayAwakePlugged(this, isChecked)
                renderScreen()
            }
        )

        val adbConsoleBtn = TextView(this).apply {
            text = "💻 온디바이스 ADB 쉘 콘솔 & 스크립트 실행기 열기"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#0F766E"))
                setStroke(dp(1), Color.parseColor("#2DD4BF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            setOnClickListener {
                showAdbConsoleDialog()
            }
        }
        card.addView(adbConsoleBtn)

        return card
    }

    private fun showAdbConsoleDialog() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        scroll.addView(box)

        val guideTv = TextView(this).apply {
            text = "PC 연결 없이 스마트폰에서 직접 input tap/swipe/pinch/keyevent, settings get/put, am start/kill 및 리눅스 쉘 명령어를 즉시 실행하거나 매크로로 저장할 수 있습니다."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(10))
        }
        box.addView(guideTv)

        val scriptInput = EditText(this).apply {
            val pt = MacroRepository.getPickedPoint(this@MainActivity)
            setText("getprop ro.product.model\nsettings get global window_animation_scale\n# input tap ${pt.first} ${pt.second}")
            setTextColor(Color.parseColor("#38BDF8"))
            setHintTextColor(Color.parseColor("#64748B"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 13f
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        presetRow.addView(createCardActionButton("🔋 배터리정보", "#334155") {
            scriptInput.setText("dumpsys battery | head -n 12")
        })
        presetRow.addView(space(4))
        presetRow.addView(createCardActionButton("🚀 0배속부스트", "#7C3AED") {
            scriptInput.setText(
                "settings put global window_animation_scale 0.0\n" +
                    "settings put global transition_animation_scale 0.0\n" +
                    "settings put global animator_duration_scale 0.0"
            )
        })
        presetRow.addView(space(4))
        presetRow.addView(createCardActionButton("👆 타겟터치", "#0284C7") {
            val pt = MacroRepository.getPickedPoint(this)
            scriptInput.setText("input tap ${pt.first} ${pt.second}")
        })
        box.addView(presetRow)
        box.addView(scriptInput)

        val outTitle = TextView(this).apply {
            text = "📟 실행 결과 출력:"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        box.addView(outTitle)

        val outputTv = TextView(this).apply {
            text = "명령어를 입력하고 아래 [▶ 즉시 실행] 버튼을 누르세요."
            setTextColor(Color.parseColor("#A7F3D0"))
            setBackgroundColor(Color.parseColor("#020617"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(outputTv)

        val dialog = AlertDialog.Builder(this)
            .setTitle("💻 온디바이스 ADB 파워 콘솔")
            .setView(scroll)
            .setPositiveButton("▶ 즉시 실행", null)
            .setNeutralButton("⚡ 매크로로 저장") { _, _ ->
                val script = scriptInput.text.toString().trim()
                if (script.isNotEmpty()) {
                    val rule = MacroRule(
                        name = "💻 ADB 스크립트 자동 실행",
                        enabled = true,
                        triggerType = TriggerType.MANUAL_FLOATING,
                        actions = listOf(
                            ActionStep(
                                type = ActionType.ADB_SHELL_SCRIPT,
                                textParam = script
                            )
                        )
                    )
                    MacroRepository.upsertMacro(this, rule)
                    Toast.makeText(this, "✅ ADB 스크립트 매크로가 추가되었습니다!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("닫기", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val script = scriptInput.text.toString()
                outputTv.text = "⏳ 실행 중..."
                lifecycleScope.launch {
                    val res = AdbPowerController.executeAdbScript(this@MainActivity, script)
                    outputTv.text = res.output
                }
            }
        }
        dialog.show()
    }

    private fun buildGuardToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tTv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val sTv = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        col.addView(tTv)
        col.addView(sTv)
        row.addView(col)

        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        row.addView(sw)

        return row
    }

    private fun buildPermissionRow(
        title: String,
        subtitle: String,
        isGranted: Boolean,
        actionLabel: String,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dot = if (isGranted) "🟢" else "🔴"
        val titleTv = TextView(this).apply {
            text = "$dot $title"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val subTv = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        textCol.addView(titleTv)
        textCol.addView(subTv)
        row.addView(textCol)

        val btn = TextView(this).apply {
            text = actionLabel
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(if (isGranted) "#334155" else "#D97706"))
            }
            setOnClickListener { onClick() }
        }
        row.addView(btn)

        return row
    }

    private fun buildTabAndActionRow(): View {
        val macros = MacroRepository.getMacros(this)
        val logs = MacroRepository.getLogs(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val tabMacros = createTabButton("📋 매크로 (${macros.size})", currentTab == 0) {
            currentTab = 0
            renderScreen()
        }
        val tabLogs = createTabButton("📜 로그 (${logs.size})", currentTab == 1) {
            currentTab = 1
            renderScreen()
        }
        val tabSettings = createTabButton("⚙️ 설정·업데이트", currentTab == 2) {
            currentTab = 2
            renderScreen()
        }

        row.addView(tabMacros)
        row.addView(space(5))
        row.addView(tabLogs)
        row.addView(space(5))
        row.addView(tabSettings)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        row.addView(spacer)

        val addBtn = TextView(this).apply {
            text = "＋ 추가"
            setTextColor(Color.parseColor("#0F172A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#38BDF8"))
            }
            setOnClickListener {
                MacroEditorDialog.show(this@MainActivity, null) { newRule ->
                    MacroRepository.upsertMacro(this@MainActivity, newRule)
                }
            }
        }
        row.addView(addBtn)

        return row
    }

    /**
     * ⚙️ 설정 및 자동 업데이트 섹션 (inanimals-1977.web.app & GitHub 연동, 전 버전 무관 업데이트 지원)
     */
    private fun buildSettingsAndUpdateSection() {
        val curName = AppUpdater.installedVersionName(this)
        val curCode = AppUpdater.installedVersionCode(this)

        val updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#7C3AED"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val titleTv = TextView(this).apply {
            text = "🚀 자동 업데이트 센터 (inanimals-1977.web.app & GitHub)"
            setTextColor(Color.parseColor("#E9D5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }
        updateCard.addView(titleTv)

        val descTv = TextView(this).apply {
            text = "이전/현재 설치된 버전에 상관없이 언제나 서버의 최신 APK를 다운로드하여 덮어쓰기 업데이트할 수 있습니다."
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(10))
        }
        updateCard.addView(descTv)

        val statusTv = TextView(this).apply {
            text = updateStatusMessage.ifBlank { "현재 버전: v$curName (build $curCode)" }
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#0F172A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        updateCard.addView(statusTv)

        // 1) 버전 확인 후 업데이트 다이얼로그 표시 버튼
        val checkBtn = TextView(this).apply {
            text = "🔄 최신 버전 확인 및 업데이트 (전 버전 호환)"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#7C3AED"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener {
                AppUpdater.checkManual(this@MainActivity, lifecycleScope, installImmediately = false) { msg ->
                    updateStatusMessage = msg
                    statusTv.text = msg
                }
            }
        }
        updateCard.addView(checkBtn)

        // 2) 버전 비교 없이 즉시 다운로드 & 설치 화면 직행 버튼
        val forceInstallBtn = TextView(this).apply {
            text = "⚡ 이전 버전 무관 즉시 최신 APK 다운로드 · 덮어쓰기 설치"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#059669"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            setOnClickListener {
                AppUpdater.checkManual(this@MainActivity, lifecycleScope, installImmediately = true) { msg ->
                    updateStatusMessage = msg
                    statusTv.text = msg
                }
            }
        }
        updateCard.addView(forceInstallBtn)

        // 앱 시작 시 자동 업데이트 확인 토글
        updateCard.addView(
            buildGuardToggleRow(
                title = "🔔 앱 시작 시 새 업데이트 자동 확인",
                subtitle = "앱을 켤 때 inanimals-1977.web.app 및 GitHub에서 새 버전을 자동으로 확인합니다",
                checked = MacroRepository.isAutoCheckUpdateOnStartup(this)
            ) { isChecked ->
                MacroRepository.setAutoCheckUpdateOnStartup(this, isChecked)
            }
        )

        // 포털 & GitHub 바로가기 행
        val linkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val portalBtn = createCardActionButton("🌐 inanimals-1977 포털 열기", "#2563EB") {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdater.WEB_PORTAL_URL)))
            }
        }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val githubBtn = createCardActionButton("🐙 GitHub 저장소 열기", "#334155") {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdater.GITHUB_REPO_URL)))
            }
        }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        linkRow.addView(portalBtn)
        linkRow.addView(githubBtn)
        updateCard.addView(linkRow)

        contentContainer.addView(updateCard)

        // 데이터 백업/복원 및 시스템 설정 카드
        val manageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        val manageTitle = TextView(this).apply {
            text = "🛠️ 데이터 백업 및 시스템 권한 관리"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        manageCard.addView(manageTitle)

        val backupBtn = createCardActionButton("💾 매크로 전체 JSON 백업 / 복원하기", "#0284C7") {
            showBackupRestoreDialog()
        }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        manageCard.addView(backupBtn)

        val unknownSrcBtn = createCardActionButton("📦 '출처를 알 수 없는 앱 설치' 권한 설정", "#475569") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        manageCard.addView(unknownSrcBtn)

        val appInfoBtn = createCardActionButton("⚙️ 안드로이드 시스템 앱 상세 설정 열기", "#334155") {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        manageCard.addView(appInfoBtn)

        contentContainer.addView(manageCard)
    }

    private fun showBackupRestoreDialog() {
        val json = MacroRepository.exportMacrosJson(this)
        val input = EditText(this).apply {
            setText(json)
            setTextColor(Color.parseColor("#F8FAFC"))
            setBackgroundColor(Color.parseColor("#0F172A"))
            textSize = 12f
            minLines = 8
            maxLines = 14
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        AlertDialog.Builder(this)
            .setTitle("💾 매크로 JSON 백업 및 복원")
            .setView(input)
            .setPositiveButton("클립보드 복사") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("aranger_macros_backup", input.text.toString()))
                Toast.makeText(this, "매크로 JSON이 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("JSON으로 복원(가져오기)") { _, _ ->
                try {
                    val count = MacroRepository.importMacrosJson(this, input.text.toString())
                    Toast.makeText(this, "${count}개의 매크로를 가져왔습니다!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "JSON 형식 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun buildMacroListSection() {
        val macros = MacroRepository.getMacros(this)
        if (macros.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "등록된 매크로가 없습니다. 우측 상단 [＋ 추가] 버튼을 눌러 첫 자동화 규칙을 만들어 보세요!"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(dp(12), dp(24), dp(12), dp(24))
                gravity = Gravity.CENTER
            }
            contentContainer.addView(emptyTv)
            return
        }

        macros.forEach { rule ->
            contentContainer.addView(buildMacroCard(rule))
        }
    }

    private fun buildMacroCard(rule: MacroRule): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(
                    dp(1),
                    Color.parseColor(if (rule.enabled) "#38BDF8" else "#334155")
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameTv = TextView(this).apply {
            text = rule.name
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(nameTv)

        val toggle = Switch(this).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, isChecked ->
                MacroRepository.toggleMacro(this@MainActivity, rule.id, isChecked)
            }
        }
        topRow.addView(toggle)
        card.addView(topRow)

        val triggerTv = TextView(this).apply {
            text = rule.triggerSummary()
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(6), 0, dp(2))
        }
        card.addView(triggerTv)

        val constraintTv = TextView(this).apply {
            text = "${rule.constraintSummary()}  ·  ${rule.repeatSummary()}  ·  실행 ${rule.runCount}회"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(constraintTv)

        val stepBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#0F172A"))
            }
        }
        rule.actions.forEachIndexed { idx, step ->
            val stepTv = TextView(this).apply {
                text = "${idx + 1}. ${step.toSummary()}"
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(2), 0, dp(2))
            }
            stepBox.addView(stepTv)
        }
        card.addView(stepBox)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }

        val testBtn = createCardActionButton("▶ 즉시 실행", "#16A34A") {
            MacroEngine.runSingleMacroNow(this, rule)
            Toast.makeText(this, "「${rule.name}」 매크로를 실행했습니다.", Toast.LENGTH_SHORT).show()
        }
        val editBtn = createCardActionButton("✏️ 편집", "#334155") {
            MacroEditorDialog.show(this, rule) { updated ->
                MacroRepository.upsertMacro(this, updated)
            }
        }
        val copyBtn = createCardActionButton("📑 복제", "#475569") {
            MacroRepository.duplicateMacro(this, rule)
        }
        val deleteBtn = createCardActionButton("🗑️ 삭제", "#991B1B") {
            MacroRepository.deleteMacro(this, rule.id)
        }

        actionRow.addView(testBtn)
        actionRow.addView(space(6))
        actionRow.addView(editBtn)
        actionRow.addView(space(6))
        actionRow.addView(copyBtn)
        actionRow.addView(space(6))
        actionRow.addView(deleteBtn)

        card.addView(actionRow)
        return card
    }

    private fun buildLogsSection() {
        val logs = MacroRepository.getLogs(this)

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val clearBtn = createCardActionButton("🗑️ 로그 비우기", "#475569") {
            MacroRepository.clearLogs(this)
        }
        topRow.addView(clearBtn)
        contentContainer.addView(topRow)

        if (logs.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "아직 기록된 매크로 실행 로그가 없습니다."
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(dp(12), dp(24), dp(12), dp(24))
                gravity = Gravity.CENTER
            }
            contentContainer.addView(emptyTv)
            return
        }

        logs.forEach { entry ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val badge = if (entry.isSuccess) "✅" else "⚠️"
            val headerTv = TextView(this).apply {
                text = "$badge [${entry.formattedTime()}] ${entry.macroName}"
                setTextColor(Color.parseColor(if (entry.isSuccess) "#38BDF8" else "#F59E0B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(typeface, Typeface.BOLD)
            }
            val msgTv = TextView(this).apply {
                text = entry.message
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(3), 0, 0)
            }
            item.addView(headerTv)
            item.addView(msgTv)
            contentContainer.addView(item)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (ARangerAccessibilityService.isRunning()) return true
        val expected = ComponentName(this, ARangerAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == expected }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        if (ARangerNotificationService.isConnected) return true
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val expected = ComponentName(this, ARangerNotificationService::class.java).flattenToString()
        return flat.contains(expected)
    }

    private fun createTabButton(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(if (selected) "#0F172A" else "#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(if (selected) "#38BDF8" else "#1E293B"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createCardActionButton(label: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun space(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
