package com.mj.aranger.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mj.aranger.engine.MacroEngine
import com.mj.aranger.engine.VisionOcrEngine
import com.mj.aranger.model.LogEntry
import com.mj.aranger.model.UiNodeItem
import com.mj.aranger.store.MacroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class ARangerAccessibilityService : AccessibilityService(), SensorEventListener {

    companion object {
        @Volatile
        var instance: ARangerAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun colorsMatch(hexA: String, hexB: String, tolerance: Int = 36): Boolean {
            return try {
                val c1 = Color.parseColor(if (hexA.startsWith("#")) hexA else "#$hexA")
                val c2 = Color.parseColor(if (hexB.startsWith("#")) hexB else "#$hexB")
                val dr = abs(Color.red(c1) - Color.red(c2))
                val dg = abs(Color.green(c1) - Color.green(c2))
                val db = abs(Color.blue(c1) - Color.blue(c2))
                dr <= tolerance && dg <= tolerance && db <= tolerance
            } catch (_: Exception) {
                false
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screenshotMutex = Mutex()
    private var lastScreenshotAt = 0L
    private val MIN_SCREENSHOT_INTERVAL_MS = 350L
    private var lastContentCheckTime = 0L
    private var lastAutoGuardTime = 0L

    private var sensorManager: SensorManager? = null
    private var accelCurrent = SensorManager.GRAVITY_EARTH
    private var accelLast = SensorManager.GRAVITY_EARTH
    private var shakeDelta = 0f
    private var lastShakeTime = 0L

    private val systemDialogPackages = setOf(
        "com.android.systemui",
        "com.samsung.android.MtpApplication",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.android.settings"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerShakeSensor()
        MacroRepository.addLog(
            this,
            LogEntry(
                macroName = "접근성 서비스",
                message = "A-ranger Pro 화면 제어·비전·ADB 파워 엔진(v1.5)이 연결되었습니다.",
                isSuccess = true
            )
        )
        MacroEngine.startTimerLoop(applicationContext)
    }

    private fun registerShakeSensor() {
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            sensorManager = sm
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (_: Exception) {
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        accelLast = accelCurrent
        accelCurrent = sqrt(x * x + y * y + z * z)
        val delta = accelCurrent - accelLast
        shakeDelta = shakeDelta * 0.85f + delta

        val now = System.currentTimeMillis()
        if (shakeDelta > 15.0f && now - lastShakeTime > 1200L) {
            lastShakeTime = now
            if (MacroEngine.isExecuting && MacroRepository.isVolumePanicEnabled(applicationContext)) {
                MacroEngine.stopAllExecutions(applicationContext, "📳 스마트폰 흔들기 긴급 정지")
            } else {
                MacroEngine.onDeviceShaken(applicationContext)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    /**
     * 🔊 하드웨어 볼륨[↓] 버튼 긴급 정지 (Panic Stop):
     * 매크로가 실행 중일 때만 볼륨 다운 키를 가로채어 즉시 정지시키며,
     * 매크로가 실행 중이 아닐 때는 false를 반환하여 원래의 볼륨 조절이 정상 작동합니다.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            if (MacroEngine.isExecuting && MacroRepository.isVolumePanicEnabled(applicationContext)) {
                MacroEngine.stopAllExecutions(applicationContext, "🔊 볼륨[↓] 버튼 긴급 정지")
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        if (pkg != packageName) {
            val prevPkg = MacroRepository.currentForegroundPackage
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != prevPkg) {
                MacroRepository.currentForegroundPackage = pkg
                MacroEngine.onAppOpened(applicationContext, pkg)
            } else if (prevPkg.isEmpty()) {
                MacroRepository.currentForegroundPackage = pkg
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastContentCheckTime >= 260L && pkg != packageName) {
            lastContentCheckTime = now
            val texts = collectAllScreenTexts()
            if (texts.isNotEmpty()) {
                checkAutoGuards(pkg, texts, now)
                MacroEngine.onScreenContentChanged(applicationContext, pkg, texts)
            }
        }
    }

    private fun checkAutoGuards(pkg: String, texts: List<String>, now: Long) {
        if (!MacroRepository.isMasterEnabled(applicationContext)) return
        if (now - lastAutoGuardTime < 900L) return

        // 1. USB 디버깅 / 파일 데이터 접근 / 화면 공유 수락 팝업 자동 처리
        if (MacroRepository.isAutoAcceptDialogEnabled(applicationContext) && pkg in systemDialogPackages) {
            val isUsbOrSystemPrompt = texts.any {
                it.contains("USB 디버깅", ignoreCase = true) ||
                    it.contains("데이터에 접근", ignoreCase = true) ||
                    it.contains("허용하시겠습니까", ignoreCase = true) ||
                    it.contains("지금 시작", ignoreCase = true) ||
                    it.contains("Allow USB debugging", ignoreCase = true)
            }
            if (isUsbOrSystemPrompt) {
                lastAutoGuardTime = now
                serviceScope.launch {
                    clickUncheckedBoxByKeyword("항상")
                    clickUncheckedBoxByKeyword("Always")
                    val clicked = clickNodeByText("허용") ||
                        clickNodeByText("지금 시작") ||
                        clickNodeByText("확인") ||
                        clickNodeByText("Allow")
                    if (clicked) {
                        MacroRepository.addLog(
                            applicationContext,
                            LogEntry(
                                macroName = "🛡️ 스마트 가드",
                                message = "시스템 수락 팝업($pkg)을 감지하여 자동으로 [허용] 처리했습니다.",
                                isSuccess = true
                            )
                        )
                    }
                }
                return
            }
        }

        // 2. 영상/앱 광고 건너뛰기 자동 처리
        if (MacroRepository.isAutoSkipAdEnabled(applicationContext)) {
            val skipTargets = listOf("광고 건너뛰기", "건너뛰기", "Skip Ad", "Skip Ads", "광고 닫기")
            val matchedSkip = skipTargets.firstOrNull { target ->
                texts.any { it.equals(target, ignoreCase = true) || it.contains("광고 건너뛰기") }
            }
            if (matchedSkip != null) {
                lastAutoGuardTime = now
                serviceScope.launch {
                    val ok = clickNodeByText(matchedSkip) || clickNodeByViewId("skip_ad")
                    if (ok) {
                        MacroRepository.addLog(
                            applicationContext,
                            LogEntry(
                                macroName = "⏭️ 광고 스킵 가드",
                                message = "화면에서 「$matchedSkip」 버튼을 감지해 자동 클릭했습니다.",
                                isSuccess = true
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun getTargetRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (win in windows) {
                val r = win.root ?: continue
                if (r.packageName?.toString() != packageName) {
                    roots.add(r)
                }
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add(it) }
        }
        return roots
    }

    fun collectAllScreenTexts(): List<String> {
        val result = mutableListOf<String>()
        for (root in getTargetRoots()) {
            traverseTexts(root, result, 0)
        }
        return result
    }

    private fun traverseTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 35) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        val count = node.childCount
        for (i in 0 until count) {
            traverseTexts(node.getChild(i), out, depth + 1)
        }
    }

    fun inspectCurrentScreenNodes(): List<UiNodeItem> {
        val items = mutableListOf<UiNodeItem>()
        val seenKeys = HashSet<String>()
        for (root in getTargetRoots()) {
            collectUiNodes(root, items, seenKeys, 0)
        }
        return items.sortedWith(
            compareByDescending<UiNodeItem> { it.isClickable }
                .thenBy { it.centerY }
                .thenBy { it.centerX }
        )
    }

    private fun collectUiNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<UiNodeItem>,
        seen: HashSet<String>,
        depth: Int
    ) {
        if (node == null || depth > 35 || out.size >= 80) return
        if (node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val vid = node.viewIdResourceName?.trim().orEmpty()
            val cls = node.className?.toString()?.trim().orEmpty()
            val clickable = node.isClickable || node.parent?.isClickable == true

            if (!rect.isEmpty && rect.width() > 8 && rect.height() > 8 &&
                (txt.isNotEmpty() || desc.isNotEmpty() || vid.isNotEmpty() || clickable)
            ) {
                val cx = rect.centerX()
                val cy = rect.centerY()
                val key = "$txt|$desc|$vid|$cx|$cy"
                if (seen.add(key)) {
                    out.add(
                        UiNodeItem(
                            text = txt,
                            contentDesc = desc,
                            viewId = vid,
                            className = cls,
                            centerX = cx,
                            centerY = cy,
                            boundsStr = "[${rect.left},${rect.top} - ${rect.right},${rect.bottom}]",
                            isClickable = clickable
                        )
                    )
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectUiNodes(node.getChild(i), out, seen, depth + 1)
        }
    }

    fun containsScreenText(keyword: String): Boolean {
        val target = keyword.trim()
        if (target.isEmpty()) return false
        return collectAllScreenTexts().any { it.contains(target, ignoreCase = true) }
    }

    /**
     * 📸 현재 화면 전체를 소프트웨어 Bitmap(ARGB_8888)으로 안전하게 캡처합니다 (Android 11 / API 30+).
     */
    suspend fun captureScreenBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return screenshotMutex.withLock {
            val wait = MIN_SCREENSHOT_INTERVAL_MS - (SystemClock.uptimeMillis() - lastScreenshotAt)
            if (wait > 0) delay(wait)
            val bmp = takeRawSoftwareBitmap()
            lastScreenshotAt = SystemClock.uptimeMillis()
            bmp
        }
    }

    private suspend fun takeRawSoftwareBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            var softBitmap: Bitmap? = null
                            try {
                                val hwBuffer = screenshot.hardwareBuffer
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                if (hwBitmap != null) {
                                    softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()
                                }
                                hwBuffer.close()
                            } catch (_: Exception) {
                            }
                            if (cont.isActive) cont.resume(softBitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * 🎨 화면 특정 (x, y) 좌표의 실제 픽셀 색상(#RRGGBB)을 추출합니다 (Android 11 / API 30+).
     */
    suspend fun samplePixelColor(x: Int, y: Int): String? {
        val softBitmap = captureScreenBitmap() ?: return null
        return try {
            val px = x.coerceIn(0, (softBitmap.width - 1).coerceAtLeast(0))
            val py = y.coerceIn(0, (softBitmap.height - 1).coerceAtLeast(0))
            val colorInt = softBitmap.getPixel(px, py)
            String.format(
                "#%02X%02X%02X",
                Color.red(colorInt),
                Color.green(colorInt),
                Color.blue(colorInt)
            )
        } catch (_: Exception) {
            null
        } finally {
            softBitmap.recycle()
        }
    }

    /**
     * 🖼️ 화면 (x, y) 좌표 주변의 아이콘·버튼 이미지를 템플릿 JSON으로 캡처합니다.
     */
    suspend fun captureImageTemplateAt(x: Int, y: Int, patchSize: Int = 64): String? {
        val softBitmap = captureScreenBitmap() ?: return null
        return try {
            VisionOcrEngine.captureTemplateFromBitmap(softBitmap, x, y, patchSize)
        } finally {
            softBitmap.recycle()
        }
    }

    /**
     * 🖼️ 캡처된 이미지 템플릿과 일치하는 아이콘·버튼을 현재 화면 전체에서 탐색합니다.
     */
    suspend fun findImageTemplateCoordinates(
        templateJson: String,
        tolerance: Int = 34
    ): VisionOcrEngine.TemplateMatchResult? {
        if (templateJson.isBlank()) return null
        val softBitmap = captureScreenBitmap() ?: return null
        return try {
            VisionOcrEngine.findTemplateOnScreen(softBitmap, templateJson, tolerance)
        } finally {
            softBitmap.recycle()
        }
    }

    /**
     * 👁️ 하이브리드 온디바이스 비전 OCR 텍스트 탐색:
     * 1차: 모든 윈도우 노드(클릭 불가 텍스트뷰/웹뷰/툴팁 포함)에서 화면 좌표(Rect) 추출
     * 2차: 접근성 트리에 없는 캔버스/게임/이미지 텍스트는 실제 화면 Bitmap 글리프 대비 매칭으로 좌표 추출
     */
    suspend fun findTextCoordinatesByHybridOcr(targetText: String): Pair<Int, Int>? {
        val query = targetText.trim()
        if (query.isEmpty()) return null

        // 1차: 접근성 노드 트리에서 좌표(Bounds) 직접 탐색
        for (root in getTargetRoots()) {
            val matched = findMatchingNode(root, query, 0)
            if (matched != null) {
                val rect = Rect()
                matched.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
                    return Pair(rect.centerX(), rect.centerY())
                }
            }
        }

        // 2차: 화면 캡처 Bitmap 기반 온디바이스 비전 글리프 OCR 스캔
        val softBitmap = captureScreenBitmap()
        if (softBitmap != null) {
            try {
                val ocrPt = VisionOcrEngine.findTextByVisualOcr(softBitmap, query)
                if (ocrPt != null) return ocrPt
            } finally {
                softBitmap.recycle()
            }
        }

        // 3차: 현재 활성 윈도우(자가 테스트 등) 노드 좌표 폴백
        rootInActiveWindow?.let { activeRoot ->
            findMatchingNode(activeRoot, query, 0)?.let { matched ->
                val rect = Rect()
                matched.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
                    return Pair(rect.centerX(), rect.centerY())
                }
            }
        }
        return null
    }

    suspend fun clickTextByHybridOcr(targetText: String, durationMs: Long = 95L): Boolean {
        val pt = findTextCoordinatesByHybridOcr(targetText) ?: return false
        return performTap(pt.first, pt.second, durationMs)
    }

    private fun humanizeCoord(coord: Int): Int {
        if (!MacroRepository.isHumanizerEnabled(applicationContext)) return coord
        val jitter = Random.nextInt(-7, 8)
        return (coord + jitter).coerceAtLeast(2)
    }

    private fun humanizeDuration(durationMs: Long): Long {
        if (!MacroRepository.isHumanizerEnabled(applicationContext)) return durationMs
        val factor = Random.nextDouble(0.86, 1.15)
        return (durationMs * factor).toLong().coerceIn(25L, 8000L)
    }

    /**
     * 지정한 (x, y) 좌표를 터치합니다 (Humanizer 활성 시 미세 랜덤 오프셋 자동 적용).
     */
    suspend fun performTap(x: Int, y: Int, durationMs: Long = 100L): Boolean =
        suspendCancellableCoroutine { cont ->
            val safeX = humanizeCoord(x).toFloat()
            val safeY = humanizeCoord(y).toFloat()
            val safeDuration = humanizeDuration(durationMs.coerceIn(20L, 8000L))

            val path = Path().apply { moveTo(safeX, safeY) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }

    /**
     * (x1, y1)에서 (x2, y2)로 스와이프 제스처를 수행합니다.
     */
    suspend fun performSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long = 300L
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(humanizeCoord(x1).toFloat(), humanizeCoord(y1).toFloat())
            lineTo(humanizeCoord(x2).toFloat(), humanizeCoord(y2).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            humanizeDuration(durationMs.coerceIn(50L, 8000L))
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )
        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    /**
     * 📹 사용자가 화면에 직접 그려 녹화한 자유 곡선·패턴 제스처 경로(JSON)를 그대로 재현합니다.
     */
    suspend fun performCustomPath(pathJson: String, fallbackDurationMs: Long = 600L): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                if (pathJson.isBlank()) {
                    if (cont.isActive) cont.resume(false)
                    return@suspendCancellableCoroutine
                }
                val obj = JSONObject(pathJson)
                val ptsArr = obj.optJSONArray("points")
                if (ptsArr == null || ptsArr.length() == 0) {
                    if (cont.isActive) cont.resume(false)
                    return@suspendCancellableCoroutine
                }
                val recordedDur = obj.optLong("durationMs", fallbackDurationMs).coerceIn(60L, 12000L)
                val path = Path()
                for (i in 0 until ptsArr.length()) {
                    val pair = ptsArr.optJSONArray(i) ?: continue
                    val px = pair.optInt(0, 540).coerceAtLeast(1).toFloat()
                    val py = pair.optInt(1, 1200).coerceAtLeast(1).toFloat()
                    if (i == 0) {
                        path.moveTo(px, py)
                    } else {
                        path.lineTo(px, py)
                    }
                }
                val stroke = GestureDescription.StrokeDescription(path, 0L, humanizeDuration(recordedDur))
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val dispatched = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }

    private fun clickUncheckedBoxByKeyword(keyword: String) {
        for (root in getTargetRoots()) {
            val node = findMatchingNode(root, keyword, 0) ?: continue
            if (node.isCheckable && !node.isChecked) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
    }

    suspend fun clickNodeByText(targetText: String): Boolean {
        val query = targetText.trim()
        if (query.isEmpty()) return false

        var matchedNode: AccessibilityNodeInfo? = null
        for (root in getTargetRoots()) {
            matchedNode = findMatchingNode(root, query, 0)
            if (matchedNode != null) break
        }
        val target = matchedNode ?: return false
        return clickOrTapNode(target)
    }

    suspend fun clickNodeByViewId(viewIdQuery: String): Boolean {
        val query = viewIdQuery.trim()
        if (query.isEmpty()) return false

        var matchedNode: AccessibilityNodeInfo? = null
        for (root in getTargetRoots()) {
            matchedNode = findMatchingViewIdNode(root, query, 0)
            if (matchedNode != null) break
        }
        val target = matchedNode ?: return false
        return clickOrTapNode(target)
    }

    private suspend fun clickOrTapNode(node: AccessibilityNodeInfo): Boolean {
        var clickable: AccessibilityNodeInfo? = node
        var hops = 0
        while (clickable != null && hops < 6) {
            if (clickable.isClickable) {
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                break
            }
            clickable = clickable.parent
            hops++
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            return performTap(rect.centerX(), rect.centerY(), 90L)
        }
        return false
    }

    private fun findMatchingNode(
        node: AccessibilityNodeInfo?,
        query: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 35) return null
        val textMatch = node.text?.toString()?.contains(query, ignoreCase = true) == true
        val descMatch = node.contentDescription?.toString()?.contains(query, ignoreCase = true) == true
        if ((textMatch || descMatch) && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findMatchingNode(node.getChild(i), query, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun findMatchingViewIdNode(
        node: AccessibilityNodeInfo?,
        query: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 35) return null
        val vid = node.viewIdResourceName.orEmpty()
        if (vid.isNotEmpty() && vid.contains(query, ignoreCase = true) && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findMatchingViewIdNode(node.getChild(i), query, depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun inputText(text: String): Boolean {
        for (root in getTargetRoots()) {
            val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findEditableNode(root, 0)
            if (targetNode != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
        return false
    }

    fun pasteTextViaClipboard(text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("A-ranger", text))

        for (root in getTargetRoots()) {
            val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findEditableNode(root, 0)
            if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return true
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
        return true
    }

    /**
     * 🤏 두 손가락 멀티터치 핀치 줌인(확대) / 줌아웃(축소) 제스처를 수행합니다.
     */
    suspend fun performPinch(
        centerX: Int,
        centerY: Int,
        zoomIn: Boolean,
        spanPx: Int = 340,
        durationMs: Long = 380L
    ): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.coerceAtLeast(300)
            val sh = dm.heightPixels.coerceAtLeast(400)
            val cx = centerX.coerceIn(120, sw - 120)
            val cy = centerY.coerceIn(160, sh - 160)
            val innerOffset = 55
            val outerOffset = spanPx.coerceIn(120, minOf(cx - 20, sw - cx - 20, cy - 20, sh - cy - 20).coerceAtLeast(130))

            val f1StartX = if (zoomIn) (cx - innerOffset).toFloat() else (cx - outerOffset).toFloat()
            val f1StartY = if (zoomIn) (cy - innerOffset).toFloat() else (cy - outerOffset).toFloat()
            val f1EndX = if (zoomIn) (cx - outerOffset).toFloat() else (cx - innerOffset).toFloat()
            val f1EndY = if (zoomIn) (cy - outerOffset).toFloat() else (cy - innerOffset).toFloat()

            val f2StartX = if (zoomIn) (cx + innerOffset).toFloat() else (cx + outerOffset).toFloat()
            val f2StartY = if (zoomIn) (cy + innerOffset).toFloat() else (cy + outerOffset).toFloat()
            val f2EndX = if (zoomIn) (cx + outerOffset).toFloat() else (cx + innerOffset).toFloat()
            val f2EndY = if (zoomIn) (cy + outerOffset).toFloat() else (cy + innerOffset).toFloat()

            val path1 = Path().apply {
                moveTo(f1StartX, f1StartY)
                lineTo(f1EndX, f1EndY)
            }
            val path2 = Path().apply {
                moveTo(f2StartX, f2StartY)
                lineTo(f2EndX, f2EndY)
            }
            val safeDur = humanizeDuration(durationMs.coerceIn(100L, 4000L))
            val stroke1 = GestureDescription.StrokeDescription(path1, 0L, safeDur)
            val stroke2 = GestureDescription.StrokeDescription(path2, 0L, safeDur)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke1)
                .addStroke(stroke2)
                .build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }

    /**
     * ⌨️ 포커스된 입력창에서 IME 엔터(검색/전송/줄바꿈) 동작을 수행합니다.
     */
    fun performImeEnter(): Boolean {
        for (root in getTargetRoots() + listOfNotNull(rootInActiveWindow)) {
            val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditableNode(root, 0)
            if (targetNode != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                        return true
                    }
                }
                val current = targetNode.text?.toString().orEmpty()
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$current\n")
                }
                return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
        return false
    }

    /**
     * ⌫ 포커스된 입력창에서 마지막 글자 1개를 지웁니다 (KEYCODE_DEL 대체).
     */
    fun performBackspace(): Boolean {
        for (root in getTargetRoots() + listOfNotNull(rootInActiveWindow)) {
            val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findEditableNode(root, 0)
            if (targetNode != null) {
                val cur = targetNode.text?.toString().orEmpty()
                val next = if (cur.isNotEmpty()) cur.dropLast(1) else ""
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
                }
                return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 35) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }
}
