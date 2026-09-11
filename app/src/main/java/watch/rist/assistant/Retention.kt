package watch.rist.assistant

/**
 * How long an answer to a direct request stays on the home screen.
 *
 * This governs ONLY the transcript — the things you asked for and the replies to them.
 * Texts, calls, voicemail and notifications are a different regime with their own window;
 * see [CommsFeed.MAX_AGE_MS]. Nothing here touches them.
 *
 * A closed, ordered list rather than a free number: every value has to be sayable back to
 * the user ("kept for a week"), settable from the assistant, and pinned by a test. The order
 * is longest-first, which is the order the picker shows.
 */
object Retention {

    private const val SECOND = 1000L
    private const val MINUTE = 60L * SECOND
    private const val HOUR = 60L * MINUTE
    private const val DAY = 24L * HOUR

    /** [ms] of 0 means no time limit at all; [Transcript] skips the age sweep entirely. */
    data class Choice(val id: String, val label: String, val ms: Long)

    val FOREVER = Choice("forever", "Forever", 0L)

    val CHOICES: List<Choice> = listOf(
        FOREVER,
        Choice("30d", "30 days", 30 * DAY),
        Choice("7d", "1 week", 7 * DAY),
        Choice("1d", "1 day", DAY),
        Choice("12h", "12 hours", 12 * HOUR),
        Choice("1h", "1 hour", HOUR),
        Choice("30m", "30 minutes", 30 * MINUTE),
        Choice("5m", "5 minutes", 5 * MINUTE),
        Choice("2m", "2 minutes", 2 * MINUTE),
        Choice("30s", "30 seconds", 30 * SECOND),
    )

    fun byId(id: String): Choice? = CHOICES.firstOrNull { it.id == id.trim().lowercase() }

    /**
     * The choice a stored millisecond value represents.
     *
     * An unrecognised value — one written by an older build, or by hand — falls to the
     * nearest choice that keeps messages AT LEAST as long, so a stale number can never
     * silently shorten how long somebody's messages are kept.
     */
    fun byMs(ms: Long): Choice {
        if (ms <= 0L) return FOREVER
        CHOICES.firstOrNull { it.ms == ms }?.let { return it }
        val timed = CHOICES.filter { it.ms > 0L }
        // The smallest choice that is still at least as long; if the value is longer than
        // anything offered, the LONGEST one — never CHOICES.last(), which is the shortest.
        return timed.filter { it.ms >= ms }.minByOrNull { it.ms }
            ?: timed.maxByOrNull { it.ms }
            ?: FOREVER
    }

    /** The ids the assistant may be given, for the settings registry's error message. */
    fun ids(): List<String> = CHOICES.map { it.id }
}
