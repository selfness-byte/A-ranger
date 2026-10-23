package com.mj.aranger.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mj.aranger.engine.MacroEngine
import com.mj.aranger.model.ActionStep
import com.mj.aranger.model.ActionType
import com.mj.aranger.model.ConstraintType
import com.mj.aranger.model.MacroRule
import com.mj.aranger.model.TriggerType
import com.mj.aranger.store.MacroRepository
import com.mj.aranger.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "aranger_overlay_channel"
        private const val NOTIFICATION_ID = 7101

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "먼저 [다른 앱 위에 표시] 권한을 허용해 주세요.", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var crosshairView: View? = null
    private var inspectorView: View? = null
    private var pathRecorderView: View? = null
    private var subRecorderRow: LinearLayout? = null
    private var coordLabel: TextView? = null
    private var colorDotView: View? = null
    private var crosshairVisible = false

    private val recordedPoints = mutableListOf<Pair<Int, Int>>()
    private val pinMarkerViews = mutableListOf<View>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        MacroEngine.startTimerLoop(applicationContext)
        showFloatingPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyKeepScreenOnFlag()
        return START_STICKY
    }

    override fun onDestroy() {
        clearPinMarkers()
        removeCrosshair()
        removeInspectorModal()
        removePathRecorderOverlay()
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelView = null
        isRunning = false
        super.onDestroy()
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun applyKeepScreenOnFlag() {
        val view = panelView ?: return
        val params = panelParams ?: return
        val keepAwake = MacroRepository.isKeepScreenOnEnabled(this)
        params.flags = if (keepAwake) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "A-ranger 화면 제어 리모컨",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "화면 위 플로팅 리모컨 및 매크로 백그라운드 실행 유지"
            }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("A-ranger Pro v1.3 화면 제어 리모컨 실행 중")
            .setContentText("터치하여 A-ranger 대시보드 열기")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateHeaderInfo(x: Int, y: Int, colorHex: String) {
        coordLabel?.text = "⚡ A-ranger Pro  ($x, $y)  🎨$colorHex"
        try {
            (colorDotView?.background as? GradientDrawable)?.setColor(Color.parseColor(colorHex))
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingPanel() {
        if (panelView != null) return

        var baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (MacroRepository.isKeepScreenOnEnabled(this)) {
            baseFlags = baseFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 230
        }
        panelParams = params

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#EC0F172A"))
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
        }

        val picked = MacroRepository.getPickedPoint(this)
        val pickedColor = MacroRepository.getPickedColorHex(this)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                marginEnd = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                runCatching { setColor(Color.parseColor(pickedColor)) }
                setStroke(dp(1), Color.WHITE)
            }
        }
        colorDotView = swatch
        headerRow.addView(swatch)

        val header = TextView(this).apply {
            text = "⚡ A-ranger Pro  (${picked.first}, ${picked.second})  🎨$pickedColor"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }
        coordLabel = header
        headerRow.addView(header)
        root.addView(headerRow)

        // 메인 버튼 행: [▶ 실행] [⏹ 정지] [🎯 조준] [📹 궤적] [🔍 분석] [✕]
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnRun = createChipButton("▶ 실행", "#16A34A") {
            MacroEngine.triggerManualFloating(this)
        }
        val btnStop = createChipButton("⏹ 정지", "#DC2626") {
            MacroEngine.stopAllExecutions(this)
        }
        val btnTarget = createChipButton("🎯 조준", "#D97706") {
            toggleCrosshair()
        }
        val btnPathRec = createChipButton("📹 궤적", "#E11D48") {
            showTouchPathRecorderOverlay()
        }
        val btnInspect = createChipButton("🔍 분석", "#7C3AED") {
            showUiInspectorOverlay()
        }
        val btnClose = createChipButton("✕", "#475569") {
            stopSelf()
        }

        buttonRow.addView(btnRun)
        buttonRow.addView(spaceView(4))
        buttonRow.addView(btnStop)
        buttonRow.addView(spaceView(4))
        buttonRow.addView(btnTarget)
        buttonRow.addView(spaceView(4))
        buttonRow.addView(btnPathRec)
        buttonRow.addView(spaceView(4))
        buttonRow.addView(btnInspect)
        buttonRow.addView(spaceView(4))
        buttonRow.addView(btnClose)
        root.addView(buttonRow)

        // 서브 레코더 행 (조준기가 켜졌을 때만 표시: [🎨 스포이드] [🎨 색상매크로] [➕ 핀추가] [💾 핀매크로] [초기화])
        val recorderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        val btnEyedropper = createChipButton("🎨 스포이드", "#0D9488") {
            sampleColorAtCrosshair(showToastResult = true)
        }
        val btnColorMacro = createChipButton("🎨 색상매크로", "#9333EA") {
            createColorConditionalMacro()
        }
        val btnAddPin = createChipButton("➕ 핀추가", "#0284C7") {
            addCurrentCrosshairPin()
        }
        val btnSaveSeq = createChipButton("💾 핀저장", "#059669") {
            saveRecordedSequenceAsMacro()
        }
        val btnClearPins = createChipButton("지움", "#475569") {
            clearPinMarkers()
            Toast.makeText(this, "찍어둔 핀을 모두 지웠습니다.", Toast.LENGTH_SHORT).show()
        }
        recorderRow.addView(btnEyedropper)
        recorderRow.addView(spaceView(4))
        recorderRow.addView(btnColorMacro)
        recorderRow.addView(spaceView(4))
        recorderRow.addView(btnAddPin)
        recorderRow.addView(spaceView(4))
        recorderRow.addView(btnSaveSeq)
        recorderRow.addView(spaceView(4))
        recorderRow.addView(btnClearPins)
        subRecorderRow = recorderRow
        root.addView(recorderRow)

        // 패널 드래그 이동 처리
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(root, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        panelView = root
        windowManager.addView(root, params)
    }

    private fun toggleCrosshair() {
        if (crosshairVisible) {
            removeCrosshair()
            clearPinMarkers()
            subRecorderRow?.visibility = View.GONE
            Toast.makeText(this, "좌표·색상 조준기를 숨겼습니다.", Toast.LENGTH_SHORT).show()
        } else {
            showCrosshair()
            subRecorderRow?.visibility = View.VISIBLE
            Toast.makeText(
                this,
                "🎯 십자 타겟을 놓으면 좌표와 픽셀 색상(#HEX)이 자동 추출됩니다!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCrosshair() {
        if (crosshairView != null) return

        val sizePx = dp(52)
        val picked = MacroRepository.getPickedPoint(this)

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (picked.first - sizePx / 2).coerceAtLeast(0)
            y = (picked.second - sizePx / 2).coerceAtLeast(0)
        }

        val target = TextView(this).apply {
            text = "┼"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFEB3B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#99EF4444"))
                setStroke(dp(2), Color.parseColor("#FFEB3B"))
            }
        }

        var initX = 0
        var initY = 0
        var downRawX = 0f
        var downRawY = 0f

        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initX + (event.rawX - downRawX).toInt()).coerceAtLeast(0)
                    params.y = (initY + (event.rawY - downRawY).toInt()).coerceAtLeast(0)
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (_: Exception) {
                    }
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val cx = loc[0] + v.width / 2
                    val cy = loc[1] + v.height / 2
                    updateHeaderInfo(cx, cy, MacroRepository.getPickedColorHex(this))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val cx = loc[0] + v.width / 2
                    val cy = loc[1] + v.height / 2
                    MacroRepository.savePickedPoint(this, cx, cy)
                    sampleColorAtCrosshair(
                        showToastResult = abs(event.rawX - downRawX) < 10 && abs(event.rawY - downRawY) < 10
                    )
                    true
                }
                else -> false
            }
        }

        crosshairView = target
        crosshairVisible = true
        windowManager.addView(target, params)
    }

    /**
     * 🎨 조준기 중심의 실제 화면 픽셀 색상(#RRGGBB)을 추출합니다.
     * 십자선 자체의 색상이 찍히지 않도록 80ms 동안 십자선을 투명하게 숨긴 후 캡처합니다.
     */
    private fun sampleColorAtCrosshair(showToastResult: Boolean) {
        val svc = ARangerAccessibilityService.instance
        val pt = MacroRepository.getPickedPoint(this)
        if (svc == null) {
            if (showToastResult) {
                Toast.makeText(this, "접근성 서비스가 켜져 있어야 픽셀 색상을 추출할 수 있습니다.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        overlayScope.launch {
            crosshairView?.alpha = 0f
            delay(85L)
            val hex = svc.samplePixelColor(pt.first, pt.second)
            crosshairView?.alpha = 1f
            if (hex != null) {
                MacroRepository.savePickedPoint(
                    this@FloatingOverlayService,
                    pt.first,
                    pt.second,
                    colorHex = hex
                )
                updateHeaderInfo(pt.first, pt.second, hex)
                if (showToastResult) {
                    Toast.makeText(
                        this@FloatingOverlayService,
                        "🎨 좌표 (${pt.first}, ${pt.second}) 색상 추출: $hex",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (showToastResult) {
                Toast.makeText(
                    this@FloatingOverlayService,
                    "📌 좌표 (${pt.first}, ${pt.second}) 저장됨",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun createColorConditionalMacro() {
        val pt = MacroRepository.getPickedPoint(this)
        val hex = MacroRepository.getPickedColorHex(this)
        val rule = MacroRule(
            name = "🎨 색상 감지 터치 (${pt.first},${pt.second} = $hex)",
            enabled = true,
            triggerType = TriggerType.MANUAL_FLOATING,
            constraintType = ConstraintType.NONE,
            actions = listOf(
                ActionStep(
                    type = ActionType.LOOP_UNTIL_COLOR,
                    x = pt.first,
                    y = pt.second,
                    colorHex = hex,
                    durationMs = 95L,
                    repeatCount = 20,
                    repeatIntervalMs = 400L
                ),
                ActionStep(
                    type = ActionType.TOAST_VIBRATE,
                    textParam = "🎨 색상($hex) 일치 확인 후 자동 터치 완료!"
                )
            )
        )
        MacroRepository.upsertMacro(this, rule)
        Toast.makeText(
            this,
            "✅ 「${rule.name}」 매크로가 생성되었습니다! 리모컨 [▶ 실행]으로 바로 작동합니다.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 📹 자유 곡선·패턴 제스처 레코더 오버레이:
     * 화면 전체에 반투명 드로잉 캔버스를 띄워 사용자가 한 손가락으로 그린 곡선/스와이프/패턴을
     * 그대로 녹화하고 즉시 실행 가능한 매크로로 저장합니다.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showTouchPathRecorderOverlay() {
        removePathRecorderOverlay()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#660F172A"))
        }

        val drawPath = Path()
        val sampledPts = mutableListOf<Pair<Int, Int>>()
        var downTimeMs = 0L

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = dp(5).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val canvasView = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawPath(drawPath, paint)
            }
        }

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#EE0F172A"))
                setStroke(dp(1), Color.parseColor("#E11D48"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(60)
            }
        }

        val bannerTv = TextView(this).apply {
            text = "📹 화면 위에 원하는 스와이프·곡선·패턴을 한 번에 그리세요! (손을 떼면 즉시 저장)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, dp(10), 0)
        }
        val cancelBtn = createChipButton("취소 ✕", "#475569") {
            removePathRecorderOverlay()
        }
        banner.addView(bannerTv)
        banner.addView(cancelBtn)

        canvasView.setOnTouchListener { v, event ->
            val rx = event.rawX.toInt()
            val ry = event.rawY.toInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drawPath.reset()
                    sampledPts.clear()
                    downTimeMs = System.currentTimeMillis()
                    drawPath.moveTo(event.x, event.y)
                    sampledPts.add(Pair(rx, ry))
                    v.invalidate()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    drawPath.lineTo(event.x, event.y)
                    val last = sampledPts.lastOrNull()
                    if (last == null || abs(rx - last.first) + abs(ry - last.second) >= 12) {
                        if (sampledPts.size < 120) {
                            sampledPts.add(Pair(rx, ry))
                        }
                    }
                    v.invalidate()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    sampledPts.add(Pair(rx, ry))
                    val durMs = (System.currentTimeMillis() - downTimeMs).coerceIn(120L, 8000L)
                    if (sampledPts.size >= 2) {
                        val ptsArr = JSONArray()
                        sampledPts.forEach { pt ->
                            ptsArr.put(JSONArray().put(pt.first).put(pt.second))
                        }
                        val pathJson = JSONObject().apply {
                            put("durationMs", durMs)
                            put("points", ptsArr)
                        }.toString()

                        MacroRepository.saveRecordedPathJson(this, pathJson, sampledPts.size, durMs)
                        val newRule = MacroRule(
                            name = "📹 자유 궤적 제스처 (${sampledPts.size}점, ${durMs}ms)",
                            enabled = true,
                            triggerType = TriggerType.MANUAL_FLOATING,
                            constraintType = ConstraintType.NONE,
                            actions = listOf(
                                ActionStep(
                                    type = ActionType.CUSTOM_GESTURE_PATH,
                                    durationMs = durMs,
                                    repeatCount = 1,
                                    gesturePathJson = pathJson
                                )
                            )
                        )
                        MacroRepository.upsertMacro(this, newRule)
                        Toast.makeText(
                            this,
                            "✅ 「${newRule.name}」 녹화 완료! 리모컨 [▶ 실행]을 누르면 그대로 재현됩니다.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    removePathRecorderOverlay()
                    true
                }
                else -> false
            }
        }

        container.addView(canvasView)
        container.addView(banner)
        pathRecorderView = container
        windowManager.addView(container, params)
    }

    private fun removePathRecorderOverlay() {
        pathRecorderView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        pathRecorderView = null
    }

    private fun addCurrentCrosshairPin() {
        val pt = MacroRepository.getPickedPoint(this)
        recordedPoints.add(pt)
        val indexNum = recordedPoints.size
        val badgeSize = dp(30)

        val params = WindowManager.LayoutParams(
            badgeSize,
            badgeSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (pt.first - badgeSize / 2).coerceAtLeast(0)
            y = (pt.second - badgeSize / 2).coerceAtLeast(0)
        }

        val badge = TextView(this).apply {
            text = indexNum.toString()
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC0284C7"))
                setStroke(dp(1), Color.WHITE)
            }
        }

        try {
            windowManager.addView(badge, params)
            pinMarkerViews.add(badge)
            Toast.makeText(this, "핀 #$indexNum (${pt.first}, ${pt.second}) 추가됨!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun saveRecordedSequenceAsMacro() {
        if (recordedPoints.isEmpty()) {
            Toast.makeText(this, "먼저 [➕ 핀추가]를 눌러 최소 1개 이상의 위치를 찍어주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val steps = mutableListOf<ActionStep>()
        recordedPoints.forEachIndexed { idx, pt ->
            steps.add(
                ActionStep(
                    type = ActionType.TAP_XY,
                    x = pt.first,
                    y = pt.second,
                    durationMs = 90L,
                    repeatCount = 1
                )
            )
            if (idx < recordedPoints.size - 1) {
                steps.add(ActionStep(type = ActionType.WAIT, durationMs = 450L))
            }
        }
        val newRule = MacroRule(
            name = "🎯 연속 핀 터치 (${recordedPoints.size}곳)",
            enabled = true,
            triggerType = TriggerType.MANUAL_FLOATING,
            constraintType = ConstraintType.NONE,
            actions = steps
        )
        MacroRepository.upsertMacro(this, newRule)
        clearPinMarkers()
        Toast.makeText(
            this,
            "✅ 「${newRule.name}」 매크로가 생성되었습니다! 리모컨 [▶ 실행]을 누르면 바로 작동합니다.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun clearPinMarkers() {
        pinMarkerViews.forEach {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        pinMarkerViews.clear()
        recordedPoints.clear()
    }

    private fun removeCrosshair() {
        crosshairView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        crosshairView = null
        crosshairVisible = false
    }

    private fun showUiInspectorOverlay() {
        removeInspectorModal()
        val svc = ARangerAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "접근성 서비스가 꺼져 있어 화면 요소를 분석할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val nodes = svc.inspectCurrentScreenNodes()
        val dm = resources.displayMetrics

        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.90).toInt(),
            (dm.heightPixels * 0.62).toInt(),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val modal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F50F172A"))
                setStroke(dp(2), Color.parseColor("#A855F7"))
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val titleTv = TextView(this).apply {
            text = "🔍 현재 화면 UI 요소 분석 (${nodes.size}개 감지됨)"
            setTextColor(Color.parseColor("#E9D5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = createChipButton("닫기 ✕", "#475569") {
            removeInspectorModal()
        }
        topBar.addView(titleTv)
        topBar.addView(closeBtn)
        modal.addView(topBar)

        val guideTv = TextView(this).apply {
            text = "원하는 버튼/글자를 누르면 좌표·ID가 저장되며, [⚡ 매크로화]를 누르면 즉시 자동 클릭 규칙으로 등록됩니다."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, 0, 0, dp(8))
        }
        modal.addView(guideTv)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listCol)

        if (nodes.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "감지된 화면 요소가 없습니다. 분석할 앱 화면을 띄운 상태에서 다시 눌러보세요."
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(8), dp(20), dp(8), dp(20))
            }
            listCol.addView(emptyTv)
        } else {
            nodes.forEach { item ->
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
                        bottomMargin = dp(6)
                    }
                }

                val infoCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        MacroRepository.savePickedPoint(
                            this@FloatingOverlayService,
                            item.centerX,
                            item.centerY,
                            item.text.ifBlank { item.contentDesc },
                            item.viewId
                        )
                        updateHeaderInfo(
                            item.centerX,
                            item.centerY,
                            MacroRepository.getPickedColorHex(this@FloatingOverlayService)
                        )
                        Toast.makeText(
                            this@FloatingOverlayService,
                            "📌 타겟 저장됨: (${item.centerX}, ${item.centerY})",
                            Toast.LENGTH_SHORT
                        ).show()
                        removeInspectorModal()
                    }
                }

                val labelTv = TextView(this).apply {
                    text = item.displayLabel()
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTypeface(typeface, Typeface.BOLD)
                }
                val subInfo = buildString {
                    if (item.viewId.isNotBlank()) append("ID: ${item.viewId}  ")
                    append("영역: ${item.boundsStr}")
                }
                val subTv = TextView(this).apply {
                    text = subInfo
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
                infoCol.addView(labelTv)
                infoCol.addView(subTv)
                row.addView(infoCol)

                val quickMacroBtn = createChipButton("⚡ 매크로화", "#7C3AED") {
                    createQuickMacroFromNode(item)
                    removeInspectorModal()
                }
                row.addView(quickMacroBtn)
                listCol.addView(row)
            }
        }

        modal.addView(scroll)
        inspectorView = modal
        windowManager.addView(modal, params)
    }

    private fun createQuickMacroFromNode(item: com.mj.aranger.model.UiNodeItem) {
        val label = item.text.ifBlank { item.contentDesc }.ifBlank { item.viewId.substringAfterLast('/') }
        val step = when {
            item.text.isNotBlank() -> ActionStep(
                type = ActionType.CLICK_TEXT,
                textParam = item.text,
                x = item.centerX,
                y = item.centerY
            )
            item.viewId.isNotBlank() -> ActionStep(
                type = ActionType.CLICK_VIEW_ID,
                textParam = item.viewId.substringAfterLast('/'),
                x = item.centerX,
                y = item.centerY
            )
            else -> ActionStep(
                type = ActionType.TAP_XY,
                x = item.centerX,
                y = item.centerY
            )
        }
        val rule = MacroRule(
            name = "⚡ 자동클릭: ${label.ifBlank { "(${item.centerX},${item.centerY})" }}",
            enabled = true,
            triggerType = if (item.text.isNotBlank()) TriggerType.SCREEN_TEXT_FOUND else TriggerType.MANUAL_FLOATING,
            triggerParam = item.text,
            constraintType = ConstraintType.NONE,
            actions = listOf(step)
        )
        MacroRepository.upsertMacro(this, rule)
        Toast.makeText(
            this,
            "✅ 「${rule.name}」 매크로가 즉시 생성되었습니다!",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun removeInspectorModal() {
        inspectorView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        inspectorView = null
    }

    private fun createChipButton(label: String, bgColorHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(bgColorHex))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun spaceView(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
