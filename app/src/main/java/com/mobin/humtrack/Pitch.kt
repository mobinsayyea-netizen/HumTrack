package com.mobin.humtrack

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.*

/** AI pitch tracking (CREPE tiny neural network, running on-device with ONNX Runtime). */
object Pitch {
    private const val SR16 = 16000
    private const val N = 1024
    private const val HOP = 256
    private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null

    val ready get() = session != null

    fun init(ctx: Context) {
        if (session != null) return
        try {
            val bytes = ctx.assets.open("crepe_tiny.onnx").use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            env = e
            session = e.createSession(bytes, OrtSession.SessionOptions())
        } catch (t: Throwable) {
            session = null
        }
    }

    private fun resample(x: FloatArray, srcRate: Int): FloatArray {
        val ratio = srcRate.toDouble() / SR16
        val m = (x.size / ratio).toInt()
        return FloatArray(m) { i ->
            val p = i * ratio
            val k = p.toInt()
            val f = (p - k).toFloat()
            val a = x[k]
            val b = if (k + 1 < x.size) x[k + 1] else a
            a + (b - a) * f
        }
    }

    private fun median(v: DoubleArray): Double { val s = v.sortedArray(); return s[s.size / 2] }

    /** x is mono audio at Analyze.SR2. Returns null if the AI model is unavailable or nothing was hummed. */
    fun melody(x: FloatArray): Melody? {
        val s = session ?: return null
        val e = env ?: return null
        return try { run(s, e, x) } catch (t: Throwable) { null }
    }

    private fun run(s: OrtSession, e: OrtEnvironment, x: FloatArray): Melody? {
        val y = resample(x, Analyze.SR2)
        val frames = (y.size - N) / HOP + 1
        if (frames < 3) return null

        val midi = DoubleArray(frames) { Double.NaN }
        val conf = FloatArray(frames)
        val rmsOk = BooleanArray(frames)
        var f0 = 0
        val batch = 96
        while (f0 < frames) {
            val nb = min(batch, frames - f0)
            val buf = FloatArray(nb * N)
            for (b in 0 until nb) {
                val st = (f0 + b) * HOP
                var mean = 0.0
                for (i in 0 until N) mean += y[st + i]
                mean /= N
                var v = 0.0
                for (i in 0 until N) { val d = y[st + i] - mean; v += d * d }
                val sd = sqrt(v / N)
                rmsOk[f0 + b] = sd > 0.010
                val inv = (1.0 / max(sd, 1e-8)).toFloat()
                for (i in 0 until N) buf[b * N + i] = ((y[st + i] - mean).toFloat()) * inv
            }
            val t = OnnxTensor.createTensor(e, FloatBuffer.wrap(buf), longArrayOf(nb.toLong(), N.toLong()))
            t.use {
                s.run(mapOf("audio" to it)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = res.get(0).value as Array<FloatArray>
                    for (b in 0 until nb) {
                        val p = out[b]
                        var c = 0
                        for (i in 1 until p.size) if (p[i] > p[c]) c = i
                        val lo = max(0, c - 4)
                        val hi = min(p.size - 1, c + 4)
                        var num = 0.0
                        var den = 0.0
                        for (i in lo..hi) { num += p[i] * (i * 20.0 + 1997.3794084376191); den += p[i] }
                        val cents = num / den
                        val hz = 10.0 * 2.0.pow(cents / 1200.0)
                        val m = 69 + 12 * ln(hz / 440.0) / ln(2.0)
                        conf[f0 + b] = p[c]
                        if (p[c] >= 0.45f && rmsOk[f0 + b] && m in 36.0..88.0) midi[f0 + b] = m
                    }
                }
            }
            f0 += nb
        }

        // median smoothing over voiced neighbours (removes octave jumps / glitches)
        val sm = DoubleArray(frames) { Double.NaN }
        for (i in 0 until frames) {
            if (midi[i].isNaN()) continue
            val w = (max(0, i - 3)..min(frames - 1, i + 3)).map { midi[it] }.filter { !it.isNaN() }.toDoubleArray()
            if (w.size >= 3) sm[i] = median(w)
        }
        val voiced = sm.filter { !it.isNaN() }
        if (voiced.size < 5) return null

        // auto-tune: remove the singer's constant sharp/flat offset so notes snap to the nearest scale step
        var sx = 0.0
        var sy = 0.0
        for (m in voiced) { val a = 2 * PI * (m - floor(m)); sx += cos(a); sy += sin(a) }
        var off = atan2(sy, sx) / (2 * PI)           // in -0.5..0.5, relative to a whole note
        off = if (voiced.size >= 10) off else 0.0
        val q = IntArray(frames) { i -> if (sm[i].isNaN()) 0 else Math.round(sm[i] - off).toInt() }

        val ft = HOP.toDouble() / SR16
        data class R(var midi: Int, var a: Int, var b: Int)
        val runs = ArrayList<R>()
        var i = 0
        while (i < frames) {
            if (q[i] == 0) { i++; continue }
            var j = i
            while (j < frames && q[j] == q[i]) j++
            runs += R(q[i], i, j)
            i = j
        }
        val keep = runs.filter { (it.b - it.a) >= 5 }.toMutableList()
        if (keep.isEmpty()) return null
        // join tiny gaps and neighbouring same-pitch notes
        val merged = ArrayList<R>()
        for (r in keep) {
            val last = merged.lastOrNull()
            if (last != null && r.a - last.b <= 7) {
                if (last.midi == r.midi) last.b = r.b
                else { last.b = r.a; merged += R(r.midi, r.a, r.b) }
            } else merged += R(r.midi, r.a, r.b)
        }
        val notes = merged.map { Note(it.midi, it.a * ft, (it.b - it.a) * ft) }
        val t0 = notes[0].start
        val sh = notes.map { Note(it.midi, it.start - t0, it.dur) }
        return Melody(sh, sh.last().start + sh.last().dur + .4)
    }
}
