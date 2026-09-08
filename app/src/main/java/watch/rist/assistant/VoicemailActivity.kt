package watch.rist.assistant

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoicemailActivity : AppCompatActivity() {

    private val pixelTf: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.pixel) }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = Themes.byId(Config.themeId(this))

        val scroll = ScrollView(this).apply { setBackgroundColor(t.ground) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18f), px(18f), px(18f), px(24f))
        }
        scroll.addView(col)
        setContentView(scroll)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        col.addView(TextView(this).apply {
            text = "[ < BACK ]"
            setTextColor(t.ink); textSize = 13f; typeface = pixelTf
            setPadding(0, 0, 0, px(14f))
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })

        render(col, t)
    }

    override fun onStop() {
        super.onStop()
        VoicemailPlayback.stop()
    }

    private fun render(col: LinearLayout, t: RistTheme) {
        val messages = Voicemails.all(this)
        val muted = androidx.core.graphics.ColorUtils.blendARGB(t.ink, t.ground, 0.45f)

        col.addView(TextView(this).apply {
            text = "VOICEMAIL"
            setTextColor(t.ink); textSize = 18f; typeface = pixelTf; isAllCaps = true
            setPadding(0, 0, 0, px(10f))
        })

        col.addView(mailboxCard(t, muted))

        if (messages.isEmpty()) {
            col.addView(TextView(this).apply {
                text = if (Config.authToken(this@VoicemailActivity).isBlank())
                    "This device isn't set up yet, so Rist can't take messages of its own."
                else "Rist hasn't taken any messages of its own yet."
                setTextColor(muted); textSize = 12f
                setPadding(0, px(4f), 0, 0)
            })
            return
        }

        messages.forEach { vm -> col.addView(card(vm, t, muted)) }
    }

    private fun mailboxCard(t: RistTheme, muted: Int): View {
        val waiting = CarrierVoicemail.waiting(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(androidx.core.graphics.ColorUtils.blendARGB(t.ground, t.ink, 0.06f))
                cornerRadius = px(10f).toFloat()
            }
            setPadding(px(12f), px(12f), px(12f), px(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(14f) }
        }
        box.addView(TextView(this).apply {
            text = (if (waiting) "●  " else "") + "Your phone's mailbox"
            setTextColor(t.ink); textSize = 14f
            typeface = if (waiting) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        })
        box.addView(TextView(this).apply {
            text = if (waiting) "Your carrier says a message is waiting."
            else "Messages left when you miss a call on your own number are kept by your carrier."
            setTextColor(muted); textSize = 11f
            setPadding(0, px(4f), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = "CALL MAILBOX"
            setTextColor(t.accent); textSize = 12f; typeface = pixelTf
            setPadding(0, px(10f), 0, 0)
            gravity = Gravity.START
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (!CarrierVoicemail.call(this@VoicemailActivity)) {
                    toast("Couldn't reach your mailbox")
                }
            }
        })
        return box
    }

    private fun card(vm: Voicemails.Voicemail, t: RistTheme, muted: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(androidx.core.graphics.ColorUtils.blendARGB(t.ground, t.ink, 0.06f))
                cornerRadius = px(10f).toFloat()
            }
            setPadding(px(12f), px(12f), px(12f), px(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(10f) }
        }

        val unheard = !vm.heard && !vm.heardPending
        box.addView(TextView(this).apply {
            text = (if (unheard) "●  " else "") + vm.who
            setTextColor(t.ink); textSize = 14f
            typeface = if (unheard) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        })

        val when_ = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(vm.receivedAtMs))
        val how = when {
            vm.carrierHeld -> "in your carrier mailbox"
            vm.screened -> "screened by Rist"
            else -> "you missed this one"
        }
        box.addView(TextView(this).apply {
            text = "$when_  ·  $how" + if (vm.durationS > 0) "  ·  ${vm.durationS}s" else ""
            setTextColor(muted); textSize = 10.5f
            setPadding(0, px(3f), 0, px(6f))
        })

        if (vm.carrierHeld && vm.transcript.isNotBlank()) {
            box.addView(TextView(this).apply {
                text = "FROM YOUR CARRIER"
                setTextColor(muted); textSize = 9.5f; typeface = pixelTf
                letterSpacing = 0.08f
                setPadding(0, 0, 0, px(3f))
            })
        }

        when {
            vm.transcript.isNotBlank() -> box.addView(TextView(this).apply {
                text = vm.transcript
                setTextColor(t.ink); textSize = 12.5f
            })
            vm.carrierHeld -> Unit
            vm.audioDropped -> Unit
            else -> box.addView(TextView(this).apply {
                text = "READ IT"
                setTextColor(t.accent); textSize = 12f; typeface = pixelTf
                setPadding(0, px(8f), 0, 0)
                gravity = Gravity.START
                isClickable = true; isFocusable = true
                setOnClickListener { readIt(vm, this, t, muted) }
            })
        }

        when {
            vm.carrierHeld -> Unit
            vm.audioDropped -> box.addView(TextView(this).apply {
                text = "Recording no longer available" +
                    if (vm.transcript.isBlank()) " — nothing to read" else ""
                setTextColor(muted); textSize = 11f; typeface = pixelTf
                setPadding(0, px(10f), 0, 0)
            })
            else -> box.addView(TextView(this).apply {
                text = "PLAY"
                setTextColor(t.accent); textSize = 12f; typeface = pixelTf
                setPadding(0, px(10f), 0, 0)
                gravity = Gravity.START
                isClickable = true; isFocusable = true
                setOnClickListener { play(vm, this) }
            })
        }
        return box
    }

    /** The only permitted caller of [VoicemailTranscript.fetch]: transcription happens only on the user's tap. */
    private fun readIt(vm: Voicemails.Voicemail, label: TextView, t: RistTheme, muted: Int) {
        label.isClickable = false
        label.text = "READING…"
        Thread {
            val result = VoicemailTranscript.fetch(applicationContext, vm.id)
            runOnUiThread {
                when (result) {
                    is VoicemailTranscript.Result.Ready -> {
                        // Persist before painting, so a re-render does not transcribe twice.
                        Voicemails.setTranscript(applicationContext, vm.id, result.text)
                        label.typeface = Typeface.DEFAULT
                        label.textSize = 12.5f
                        label.setTextColor(t.ink)
                        label.setPadding(0, 0, 0, 0)
                        label.text = result.text
                    }
                    is VoicemailTranscript.Result.NotReady -> {
                        label.text = "READ IT"
                        label.isClickable = true
                        toast("Rist is still reading that one. Try again in a moment.")
                    }
                    is VoicemailTranscript.Result.Expired -> {
                        label.typeface = Typeface.DEFAULT
                        label.textSize = 11f
                        label.setTextColor(muted)
                        label.text = "Nothing to read — that recording is gone."
                    }
                    is VoicemailTranscript.Result.NotFound -> {
                        label.text = "READ IT"
                        label.isClickable = true
                        toast("Rist couldn't find that message")
                    }
                    is VoicemailTranscript.Result.Failed -> {
                        label.text = "READ IT"
                        label.isClickable = true
                        toast(result.why)
                    }
                }
            }
        }.start()
    }

    private fun play(vm: Voicemails.Voicemail, label: TextView) {
        label.text = "LOADING…"
        Thread {
            val result = VoicemailAudio.fetch(applicationContext, vm.id)
            runOnUiThread {
                when (result) {
                    is VoicemailAudio.Result.Ready -> {
                        label.text = "PLAYING…"
                        Voicemails.markHeardLocally(applicationContext, vm.id)
                        VoicemailPlayback.play(applicationContext, result.file) {
                            label.text = "PLAY AGAIN"
                        }
                    }
                    is VoicemailAudio.Result.Expired -> {
                        label.text = "EXPIRED"
                        toast("That recording expired.")
                    }
                    is VoicemailAudio.Result.NotFound -> {
                        label.text = "PLAY"
                        toast("Rist couldn't find that recording")
                    }
                    is VoicemailAudio.Result.Failed -> {
                        label.text = "PLAY"
                        toast(result.why)
                    }
                }
            }
        }.start()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

}
