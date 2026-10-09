package watch.rist.assistant

import android.app.Application
import android.os.HandlerThread
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.lang.reflect.Method
import org.robolectric.TestLifecycleApplication

/**
 * The application every Robolectric test runs in (robolectric.properties). It clears two pieces
 * of state that outlive a test, because a test fork otherwise gets slower with each test it runs:
 * in one fork the home-screen tests went from under a second each to 13-19 s.
 *
 * - LocalBroadcastManager is a process-wide singleton that Robolectric does not reset. Every
 *   activity a test starts registers receivers on it and is never destroyed, so by the end of a
 *   fork about a thousand receivers from earlier tests were still answering each broadcast,
 *   each one redrawing a screen nobody looks at.
 * - Robolectric resets QueuedWork by dropping its handler without quitting the looper, so each
 *   test that calls SharedPreferences.apply() leaves a "queued-work-looper" thread behind, and
 *   every live looper is visited on each clock advance (once per frame in a UI test).
 */
class RistTestApp : Application(), TestLifecycleApplication {
    override fun beforeTest(method: Method) {}

    override fun prepareTest(test: Any) {}

    override fun afterTest(method: Method) {
        val lbm = LocalBroadcastManager::class.java
        val lock = lbm.getDeclaredField("mLock").apply { isAccessible = true }.get(null)
        synchronized(lock) {
            lbm.getDeclaredField("mInstance").apply { isAccessible = true }.set(null, null)
        }

        Thread.getAllStackTraces().keys
            .filterIsInstance<HandlerThread>()
            .filter { it.name == "queued-work-looper" && it.isAlive }
            .forEach { it.quitSafely() }
    }
}
