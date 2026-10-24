package com.mobin.humtrack

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

const val SR = 44100

class Note(val midi: Int, val start: Double, val dur: Double)
class Melody(val notes: List<Note>, val dur: Double)
/** type: 0 = low (bass), 1 = mid, 2 = high */
class Ev(val t: Double, val type: Int, val vel: Float)
class Cycle(val events: List<Ev>, val len: Double)
class Part(val mel: Melody, val inst: String, val rhythm: FloatArray?)

object Taals {
    val names = listOf(
        "None", "Keherwa (8 beats)", "Dadra (6 beats)", "Teentaal (16 beats)",
        "Rupak (7 beats)", "Create my own rhythm (mouth)"
    )
    // D = bass+high, B = bass, b = soft bass, N = mid, T = high
    private val pats = listOf("", "DBNTNbDN", "DTNDbN", "DNNDDNNDDTTNNDDD", "TTNDNDN")

    fun cycle(idx: Int, bpm: Int): Cycle? {
        if (idx !in 1..4) return null
        val beat = 60.0 / bpm
        val ev = ArrayList<Ev>()
        pats[idx].forEachIndexed { i, c ->
            val t = i * beat
            when (c) {
                'D' -> { ev += Ev(t, 0, 1f); ev += Ev(t, 2, .6f) }
                'B' -> ev += Ev(t, 0, 1f)
                'b' -> ev += Ev(t, 0, .5f)
                'N' -> ev += Ev(t, 1, .8f)
                'T' -> ev += Ev(t, 2, .8f)
            }
        }
        return Cycle(ev, pats[idx].length * beat)
    }
}

object Synth {
    val melodyNames = listOf("Harmonium", "Flute", "Sitar", "Violin", "Shehnai", "Piano", "Guitar")
    val rhythmNames = listOf("Tabla", "Dholak", "Drum Kit", "Dhol", "Mridang", "Conga")

    private class MV(val h: FloatArray, val a: Double, val r: Double, val decay: Double, val vib: Double)

    private val mv = mapOf(
        "Harmonium" to MV(floatArrayOf(1f, .5f, .6f, .3f, .25f, .15f, .1f), .03, .1, 0.0, 0.0),
        "Flute" to MV(floatArrayOf(1f, .15f, .05f), .07, .12, 0.0, .006),
        "Sitar" to MV(floatArrayOf(1f, .8f, .7f, .6f, .5f, .45f, .35f, .25f), .004, .1, .5, 0.0),
        "Violin" to MV(floatArrayOf(1f, .7f, .6f, .5f, .4f, .3f, .2f, .1f), .09, .15, 0.0, .008),
        "Shehnai" to MV(floatArrayOf(1f, .2f, .9f, .3f, .8f, .2f, .5f, .15f), .04, .1, 0.0, .007),
        "Piano" to MV(floatArrayOf(1f, .5f, .25f, .12f, .06f), .005, .1, .7, 0.0),
        "Guitar" to MV(floatArrayOf(1f, .6f, .4f, .25f, .12f, .08f), .004, .1, .5, 0.0)
    )

    fun note(inst: String, midi: Int, dur: Double): FloatArray {
        val v = mv[inst] ?: mv["Harmonium"]!!
        val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val n = ((dur + v.r) * SR).toInt()
        val out = FloatArray(n)
        val norm = 1.0 / v.h.sum()
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var env = min(1.0, t / v.a)
            if (v.decay > 0) env *= exp(-t / v.decay)
            if (t > dur) env *= max(0.0, 1.0 - (t - dur) / v.r)
            val vibr = if (v.vib > 0) 1.0 + v.vib * sin(2 * PI * 5.3 * t) * min(1.0, t / .3) else 1.0
            ph += 2 * PI * f * vibr / SR
            var s = 0.0
            for (k in v.h.indices) s += v.h[k] * sin((k + 1) * ph)
            out[i] = (s * norm * env * .5).toFloat()
        }
        return out
    }

    /** tone start Hz, tone end Hz, tone decay, tone amp, noise decay, noise amp, bright noise */
    private class PV(val f0: Double, val f1: Double, val td: Double, val ta: Double,
                     val nd: Double, val na: Double, val br: Boolean)

    private val pv = mapOf(
        "Tabla" to listOf(PV(150.0, 85.0, .35, .9, .02, .1, false), PV(330.0, 320.0, .10, .8, .01, .1, false), PV(580.0, 560.0, .28, .7, .01, .1, true)),
        "Dholak" to listOf(PV(130.0, 70.0, .30, 1.0, .03, .15, false), PV(250.0, 240.0, .12, .7, .05, .3, false), PV(430.0, 400.0, .10, .6, .06, .35, true)),
        "Drum Kit" to listOf(PV(150.0, 50.0, .30, 1.0, .01, .1, false), PV(190.0, 180.0, .12, .6, .16, .8, true), PV(100.0, 100.0, .01, 0.0, .05, .6, true)),
        "Dhol" to listOf(PV(100.0, 55.0, .40, 1.0, .04, .2, false), PV(200.0, 190.0, .10, .6, .08, .5, true), PV(340.0, 320.0, .08, .6, .08, .5, true)),
        "Mridang" to listOf(PV(140.0, 80.0, .40, 1.0, .01, .05, false), PV(290.0, 285.0, .15, .8, .02, .1, false), PV(520.0, 510.0, .22, .7, .02, .1, true)),
        "Conga" to listOf(PV(210.0, 150.0, .30, .9, .02, .1, false), PV(330.0, 300.0, .14, .8, .03, .2, false), PV(480.0, 470.0, .12, .7, .05, .3, true))
    )
    private val cache = ConcurrentHashMap<String, FloatArray>()

    private fun hit(inst: String, type: Int): FloatArray = cache.getOrPut("$inst$type") {
        val p = (pv[inst] ?: pv["Tabla"]!!)[type]
        val n = (max(p.td, p.nd) * 4 * SR).toInt()
        val o = FloatArray(n)
        var ph = 0.0
        var prev = 0.0
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val f = p.f1 + (p.f0 - p.f1) * exp(-t * 30)
            ph += 2 * PI * f / SR
            val r = rnd.nextDouble() * 2 - 1
            val x = if (p.br) r - prev else r
            prev = r
            o[i] = (p.ta * sin(ph) * exp(-t / p.td) + p.na * x * exp(-t / p.nd) * .5).toFloat()
        }
        o
    }

    /** One loop of the rhythm as audio (tails wrap around so the loop is seamless). */
    fun cycle(c: Cycle, inst: String): FloatArray {
        val n = max(1, (c.len * SR).toInt())
        val out = FloatArray(n)
        for (e in c.events) {
            val h = hit(inst, e.type)
            val s0 = (e.t * SR).toInt()
            for (i in h.indices) out[(s0 + i) % n] += h[i] * e.vel
        }
        return out
    }

    fun repeat(c: FloatArray, times: Int): FloatArray {
        val out = FloatArray(c.size * times)
        for (i in out.indices) out[i] = c[i % c.size]
        return out
    }

    fun mix(parts: List<Part>): FloatArray {
        var total = 0
        parts.forEach { total += (it.mel.dur * SR).toInt() }
        val out = FloatArray(total + SR)
        var off = 0
        for (p in parts) {
            val len = (p.mel.dur * SR).toInt()
            p.rhythm?.let { r -> for (i in 0 until len) out[off + i] += r[i % r.size] * .8f }
            for (n in p.mel.notes) {
                val w = note(p.inst, n.midi, n.dur)
                val s0 = off + (n.start * SR).toInt()
                for (i in w.indices) if (s0 + i < out.size) out[s0 + i] += w[i]
            }
            off += len
        }
        var pk = 0f
        for (v in out) pk = max(pk, abs(v))
        if (pk > .95f) { val g = .95f / pk; for (i in out.indices) out[i] *= g }
        return out
    }

    fun wav(f: FloatArray): ByteArray {
        val n = f.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(SR).putInt(SR * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        for (v in f) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return bb.array()
    }
}

object Analyze {
    const val SR2 = SR / 2

    fun decimate(x: ShortArray): FloatArray {
        val m = x.size / 2
        return FloatArray(m) { (x[2 * it] + x[2 * it + 1]) / 65536f }
    }

    /** Hummed melody -> list of notes (autocorrelation pitch tracking). */
    fun melody(x: FloatArray): Melody? {
        val sr = SR2
        val n = 1024
        val hop = 512
        val minL = sr / 900
        val maxL = sr / 80
        val w = n - maxL
        val frames = (x.size - n) / hop + 1
        if (frames < 3) return null
        val cum = DoubleArray(x.size + 1)
        for (i in x.indices) cum[i + 1] = cum[i] + x[i] * x[i]
        val mi = IntArray(frames)
        for (fi in 0 until frames) {
            val s0 = fi * hop
            val rms = sqrt((cum[s0 + n] - cum[s0]) / n)
            if (rms < 0.015) continue
            val e0 = cum[s0 + w] - cum[s0]
            val r = DoubleArray(maxL + 2)
            var best = 0.0
            for (l in minL..maxL) {
                var s = 0.0
                for (i in 0 until w) s += x[s0 + i] * x[s0 + i + l]
                val e = cum[s0 + l + w] - cum[s0 + l]
                r[l] = s / sqrt(e0 * e + 1e-9)
                if (r[l] > best) best = r[l]
            }
            if (best < 0.6) continue
            for (l in minL + 1 until maxL) {
                if (r[l] >= .9 * best && r[l] >= r[l - 1] && r[l] >= r[l + 1]) {
                    mi[fi] = Math.round(69 + 12 * ln(sr.toDouble() / l / 440.0) / ln(2.0)).toInt()
                    break
                }
            }
        }
        val sm = IntArray(frames) { i ->
            if (mi[i] == 0) 0 else {
                val ws = (max(0, i - 2)..min(frames - 1, i + 2)).map { mi[it] }.filter { it > 0 }.sorted()
                if (ws.size >= 3) ws[ws.size / 2] else mi[i]
            }
        }
        val ft = hop.toDouble() / sr
        val notes = ArrayList<Note>()
        var i = 0
        while (i < frames) {
            if (sm[i] == 0) { i++; continue }
            var j = i
            while (j < frames && sm[j] == sm[i]) j++
            if ((j - i) * ft >= .08) notes += Note(sm[i], i * ft, (j - i) * ft)
            i = j
        }
        if (notes.isEmpty()) return null
        val t0 = notes[0].start
        val sh = notes.map { Note(it.midi, it.start - t0, it.dur) }
        return Melody(sh, sh.last().start + sh.last().dur + .4)
    }

    /** Mouth sounds -> rhythm events. Deep sounds = low, medium = mid, sharp/airy = high. */
    fun rhythm(x: FloatArray): Cycle? {
        val sr = SR2
        val fl = sr / 100
        val nf = x.size / fl
        if (nf < 10) return null
        val e = DoubleArray(nf) { f ->
            var s = 0.0
            for (i in 0 until fl) s += x[f * fl + i] * x[f * fl + i]
            sqrt(s / fl)
        }
        val mx = e.max()
        if (mx < 0.03) return null
        val floor = max(0.02, mx * 0.15)
        val ons = ArrayList<Int>()
        var last = -100
        for (f in 1 until nf) {
            val prev = (max(0, f - 4) until f).map { e[it] }.average()
            if (e[f] > floor && e[f] > 1.8 * prev + 0.005 && f - last >= 8) { ons += f; last = f }
        }
        if (ons.size < 2) return null
        val evs = ons.map { f ->
            val a = f * fl
            val b = min(x.size - 1, a + 5 * fl)
            var zc = 0
            for (i in a + 1..b) if ((x[i] >= 0f) != (x[i - 1] >= 0f)) zc++
            val hz = zc * sr / (2.0 * (b - a))
            val type = if (hz < 600) 0 else if (hz < 1800) 1 else 2
            val vel = (e[min(nf - 1, f + 2)] / mx).toFloat().coerceIn(.3f, 1f)
            Ev((f - ons[0]) * fl.toDouble() / sr, type, vel)
        }
        val iois = ons.zipWithNext { p, q -> q - p }.sorted()
        val med = iois[iois.size / 2] * fl.toDouble() / sr
        return Cycle(evs, max(.5, evs.last().t + med))
    }
}

class Player {
    @Volatile private var halt = false
    @Volatile private var track: AudioTrack? = null
    private var th: Thread? = null

    fun play(pcm: FloatArray, loop: Boolean, done: () -> Unit) {
        stop()
        if (pcm.isEmpty()) return
        halt = false
        val s = ShortArray(pcm.size) { (pcm[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        th = Thread {
            val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = max(minBuf, 16384)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = t
            t.play()
            var p = 0
            while (!halt) {
                if (p >= s.size) { if (loop) p = 0 else break }
                val k = min(4096, s.size - p)
                t.write(s, p, k)
                p += k
            }
            var waited = 0L
            val wait = bufBytes / 2 * 1000L / SR + 150
            while (!halt && waited < wait) { Thread.sleep(50); waited += 50 }
            val natural = !halt
            try { t.stop() } catch (_: Exception) {}
            t.release()
            track = null
            if (natural) done()
        }.also { it.start() }
    }

    fun stop() {
        halt = true
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        th?.join(500)
        th = null
    }
}

class Recorder {
    @Volatile var live = false
        private set
    private var rec: AudioRecord? = null
    private var th: Thread? = null
    private var buf = ShortArray(SR * 10)
    private var n = 0

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (live) return true
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192) * 2
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        rec = r
        n = 0
        live = true
        r.startRecording()
        th = Thread {
            val tmp = ShortArray(2048)
            while (live) {
                val k = r.read(tmp, 0, tmp.size)
                if (k > 0) {
                    if (n + k > buf.size) buf = buf.copyOf(buf.size * 2)
                    System.arraycopy(tmp, 0, buf, n, k)
                    n += k
                }
            }
        }.also { it.start() }
        return true
    }

    fun stop(): ShortArray {
        live = false
        th?.join()
        try { rec?.stop() } catch (_: Exception) {}
        rec?.release()
        rec = null
        return buf.copyOf(n)
    }
}
