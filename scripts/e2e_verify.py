#!/usr/bin/env python3
"""A-ranger Pro 실기기 E2E 기능 검증 스크립트.

검증 항목
  1) MY_PACKAGE_REPLACED → BootAndUpdateReceiver 자가복구 로그
  2) 🎨 픽셀 색상 추출(takeScreenshot) 정확도 (TAP_IF_COLOR 일치/불일치 대조)
  3) 📹 CUSTOM_GESTURE_PATH 궤적 재생
  4) 🌐 WEBHOOK_HTTP 전송 + {time}/{pkg} 치환
  5) 🔊 볼륨[↓] 긴급 정지 (실행 중일 때만 가로채기, 대기 중엔 원래 볼륨 동작)
  6) ♻️ 접근성 서비스 강제 해제 후 자가 복구
테스트가 끝나면 원래 매크로/설정(shared_prefs)을 그대로 복원합니다.

사용법: python3 /Volumes/M/A-ranger/scripts/e2e_verify.py
"""
import json
import os
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "/Volumes/M/DevTools/android-sdk/platform-tools/adb"
PKG = "com.mj.aranger"
ACC = "com.mj.aranger/com.mj.aranger.service.ARangerAccessibilityService"
PREFS = f"/data/data/{PKG}/shared_prefs/aranger_store.xml"
WORK = "/Volumes/M/A-ranger/build-verify"
os.environ.setdefault("ADB_VENDOR_KEYS", "/Users/mj/.android/adbkey")

results = []


def adb(*args, data=None, binary=False):
    out = subprocess.run([ADB, *args], input=data, capture_output=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace").strip()


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("✅ " if ok else "❌ ") + name + (f"  — {detail}" if detail else ""))


def read_prefs():
    return adb("exec-out", "run-as", PKG, "cat", PREFS, binary=True)


def write_prefs(xml_bytes):
    adb("shell", "run-as", PKG, "sh", "-c", f"'cat > {PREFS}'", data=xml_bytes)


NOTI = "com.mj.aranger/com.mj.aranger.service.ARangerNotificationService"


def replace_prefs_offline(xml_bytes):
    """앱 프로세스가 완전히 죽은 상태에서만 prefs 파일을 교체합니다.

    force-stop 직후 시스템이 알림 리스너를 다시 바인딩하면서 프로세스가 곧바로 재기동되어,
    메모리에 남은 옛 설정으로 방금 쓴 파일을 덮어쓰는 문제가 있었습니다.
    그래서 알림 리스너를 잠시 해제하고, 프로세스 종료를 확인한 뒤 쓰고, 다시 읽어 검증합니다.
    """
    adb("shell", "cmd", "notification", "disallow_listener", NOTI)
    for _ in range(5):
        adb("shell", "am", "force-stop", PKG)
        time.sleep(1.0)
        if adb("shell", "pidof", PKG) == "":
            break
    write_prefs(xml_bytes)
    alive = adb("shell", "pidof", PKG)
    written_ok = ET.tostring(ET.fromstring(read_prefs())) == ET.tostring(ET.fromstring(xml_bytes))
    return alive == "" and written_ok


def allow_noti():
    adb("shell", "cmd", "notification", "allow_listener", NOTI)


def get_pref_string(xml_bytes, key):
    root = ET.fromstring(xml_bytes)
    for el in root:
        if el.get("name") == key:
            return el.text or ""
    return None


def set_pref(xml_bytes, key, value, tag="string"):
    root = ET.fromstring(xml_bytes)
    target = next((el for el in root if el.get("name") == key), None)
    if target is None:
        target = ET.SubElement(root, tag, {"name": key})
    if tag == "string":
        target.text = value
    else:
        target.set("value", value)
    # ET.tostring(encoding="utf-8")는 XML 선언을 자체적으로 붙이므로 별도로 덧붙이지 않습니다 (이중 선언 시 Android가 파싱 실패).
    return ET.tostring(root, encoding="utf-8")


def logs(xml_bytes):
    raw = get_pref_string(xml_bytes, "logs_json") or "[]"
    return json.loads(raw)


def acc_list():
    return adb("shell", "settings", "get", "secure", "enabled_accessibility_services")


def acc_bound():
    dump = adb("shell", "dumpsys", "accessibility")
    bound_section = dump.split("Bound services:")[1].split("Enabled services:")[0] if "Bound services:" in dump else dump
    return "com.mj.aranger" in bound_section


def wait_for(pred, timeout, step=1.0):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(step)
    return False


def pixel_hex(x, y):
    raw = adb("exec-out", "screencap", binary=True)
    w, h = struct.unpack_from("<II", raw, 0)
    header = len(raw) - w * h * 4
    off = header + (y * w + x) * 4
    r, g, b = raw[off], raw[off + 1], raw[off + 2]
    return f"#{r:02X}{g:02X}{b:02X}", w, h


def music_volume():
    out = adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--get")
    return out.splitlines()[-1] if out else ""


def volume_key_device():
    """물리 볼륨키가 연결된 /dev/input/eventN 경로를 찾습니다."""
    current = None
    for line in adb("shell", "getevent", "-pl").splitlines():
        if line.startswith("add device"):
            current = line.split(":", 1)[1].strip()
        elif "KEY_VOLUMEDOWN" in line and current:
            return current
    return None


def press_physical_volume_down(dev):
    """adb `input keyevent`는 접근성 키 필터 뒤에서 주입되어 onKeyEvent에 도달하지 않으므로,
    커널 입력 장치에 실제 하드웨어 키 이벤트(EV_KEY 114)를 써서 전체 입력 파이프라인을 통과시킵니다."""
    cmd = (f"sendevent {dev} 1 114 1; sendevent {dev} 0 0 0; "
           f"sleep 0.08; sendevent {dev} 1 114 0; sendevent {dev} 0 0 0")
    adb("shell", cmd)


def main():
    os.makedirs(WORK, exist_ok=True)
    if "device" not in adb("get-state"):
        print("기기가 연결되어 있지 않습니다.")
        sys.exit(1)

    # ---- 1) 업데이트 직후 BootAndUpdateReceiver 로그 ----
    original = read_prefs()
    backup_path = f"{WORK}/aranger_store.backup.xml"
    cur_macros = json.loads(get_pref_string(original, "macros_json") or "[]")
    if any(m.get("id", "").startswith("verify-") for m in cur_macros) and os.path.exists(backup_path):
        # 이전 테스트 잔여물이 남아 있으면 깨끗한 백업본을 원본으로 사용
        print("   ⚠️ 이전 테스트 매크로 잔여물 발견 → 깨끗한 백업본으로 원본 대체")
        original = open(backup_path, "rb").read()
    else:
        open(backup_path, "wb").write(original)
    # 원본에서 이전 테스트 잔여 매크로(verify-*)를 항상 제거
    clean_macros = [m for m in json.loads(get_pref_string(original, "macros_json") or "[]")
                    if not m.get("id", "").startswith("verify-")]
    original = set_pref(original, "macros_json", json.dumps(clean_macros, ensure_ascii=False))
    open(backup_path, "wb").write(original)
    ORIGINAL["xml"] = original
    replaced_logs = [l for l in logs(read_prefs()) if "자가 복구 워치독" in l.get("macroName", "")]
    check("1. MY_PACKAGE_REPLACED → BootAndUpdateReceiver 실행",
          bool(replaced_logs),
          replaced_logs[0]["message"] if replaced_logs else "로그 없음")

    # ---- 기준 픽셀 색상 측정 (MainActivity 좌측 여백) ----
    adb("shell", "am", "start", "-n", f"{PKG}/.ui.MainActivity")
    time.sleep(2.5)
    px, py = 18, 1500
    expected, w, h = pixel_hex(px, py)
    print(f"   기준 픽셀 ({px},{py}) = {expected}  (화면 {w}x{h})")

    # ---- 테스트 매크로 주입 ----
    path_json = json.dumps({"durationMs": 320, "points": [[720, 1900], [740, 1800], [720, 1700]]})
    macros = json.loads(get_pref_string(original, "macros_json") or "[]")
    for m in macros:
        m["enabled"] = False  # 기존 매크로가 테스트에 간섭하지 않도록 일시 비활성화 (복원 시 원상복구)
    test_a = {
        "id": "verify-a", "name": "VERIFY-A", "enabled": True,
        "triggerType": "INTERVAL_TIMER", "triggerParam": "3600",
        "constraintType": "NONE", "constraintParam": "", "cooldownMs": 1000,
        "lastRunAt": 0, "runCount": 0,
        "actions": [
            {"type": "WAIT", "durationMs": 9000},
            {"type": "TAP_IF_COLOR", "x": px, "y": py, "durationMs": 60, "colorHex": expected},
            {"type": "TAP_IF_COLOR", "x": px, "y": py, "durationMs": 60, "colorHex": "#FF00FF"},
            {"type": "CUSTOM_GESTURE_PATH", "durationMs": 320, "repeatCount": 1, "gesturePathJson": path_json},
            {"type": "WEBHOOK_HTTP", "textParam": "https://inanimals-1977.web.app/version.json?verify={time}&pkg={pkg}"},
        ],
    }
    test_b = {
        "id": "verify-b", "name": "VERIFY-B", "enabled": True,
        "triggerType": "INTERVAL_TIMER", "triggerParam": "3600",
        "constraintType": "NONE", "constraintParam": "", "cooldownMs": 1000,
        "lastRunAt": 0, "runCount": 0,
        "actions": [
            {"type": "WAIT", "durationMs": 25000},
            {"type": "WEBHOOK_HTTP", "textParam": "https://inanimals-1977.web.app/version.json?B_NOT_CANCELLED=1"},
        ],
    }
    patched = set_pref(original, "macros_json", json.dumps([test_a, test_b] + macros, ensure_ascii=False))
    patched = set_pref(patched, "logs_json", "[]")
    patched = set_pref(patched, "master_enabled", "true", tag="boolean")
    # 테스트 동안만 기능 스위치를 켭니다 (종료 시 사용자의 원래 설정 파일로 그대로 복원)
    for key in ("self_healing_enabled", "volume_panic_enabled", "humanizer_enabled"):
        patched = set_pref(patched, key, "true", tag="boolean")

    ok_write = replace_prefs_offline(patched)
    print(f"   테스트 prefs 주입(프로세스 종료 확인+파일 검증): {ok_write}")
    if not ok_write:
        check("0.  테스트 매크로 주입", False, "프로세스가 살아있거나 파일 검증 실패")
        return
    after_stop = acc_list()
    print(f"   force-stop 직후 A-ranger 접근성 목록 포함 여부: {ACC in after_stop}")
    adb("shell", "am", "start", "-n", f"{PKG}/.ui.MainActivity")
    t0 = time.time()
    time.sleep(2)
    allow_noti()

    # ---- 6-a) force-stop 이후 자가 복구 ----
    healed = wait_for(lambda: ACC in acc_list() and acc_bound(), timeout=15)
    check("6a. 강제 종료 후 접근성 서비스 자가 복구(재등록+바인딩)", healed,
          f"{time.time() - t0:.1f}s 소요" if healed else acc_list())

    # ---- 5) 볼륨[↓] 긴급 정지 (VERIFY-B 실행 중) ----
    vol_dev = volume_key_device()
    print(f"   물리 볼륨키 입력 장치: {vol_dev}")
    time.sleep(max(0, 16 - (time.time() - t0)))
    vol_before_panic = music_volume()
    press_physical_volume_down(vol_dev)
    time.sleep(1.2)
    vol_after_panic = music_volume()
    time.sleep(13)

    cur = read_prefs()
    entries = logs(cur)
    open(f"{WORK}/run_logs.json", "w").write(json.dumps(entries, ensure_ascii=False, indent=2))
    msgs = [f"[{e['macroName']}] {e['message']}" for e in entries]

    a_started = any(e["macroName"] == "VERIFY-A" and "발동됨" in e["message"] for e in entries)
    a_fail_steps = [e["message"] for e in entries if e["macroName"] == "VERIFY-A" and "미수행" in e["message"]]
    check("2a. 🎨 색상 일치 시 TAP_IF_COLOR 실행", a_started and not any("단계 2 " in m for m in a_fail_steps),
          f"기준색 {expected}")
    check("2b. 🎨 색상 불일치(#FF00FF) 시 터치 건너뜀", any("단계 3 " in m for m in a_fail_steps))
    check("3.  📹 자유 궤적 제스처 재생", a_started and not any("단계 4 " in m for m in a_fail_steps))
    hook = [e for e in entries if e["macroName"].startswith("🌐") and "verify=" in e["message"]]
    check("4.  🌐 웹훅 전송 + {time}/{pkg} 치환",
          bool(hook) and hook[0]["isSuccess"] and "%7Btime%7D" not in hook[0]["message"] and "{time}" not in hook[0]["message"],
          hook[0]["message"] if hook else "웹훅 로그 없음")
    panic = [e["message"] for e in entries if e["macroName"] == "긴급 실행 중지"]
    not_cancelled = any("B_NOT_CANCELLED" in e["message"] for e in entries)
    check("5a. 🔊 실행 중 물리 볼륨[↓] → 매크로 즉시 중단 + 볼륨 유지(키 가로챔)",
          bool(panic) and not not_cancelled and vol_before_panic == vol_after_panic,
          (panic[0] if panic else "긴급정지 로그 없음") + f" / 볼륨 {vol_before_panic} → {vol_after_panic}")

    # ---- 5-b) 대기 중엔 볼륨키를 가로채지 않는지 (물리 키 이벤트로 검증) ----
    before = music_volume()
    press_physical_volume_down(vol_dev)
    time.sleep(1.2)
    after = music_volume()
    adb("shell", "input", "keyevent", "KEYCODE_VOLUME_UP")
    check("5b. 🔊 매크로 대기 중엔 물리 볼륨키 정상 동작(가로채지 않음)", before != after,
          f"{before} → {after}")

    # ---- 6-b) 실행 중 접근성 목록에서 강제로 제거 → 자가 복구 ----
    cur_list = acc_list()
    stripped = ":".join(s for s in cur_list.split(":") if s and s != ACC)
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services", stripped or "null")
    t1 = time.time()
    time.sleep(1.5)
    removed = ACC not in acc_list()
    healed2 = wait_for(lambda: ACC in acc_list() and acc_bound(), timeout=15)
    check("6b. 실행 중 접근성 강제 해제 → 자가 복구", removed and healed2,
          f"{time.time() - t1:.1f}s 만에 복구" if healed2 else "복구 안 됨")

    print("\n--- 테스트 실행 로그 (최신순) ---")
    for m in msgs[:20]:
        print("   " + m)

    # ---- 원상 복구 ----
    restore_original()


ORIGINAL = {"xml": None}


def restore_original():
    original = ORIGINAL["xml"]
    if original is None:
        return
    ok_write = False
    for attempt in range(3):
        ok_write = replace_prefs_offline(original)
        if ok_write:
            break
        print(f"   ⚠️ 복원 쓰기 재시도 {attempt + 1}: pid={adb('shell', 'pidof', PKG)!r}")
    # 사용자 원래 설정에서 자가복구가 꺼져 있을 수 있으므로 접근성은 직접 재등록합니다
    entries = [s for s in acc_list().split(":") if s and s != "null"]
    if ACC not in entries:
        entries.append(ACC)
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(entries))
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    adb("shell", "am", "start", "-n", f"{PKG}/.ui.MainActivity")
    time.sleep(2)
    allow_noti()
    ORIGINAL["xml"] = None
    restored = wait_for(lambda: ACC in acc_list() and acc_bound(), timeout=15)
    final_macros = json.loads(get_pref_string(read_prefs(), "macros_json") or "[]")
    check("7.  원래 매크로/설정 복원 + 접근성 재연결",
          restored and not any(m.get("id", "").startswith("verify-") for m in final_macros),
          f"매크로 {len(final_macros)}개")

    passed = sum(1 for _, ok, _ in results if ok)
    print(f"\n=== 결과: {passed}/{len(results)} 통과 ===")


if __name__ == "__main__":
    saved_acc = acc_list()
    try:
        main()
    finally:
        # 안전장치 1: 중간에 실패해도 사용자 원래 prefs를 복원
        try:
            restore_original()
        except Exception as exc:
            print(f"⚠️ prefs 복원 중 오류: {exc}")
        allow_noti()
        # 안전장치 2: 테스트 결과와 무관하게 원래 접근성 목록(+A-ranger)을 반드시 복원
        entries = [s for s in saved_acc.split(":") if s and s != "null"]
        if ACC not in entries:
            entries.append(ACC)
        current = [s for s in acc_list().split(":") if s and s != "null"]
        if set(current) != set(entries):
            adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(entries))
            adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
            print("🛟 접근성 서비스 목록을 테스트 전 상태로 복원했습니다.")
