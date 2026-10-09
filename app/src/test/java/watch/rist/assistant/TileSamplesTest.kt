package watch.rist.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rist.v1.BoxSet
import rist.v1.HomeBox
import rist.v1.TileBlock
import rist.v1.TileRow
import java.io.File

/**
 * Pictures of expanded tiles drawn from blocks, light and dark, for a person to look at. Written
 * only when RIST_TILE_SAMPLES names a folder; otherwise skipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TileSamplesTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val out: String? = System.getenv("RIST_TILE_SAMPLES")

    @Before fun setUp() { HomeBoxes.resetForTest(app); TestLooks.reset(app) }
    @After fun tidy() { HomeBoxes.resetForTest(app); TestLooks.reset(app) }

    private fun row(text: String = "", b: TileRow.Builder.() -> Unit = {}) = TileRow.newBuilder().setText(text).apply(b).build()
    private fun block(kind: String, vararg rows: TileRow, b: TileBlock.Builder.() -> Unit = {}) =
        TileBlock.newBuilder().setKind(kind).addAllRows(rows.toList()).apply(b).build()

    private fun weather() = HomeBox.newBuilder().setTitle("Weather").setValue("58°F").setIcon("rainy_light").setIconTone("cool")
        .addBlocks(block("list", row("58°F") { label = "Now"; detail = "Feels like 55° · Humidity 81%"; extra = "Wind 9 mph S"; icon = "rainy_light"; iconTone = "cool"; iconDesc = "light rain" }) { title = "Bellevue" })
        .addBlocks(block("forecast",
            row("60°") { label = "Fri"; detail = "44°"; extra = "40%"; icon = "rainy_light"; iconTone = "cool"; iconDesc = "light rain" },
            row("66°") { label = "Sat"; detail = "48°"; extra = "0%"; icon = "sunny"; iconTone = "warm"; iconDesc = "sunny" },
            row("63°") { label = "Sun"; detail = "47°"; extra = "10%"; icon = "partly_cloudy_day"; iconTone = "warm"; iconDesc = "partly cloudy" },
            row("55°") { label = "Mon"; detail = "45°"; extra = "80%"; icon = "thunderstorm"; iconTone = "storm"; iconDesc = "thunderstorm" },
            row("41°") { label = "Tue"; detail = "30°"; extra = "60%"; icon = "weather_snowy"; iconTone = "cold"; iconDesc = "snow" },
        ) { title = "Next five days" })
        .addBlocks(block("text") { title = "Summary"; fallbackMarkdown = "Light rain until mid-morning, then a **dry, sunny** weekend." })

    private fun item(id: String, text: String, checked: Boolean = false) = row(text) {
        this.id = id; target = "item"; checkable = true; this.checked = checked; editable = true; deletable = true; editText = text
    }

    private fun shopping() = HomeBox.newBuilder().setTitle("Shopping").setValue("4").setIcon("shopping_cart").setIconTone("accent")
        .addBlocks(block("checklist", item("m", "Milk"), item("e", "6 eggs"), item("b", "Bread"), item("t", "Tea"),
            item("a", "Apples", checked = true)) { title = "Shopping list"; addTo = "shopping" })
        .addBlocks(block("list", row("Standup") { id = "ev"; target = "event"; label = "9:00 AM"; detail = "Room 4"; icon = "event"; iconTone = "accent"; editable = true; deletable = true; editText = "Standup" },
            row("Dentist") { id = "ev2"; target = "event"; label = "2:30 PM"; detail = "Main St"; icon = "event"; iconTone = "accent"; editable = true; deletable = true; editText = "Dentist" }) { title = "Today" })

    private fun numbers() = HomeBox.newBuilder().setTitle("Markets").setValue("+0.8%").setIcon("trending_up").setIconTone("good")
        .addBlocks(block("stat", row("3") { label = "unread emails"; icon = "mark_email_unread"; iconTone = "accent" }))
        .addBlocks(block("table",
            row { addAllCells(listOf("ACME", "112.40", "+1.2%")) },
            row { addAllCells(listOf("Globex", "48.05", "-0.6%")) },
            row { addAllCells(listOf("Initech", "9.87", "+0.1%")) }) { title = "Watchlist"; addAllColumns(listOf("Stock", "Price", "Change")) })
        .addBlocks(block("progress",
            row("6,240 of 10,000") { label = "Steps"; value = 6240.0; max = 10000.0; iconTone = "good" },
            row("72%") { label = "Battery"; value = 72.0; max = 100.0 }) { title = "Today" })
        .addBlocks(block("list", row("Rain gear by 5 PM") { label = "Alert"; icon = "warning"; iconTone = "alert"; iconDesc = "warning" },
            row("Missed call from Sam") { icon = "phone_missed"; iconTone = "bad"; iconDesc = "missed call" },
            row("Backup finished") { icon = "check_circle"; iconTone = "good"; iconDesc = "done" }) { title = "Status" })

    private fun shot(box: HomeBox.Builder, name: String, dark: Boolean) {
        HomeBoxes.resetForTest(app)
        if (dark) TestLooks.use(app, Themes.byId("night")) else TestLooks.reset(app)
        HomeBoxes.apply(app, BoxSet.newBuilder().setVersion(System.nanoTime()).addBoxes(
            box.setId("t").setKind("display").setState("ok").setUpdatedAtEpochS(System.currentTimeMillis() / 1000 - 120)).build())
        shadowOf(Looper.getMainLooper()).idle()
        val a = Robolectric.buildActivity(BoxExpandedActivity::class.java, BoxExpandedActivity.intent(app, "t")).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val v = a.window.decorView
        val w = app.resources.displayMetrics.widthPixels
        val h = app.resources.displayMetrics.heightPixels
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val f = File(out!!, "$name-${if (dark) "dark" else "light"}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(f.length() > 0)
        a.finish()
    }

    @Test
    fun `write sample pictures`() {
        assumeTrue("set RIST_TILE_SAMPLES to a folder to write the pictures", !out.isNullOrBlank())
        File(out!!).mkdirs()
        for (dark in listOf(false, true)) {
            shot(weather(), "weather", dark)
            shot(shopping(), "shopping-and-calendar", dark)
            shot(numbers(), "numbers-and-status", dark)
        }
    }
}
