package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.Capabilities

class DeviceProfileTest {

    @Test
    fun phoneCapabilities_advertiseFullColorTouchProfile() {
        val caps: Capabilities = DeviceProfile.capabilities(screenW = 1080, screenH = 2400)

        assertEquals(DeviceProfile.RCS_SCHEMA_VERSION, caps.schemaVersion)
        assertEquals(24, caps.colorDepth)
        assertEquals(3 * 1024 * 1024, caps.maxImageBytes)
        assertEquals(listOf("button", "voice", "touch"), caps.inputList)
        assertTrue(caps.componentsList.containsAll(
            listOf("card", "stack", "text", "stat", "list", "image", "chart", "button", "divider",
                   "map_tiles")
        ))
        assertEquals(10, caps.componentsCount)
    }

    @Test
    fun maxNotificationsIsAdvertised_andIsNotZero() {
        val caps = DeviceProfile.capabilities(1080, 2400)
        assertEquals(CommsFeed.MAX_NOTIFICATIONS, caps.maxNotifications)
        assertTrue(
            "caps.max_notifications is 0, which tells the backend this build cannot render " +
                "notifications at all. It will silently speak them on the next turn instead.",
            caps.maxNotifications > 0
        )
        assertTrue(
            "advertising the whole feed would let one burst of mail evict every missed call",
            caps.maxNotifications < CommsFeed.HARD_CAP
        )
    }

    @Test
    fun screenDimensions_areWiredThroughFromDisplay() {
        val caps = DeviceProfile.capabilities(screenW = 1440, screenH = 3120)
        assertEquals(1440, caps.screenW)
        assertEquals(3120, caps.screenH)
    }

    @Test
    fun capabilities_roundTripThroughProto() {
        val caps = DeviceProfile.capabilities(1080, 2400)
        val parsed = Capabilities.parseFrom(caps.toByteArray())
        assertEquals(caps, parsed)
    }
}
