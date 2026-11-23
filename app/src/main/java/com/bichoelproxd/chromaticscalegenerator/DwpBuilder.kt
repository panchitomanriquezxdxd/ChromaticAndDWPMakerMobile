package com.bichoelproxd.chromaticscalegenerator

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.truncate

/**
 * Port a Kotlin del nucleo de "DWP Converter.html" (WAV -> programa de DirectWave .dwp).
 * Misma estructura de archivo, mismas notas, mismo loop automatico con crossfade y mismas
 * calidades. Es Kotlin puro (sin APIs de Android).
 *
 * Diferencia con el HTML: alli las regiones salen de los markers de un WAV; aqui cada muestra
 * (1.wav, 2.wav, ...) es una region, igual que un WAV con un marker al inicio de cada muestra.
 */
class DwpTemplate(
    val prefix: ByteArray,
    val hdr: ByteArray,
    val rest: ByteArray,
    val region: List<Pair<Int, ByteArray>>,
    val d503: ByteArray,
    val d500: ByteArray,
)

object DwpBuilder {

    const val MAX_REGIONS = 128
    const val BASE_NOTE = 60

    private const val GUARD = 256
    private const val TAIL = 1024
    private const val FADE = 32
    private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Calidad: 1 = frecuencia original, 2 = mitad, 4 = cuarta parte. [loopBits32] = loops a 32 bit. */
    class Options(
        val loop: Boolean = true,
        val rateDiv: Int = 1,
        val loopBits32: Boolean = false,
        /** true = las muestras ya traen su nivel (normalizadas a RMS comun): no se vuelve a normalizar al pico. */
        val keepLevels: Boolean = false,
    )

    class Region(val note: Int, val name: String, val lo: Int, val hi: Int)

    class Result(val bytes: Int, val regions: List<Region>, val name: String, val loops: Int)

    fun noteName(n: Int) = NOTES[n % 12] + (n / 12)

    /** Nombre del programa: solo letras, numeros, espacio, guion y guion bajo. */
    fun cleanName(input: String?): String {
        var s = Normalizer.normalize(input ?: "", Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")
        s = s.replace(Regex("\\.[A-Za-z0-9]{1,4}$"), "")
        s = s.replace(Regex("[^A-Za-z0-9 _-]+"), "_").replace(Regex("\\s+"), " ").trim()
        if (s.length > 40) s = s.substring(0, 40).trim()
        return if (s.isEmpty()) "programa" else s
    }

    /** Notas de cada region: la primera en C5 (60), una por semitono; con mas de 68 baja la primera. */
    fun plan(count: Int): List<Region> {
        if (count < 1) throw IllegalArgumentException("No samples")
        if (count > MAX_REGIONS) throw IllegalArgumentException("$count samples; the maximum is $MAX_REGIONS (one region per key)")
        val first = if (count <= 128 - BASE_NOTE) BASE_NOTE else 128 - count
        return List(count) { k ->
            val note = first + k
            Region(note, noteName(note), if (k == 0) 0 else note, if (k == count - 1) 127 else note)
        }
    }

    // ------------------------------------------------------------ bytes

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun ascii(s: String) = ByteArray(s.length) { (s[it].code and 0x7f).toByte() }

    private fun chunkHeader(tag: Int, size: Int): ByteArray =
        le(12).putInt(tag).putInt(size).putInt(0).array()

    private fun chunk(tag: Int, data: ByteArray): ByteArray = chunkHeader(tag, data.size) + data

    // ----------------------------------------------------- loop automatico

    private val XF = doubleArrayOf(
        1.0, 0.9965, 0.986, 0.9685, 0.944, 0.912, 0.872, 0.824, 0.7685, 0.705, 0.6335, 0.556, 0.474,
        0.3895, 0.305, 0.224, 0.151, 0.0875, 0.0405, 0.010, 0.0,
    )

    private fun xfw(t: Double): Double {
        val p = t * 20
        val i = min(19, floor(p).toInt())
        val f = p - i
        return XF[i] + (XF[i + 1] - XF[i]) * f
    }

    private fun nacorr(x: DoubleArray, i0: Int, j0: Int, len: Int): Double {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until len) {
            val p = x[i0 + i]
            val q = x[j0 + i]
            ab += p * q; aa += p * p; bb += q * q
        }
        return ab / (sqrt(aa * bb) + 1e-9)
    }

    private fun rmsRange(x: DoubleArray, a: Int, b: Int): Double {
        var s = 0.0
        for (i in a until b) s += x[i] * x[i]
        return sqrt(s / max(1, b - a))
    }

    class Loop(val start: Int, val end: Int, val cf: Int)

    /** Busca un loop; devuelve null si el sonido no sirve para loop (corto o que decae). */
    fun findLoop(x: DoubleArray, rate: Double): Loop? {
        val T = x.size
        if (T < 0.3 * rate) return null
        val mid = rmsRange(x, floor(T * 0.4).toInt(), floor(T * 0.6).toInt())
        val tail = rmsRange(x, floor(T * 0.75).toInt(), floor(T * 0.95).toInt())
        if (mid < 1 || tail < 0.5 * mid) return null
        val dec = 4
        val wl = min(16384, floor(T * 0.5).toInt())
        val w0 = floor(T * 0.3).toInt()
        val dn = wl / dec
        val d = DoubleArray(dn)
        for (i in 0 until dn) {
            var s = 0.0
            for (k in 0 until dec) s += x[w0 + i * dec + k]
            d[i] = s
        }
        val minLag = max(2, ceil(rate / 2000 / dec).toInt())
        val maxLag = min(dn / 2, floor(rate / 30 / dec).toInt())
        var best = -1.0
        var bestLag = 0
        val nac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            nac[lag] = nacorr(d, 0, lag, dn - lag)
            if (nac[lag] > best) { best = nac[lag]; bestLag = lag }
        }
        var P = 0
        if (best >= 0.5) {
            for (lag in minLag + 1 until maxLag) {
                if (nac[lag] > nac[lag - 1] && nac[lag] >= nac[lag + 1] && nac[lag] >= 0.9 * best) { bestLag = lag; break }
            }
            var c = bestLag * dec
            var bc = -2.0
            val wr = min(8192, if (wl - 3 * dec * bestLag > 0) 8192 else 2048)
            for (L in bestLag * dec - dec * 2..bestLag * dec + dec * 2) {
                val s = nacorr(x, w0, w0 + L, min(wr, wl - L))
                if (s > bc) { bc = s; c = L }
            }
            P = c
        }
        val W = min(8192, floor(T * 0.2).toInt())
        val L0: Int
        if (P > 0) {
            var k = 1
            while (k * 2 * P <= 0.8 * T) k *= 2
            L0 = Math.round(k.toDouble() * P).toInt()
        } else L0 = Math.round(T * 0.5).toInt()
        val span = if (P > 0) ceil(P / 2.0).toInt() else floor(T * 0.05).toInt()
        val step = if (P > 0) 1 else 4
        var L = L0
        var bs = -2.0
        var c = max(256, L0 - span)
        val cEnd = min(floor(T * 0.8).toInt(), L0 + span)
        while (c <= cEnd) {
            if (T - W - c >= 0) {
                val s = nacorr(x, T - W, T - W - c, W)
                if (s > bs) { bs = s; L = c }
            }
            c += step
        }
        val S = T - L
        val cf = min(L, S - FADE)
        if (cf < 512) return null
        return Loop(S, T, cf)
    }

    /** Mezcla el final del loop con el material previo al inicio: y[i] = a(t)*x[i] + b(t)*x[i-L]. */
    fun crossfade(x: DoubleArray, lp: Loop): DoubleArray {
        val L = lp.end - lp.start
        val y = x.copyOf()
        val s0 = lp.end - lp.cf
        for (i in s0 until lp.end) {
            val t = (i - s0).toDouble() / (lp.cf - 1)
            y[i] = xfw(t) * x[i] + xfw(1 - t) * x[i - L]
        }
        return y
    }

    // ------------------------------------------- reduccion de la frecuencia

    fun decimate(x: DoubleArray, f: Int): DoubleArray {
        val half = 8 * f
        val M = 2 * half + 1
        val fc = 0.45 / f
        val h = DoubleArray(M)
        var sum = 0.0
        for (k in 0 until M) {
            val m = k - half
            val s = if (m == 0) 2 * fc else sin(2 * Math.PI * fc * m) / (Math.PI * m)
            val w = 0.42 - 0.5 * cos(2 * Math.PI * k / (M - 1)) + 0.08 * cos(4 * Math.PI * k / (M - 1))
            h[k] = s * w
            sum += h[k]
        }
        for (k in 0 until M) h[k] /= sum
        val n = x.size
        val m = ceil(n.toDouble() / f).toInt()
        val y = DoubleArray(m)
        for (j in 0 until m) {
            var acc = 0.0
            val c0 = j * f - half
            val k0 = max(0, -c0)
            val k1 = min(M, n - c0)
            for (k in k0 until k1) acc += h[k] * x[c0 + k]
            y[j] = acc
        }
        return y
    }

    // ------------------------------------------------------- construccion

    /**
     * Arma el .dwp con una region por muestra y lo escribe en [out]. Las muestras ya deben venir
     * recortadas y/o normalizadas (ver [AudioPrep.prepare]).
     */
    fun build(
        samples: List<Audio>,
        programName: String,
        tpl: DwpTemplate,
        out: OutputStream,
        opts: Options = Options(),
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Result {
        val regs = plan(samples.size)
        val n = regs.size
        val hdr = tpl.hdr.copyOf()
        ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).putInt(26, n)
        val name = cleanName(programName)
        val progBytes = hdr + chunk(102, ascii(name)) + chunk(103, ascii("C:\\samples\\$name.dwp")) + tpl.rest
        var total = progBytes.size
        val regionParts = ArrayList<ByteArray>()
        var loops = 0

        for (k in 0 until n) {
            val r = regs[k]
            val audio = samples[k]
            val div = opts.rateDiv
            val rate = audio.rate.toDouble() / div
            val rname = name + "_" + r.name + "_127"
            val same = audio.channels.size < 2
            // Escala de 16 bit (igual que el HTML): la normalizacion final es relativa a ella.
            var vals = Array(if (same) 1 else 2) { c -> DoubleArray(audio.frames) { audio.channels[c][it] * 32768.0 } }
            if (div > 1) vals = Array(vals.size) { decimate(vals[it], div) }
            val T = vals[0].size
            var peak = 0.0
            for (c in vals.indices) for (i in 0 until T) { val a = abs(vals[c][i]); if (a > peak) peak = a }
            if (peak < 1) peak = 1.0
            val g = if (opts.keepLevels) 1.0 else 32767.0 / peak
            val f = min(FADE, T shr 2)
            fun ramp(i: Int, up: Boolean): Double =
                if (f == 1) (if (up) 0.0 else 1.0)
                else if (up) (if (i == f - 1) 1.0 else i * (1.0 / (f - 1)))
                else (if (i == f - 1) 0.0 else 1.0 + i * (-1.0 / (f - 1)))
            val xi = Array(vals.size) { c -> DoubleArray(T) { Math.rint(vals[c][it] * g) } }
            val lp = if (opts.loop) findLoop(xi[0], rate) else null
            for (c in xi.indices) {
                for (i in 0 until f) xi[c][i] *= ramp(i, true)
                if (lp == null) for (i in T - f until T) xi[c][i] *= ramp(i - (T - f), false)
                for (i in 0 until T) xi[c][i] = truncate(max(-32768.0, min(32767.0, xi[c][i])))
            }
            val frames: Int
            val pcm: ByteArray
            var bits = 16
            if (lp != null) {
                loops++
                bits = if (opts.loopBits32) 32 else 16
                frames = T
                val bps = bits / 8
                val blockLen = GUARD + T + GUARD
                pcm = ByteArray(blockLen * 2 * bps)
                val fb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                val ys = Array(xi.size) { crossfade(xi[it], lp) }
                var ymax = 0.0
                for (y in ys) for (i in 0 until T) { val a = abs(y[i]); if (a > ymax) ymax = a }
                val gain = if (ymax > 32767) 32767.0 / ymax else 1.0
                for (c in 0 until 2) {
                    if (c == 1 && same) { System.arraycopy(pcm, 0, pcm, blockLen * bps, blockLen * bps); break }
                    val y = ys[c]
                    val base = c * blockLen * bps
                    if (bits == 32) for (i in 0 until T) fb.putFloat(base + (GUARD + i) * 4, (y[i] * gain / 32768.0).toFloat())
                    else for (i in 0 until T) fb.putShort(base + (GUARD + i) * 2, max(-32768.0, min(32767.0, Math.rint(y[i] * gain))).toInt().toShort())
                }
            } else {
                frames = T + TAIL
                val blockLen = GUARD + T + TAIL + GUARD
                pcm = ByteArray(blockLen * 4)
                val pb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                for (c in 0 until 2) {
                    if (c == 1 && same) { System.arraycopy(pcm, 0, pcm, blockLen * 2, blockLen * 2); break }
                    for (i in 0 until T) pb.putShort(c * blockLen * 2 + (GUARD + i) * 2, xi[c][i].toInt().toShort())
                }
            }

            val rpath = "C:\\samples\\$name\\$rname.wav"
            val subs = ArrayList<ByteArray>()
            var rsize = 0
            for ((tag, stored) in tpl.region) {
                val d: ByteArray = when (tag) {
                    500 -> tpl.d500.copyOf().also { it[0] = r.note.toByte(); it[1] = r.lo.toByte(); it[2] = r.hi.toByte(); it[3] = 0; it[4] = 127 }
                    501 -> ascii(rname)
                    502 -> ascii(rpath)
                    503 -> {
                        val a = tpl.d503.copyOf()
                        val v = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
                        v.putInt(0, frames); v.putFloat(16, rate.toFloat())
                        if (lp != null) { v.putInt(20, 2); v.putInt(24, lp.start); v.putInt(28, lp.end) }
                        v.putInt(36, bits)
                        a
                    }
                    517 -> pcm
                    else -> stored
                }
                subs.add(chunkHeader(tag, d.size)); subs.add(d)
                rsize += 12 + d.size
            }
            regionParts.add(chunkHeader(3, rsize))
            regionParts.addAll(subs)
            total += 12 + rsize
            onProgress?.invoke(k + 1, n)
        }
        total += 12
        out.write(tpl.prefix)
        out.write(chunkHeader(1, total))
        out.write(progBytes)
        var written = tpl.prefix.size + 12 + progBytes.size
        for (p in regionParts) { out.write(p); written += p.size }
        out.write(chunkHeader(2, 0))
        written += 12
        return Result(written, regs, name, loops)
    }
}
