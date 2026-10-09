package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.Capabilities

class DeviceProfileTest {

    @Test
    fun schemaVersion_isTheOneTheProtoCallsCurrent() {
        // The proto follows the server's schema; the constant is typed by hand.
        // When they drift, the backend is told the device speaks a version it does not.
        assertEquals(rist.v1.SchemaVersion.SCHEMA_VERSION_CURRENT_VALUE, DeviceProfile.RCS_SCHEMA_VERSION)
    }

    @Test
    fun phoneCapabilities_advertiseFullColorTouchProfile() {
        val caps: Capabilities = DeviceProfile.capabilities(screenW = 1080, screenH = 2400)

        assertEquals(DeviceProfile.RCS_SCHEMA_VERSION, caps.schemaVersion)
        assertEquals(24, caps.colorDepth)
        assertEquals(3 * 1024 * 1024, caps.maxImageBytes)
        assertEquals(listOf("button", "voice", "touch"), caps.inputList)
        assertTrue(caps.componentsList.containsAll(
            listOf("card", "stack", "text", "image", "map_tiles", "map_tiles_hd")
        ))
        // ViewSpec kinds nothing on the phone draws are not advertised.
        for (gone in listOf("stat", "list", "chart", "button", "divider")) {
            assertTrue("$gone is advertised", gone !in caps.componentsList)
        }
        // capabilities(w, h) lists no fonts; the device's own call adds one "font:<id>" per font.
        val expected = 6 + listOf(VideoCalls.SHIPPED, HomeBoxes.SHIPPED, DesignSync.SHIPPED, Checklists.SHIPPED,
            NoteEdits.SHIPPED, TileBlocks.SHIPPED, ItemEdits.SHIPPED).count { it }
        assertEquals(expected, caps.componentsCount)
    }

    @Test
    fun tileComponents_areDeclaredOnlyWithBoxes_andEditsOnlyWithBlocks() {
        val all = DeviceProfile.capabilities(1080, 2400, homeBoxes = true, tileBlocks = true, itemEdit = true).componentsList
        assertTrue(all.containsAll(listOf("home_boxes", "tile_blocks_v1", "item_edit_v1")))
        val noEdits = DeviceProfile.capabilities(1080, 2400, homeBoxes = true, tileBlocks = true, itemEdit = false).componentsList
        assertTrue("tile_blocks_v1" in noEdits && "item_edit_v1" !in noEdits)
        val noBlocks = DeviceProfile.capabilities(1080, 2400, homeBoxes = true, tileBlocks = false, itemEdit = true).componentsList
        assertTrue("tile_blocks_v1" !in noBlocks && "item_edit_v1" !in noBlocks)
        val noBoxes = DeviceProfile.capabilities(1080, 2400, homeBoxes = false, tileBlocks = true, itemEdit = true).componentsList
        assertTrue("tile_blocks_v1" !in noBoxes && "item_edit_v1" !in noBoxes)
    }

    @Test
    fun hdMapTiles_areAdvertisedWithPlainTiles_withOrWithoutVideoCalls() {
        // map_tiles_hd alone gets no tiles at all; the backend needs both to send 512-px tiles.
        for (vc in listOf(false, true)) {
            val comps = DeviceProfile.capabilities(1080, 2400, videoCalls = vc).componentsList
            assertTrue(comps.contains("map_tiles"))
            assertTrue(comps.contains("map_tiles_hd"))
            assertEquals(1, comps.count { it == "map_tiles_hd" })
        }
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
