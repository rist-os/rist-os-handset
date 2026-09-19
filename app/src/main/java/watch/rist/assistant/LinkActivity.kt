package watch.rist.assistant

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Where a web link tapped anywhere on the phone lands; the browser is hidden so that every one
 * comes here ([KioskManager.setAsDefaultForLinks]). Only a link from the camera's QR scanner
 * opens: the site it names, locked to that site, or a meeting's join screen. A link from anywhere
 * else (Messages, a saved page, an answer) goes nowhere.
 *
 * The sender is the one the system recorded for this launch, not anything in the intent. The
 * referrer reports that record unless the sender wrote its own referrer extra, so a link carrying
 * one is refused outright; what is left cannot be forged.
 */
class LinkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RistLink"

        /** The only app whose links open: its QR result is the person pointing at a code. */
        internal fun opensFrom(sender: String?): Boolean = sender == AppLauncher.PKG_CAMERA
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        val sender = recordedSender()
        val url = link?.let { SiteLock.openable(it) }
        if (url == null || !opensFrom(sender)) {
            // Which app and whether a link came, never the link itself.
            Log.i(TAG, "link from ${sender ?: "unknown"} not opened")
            if (url != null) Toast.makeText(this, R.string.link_qr_only, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val host = VideoCalls.ristHost(Config.backendUrl(this))
        // A meeting printed as a code is a call: the join screen, where nothing connects until Join.
        val opened = (VideoCalls.classify(url, host) != null && VideoCalls.join(this, url, "", "", "")) ||
            runCatching { startActivity(LockedBrowserActivity.intent(this, url)); true }
                .onFailure { Log.w(TAG, "could not open the scanned site", it) }
                .getOrDefault(false)
        if (!opened) Toast.makeText(this, R.string.link_failed, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** The launching app as the system recorded it, or null when that cannot be known for sure. */
    private fun recordedSender(): String? {
        runCatching { launchedFromPackage }.getOrNull()?.let { return it }
        val extras = intent?.extras
        if (extras != null && (extras.containsKey(Intent.EXTRA_REFERRER) || extras.containsKey(Intent.EXTRA_REFERRER_NAME))) {
            return null
        }
        return referrer?.takeIf { it.scheme == "android-app" }?.host
    }
}
