package watch.rist.assistant

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import rist.v1.ContactRecord
import java.io.File

/**
 * Phone numbers in one comparable form.
 *
 * The backend stores E.164 (+12065550100); the radio and the call log hand us whatever the network
 * sent, often national (2065550100) or with formatting. Both sides go through [key] before they
 * are compared, and [tail] is the fallback for a number that cannot be put in E.164.
 */
object PhoneNumbers {

    /** The country to read a national number in: the network's, then the SIM's, then US. */
    fun country(ctx: Context): String = runCatching {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
    }.getOrNull()?.uppercase() ?: "US"

    /** E.164 when the number can be read as one, else its digits. Empty for a number with none. */
    fun key(number: String, country: String): String {
        val raw = number.trim()
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        if (raw.startsWith("+")) return "+$digits"
        runCatching { PhoneNumberUtils.formatNumberToE164(raw, country) }.getOrNull()
            ?.takeIf { it.startsWith("+") }?.let { return it }
        val nanp = country.uppercase() in NANP
        return when {
            nanp && digits.length == 10 -> "+1$digits"
            nanp && digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.startsWith("00") && digits.length > 4 -> "+" + digits.drop(2)
            else -> digits
        }
    }

    /** The last ten digits, for matching numbers that [key] could not settle. */
    fun tail(number: String): String = number.filter { it.isDigit() }.takeLast(TAIL)

    private const val TAIL = 10
    private val NANP = setOf("US", "CA", "PR", "GU", "VI", "AS", "MP")
}

/**
 * The owner's backend contacts as number to name, kept by the app itself.
 *
 * The system Contacts provider holds the real mirror ([ContactsMirror]) so the dialer and messages
 * apps see the names too. This index is the same data in the app's own storage, so Rist's screens
 * (the ring screen, the feed, the send toast) can name a caller even if the provider write was
 * refused, the contacts permission was not granted, or the account was removed.
 */
object ContactIndex {

    private const val TAG = "RistContacts"
    private const val FILE = "contacts_index.json"

    data class Entry(val id: String, val name: String, val numbers: List<String>)

    @Volatile private var loaded: Map<String, Entry>? = null
    @Volatile private var byKey: Map<String, String>? = null
    @Volatile private var byTail: Map<String, String?>? = null
    @Volatile private var keyCountry: String = ""

    private fun file(ctx: Context) = File(ctx.applicationContext.noBackupFilesDir, FILE)

    @Synchronized
    internal fun entries(ctx: Context): Map<String, Entry> {
        loaded?.let { return it }
        val out = LinkedHashMap<String, Entry>()
        runCatching {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val nums = o.optJSONArray("n") ?: JSONArray()
                    out[o.getString("i")] = Entry(
                        o.getString("i"), o.optString("d"),
                        (0 until nums.length()).map { nums.getString(it) },
                    )
                }
            }
        }.onFailure { Log.w(TAG, "contact index unreadable; starting empty", it) }
        loaded = out
        return out
    }

    fun size(ctx: Context): Int = entries(ctx).size

    /**
     * Applies one pull: on [full] the index becomes exactly [records]; otherwise [records] are
     * upserted on id and [deletedIds] removed.
     */
    @Synchronized
    fun apply(ctx: Context, full: Boolean, records: Collection<ContactRecord>, deletedIds: Collection<String>) {
        val next = if (full) LinkedHashMap() else LinkedHashMap(entries(ctx))
        deletedIds.forEach { next.remove(it) }
        for (r in records) {
            if (r.id.isBlank()) continue
            val numbers = r.methodsList.filter { it.kind.equals("phone", true) && it.value.isNotBlank() }
                .sortedByDescending { it.isPrimary }.map { it.value }
            next[r.id] = Entry(r.id, r.displayName, numbers)
        }
        save(ctx, next)
    }

    @Synchronized
    fun clear(ctx: Context) = save(ctx, emptyMap())

    @Synchronized
    private fun save(ctx: Context, m: Map<String, Entry>) {
        runCatching {
            val arr = JSONArray()
            m.values.forEach { e ->
                arr.put(JSONObject().put("i", e.id).put("d", e.name).put("n", JSONArray(e.numbers)))
            }
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "could not save the contact index", it) }
        loaded = LinkedHashMap(m)
        byKey = null
        byTail = null
    }

    /** The name for [number], or null. Exact on the normalised form, then on a unique last ten digits. */
    fun nameFor(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        val country = PhoneNumbers.country(ctx)
        var keys = byKey
        var tails = byTail
        if (keys == null || tails == null || keyCountry != country) {
            val k = HashMap<String, String>()
            val t = HashMap<String, String?>()
            for (e in entries(ctx).values) {
                if (e.name.isBlank()) continue
                for (n in e.numbers) {
                    PhoneNumbers.key(n, country).takeIf { it.isNotEmpty() }?.let { k.putIfAbsent(it, e.name) }
                    val tail = PhoneNumbers.tail(n)
                    if (tail.length >= 7) {
                        // Two people sharing a tail is no match: a wrong name is worse than none.
                        if (t.containsKey(tail) && t[tail] != e.name) t[tail] = null else t[tail] = e.name
                    }
                }
            }
            keys = k; tails = t
            byKey = k; byTail = t; keyCountry = country
        }
        PhoneNumbers.key(number, country).takeIf { it.isNotEmpty() }?.let { k -> keys[k]?.let { return it } }
        val tail = PhoneNumbers.tail(number)
        return if (tail.length >= 7) tails[tail] else null
    }

    internal fun resetForTest(ctx: Context) {
        file(ctx).delete()
        loaded = null; byKey = null; byTail = null
    }
}
