package watch.rist.assistant

import android.content.Context
import android.provider.ContactsContract
import kotlin.math.abs

/**
 * The phone's own check on calls and texts the backend asks it to place with no tap. Mirrors
 * the core of the router's dial_policy.py, so a backend or TLS compromise cannot run up the bill.
 * Anything refused here is offered to the user as a tap instead. Emergency numbers never come
 * through this: an auto-call to one opens the dialer, and the user's own dialing is never touched.
 */
object DialPolicy {

    enum class Verdict { OK, SERVICE, EMERGENCY, PREMIUM, SPECIAL, INTERNATIONAL, SHORT_CODE, UNRECOGNISED, EMPTY }

    private val NANP_FOREIGN = setOf(
        "242", "246", "264", "268", "284", "340", "345", "441", "473", "649", "658", "664", "670",
        "671", "684", "721", "758", "767", "784", "787", "809", "829", "849", "868", "869", "876", "939",
    )
    private val NANP_PREMIUM_NPAS = setOf("900")
    private val NANP_PREMIUM_NXX = setOf("976")
    private val NANP_SPECIAL = setOf(
        "500", "521", "522", "523", "524", "525", "526", "527", "528", "529", "532", "533", "535",
        "538", "542", "543", "544", "545", "546", "547", "549", "550", "552", "553", "554", "556",
        "558", "566", "569", "577", "578", "588", "589",
        "456", "600", "622", "700", "710", "880", "881", "882",
    )
    private val EMERGENCY = setOf("911", "112", "988", "933")
    private val SERVICE_N11 = setOf("211", "311", "511", "611", "711", "811")

    const val CALLS_PER_DAY = 50
    const val TEXTS_PER_DAY = 100
    private const val PER_MINUTE = 3
    private const val MIN_GAP_MS = 10_000L
    private const val DAY_MS = 86_400_000L

    /** Same shape as phone.to_e164: a national ten digits gets +1, nothing else gets a country guessed. */
    internal fun toE164(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return when {
            number.trimStart().startsWith("+") -> "+$digits"
            digits.length <= 6 -> digits
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> digits
        }
    }

    fun classify(raw: String): Verdict {
        val number = toE164(raw)
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return Verdict.EMPTY
        val bare = if (digits.length == 4 && digits[0] == '1') digits.substring(1) else digits
        if (bare in EMERGENCY) return Verdict.EMERGENCY
        if (!number.startsWith("+")) {
            if (digits in SERVICE_N11) return Verdict.SERVICE
            if (digits.length <= 6) return Verdict.SHORT_CODE
            if (digits.startsWith("011")) return Verdict.INTERNATIONAL
            if (digits.length == 7 && digits[0] !in "01") {
                return if (digits.substring(0, 3) in NANP_PREMIUM_NXX) Verdict.PREMIUM else Verdict.OK
            }
            return Verdict.UNRECOGNISED
        }
        if (!number.startsWith("+1")) return Verdict.INTERNATIONAL
        val national = digits.substring(1)
        if (national.length != 10 || national[0] in "01" || national[3] in "01") return Verdict.UNRECOGNISED
        val npa = national.substring(0, 3)
        val nxx = national.substring(3, 6)
        if (npa in NANP_PREMIUM_NPAS || nxx in NANP_PREMIUM_NXX) return Verdict.PREMIUM
        if (npa in NANP_SPECIAL) return Verdict.SPECIAL
        if (npa in NANP_FOREIGN) return Verdict.INTERNATIONAL
        return Verdict.OK
    }

    /** Premium is never placed without a tap, even for a saved contact. */
    fun autoAllowed(v: Verdict, inContacts: Boolean): Boolean = when (v) {
        Verdict.OK, Verdict.SERVICE -> true
        Verdict.INTERNATIONAL, Verdict.SPECIAL, Verdict.SHORT_CODE, Verdict.UNRECOGNISED -> inContacts
        Verdict.PREMIUM, Verdict.EMERGENCY, Verdict.EMPTY -> false
    }

    fun inContacts(ctx: Context, number: String): Boolean = runCatching {
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
        )
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    enum class Quota { OK, TOO_SOON, MINUTE, DAY }

    /** Wall-clock stamps so the window survives a restart; a clock moved back still counts what it covers. */
    internal fun quota(history: List<Long>, now: Long, perDay: Int): Quota {
        val live = history.filter { abs(now - it) < DAY_MS }
        if (live.any { abs(now - it) < MIN_GAP_MS }) return Quota.TOO_SOON
        if (live.count { abs(now - it) < 60_000L } >= PER_MINUTE) return Quota.MINUTE
        if (live.size >= perDay) return Quota.DAY
        return Quota.OK
    }

    private const val PREFS = "rist_comms_quota"

    private fun history(ctx: Context, kind: String): List<Long> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind, "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }

    /** Checks the window for [kind] ("call" or "sms") and, when it allows, counts this one. */
    @Synchronized
    fun take(ctx: Context, kind: String, perDay: Int, now: Long = System.currentTimeMillis()): Quota {
        val past = history(ctx, kind).filter { abs(now - it) < DAY_MS }
        val q = quota(past, now, perDay)
        if (q == Quota.OK) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(kind, (past + now).joinToString(",")).commit()
        }
        return q
    }
}
