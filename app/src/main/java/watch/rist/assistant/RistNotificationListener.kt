package watch.rist.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RistNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
        NotificationHub.listenerConnected = true
        refresh()
    }

    override fun onListenerDisconnected() {
        NotificationHub.listenerConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        refresh()
        if (sbn != null) {
            TextAlert.onPosted(this, sbn, factsFor(sbn, rankingMap)) { key ->
                runCatching { cancelNotification(key) }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = onNotificationPosted(sbn, null)

    /** Android's own verdict on the notification: Do Not Disturb, importance, the channel's sound. */
    private fun factsFor(sbn: StatusBarNotification, map: RankingMap?): TextAlert.RankFacts? = runCatching {
        val r = Ranking()
        val m = map ?: currentRanking ?: return@runCatching null
        if (!m.getRanking(sbn.key, r)) return@runCatching null
        val ch = r.channel
        TextAlert.RankFacts(
            matchesInterruptionFilter = r.matchesInterruptionFilter(),
            peekSuppressed = r.suppressedVisualEffects and
                android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK != 0,
            importance = r.importance,
            channelSound = ch?.sound,
            channelVibrates = ch?.shouldVibrate() == true ||
                (sbn.notification?.defaults ?: 0) and android.app.Notification.DEFAULT_VIBRATE != 0 ||
                sbn.notification?.vibrate != null,
            channelVibration = ch?.vibrationPattern,
        )
    }.getOrNull()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RistNotif"

        @Volatile private var instance: RistNotificationListener? = null

        fun dismissFor(packages: Set<String>): Int {
            val svc = instance ?: return 0
            var n = 0
            runCatching {
                for (sbn in svc.activeNotifications ?: emptyArray()) {
                    if (sbn == null || sbn.packageName !in packages) continue
                    if (sbn.isOngoing || !sbn.isClearable) continue
                    svc.cancelNotification(sbn.key)
                    n++
                }
            }.onFailure { Log.w(TAG, "could not dismiss notifications", it) }
            if (n > 0) Log.i(TAG, "dismissed $n notification(s) for ${packages.joinToString()}")
            return n
        }
    }

    private fun isVoicemail(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.channelId?.contains("voicemail", true) == true) return true
        val title = n.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        return title?.contains("voicemail", true) == true
    }

    private fun refresh() {
        val tally = HashMap<String, Int>()
        var voicemail = false
        val active = runCatching { activeNotifications }.getOrNull() ?: run {
            Log.w(TAG, "could not read active notifications; leaving the last tally standing")
            return
        }
        for (sbn in active) {
            if (sbn == null) continue
            if (sbn.isOngoing) continue
            if (sbn.packageName == packageName) continue
            val flags = sbn.notification?.flags ?: 0
            if (flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) continue
            tally[sbn.packageName] = (tally[sbn.packageName] ?: 0) + 1
            if (isVoicemail(sbn)) voicemail = true
        }
        if (tally.isNotEmpty()) Log.i(TAG, "unread by package: $tally (voicemail=$voicemail)")
        NotificationHub.update(applicationContext, tally, voicemail)
    }

}
