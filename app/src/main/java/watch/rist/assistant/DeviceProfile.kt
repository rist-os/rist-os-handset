package watch.rist.assistant

import android.content.Context
import rist.v1.Capabilities

object DeviceProfile {

    // Must equal SCHEMA_VERSION_CURRENT in the canonical proto.
    internal const val RCS_SCHEMA_VERSION = 12

    private const val COLOR_DEPTH_BITS = 24

    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    private val INPUT = listOf("button", "voice", "touch")

    private val COMPONENTS = listOf(
        "card", "stack", "text", "stat", "list", "image", "chart", "button", "divider"
    )

    internal fun maxImageBytes(): Int = MAX_IMAGE_BYTES

    fun capabilities(ctx: Context): Capabilities {
        val dm = ctx.resources.displayMetrics
        return capabilities(dm.widthPixels, dm.heightPixels)
    }

    internal fun capabilities(screenW: Int, screenH: Int): Capabilities =
        Capabilities.newBuilder()
            .setSchemaVersion(RCS_SCHEMA_VERSION)
            .setScreenW(screenW)
            .setScreenH(screenH)
            .setColorDepth(COLOR_DEPTH_BITS)
            .addAllInput(INPUT)
            .addAllComponents(COMPONENTS)
            .addComponents("map_tiles")
            .setMaxImageBytes(MAX_IMAGE_BYTES)
            // 0 or unset means "cannot do place triggers".
            .setMaxGeofences(Geofences.MAX_FENCES)
            // 0 or unset means "cannot render notifications".
            .setMaxNotifications(CommsFeed.MAX_NOTIFICATIONS)
            .build()
}
