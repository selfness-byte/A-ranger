package com.mj.aranger.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.mj.aranger.model.LogEntry
import com.mj.aranger.store.MacroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String
)

/**
 * inanimals-1977.web.app / www.inanimals.co.kr / GitHub(selfness-byte/A-ranger) 저장소에서
 * 최신 버전 정보를 조회하고, 이전 설치 버전에 상관없이 언제나 최신 APK를 다운로드하여
 * 덮어쓰기 업데이트를 수행하는 자동 업데이트 매니저.
 */
object AppUpdater {

    const val WEB_PORTAL_URL = "https://inanimals-1977.web.app/aranger/"
    const val GITHUB_REPO_URL = "https://github.com/selfness-byte/A-ranger"
    private const val DIRECT_FALLBACK_APK_URL =
        "https://raw.githubusercontent.com/selfness-byte/A-ranger/main/dist/A-ranger-debug.apk"

    private val UPDATE_URLS = listOf(
        "https://inanimals-1977.web.app/aranger/update.json",
        "https://www.inanimals.co.kr/aranger/update.json",
        "https://raw.githubusercontent.com/selfness-byte/A-ranger/main/dist/update.json",
        "https://inanimals-1977.web.app/version.json"
    )

    @Volatile
    private var checkedThisSession = false

    @Volatile
    private var pendingInstallFile: File? = null

    /**
     * BuildConfig 상수 인라인 캐시 문제를 원천 방지하기 위해
     * OS PackageManager에서 현재 실제 설치된 versionCode를 실시간으로 조회합니다.
     */
    fun installedVersionCode(context: Context): Int = runCatching {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pInfo).toInt()
    }.getOrDefault(1)

    /**
     * OS PackageManager에서 현재 실제 설치된 versionName을 실시간으로 조회합니다.
     */
    fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    /**
     * '출처를 알 수 없는 앱 설치' 권한 허용 화면에서 돌아왔을 때 대기 중이던 APK 설치를 즉시 이어서 실행합니다.
     */
    fun resumePendingInstallIfAllowed(activity: Activity) {
        val file = pendingInstallFile ?: return
        if (!file.exists()) {
            pendingInstallFile = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = null
            launchInstaller(activity, file)
        }
    }

    /**
     * 앱 시작 시 임시 업데이트 APK 정리 및 백그라운드 자동 업데이트 확인 (세션당 1회)
     */
    fun checkOnStartup(activity: Activity, scope: CoroutineScope) {
        if (checkedThisSession) return
        checkedThisSession = true
        if (!MacroRepository.isAutoCheckUpdateOnStartup(activity)) return

        scope.launch {
            withContext(Dispatchers.IO) { cleanOldApks(activity.applicationContext) }
            val info = fetchLatestUpdate(activity) ?: return@launch
            val curCode = installedVersionCode(activity)
            val curName = installedVersionName(activity)
            // 서버 빌드가 설치본보다 "높을 때만" 알립니다 (versionName만 다르거나 서버가 더 낮으면 다운그레이드 제안이 되므로 제외)
            if (info.versionCode > curCode &&
                !activity.isFinishing && !activity.isDestroyed
            ) {
                showUpdateDialog(activity, scope, info, forceOffer = true)
            }
        }
    }

    /**
     * 설정 메뉴에서 사용자가 직접 [최신 업데이트 확인 및 자동 설치] 버튼을 눌렀을 때 호출.
     * 이전 버전에 상관없이(같거나 높거나 낮거나 무관하게) 언제나 최신 서버 APK를 바로 다운로드·덮어쓰기 설치할 수 있도록 합니다.
     */
    fun checkManual(
        activity: Activity,
        scope: CoroutineScope,
        installImmediately: Boolean = false,
        onStatus: (String) -> Unit
    ) {
        val curName = installedVersionName(activity)
        val curCode = installedVersionCode(activity)
        onStatus("서버(inanimals-1977.web.app / GitHub)에서 최신 버전 확인 중… (현재 v$curName)")

        scope.launch {
            val fetched = fetchLatestUpdate(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch

            val info = fetched ?: UpdateInfo(
                versionCode = curCode,
                versionName = curName,
                apkUrl = DIRECT_FALLBACK_APK_URL,
                sha256 = "",
                notes = "GitHub 최신 빌드 APK 직접 다운로드 (전 버전 무관 강제 업데이트)"
            )

            if (info.versionCode > curCode) {
                onStatus("🚀 새 버전 발견: v${info.versionName} (build ${info.versionCode}) ← 현재 v$curName")
            } else if (info.versionCode < curCode) {
                // Android는 같은 서명이라도 낮은 versionCode 설치(다운그레이드)를 거부하므로 설치를 시도하지 않습니다.
                onStatus("✅ 현재 v$curName (build $curCode)이 서버 v${info.versionName} (build ${info.versionCode})보다 최신입니다")
                return@launch
            } else {
                onStatus("✅ 서버 최신 버전: v${info.versionName} (build ${info.versionCode}) · 전 버전 무관 덮어쓰기 업데이트 가능")
            }

            if (installImmediately) {
                downloadAndInstall(activity, scope, info)
            } else {
                showUpdateDialog(activity, scope, info, forceOffer = true)
            }
        }
    }

    suspend fun fetchLatestUpdate(context: Context? = null): UpdateInfo? = withContext(Dispatchers.IO) {
        val uaVersion = context?.let { installedVersionName(it) } ?: "1.2.0"
        for (url in UPDATE_URLS) {
            val info = runCatching { requestUpdateJson(url, uaVersion) }.getOrNull()
            if (info != null && info.versionCode >= 1 && info.apkUrl.startsWith("https://")) {
                return@withContext info
            }
        }
        null
    }

    private fun requestUpdateJson(urlStr: String, uaVersion: String): UpdateInfo? {
        val sep = if (urlStr.contains("?")) "&" else "?"
        val cacheBustedUrl = "${urlStr}${sep}t=${System.currentTimeMillis()}"
        val conn = (URL(cacheBustedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("User-Agent", "ARanger/$uaVersion")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(body)

            // version.json 통합 포털 포맷(apps["com.mj.aranger"])과 개별 update.json 포맷 모두 지원
            val o = root.optJSONObject("apps")?.optJSONObject("com.mj.aranger") ?: root

            UpdateInfo(
                versionCode = o.optInt("versionCode", o.optInt("version_code", 0)),
                versionName = o.optString("versionName", o.optString("latest_version", "")),
                apkUrl = o.optString("apkUrl", o.optString("download_url", "")),
                sha256 = o.optString("sha256", "").trim().lowercase(),
                notes = o.optString(
                    "notes",
                    o.optString("release_notes", "A-ranger Pro 최신 기능 및 안정성 업데이트")
                )
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun showUpdateDialog(
        activity: Activity,
        scope: CoroutineScope,
        info: UpdateInfo,
        forceOffer: Boolean
    ) {
        val curName = installedVersionName(activity)
        val curCode = installedVersionCode(activity)
        val isNewer = info.versionCode > curCode
        val title = if (isNewer) {
            "🚀 새 버전 업데이트 (v${info.versionName})"
        } else {
            "🔄 최신 버전 강제 업데이트 / 재설치 (v${info.versionName})"
        }
        val statusLine = if (isNewer) {
            "현재 버전: v$curName (build $curCode)\n최신 버전: v${info.versionName} (build ${info.versionCode})"
        } else {
            "현재 버전: v$curName (build $curCode)\n서버 버전: v${info.versionName} (build ${info.versionCode})\n※ 이전/현재 버전에 상관없이 즉시 최신 파일로 덮어써서 업데이트할 수 있습니다."
        }
        val btnLabel = if (isNewer || forceOffer) "지금 업데이트 설치" else "다시 설치"

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("$statusLine\n\n${info.notes}")
            .setPositiveButton(btnLabel) { _, _ ->
                downloadAndInstall(activity, scope, info)
            }
            .setNeutralButton("웹 포털 열기") { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_PORTAL_URL)))
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    fun downloadAndInstall(activity: Activity, scope: CoroutineScope, info: UpdateInfo) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val statusTv = TextView(activity).apply {
            text = "APK 파일 다운로드 준비 중…"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        box.addView(statusTv)
        box.addView(progressBar)

        var cancelled = false
        val dlg = AlertDialog.Builder(activity)
            .setTitle("업데이트 다운로드 중 (v${info.versionName})")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소") { d, _ ->
                cancelled = true
                d.dismiss()
            }
            .create()
        dlg.show()

        val uaVersion = installedVersionName(activity)
        scope.launch {
            val outFile = withContext(Dispatchers.IO) {
                val candidateUrls = listOf(info.apkUrl, DIRECT_FALLBACK_APK_URL).distinct()
                var downloadedFile: File? = null

                for (baseUrl in candidateUrls) {
                    if (cancelled) break
                    val file = runCatching {
                        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                        val target = File(dir, "A-ranger-${info.versionName}-b${info.versionCode}.apk")
                        val sep = if (baseUrl.contains("?")) "&" else "?"
                        val downloadUrl = "${baseUrl}${sep}t=${System.currentTimeMillis()}"
                        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 12_000
                            readTimeout = 25_000
                            useCaches = false
                            instanceFollowRedirects = true
                            setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
                            setRequestProperty("Pragma", "no-cache")
                            setRequestProperty("User-Agent", "ARanger/$uaVersion")
                        }
                        try {
                            if (conn.responseCode !in 200..299) return@runCatching null
                            val total = conn.contentLengthLong
                            conn.inputStream.use { input ->
                                FileOutputStream(target).use { output ->
                                    val buf = ByteArray(16 * 1024)
                                    var downloaded = 0L
                                    while (!cancelled) {
                                        val r = input.read(buf)
                                        if (r < 0) break
                                        output.write(buf, 0, r)
                                        downloaded += r
                                        if (total > 0) {
                                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                            withContext(Dispatchers.Main) {
                                                progressBar.progress = pct
                                                statusTv.text = "다운로드 중… $pct% (${downloaded / 1024}KB / ${total / 1024}KB)"
                                            }
                                        }
                                    }
                                }
                            }
                            if (cancelled) {
                                target.delete()
                                return@runCatching null
                            }
                            if (!isValidApkFile(target)) {
                                target.delete()
                                return@runCatching null
                            }
                            target
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrNull()

                    if (file != null && file.exists()) {
                        downloadedFile = file
                        break
                    }
                }
                downloadedFile
            }

            if (dlg.isShowing) dlg.dismiss()
            if (cancelled || activity.isFinishing || activity.isDestroyed) return@launch

            if (outFile == null || !outFile.exists()) {
                Toast.makeText(
                    activity,
                    "다운로드에 실패했습니다. inanimals-1977.web.app 다운로드 페이지를 엽니다.",
                    Toast.LENGTH_LONG
                ).show()
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_PORTAL_URL)))
                }
                return@launch
            }

            MacroRepository.addLog(
                activity,
                LogEntry(
                    macroName = "자동 업데이트",
                    message = "최신 APK (v${info.versionName}, ${outFile.length() / 1024}KB) 다운로드 완료 → 설치 화면 실행",
                    isSuccess = true
                )
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                pendingInstallFile = outFile
                Toast.makeText(
                    activity,
                    "‘출처를 알 수 없는 앱 설치’ 권한을 켜 주시면 즉시 업데이트 설치가 시작됩니다.",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
                return@launch
            }

            launchInstaller(activity, outFile)
        }
    }

    private fun launchInstaller(context: Context, apkFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(fallbackIntent)
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "설치 화면을 열지 못했습니다: ${err.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 200_000L) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val b0 = fis.read()
                val b1 = fis.read()
                b0 == 0x50 && b1 == 0x4B // 'P', 'K' (ZIP/APK header)
            }
        }.getOrDefault(false)
    }

    private fun cleanOldApks(context: Context) {
        runCatching {
            val pending = pendingInstallFile?.canonicalPath
            val dir = File(context.cacheDir, "updates")
            if (dir.exists()) {
                dir.listFiles()?.forEach { f ->
                    if (f.canonicalPath != pending) f.delete()
                }
            }
        }
    }
}
