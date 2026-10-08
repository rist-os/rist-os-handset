package watch.rist.assistant

import android.content.Context

/**
 * Texts and calls on request (schema v26). The phone keeps no queue of texts and sends none on its
 * own. When the owner asks something that needs them ("save the number of whoever just texted me"),
 * the backend answers that turn with `inbound_sms_request`, and the phone sends the same turn again,
 * once, with its last day of texts ([SmsInbox.read]) and calls ([RecentCalls.read]).
 *
 * The phone offers this only while the owner's switch is on and Rist can read its messages; with it
 * off, the backend never asks and nothing leaves the phone.
 */
object TextsOnRequest {

    /** The capability the backend looks for before it asks. */
    const val COMPONENT = "texts_on_request"

    fun declared(ctx: Context): Boolean = Config.textsOnAsk(ctx) && SmsInbox.canRead(ctx)
}
