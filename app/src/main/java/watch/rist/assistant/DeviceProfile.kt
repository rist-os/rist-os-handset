package watch.rist.assistant

import android.content.Context
import rist.v1.Capabilities

object DeviceProfile {

    // Must equal SCHEMA_VERSION_CURRENT in the server's schema.
    internal const val RCS_SCHEMA_VERSION = 18

    private const val COLOR_DEPTH_BITS = 24

    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    private val INPUT = listOf("button", "voice", "touch")

    // The ViewSpec kinds ViewRenderer draws. "stat", "list", "chart", "button" and "divider" are not
    // listed: nothing here draws them (tiles draw their own blocks, declared below).
    private val COMPONENTS = listOf("card", "stack", "text", "image")

    internal fun maxImageBytes(): Int = MAX_IMAGE_BYTES

    fun capabilities(ctx: Context): Capabilities {
        val dm = ctx.resources.displayMetrics
        // A phone whose account has no video calls does not offer to open one.
        return capabilities(dm.widthPixels, dm.heightPixels,
            videoCalls = VideoCalls.SHIPPED && Features.isOn(ctx, Features.Id.VIDEO_CALLS),
            fontIds = if (DesignSync.declared()) Fonts.available(ctx) else emptyList(),
            textsOnRequest = TextsOnRequest.declared(ctx))
    }

    internal fun capabilities(
        screenW: Int,
        screenH: Int,
        videoCalls: Boolean = VideoCalls.SHIPPED,
        homeBoxes: Boolean = HomeBoxes.declared(),
        design: Boolean = DesignSync.declared(),
        fontIds: List<String> = emptyList(),
        checklists: Boolean = Checklists.declared(),
        noteEdit: Boolean = NoteEdits.declared(),
        textsOnRequest: Boolean = false,
        tileBlocks: Boolean = TileBlocks.declared(),
        itemEdit: Boolean = ItemEdits.declared(),
    ): Capabilities =
        Capabilities.newBuilder()
            // v18 carries every field this build reads; the video_call component alone decides
            // whether the backend sends a call.
            .setSchemaVersion(RCS_SCHEMA_VERSION)
            .apply { if (videoCalls) addComponents(VideoCalls.COMPONENT) }
            .setScreenW(screenW)
            .setScreenH(screenH)
            .setColorDepth(COLOR_DEPTH_BITS)
            .addAllInput(INPUT)
            .addAllComponents(COMPONENTS)
            .addComponents("map_tiles")
            // Asks for 512-px tiles. Safe to send only because the map places every tile by its
            // decoded width and fills gaps in a partial set from coarser tiles (TilePlan).
            .addComponents("map_tiles_hd")
            // Whether this phone can draw home boxes; the backend sends no box list without it.
            .apply { if (homeBoxes) addComponents(HomeBoxes.COMPONENT) }
            // Whether this phone takes a DesignSpec, and the fonts a design may name.
            .apply { if (design) addComponents(DesignSync.COMPONENT).addAllComponents(Fonts.capsEntries(fontIds)) }
            // Whether this phone draws lists with checkboxes; without it lists come as markdown.
            .apply { if (checklists) addComponents(Checklists.COMPONENT) }
            // Whether this phone edits notes in place; without it notes are only spoken.
            .apply { if (noteEdit) addComponents(NoteEdits.COMPONENT) }
            // Whether this phone draws a tile's typed blocks and coloured icon roles, and whether
            // it can send edits, adds and deletes from them. Both ride on the box list.
            .apply { if (homeBoxes && tileBlocks) addComponents(TileBlocks.COMPONENT) }
            .apply { if (homeBoxes && tileBlocks && itemEdit) addComponents(ItemEdits.COMPONENT) }
            // Whether this phone answers inbound_sms_request: the owner's switch, and the permission.
            .apply { if (textsOnRequest) addComponents(TextsOnRequest.COMPONENT) }
            .setMaxImageBytes(MAX_IMAGE_BYTES)
            // 0 or unset means "cannot do place triggers".
            .setMaxGeofences(Geofences.MAX_FENCES)
            // 0 or unset means "cannot render notifications".
            .setMaxNotifications(CommsFeed.MAX_NOTIFICATIONS)
            .build()
}
