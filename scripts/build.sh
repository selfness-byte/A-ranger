#!/bin/zsh
# A-ranger Pro v1.3.0 APK 빌드 및 연결된 휴대폰 설치/권한 자동 활성화 스크립트
# 사용법: zsh /Volumes/M/A-ranger/scripts/build.sh [--install]
set -e

if [ ! -d /Volumes/MDev ]; then
  dot_clean -m /Volumes/M/DevTools/MDev.sparsebundle 2>/dev/null || true
  hdiutil attach /Volumes/M/DevTools/MDev.sparsebundle
fi

source /Volumes/M/DevTools/env.sh
PROJECT=/Volumes/M/A-ranger
cd "$PROJECT"

pkill -f "KotlinCompileDaemon" 2>/dev/null || true
pkill -f "GradleDaemon" 2>/dev/null || true
dot_clean -m "$PROJECT" 2>/dev/null || true
rm -rf "$NRM_BUILD_ROOT/ARanger/app/tmp/kotlin-classes" 2>/dev/null || true

gradle --offline --no-daemon --project-cache-dir /Volumes/MDev/project-cache/ARanger :app:assembleDebug --console=plain

mkdir -p "$PROJECT/dist"
cp "$NRM_BUILD_ROOT/ARanger/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/A-ranger-debug.apk"
cp "$NRM_BUILD_ROOT/ARanger/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/A-ranger-1.0.0.apk"
cp "$NRM_BUILD_ROOT/ARanger/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/A-ranger-1.1.0.apk"
cp "$NRM_BUILD_ROOT/ARanger/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/A-ranger-1.2.0.apk"
cp "$NRM_BUILD_ROOT/ARanger/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/A-ranger-1.3.1.apk"
echo "APK 생성 완료: $PROJECT/dist/A-ranger-1.3.1.apk"

if [ "$1" = "--install" ]; then
  adb install -r -g "$PROJECT/dist/A-ranger-1.3.1.apk"

  # 1. 다른 앱 위에 표시(플로팅 리모컨) & 앱 내 자동 업데이트 설치 권한 허용
  adb shell appops set com.mj.aranger SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
  adb shell appops set com.mj.aranger REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true
  adb shell appops set com.mj.aranger ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true

  # 2. 24시간 자가 복구(Self-Healing)를 위한 WRITE_SECURE_SETTINGS 및 배터리 최적화 제외 허용
  adb shell pm grant com.mj.aranger android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
  adb shell dumpsys deviceidle whitelist +com.mj.aranger 2>/dev/null || true

  # 3. 알림 리스너 권한 자동 허용
  adb shell cmd notification allow_listener com.mj.aranger/com.mj.aranger.service.ARangerNotificationService 2>/dev/null || true

  # 4. 기존 접근성 서비스 목록을 유지하면서 A-ranger 화면 제어 엔진 추가
  CURRENT_ACC=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
  TARGET_ACC="com.mj.aranger/com.mj.aranger.service.ARangerAccessibilityService"
  if [ "$CURRENT_ACC" = "null" ] || [ -z "$CURRENT_ACC" ]; then
    adb shell settings put secure enabled_accessibility_services "$TARGET_ACC"
  elif [[ "$CURRENT_ACC" != *"$TARGET_ACC"* ]]; then
    adb shell settings put secure enabled_accessibility_services "${CURRENT_ACC}:${TARGET_ACC}"
  fi
  adb shell settings put secure accessibility_enabled 1

  # 5. 앱 실행
  adb shell am start -n com.mj.aranger/.ui.MainActivity
fi
