package watch.rist.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceConsentGateTest {

    @Test
    fun theWatcherRefusesToEvaluateWithoutTheLocationPermission() {
        val bytes = classBytes("GeofenceWatcher")
        assertTrue(
            "GeofenceWatcher no longer consults LocationProvider. Its wake is the only thing that " +
                "ever notices the permission went away, so without this check the handset keeps " +
                "fences it can never evaluate and keeps telling the backend it is watching them.",
            bytes.references("watch/rist/assistant/LocationProvider")
        )
        assertTrue(
            "GeofenceWatcher no longer calls hasPermission. §10 ties the whole feature to the OS " +
                "grant; a fence watching nothing is worse than no fence, because nothing tells the " +
                "user the instruction cannot run.",
            bytes.references("hasPermission")
        )
        assertTrue(
            "GeofenceWatcher no longer calls Geofences.dropAll. Checking the permission and then " +
                "keeping the fences would be worse than not checking: the backend's only signal " +
                "that the trigger is dead is the EMPTY geofence_state that dropping produces.",
            bytes.references("dropAll")
        )
    }

    @Test
    fun theRequestPathDropsFencesTooRatherThanWaitingForTheNextWake() {
        val bytes = classBytes("Uploader")
        assertTrue(
            "Uploader stopped checking the location permission before attaching geofence_state. " +
                "Every request between a revocation and the next wake now asserts fences that " +
                "cannot fire, and the backend has no way to tell.",
            bytes.references("hasPermission")
        )
        assertTrue(
            "Uploader must still reference dropAll.",
            bytes.references("dropAll")
        )
    }

    @Test
    fun theFencePathNeitherReadsNorWidensTheNetworkLocationConsent() {
        for (name in listOf("Geofences", "GeofenceWatcher")) {
            assertTrue(
                "$name references NetworkLocationConsent. A geofence is continuous background " +
                    "location; the network-lookup consent covers neither more nor less than it " +
                    "says, and reusing that answer here would silently repurpose it.",
                !classBytes(name).references("NetworkLocationConsent")
            )
        }
    }

    @Test
    fun aRebootRearmsTheWakeFromOurOwnStore() {
        val bytes = classBytes("BootReceiver")
        assertTrue(
            "BootReceiver no longer reaches GeofenceWatcher. The backend cannot speak first, so a " +
                "fence that only came back on the user's next conversation is a standing " +
                "instruction switched off by a reboot — invisibly, until the day it should have fired.",
            bytes.references("watch/rist/assistant/GeofenceWatcher")
        )
        assertTrue(
            "BootReceiver no longer calls reschedule. Re-registering from our OWN durable store is " +
                "the point: there is nothing else that can put the wake back.",
            bytes.references("reschedule")
        )
    }

    @Test
    fun anAppUpdateRearmsTheWakeToo() {
        val bytes = classBytes("GeofenceAlarmReceiver")
        assertTrue(
            "GeofenceAlarmReceiver no longer handles MY_PACKAGE_REPLACED. Installing a new APK " +
                "cancels every PendingIntent this package holds, so the fence wake would be dead " +
                "from the moment push.sh runs until the next reboot — days, on the one feature " +
                "whose whole purpose is to work with nobody watching.",
            bytes.references("android.intent.action.MY_PACKAGE_REPLACED")
        )
        assertTrue(
            "GeofenceAlarmReceiver stopped reacting to the power transitions. They are what make " +
                "the 30-minute charging-and-stationary sleep safe: you must unplug the phone to go " +
                "anywhere, so unplugging is the free signal that buys back the latency.",
            bytes.references("android.intent.action.ACTION_POWER_DISCONNECTED")
        )
    }

    @Test
    fun theWakeIsInexactButSurvivesDoze() {
        val bytes = classBytes("GeofenceWatcher")
        assertTrue(
            "GeofenceWatcher no longer calls setAndAllowWhileIdle. Plain set() is deferred to the " +
                "next Doze maintenance window, so an arrival home would be reported up to an hour " +
                "late — which reads as the feature being broken rather than slow.",
            bytes.references("setAndAllowWhileIdle")
        )
        assertTrue(
            "GeofenceWatcher now arms an EXACT alarm. Exact alarms cannot be coalesced with the " +
                "wakes the OS was taking anyway, which is where this feature's battery budget " +
                "comes from, and a place trigger has no deadline that could justify it.",
            !bytes.references("setExactAndAllowWhileIdle")
        )
    }

    @Test
    fun aFailedReportCannotUnwindPastTheNextWake() {
        val bytes = classBytes("GeofenceWatcher")
        assertTrue(
            "GeofenceWatcher no longer wraps its work in runCatching. A throw on the reporting leg " +
                "would escape tick() after the pass and before anything re-armed the alarm, and a " +
                "geofence that silently stops being evaluated is the single failure this feature is " +
                "not allowed to have.",
            bytes.references("runCatching") || bytes.references("kotlin/Result")
        )
    }

    // ISO-8859-1 is byte-preserving; UTF-8 would mangle constant-pool bytes.
    private fun ByteArray.references(marker: String): Boolean =
        String(this, Charsets.ISO_8859_1).contains(marker)

    private fun classBytes(simpleName: String): ByteArray {
        val entry = "watch/rist/assistant/$simpleName.class"
        val url = Geofences::class.java.getResource("Geofences.class")
        assertNotNull("cannot locate the compiled classes to scan", url)

        val bytes: ByteArray? = if (url!!.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            java.util.jar.JarFile(jarPath).use { jar ->
                jar.getJarEntry(entry)?.let { jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        } else {
            File(File(url.toURI()).parentFile, "$simpleName.class").takeIf { it.isFile }?.readBytes()
        }

        assertNotNull("$entry is not on the test classpath — this scan would prove nothing", bytes)
        assertTrue("$entry is empty", bytes!!.isNotEmpty())
        // 0xCAFEBABE: class-file magic.
        assertEquals("$entry is not a class file", 0xCA.toByte(), bytes[0])
        return bytes
    }
}
