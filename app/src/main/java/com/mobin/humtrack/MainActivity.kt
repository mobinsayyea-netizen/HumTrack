package com.mobin.humtrack

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private val parts = ArrayList<Part>()
    private var draft: Melody? = null
    private var custom: Cycle? = null

    // 0 idle, 1 recording melody, 2 recording rhythm, 3 playing track, 4 previewing rhythm, 5 busy
    private var mode = 0
    private val player = Player()
    private val recorder = Recorder()
    private val handler by lazy { Handler(mainLooper) }
    private lateinit var tts: TextToSpeech

    private lateinit var spM: Spinner
    private lateinit var spR: Spinner
    private lateinit var spI: Spinner
    private lateinit var bpm: EditText
    private lateinit var speak: CheckBox
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var btnRec: Button
    private lateinit var btnRhy: Button
    private lateinit var btnPrev: Button
    private lateinit var btnPlay: Button
    private lateinit var btnAdd: Button
    private lateinit var btnUndo: Button
    private lateinit var btnSave: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.US }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })
        col.addView(TextView(this).apply {
            text = "Hum Track"; textSize = 24f; isAccessibilityHeading = true
        })

        spM = spinner(col, "Melody instrument", Synth.melodyNames)
        spR = spinner(col, "Rhythm", Taals.names)
        spI = spinner(col, "Rhythm instrument", Synth.rhythmNames)

        val l = label(col, "Tempo (beats per minute, for ready-made rhythms)")
        bpm = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText("110"); id = View.generateViewId()
        }
        l.labelFor = bpm.id
        col.addView(bpm)

        btnRec = button(col, "") { if (mode == 1) finishRec() else if (mode == 0) startRec(1, curCycle()) }
        btnRhy = button(col, "") { if (mode == 2) finishRec() else if (mode == 0) startRec(2, null) }
        btnPrev = button(col, "") { togglePreview() }
        btnPlay = button(col, "") { togglePlay() }
        btnAdd = button(col, "Add New Part Here") { addPart() }
        btnUndo = button(col, "Remove Last Part") { undoPart() }
        btnSave = button(col, "Save Karaoke Track (WAV)") { save() }

        speak = CheckBox(this).apply { text = "Speak messages aloud"; isChecked = true }
        col.addView(speak)
        info = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        col.addView(info)
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)); textSize = 16f }
        col.addView(status)

        spR.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
        refresh()
        say("Welcome. Choose an instrument and a rhythm, then press Start Recording and hum.")
    }

    // ---------- UI helpers ----------
    private fun label(p: LinearLayout, t: String) = TextView(this).apply {
        text = t; textSize = 16f; setPadding(0, dp(12), 0, dp(4))
    }.also { p.addView(it) }

    private fun spinner(p: LinearLayout, t: String, items: List<String>): Spinner {
        val l = label(p, t)
        val s = Spinner(this)
        s.id = View.generateViewId()
        l.labelFor = s.id
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        p.addView(s)
        return s
    }

    private fun button(p: LinearLayout, t: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 18f; isAllCaps = false; minimumHeight = dp(56)
        setOnClickListener { f() }
    }.also { p.addView(it) }

    private fun say(t: String) {
        status.text = t
        if (speak.isChecked) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m")
    }

    private fun refresh() {
        val pos = spR.selectedItemPosition
        btnRec.text = if (mode == 1) "Stop Recording (melody)" else "Start Recording (melody)"
        btnRhy.text = if (mode == 2) "Stop Recording Rhythm" else "Record Rhythm with Mouth"
        btnPrev.text = if (mode == 4) "Stop Preview" else "Preview Rhythm"
        btnPlay.text = if (mode == 3) "Stop Playing" else "Play"
        btnRhy.visibility = if (pos == 5) View.VISIBLE else View.GONE
        btnPrev.visibility = if (pos != 0) View.VISIBLE else View.GONE
        btnRec.isEnabled = mode == 0 || mode == 1
        btnRhy.isEnabled = mode == 0 || mode == 2
        btnPrev.isEnabled = mode == 0 || mode == 4
        btnPlay.isEnabled = (mode == 0 && (parts.isNotEmpty() || draft != null)) || mode == 3
        btnAdd.isEnabled = mode == 0 && draft != null
        btnUndo.isEnabled = mode == 0 && parts.isNotEmpty()
        btnSave.isEnabled = mode == 0 && (parts.isNotEmpty() || draft != null)
        val total = parts.sumOf { it.mel.dur }
        val d = draft?.let { "${it.notes.size} notes, %.1f s".format(it.dur) } ?: "none"
        info.text = "Saved parts: ${parts.size} (%.1f s). Draft: $d. Own rhythm: %s.".format(
            total, if (custom != null) "recorded" else "not recorded"
        )
    }

    // ---------- state helpers ----------
    private fun bpmVal() = (bpm.text.toString().toIntOrNull() ?: 110).coerceIn(40, 220)
    private fun minst() = Synth.melodyNames[spM.selectedItemPosition]
    private fun rinst() = Synth.rhythmNames[spI.selectedItemPosition]

    private fun curCycle(): FloatArray? {
        val p = spR.selectedItemPosition
        val c = if (p in 1..4) Taals.cycle(p, bpmVal()) else if (p == 5) custom else null
        return c?.let { Synth.cycle(it, rinst()) }
    }

    private fun allParts(): List<Part> {
        val l = ArrayList(parts)
        draft?.let { l.add(Part(it, minst(), curCycle())) }
        return l
    }

    private fun hasMic(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        say("Please allow the microphone, then press the button again.")
        return false
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED)
            say("Microphone allowed. Press the button again.")
    }

    // ---------- recording ----------
    private fun startRec(newMode: Int, backing: FloatArray?) {
        if (!hasMic()) return
        mode = newMode
        refresh()
        if (newMode == 1 && spR.selectedItemPosition == 5 && backing == null)
            say("Recording without rhythm. Use Record Rhythm with Mouth first if you want a backing.")
        else say(if (backing != null) "Recording. Use headphones. Rhythm will play." else "Recording.")
        handler.postDelayed({
            if (mode == newMode) {
                if (!recorder.start()) {
                    mode = 0; refresh(); say("Could not open the microphone."); return@postDelayed
                }
                backing?.let { player.play(it, true) {} }
            }
        }, 1500)
    }

    private fun finishRec() {
        val rhythm = mode == 2
        player.stop()
        if (!recorder.live) { mode = 0; refresh(); say("Cancelled."); return }
        val pcm = recorder.stop()
        mode = 5; refresh(); say("Analysing. Please wait.")
        Thread {
            val x = Analyze.decimate(pcm)
            if (rhythm) {
                val c = Analyze.rhythm(x)
                runOnUiThread {
                    mode = 0
                    if (c != null) {
                        custom = c
                        refresh()
                        say("Rhythm captured with ${c.events.size} sounds. Press Preview Rhythm to hear it.")
                    } else {
                        refresh()
                        say("No clear rhythm found. Try sharper sounds like dum, na, ta, with small gaps.")
                    }
                }
            } else {
                val m = Analyze.melody(x)
                runOnUiThread {
                    mode = 0
                    if (m != null) {
                        draft = m
                        refresh()
                        say("Captured ${m.notes.size} notes. Press Play to listen, or record again to replace it.")
                    } else {
                        refresh()
                        say("No clear humming found. Try again, a little louder.")
                    }
                }
            }
        }.start()
    }

    // ---------- playback ----------
    private fun backToIdle(msg: String) { mode = 0; refresh(); say(msg) }

    private fun togglePreview() {
        if (mode == 4) { player.stop(); backToIdle("Stopped."); return }
        if (mode != 0) return
        val c = curCycle()
        if (c == null) { say("Choose a rhythm first, or record your own."); return }
        mode = 4; refresh(); say("Previewing rhythm.")
        player.play(Synth.repeat(c, 4), false) {
            runOnUiThread { if (mode == 4) backToIdle("Finished.") }
        }
    }

    private fun togglePlay() {
        if (mode == 3) { player.stop(); backToIdle("Stopped."); return }
        if (mode != 0) return
        val list = allParts()
        if (list.isEmpty()) { say("Nothing to play yet. Record a melody first."); return }
        mode = 3; refresh(); say("Playing.")
        Thread {
            val mix = Synth.mix(list)
            runOnUiThread {
                if (mode == 3) player.play(mix, false) {
                    runOnUiThread { if (mode == 3) backToIdle("Finished playing.") }
                }
            }
        }.start()
    }

    // ---------- parts ----------
    private fun addPart() {
        val d = draft ?: return
        parts.add(Part(d, minst(), curCycle()))
        draft = null
        refresh()
        say("Part ${parts.size} saved. Now record part ${parts.size + 1}.")
    }

    private fun undoPart() {
        if (parts.isEmpty()) return
        draft = parts.removeAt(parts.size - 1).mel
        refresh()
        say("Last part is back as the draft. Press Play to hear it, or record again to replace it.")
    }

    private fun save() {
        val list = allParts()
        if (list.isEmpty()) return
        mode = 5; refresh(); say("Saving. Please wait.")
        Thread {
            val bytes = Synth.wav(Synth.mix(list))
            val ok = try {
                val cv = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "karaoke_${System.currentTimeMillis()}.wav")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/HumTrack")
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                true
            } catch (e: Exception) { false }
            runOnUiThread {
                backToIdle(if (ok) "Saved in the Music folder, inside HumTrack." else "Saving failed.")
            }
        }.start()
    }

    override fun onDestroy() {
        player.stop()
        if (recorder.live) recorder.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
