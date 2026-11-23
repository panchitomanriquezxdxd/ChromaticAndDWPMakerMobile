package com.bichoelproxd.chromaticscalegenerator

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Preparacion de las muestras antes de usarlas: recorte automatico de silencios (inicio y final)
 * y normalizacion de volumen. Es Kotlin puro (sin APIs de Android) para poder probarlo en un PC.
 *
 * Las muestras se guardan como Float en [-1, 1] (4 bytes por muestra: el heap de Android es chico).
 */
class Audio(val rate: Int, val channels: Array<FloatArray>) {
    val frames: Int get() = channels[0].size
}

object AudioPrep {

    /** Umbral de silencio, en dB por debajo del pico de la muestra. */
    const val SILENCE_DB = -45.0

    /** Ventana de analisis, en segundos. */
    private const val WINDOW_S = 0.005

    /** Nivel RMS objetivo maximo (dBFS) al igualar el volumen. */
    private const val TARGET_RMS_DB = -18.0

    /** Pico maximo permitido tras normalizar (evita recortar). */
    private const val PEAK_LIMIT = 0.98

    // ------------------------------------------------------------------ WAV

    /** Lee un WAV PCM (8/16/24/32 bit) o float (32/64 bit), mono o estereo (se usan hasta 2 canales). */
    fun readWav(file: File): Audio = parseWav(file.readBytes())

    fun parseWav(bytes: ByteArray): Audio = parseWavWithMarkers(bytes).audio

    /** Un WAV junto con los markers (chunk `cue `) que trae, como posiciones en muestras. */
    class WavData(val audio: Audio, val markers: List<Int>)

    fun parseWavWithMarkers(bytes: ByteArray): WavData {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(o: Int) = String(bytes, o, 4, Charsets.US_ASCII)
        if (bytes.size < 44 || tag(0) != "RIFF" || tag(8) != "WAVE") throw IllegalArgumentException("Not a valid WAV file")
        var pos = 12
        var fmtTag = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var dataOff = -1
        var dataLen = 0
        val cues = ArrayList<Int>()
        while (pos + 8 <= bytes.size) {
            val id = tag(pos)
            var size = bb.getInt(pos + 4).toLong() and 0xffffffffL
            val body = pos + 8
            if (body + size > bytes.size) size = (bytes.size - body).toLong()
            if (id == "fmt " && size >= 16) {
                var t = bb.getShort(body).toInt() and 0xffff
                if (t == 0xfffe && size >= 26) t = bb.getShort(body + 24).toInt() and 0xffff
                fmtTag = t
                channels = bb.getShort(body + 2).toInt() and 0xffff
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt() and 0xffff
            } else if (id == "data") {
                dataOff = body
                dataLen = size.toInt()
            } else if (id == "cue " && size >= 4) {
                val count = bb.getInt(body)
                var i = 0
                while (i < count && 4 + (i + 1) * 24 <= size) {
                    val p = body + 4 + i * 24
                    val position = bb.getInt(p + 4)
                    val sampleOffset = bb.getInt(p + 20)
                    cues.add(if (sampleOffset != 0) sampleOffset else position)
                    i++
                }
            }
            pos = (body + size + (size and 1L)).toInt()
        }
        if (fmtTag < 0 || dataOff < 0) throw IllegalArgumentException("WAV without fmt/data chunks")
        val ok = (fmtTag == 1 && bits in intArrayOf(8, 16, 24, 32)) || (fmtTag == 3 && bits in intArrayOf(32, 64))
        if (!ok) throw IllegalArgumentException("Unsupported WAV format ($bits bit, type $fmtTag)")
        if (channels < 1) throw IllegalArgumentException("WAV without channels")
        val bps = bits / 8
        val frames = dataLen / (channels * bps)
        if (frames < 1) throw IllegalArgumentException("WAV without audio")
        val used = min(2, channels)
        val out = Array(used) { FloatArray(frames) }
        for (i in 0 until frames) {
            for (c in 0 until used) {
                val o = dataOff + (i * channels + c) * bps
                out[c][i] = when {
                    fmtTag == 3 && bits == 32 -> bb.getFloat(o)
                    fmtTag == 3 -> bb.getDouble(o).toFloat()
                    bits == 16 -> bb.getShort(o) / 32768f
                    bits == 24 -> {
                        var v = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or ((bytes[o + 2].toInt() and 0xff) shl 16)
                        if (v >= 0x800000) v -= 0x1000000
                        v / 8388608f
                    }
                    bits == 32 -> bb.getInt(o) / 2147483648f
                    else -> ((bytes[o].toInt() and 0xff) - 128) / 128f
                }
            }
        }
        val audio = Audio(rate, out)
        val markers = cues.filter { it in 0 until frames }.distinct().sorted()
        return WavData(audio, markers)
    }

    /** Escribe un WAV PCM de 24 bit (Praat lo lee sin perdida). */
    fun writeWav(file: File, audio: Audio) {
        val ch = audio.channels.size
        val n = audio.frames
        val dataLen = n * ch * 3
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
        bb.putInt(audio.rate).putInt(audio.rate * ch * 3).putShort((ch * 3).toShort()).putShort(24)
        bb.put("data".toByteArray()).putInt(dataLen)
        for (i in 0 until n) for (c in 0 until ch) {
            var v = Math.rint(audio.channels[c][i].toDouble().coerceIn(-1.0, 1.0) * 8388607.0).toInt()
            if (v < 0) v += 0x1000000
            bb.put((v and 0xff).toByte()).put(((v shr 8) and 0xff).toByte()).put(((v shr 16) and 0xff).toByte())
        }
        file.writeBytes(bb.array())
    }

    // --------------------------------------------------------- recorte

    /**
     * Quita el silencio del inicio y del final. Se conserva todo lo que hay entre el primer y el
     * ultimo instante en que la senal supera [SILENCE_DB] respecto al pico, mas un pequeno margen
     * (una ventana de 5 ms) para no cortar el ataque ni la cola. Los silencios del interior no se tocan.
     * Devuelve el mismo [Audio] si no hay nada que recortar.
     */
    fun trim(audio: Audio, thresholdDb: Double = SILENCE_DB): Audio {
        val n = audio.frames
        val w = max(1, (audio.rate * WINDOW_S).toInt())
        val nw = n / w
        if (nw < 1) return audio
        val env = DoubleArray(nw)
        var peak = 0.0
        for (k in 0 until nw) {
            var m = 0.0
            for (i in k * w until (k + 1) * w) for (ch in audio.channels) m = max(m, abs(ch[i]).toDouble())
            env[k] = m
            if (m > peak) peak = m
        }
        if (peak <= 0.0) return audio
        val thr = peak * 10.0.pow(thresholdDb / 20.0)
        var first = 0
        while (first < nw && env[first] < thr) first++
        var last = nw - 1
        while (last > first && env[last] < thr) last--
        val start = max(0, (first - 1) * w)
        val end = if (last == nw - 1) n else min(n, (last + 2) * w)
        if (start == 0 && end == n) return audio
        return Audio(audio.rate, Array(audio.channels.size) { audio.channels[it].copyOfRange(start, end) })
    }

    // ----------------------------------------------------- normalizacion

    fun peakOf(a: Audio): Double {
        var p = 0.0
        for (ch in a.channels) for (v in ch) { val x = abs(v).toDouble(); if (x > p) p = x }
        return p
    }

    fun rmsOf(a: Audio): Double {
        var s = 0.0
        var cnt = 0L
        for (ch in a.channels) for (v in ch) { s += v.toDouble() * v; cnt++ }
        return if (cnt == 0L) 0.0 else sqrt(s / cnt)
    }

    /**
     * Ganancia de cada muestra para igualar el volumen: todas quedan con el mismo nivel RMS (la
     * percepcion de volumen sigue al RMS, no al pico). El nivel comun es el mas alto que permite que
     * ninguna muestra supere [PEAK_LIMIT] (tope: [TARGET_RMS_DB]); asi ninguna se recorta ni queda mas
     * baja que otra. Las muestras en silencio total quedan con ganancia 1.
     */
    fun gainsFor(rms: DoubleArray, peak: DoubleArray): DoubleArray {
        var target = 10.0.pow(TARGET_RMS_DB / 20.0)
        for (i in rms.indices) if (rms[i] > 0.0 && peak[i] > 0.0) target = min(target, rms[i] * (PEAK_LIMIT / peak[i]))
        return DoubleArray(rms.size) { if (rms[it] <= 0.0) 1.0 else target / rms[it] }
    }

    // ------------------------------------------------ muestras bajo demanda

    /**
     * Un conjunto de muestras que se cargan de a una ([load] devuelve una copia nueva cada vez).
     * Asi nunca hay mas de una muestra en memoria, que es lo que aguanta el heap de Android.
     */
    class Source(val count: Int, val load: (Int) -> Audio)

    /** Muestras ya con el recorte y la normalizacion pedidos, tambien de a una. */
    class Prepared(private val source: Source, private val doTrim: Boolean, private val gains: DoubleArray?) {
        val count: Int get() = source.count

        fun get(i: Int): Audio {
            var a = source.load(i)
            if (doTrim) a = trim(a)
            val g = gains?.get(i)
            if (g != null && g != 1.0) {
                for (ch in a.channels) for (j in ch.indices) ch[j] = (ch[j] * g).toFloat()
            }
            return a
        }
    }

    /** Primera pasada (solo mide el RMS y el pico de cada muestra recortada); la segunda es [Prepared.get]. */
    fun prepare(source: Source, doTrim: Boolean, doNormalize: Boolean): Prepared {
        var gains: DoubleArray? = null
        if (doNormalize) {
            val rms = DoubleArray(source.count)
            val peak = DoubleArray(source.count)
            for (i in 0 until source.count) {
                var a = source.load(i)
                if (doTrim) a = trim(a)
                rms[i] = rmsOf(a)
                peak[i] = peakOf(a)
            }
            gains = gainsFor(rms, peak)
        }
        return Prepared(source, doTrim, gains)
    }

    /** Muestras 1.wav, 2.wav, ... de una carpeta, en orden numerico. */
    fun listNumbered(dir: File): List<File> {
        val files = ArrayList<File>()
        var i = 1
        while (true) {
            val f = File(dir, "$i.wav")
            if (!f.isFile) break
            files.add(f)
            i++
        }
        return files
    }

    fun fileSource(files: List<File>) = Source(files.size) { readWav(files[it]) }

    /**
     * Prepara en el sitio los 1.wav, 2.wav, ... de [dir] (recorte y/o normalizacion) antes de que
     * Praat los lea, de a un archivo. Devuelve cuantos archivos hay.
     */
    fun prepareFolder(dir: File, doTrim: Boolean, doNormalize: Boolean): Int {
        val files = listNumbered(dir)
        if (files.isEmpty() || (!doTrim && !doNormalize)) return files.size
        val prepared = prepare(fileSource(files), doTrim, doNormalize)
        for (i in files.indices) writeWav(files[i], prepared.get(i))
        return files.size
    }
}
