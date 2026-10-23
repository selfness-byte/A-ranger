#!/bin/zsh
# A-ranger Pro 설치 상태 / 권한 / 배포 엔드포인트 점검 스크립트
# 사용법: zsh /Volumes/M/A-ranger/scripts/verify.sh
source /Volumes/M/DevTools/env.sh
PKG=com.mj.aranger
ACC="com.mj.aranger/com.mj.aranger.service.ARangerAccessibilityService"

echo "=== [1] 기기 연결 ==="
adb devices -l

echo "\n=== [2] 설치 버전 ==="
adb shell dumpsys package $PKG | grep -E "versionName|versionCode" | head -n 2

echo "\n=== [3] 런타임 권한 (WRITE_SECURE_SETTINGS 등) ==="
adb shell dumpsys package $PKG | grep -E "WRITE_SECURE_SETTINGS|POST_NOTIFICATIONS|REQUEST_IGNORE_BATTERY|RECEIVE_BOOT_COMPLETED" | sort -u

echo "\n=== [4] 접근성 서비스 등록 / 바인딩 ==="
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "enabled_accessibility_services = $CUR"
[[ "$CUR" == *"$ACC"* ]] && echo "✅ A-ranger 접근성 목록 포함" || echo "❌ A-ranger 접근성 목록 미포함"
adb shell dumpsys accessibility | grep -E "aranger" | head -n 5

echo "\n=== [5] 배터리 최적화 제외 ==="
adb shell dumpsys deviceidle whitelist | grep $PKG || echo "❌ whitelist 미등록"

echo "\n=== [6] 알림 리스너 ==="
adb shell settings get secure enabled_notification_listeners | tr ':' '\n' | grep aranger || echo "❌ 알림 리스너 미등록"

echo "\n=== [7] 프로세스 / 최근 크래시 ==="
adb shell pidof $PKG || echo "프로세스 없음"
adb logcat -d -b crash | grep -A 8 $PKG | tail -n 30 || true

echo "\n=== [8] 배포 엔드포인트 ==="
for URL in \
  "https://inanimals-1977.web.app/aranger/update.json" \
  "https://inanimals-corp-1977.web.app/aranger/update.json" \
  "https://raw.githubusercontent.com/selfness-byte/A-ranger/main/dist/update.json"; do
  echo "- $URL"
  curl -fsSL "$URL?t=$(date +%s)" | grep -E '"versionCode"|"versionName"|"sha256"'
done
echo "- inanimals-1977.web.app/version.json (com.mj.aranger)"
curl -fsSL "https://inanimals-1977.web.app/version.json?t=$(date +%s)" | grep -A 3 '"com.mj.aranger"' | grep -E "latest_version|version_code"

echo "\n=== [9] GitHub APK 해시 대조 ==="
LOCAL=$(shasum -a 256 /Volumes/M/A-ranger/dist/A-ranger-1.4.0.apk | cut -d' ' -f1)
REMOTE=$(curl -fsSL "https://raw.githubusercontent.com/selfness-byte/A-ranger/main/dist/A-ranger-1.4.0.apk" | shasum -a 256 | cut -d' ' -f1)
echo "local : $LOCAL"
echo "remote: $REMOTE"
[ "$LOCAL" = "$REMOTE" ] && echo "✅ GitHub APK 해시 일치" || echo "❌ GitHub APK 해시 불일치"
