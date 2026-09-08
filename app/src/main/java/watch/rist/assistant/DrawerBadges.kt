package watch.rist.assistant

internal object DrawerBadges {

    data class Counts(
        val phone: Int, val msg: Int, val cam: Int,
        val pics: Int, val maps: Int, val settings: Int,
    ) {
        val total: Int get() = phone + msg + cam + pics + maps + settings
    }

    fun of(
        w: CommsFeed.Waiting?,
        phone: Int,
        messages: Int,
        camera: Int,
        gallery: Int,
        maps: Int,
        settings: Int,
    ): Counts = Counts(
        phone = maxOf(phone, w?.calls ?: 0),
        msg = maxOf(messages, w?.sms ?: 0),
        cam = camera,
        pics = gallery,
        maps = maps,
        settings = settings,
    )
}
