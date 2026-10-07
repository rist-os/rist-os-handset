package watch.rist.assistant

import android.content.Context
import rist.v1.DesignSpec

/**
 * Puts a look on the phone the way this build gets one: as a design from the backend when
 * designs ship, else by picking the theme in Settings.
 */
object TestLooks {

    private var version = 1_000L

    fun use(ctx: Context, t: RistTheme) {
        if (DesignSync.declared()) {
            DesignSync.apply(ctx, DesignSpec.newBuilder().setVersion(++version).setBaseTheme(t.id)
                .setCatalogue(DesignSync.CATALOGUE).putAllTokens(DesignSync.tokensOf(t)).build())
        } else {
            Config.setThemeId(ctx, t.id)
        }
    }

    /** Back to the factory look, by either route. */
    fun reset(ctx: Context) {
        DesignSync.resetForTest(ctx)
        Config.setThemeId(ctx, "ledger")
    }
}
