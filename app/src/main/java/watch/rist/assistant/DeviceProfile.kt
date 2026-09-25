package watch.rist.assistant

import android.content.Context
import rist.v1.Capabilities

object DeviceProfile {

    // Must equal SCHEMA_VERSION_CURRENT in the canonical proto.
    internal const val RCS_SCHEMA_VERSION = 13

    private const val COLOR_DEPTH_BITS = 24

    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    private val INPUT = listOf("button", "voice", "touch")

    private val COMPONENTS = listOf(
        "card", "stack", "text", "stat", "list", "image", "chart", "button", "divider"
    )

    internal fun maxImageBytes(): Int = MAX_IMAGE_BYTES

    fun capabilities(ctx: Context): Capabilities {
        val dm = ctx.resources.displayMetrics
        // A phone whose account has no video calls does not offer to open one.
        return capabilities(dm.widthPixels, dm.heightPixels,
            videoCalls = VideoCalls.SHIPPED && Features.isOn(ctx, Features.Id.VIDEO_CALLS))
    }

    internal fun capabilities(screenW: Int, screenH: Int, videoCalls: Boolean = VideoCalls.SHIPPED): Capabilities =
        Capabilities.newBuilder()
            // Declared together or not at all: the component is what makes the backend send a
            // call, and v15 is the version that carries it.
            .setSchemaVersion(if (videoCalls) VideoCalls.SCHEMA_VERSION else RCS_SCHEMA_VERSION)
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
            .setMaxImageBytes(MAX_IMAGE_BYTES)
            // 0 or unset means "cannot do place triggers".
            .setMaxGeofences(Geofences.MAX_FENCES)
            // 0 or unset means "cannot render notifications".
            .setMaxNotifications(CommsFeed.MAX_NOTIFICATIONS)
            .build()
}
