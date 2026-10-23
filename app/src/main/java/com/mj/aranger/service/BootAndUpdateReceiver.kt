package com.mj.aranger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mj.aranger.engine.MacroEngine
import com.mj.aranger.model.LogEntry
import com.mj.aranger.store.MacroRepository

class BootAndUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val healed = MacroRepository.ensureSelfHealingAccessibility(context)
            MacroEngine.startTimerLoop(context)
            MacroRepository.addLog(
                context,
                LogEntry(
                    macroName = "♻️ 자가 복구 워치독",
                    message = "시스템 이벤트($action) 감지 → 엔진 가동 완료 (접근성 자가복구=$healed)",
                    isSuccess = true
                )
            )
        }
    }
}
