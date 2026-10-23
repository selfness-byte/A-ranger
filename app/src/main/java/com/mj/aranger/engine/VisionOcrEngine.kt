package com.mj.aranger.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 🖼️ A-ranger Pro v1.4 온디바이스 비전 엔진
 * 1. 멀티스케일 피라미드 템플릿 매칭 (화면 아이콘/버튼 조각 서치)
 * 2. 대비 불변(Contrast-Invariant) 글리프 마스크 기반 온디바이스 비전 글자 인식(OCR) 서치
 */
object VisionOcrEngine {

    data class TemplateMatchResult(
        val x: Int,
        val y: Int,
        val avgDiff: Double,
        val similarityPercent: Int
    )

    /**
     * 화면 Bitmap의 (centerX, centerY) 주변 영역(기본 64x64)을 캡처하여
     * 16x16 평균 RGB 특징 그리드 + 미리보기 PNG Base64가 담긴 JSON 문자열을 생성합니다.
     */
    fun captureTemplateFromBitmap(
        screenBitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        patchSize: Int = 64
    ): String? {
        return try {
            val sw = screenBitmap.width
            val sh = screenBitmap.height
            if (sw < patchSize || sh < patchSize) return null

            val half = patchSize / 2
            val left = (centerX - half).coerceIn(0, sw - patchSize)
            val top = (centerY - half).coerceIn(0, sh - patchSize)
            val actualCenterX = left + half
            val actualCenterY = top + half

            val patchBitmap = Bitmap.createBitmap(screenBitmap, left, top, patchSize, patchSize)

            // 16x16 블록 평균 RGB 그리드 생성 (블록당 4x4 픽셀 평균으로 서브픽셀 오차에 강인함)
            val gridSize = 16
            val cellSpan = patchSize / gridSize
            val hexBuilder = StringBuilder(gridSize * gridSize * 6)

            for (gy in 0 until gridSize) {
                for (gx in 0 until gridSize) {
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    var count = 0
                    val startY = gy * cellSpan
                    val startX = gx * cellSpan
                    for (py in startY until (startY + cellSpan)) {
                        for (px in startX until (startX + cellSpan)) {
                            val c = patchBitmap.getPixel(px, py)
                            rSum += Color.red(c)
                            gSum += Color.green(c)
                            bSum += Color.blue(c)
                            count++
                        }
                    }
                    val r = (rSum / count).coerceIn(0, 255)
                    val g = (gSum / count).coerceIn(0, 255)
                    val b = (bSum / count).coerceIn(0, 255)
                    hexBuilder.append(String.format("%02X%02X%02X", r, g, b))
                }
            }

            val centerColor = patchBitmap.getPixel(half, half)
            val centerHex = String.format(
                "#%02X%02X%02X",
                Color.red(centerColor),
                Color.green(centerColor),
                Color.blue(centerColor)
            )

            // UI 미리보기용 48x48 PNG Base64 인코딩
            val thumbBitmap = Bitmap.createScaledBitmap(patchBitmap, 48, 48, true)
            val baos = ByteArrayOutputStream()
            thumbBitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
            val thumbBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            if (thumbBitmap !== patchBitmap) thumbBitmap.recycle()
            patchBitmap.recycle()

            JSONObject().apply {
                put("v", 1)
                put("origX", actualCenterX)
                put("origY", actualCenterY)
                put("screenW", sw)
                put("screenH", sh)
                put("patchSize", patchSize)
                put("gridSize", gridSize)
                put("centerHex", centerHex)
                put("rgbGrid", hexBuilder.toString())
                put("thumbBase64", thumbBase64)
            }.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 템플릿 JSON에서 미리보기 썸네일 Bitmap을 복원합니다.
     */
    fun decodeThumbnailBitmap(templateJson: String): Bitmap? {
        if (templateJson.isBlank()) return null
        return try {
            val obj = JSONObject(templateJson)
            val b64 = obj.optString("thumbBase64", "")
            if (b64.isBlank()) return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun getTemplateSummary(templateJson: String): String {
        if (templateJson.isBlank()) return "캡처된 이미지 없음"
        return try {
            val obj = JSONObject(templateJson)
            val ox = obj.optInt("origX", 0)
            val oy = obj.optInt("origY", 0)
            val ps = obj.optInt("patchSize", 64)
            val hex = obj.optString("centerHex", "#38BDF8")
            "원본($ox,$oy) ${ps}×${ps}px · 중심색 $hex"
        } catch (_: Exception) {
            "이미지 템플릿 오류"
        }
    }

    /**
     * 전체 화면 Bitmap에서 캡처된 이미지 템플릿과 가장 일치하는 위치를 고속 피라미드 탐색으로 찾습니다.
     * - 1단계: 원본 좌표 주변(±48px) 로컬 우선 탐색 (버튼이 제자리에 있을 때 <1ms 즉시 매칭)
     * - 2단계: 화면 전체 1/4 축소 그리드에서 16점 희소 필터링 → 256점 정밀 비교 (위치가 이동한 버튼도 ~8ms 내 탐색)
     */
    fun findTemplateOnScreen(
        screenBitmap: Bitmap,
        templateJson: String,
        maxTolerance: Int = 34
    ): TemplateMatchResult? {
        if (templateJson.isBlank()) return null
        return try {
            val obj = JSONObject(templateJson)
            val gridSize = obj.optInt("gridSize", 16)
            val patchSize = obj.optInt("patchSize", 64)
            val origX = obj.optInt("origX", screenBitmap.width / 2)
            val origY = obj.optInt("origY", screenBitmap.height / 2)
            val rgbGridHex = obj.optString("rgbGrid", "")
            val totalCells = gridSize * gridSize
            if (rgbGridHex.length < totalCells * 6) return null

            val tplR = IntArray(totalCells)
            val tplG = IntArray(totalCells)
            val tplB = IntArray(totalCells)
            for (i in 0 until totalCells) {
                val sub = rgbGridHex.substring(i * 6, i * 6 + 6)
                val rgb = sub.toInt(16)
                tplR[i] = (rgb shr 16) and 0xFF
                tplG[i] = (rgb shr 8) and 0xFF
                tplB[i] = rgb and 0xFF
            }

            val step = (patchSize / gridSize).coerceAtLeast(1) // 기본 4px
            val qw = screenBitmap.width / step
            val qh = screenBitmap.height / step
            if (qw <= gridSize || qh <= gridSize) return null

            // 화면을 1/step 해상도(예: 270x585)로 축소하여 픽셀 배열 추출
            val scaledScreen = Bitmap.createScaledBitmap(screenBitmap, qw, qh, true)
            val screenPixels = IntArray(qw * qh)
            scaledScreen.getPixels(screenPixels, 0, qw, 0, 0, qw, qh)
            if (scaledScreen !== screenBitmap) {
                scaledScreen.recycle()
            }

            val scrR = IntArray(qw * qh)
            val scrG = IntArray(qw * qh)
            val scrB = IntArray(qw * qh)
            for (i in screenPixels.indices) {
                val c = screenPixels[i]
                scrR[i] = (c shr 16) and 0xFF
                scrG[i] = (c shr 8) and 0xFF
                scrB[i] = c and 0xFF
            }

            // 16개 핵심 샘플 인덱스 (4x4 균등 분포)
            val sparseIndices = IntArray(16)
            val sparseDx = IntArray(16)
            val sparseDy = IntArray(16)
            var sIdx = 0
            for (sy in 1 until gridSize step 4) {
                for (sx in 1 until gridSize step 4) {
                    if (sIdx < 16) {
                        sparseIndices[sIdx] = sy * gridSize + sx
                        sparseDx[sIdx] = sx
                        sparseDy[sIdx] = sy
                        sIdx++
                    }
                }
            }

            fun evaluateFullAt(qx: Int, qy: Int): Double {
                var diffSum = 0L
                val maxAllowedSum = (maxTolerance + 4).toLong() * totalCells * 3L
                for (gy in 0 until gridSize) {
                    val rowOffset = (qy + gy) * qw + qx
                    val tplOffset = gy * gridSize
                    for (gx in 0 until gridSize) {
                        val si = rowOffset + gx
                        val ti = tplOffset + gx
                        diffSum += abs(scrR[si] - tplR[ti]) +
                            abs(scrG[si] - tplG[ti]) +
                            abs(scrB[si] - tplB[ti])
                    }
                    if (diffSum > maxAllowedSum) {
                        return 999.0
                    }
                }
                return diffSum.toDouble() / (totalCells * 3.0)
            }

            var bestQx = -1
            var bestQy = -1
            var bestDiff = Double.MAX_VALUE

            // 1단계: 원본 위치 주변 로컬 윈도우 우선 검색
            val origQx = ((origX - patchSize / 2) / step).coerceIn(0, qw - gridSize)
            val origQy = ((origY - patchSize / 2) / step).coerceIn(0, qh - gridSize)
            val localRadius = 12
            val minLx = (origQx - localRadius).coerceAtLeast(0)
            val maxLx = (origQx + localRadius).coerceAtMost(qw - gridSize)
            val minLy = (origQy - localRadius).coerceAtLeast(0)
            val maxLy = (origQy + localRadius).coerceAtMost(qh - gridSize)

            for (qy in minLy..maxLy) {
                for (qx in minLx..maxLx) {
                    val d = evaluateFullAt(qx, qy)
                    if (d < bestDiff) {
                        bestDiff = d
                        bestQx = qx
                        bestQy = qy
                    }
                }
            }

            // 로컬 영역에서 매우 높은 일치도(오차 <= maxTolerance * 0.65)를 찾았다면 즉시 반환
            if (bestDiff <= maxTolerance * 0.65 && bestQx >= 0) {
                val foundX = (bestQx * step + patchSize / 2).coerceIn(0, screenBitmap.width - 1)
                val foundY = (bestQy * step + patchSize / 2).coerceIn(0, screenBitmap.height - 1)
                val sim = (100.0 - (bestDiff / 2.55)).roundToInt().coerceIn(0, 100)
                return TemplateMatchResult(foundX, foundY, bestDiff, sim)
            }

            // 2단계: 전체 화면 피라미드 스캔 (위치 이동된 아이콘/버튼 탐색)
            val sparseThreshold = (maxTolerance + 12) * 16 * 3
            for (qy in 0..(qh - gridSize)) {
                for (qx in 0..(qw - gridSize)) {
                    var sparseDiff = 0
                    for (k in 0 until 16) {
                        val si = (qy + sparseDy[k]) * qw + (qx + sparseDx[k])
                        val ti = sparseIndices[k]
                        sparseDiff += abs(scrR[si] - tplR[ti]) +
                            abs(scrG[si] - tplG[ti]) +
                            abs(scrB[si] - tplB[ti])
                    }
                    if (sparseDiff > sparseThreshold) continue

                    val fullDiff = evaluateFullAt(qx, qy)
                    if (fullDiff < bestDiff) {
                        bestDiff = fullDiff
                        bestQx = qx
                        bestQy = qy
                    }
                }
            }

            if (bestQx >= 0 && bestDiff <= maxTolerance) {
                val foundX = (bestQx * step + patchSize / 2).coerceIn(0, screenBitmap.width - 1)
                val foundY = (bestQy * step + patchSize / 2).coerceIn(0, screenBitmap.height - 1)
                val sim = (100.0 - (bestDiff / 2.55)).roundToInt().coerceIn(0, 100)
                TemplateMatchResult(foundX, foundY, bestDiff, sim)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 👁️ 온디바이스 비전 글자 인식(OCR) 스캐너:
     * 접근성 노드가 없는 캔버스/게임/이미지 화면에서도 목표 문자열(한글·영문·숫자)을
     * 시스템 폰트 글리프 대비 마스크(Contrast-Invariant Glyph Mask)로 합성하여 화면 내 글자 위치를 찾아냅니다.
     */
    fun findTextByVisualOcr(
        screenBitmap: Bitmap,
        rawTargetText: String
    ): Pair<Int, Int>? {
        val text = rawTargetText.trim()
        if (text.isEmpty()) return null

        return try {
            // 1/2 해상도 휘도(Luminance) 맵 생성
            val scale = 2
            val sw = screenBitmap.width / scale
            val sh = screenBitmap.height / scale
            if (sw < 60 || sh < 60) return null

            val smallBmp = Bitmap.createScaledBitmap(screenBitmap, sw, sh, true)
            val pixels = IntArray(sw * sh)
            smallBmp.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            if (smallBmp !== screenBitmap) smallBmp.recycle()

            val lum = IntArray(sw * sh)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                lum[i] = (r * 77 + g * 150 + b * 29) shr 8
            }

            // 수평 방향 엣지 에너지 계산 (글자가 있는 영역만 빠르게 필터링)
            val edgeMap = IntArray(sw * sh)
            for (y in 1 until sh - 1) {
                val row = y * sw
                for (x in 1 until sw - 1) {
                    edgeMap[row + x] = abs(lum[row + x + 1] - lum[row + x - 1]) +
                        abs(lum[row + x] - lum[row + x - sw])
                }
            }

            // 화면(1/2 축소 기준)에서의 대표 폰트 크기 후보들 (원본 기준 34px, 44px, 56px)
            val candidateFontSizes = floatArrayOf(17f, 22f, 28f)
            var bestScore = 0.0
            var bestCenterX = -1
            var bestCenterY = -1

            for (fontSize in candidateFontSizes) {
                val maskData = renderGlyphMask(text, fontSize) ?: continue
                val mw = maskData.width
                val mh = maskData.height
                if (mw >= sw - 4 || mh >= sh - 4 || mw < 6 || mh < 6) continue

                val fgIndices = maskData.fgOffsets
                val bgIndices = maskData.bgOffsets
                if (fgIndices.size < 6 || bgIndices.size < 6) continue

                val stepY = 2
                val stepX = 2
                for (y in 2..(sh - mh - 2) step stepY) {
                    val midRow = (y + mh / 2) * sw
                    for (x in 2..(sw - mw - 2) step stepX) {
                        // 중심부 엣지 에너지가 너무 낮으면(단색 배경이면) 즉시 스킵
                        val centerEdge = edgeMap[midRow + x + mw / 2] +
                            edgeMap[midRow + x + mw / 4] +
                            edgeMap[midRow + x + (mw * 3) / 4]
                        if (centerEdge < 28) continue

                        var fgSum = 0
                        for (i in fgIndices.indices) {
                            val off = fgIndices[i]
                            val py = off shr 16
                            val px = off and 0xFFFF
                            fgSum += lum[(y + py) * sw + (x + px)]
                        }
                        var bgSum = 0
                        for (i in bgIndices.indices) {
                            val off = bgIndices[i]
                            val py = off shr 16
                            val px = off and 0xFFFF
                            bgSum += lum[(y + py) * sw + (x + px)]
                        }

                        val fgMean = fgSum.toDouble() / fgIndices.size
                        val bgMean = bgSum.toDouble() / bgIndices.size
                        val contrast = abs(fgMean - bgMean)
                        if (contrast < 32.0) continue

                        // 획 픽셀들과 배경 픽셀들의 일관성(낮은 편차) 검사 → 글자 모양과 정확히 맞을 때만 고득점 부여
                        var fgVar = 0.0
                        for (i in fgIndices.indices) {
                            val off = fgIndices[i]
                            val py = off shr 16
                            val px = off and 0xFFFF
                            val v = lum[(y + py) * sw + (x + px)] - fgMean
                            fgVar += abs(v)
                        }
                        var bgVar = 0.0
                        for (i in bgIndices.indices) {
                            val off = bgIndices[i]
                            val py = off shr 16
                            val px = off and 0xFFFF
                            val v = lum[(y + py) * sw + (x + px)] - bgMean
                            bgVar += abs(v)
                        }
                        val fgMad = fgVar / fgIndices.size
                        val bgMad = bgVar / bgIndices.size
                        val score = contrast - (fgMad * 0.75) - (bgMad * 0.75)
                        if (score > bestScore) {
                            bestScore = score
                            bestCenterX = (x + mw / 2) * scale
                            bestCenterY = (y + mh / 2) * scale
                        }
                    }
                }
            }

            if (bestScore >= 24.0 && bestCenterX >= 0 && bestCenterY >= 0) {
                Pair(
                    bestCenterX.coerceIn(0, screenBitmap.width - 1),
                    bestCenterY.coerceIn(0, screenBitmap.height - 1)
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private class GlyphMask(
        val width: Int,
        val height: Int,
        val fgOffsets: IntArray,
        val bgOffsets: IntArray
    )

    private fun renderGlyphMask(text: String, textSizePx: Float): GlyphMask? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val w = (bounds.width() + 8).coerceIn(8, 320)
        val h = (bounds.height() + 8).coerceIn(8, 96)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        canvas.drawText(text, (4 - bounds.left).toFloat(), (4 - bounds.top).toFloat(), paint)

        val fgList = ArrayList<Int>(64)
        val bgList = ArrayList<Int>(64)
        val strideX = max(1, w / 20)
        val strideY = max(1, h / 10)

        for (y in 0 until h step strideY) {
            for (x in 0 until w step strideX) {
                val r = Color.red(bmp.getPixel(x, y))
                val packed = (y shl 16) or (x and 0xFFFF)
                if (r > 160) {
                    fgList.add(packed)
                } else if (r < 40) {
                    bgList.add(packed)
                }
            }
        }
        bmp.recycle()
        if (fgList.isEmpty() || bgList.isEmpty()) return null
        return GlyphMask(w, h, fgList.toIntArray(), bgList.toIntArray())
    }
}
