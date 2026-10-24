package com.mj.aranger.service

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mj.aranger.engine.MacroEngine
import com.mj.aranger.store.MacroRepository

class ARangerNotificationService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: ARangerNotificationService? = null
            private set

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var lastContentIntent: PendingIntent? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected = true
    }

    override fun onListenerDisconnected() {
        isConnected = false
        if (instance === this) {
            instance = null
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val notif = sbn.notification ?: return
        notif.contentIntent?.let { lastContentIntent = it }

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $text".trim()

        if (combined.isNotEmpty()) {
            MacroRepository.lastNotificationText = combined
            MacroEngine.onNotificationReceived(applicationContext, pkg, combined)
        }
    }

    /**
     * 🔔 상단 알림창을 내리지 않고 키워드와 일치하는 알림(또는 가장 최근 알림)의 PendingIntent를 즉시 실행합니다.
     */
    fun clickNotificationByKeyword(keyword: String): Boolean {
        val query = keyword.trim()
        try {
            val activeList = activeNotifications ?: emptyArray()
            for (sbn in activeList) {
                if (sbn.packageName == packageName) continue
                val notif = sbn.notification ?: continue
                val pi = notif.contentIntent ?: continue
                val extras = notif.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                val combined = "${sbn.packageName} $title $text"
                if (query.isEmpty() || combined.contains(query, ignoreCase = true)) {
                    pi.send()
                    return true
                }
            }
            if (query.isEmpty() && lastContentIntent != null) {
                lastContentIntent?.send()
                return true
            }
        } catch (_: Exception) {
        }
        return false
    }
}
