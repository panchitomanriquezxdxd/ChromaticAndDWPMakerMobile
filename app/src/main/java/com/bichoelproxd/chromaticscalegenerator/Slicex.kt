package com.bichoelproxd.chromaticscalegenerator

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Logica de Slicex de "DWP Converter.html": un audio con markers (los de Slicex, chunk `cue ` del
 * WAV) se parte en regiones; cada marker abre una region que llega hasta el siguiente. Tambien
 * incluye la deteccion automatica de sonidos, la division en partes iguales y el ajuste de los
 * markers a un cruce por cero. Kotlin puro (sin APIs de Android).
 */
object Slicex {

    /** Sensibilidad de la deteccion: umbral en dB bajo el pico y silencio minimo entre sonidos (ms). */
    enum class Sensitivity(val thrDb: Double, val gapMs: Double) {
        HIGH(-60.0, 60.0),   // sonidos suaves
        NORMAL(-50.0, 120.0),
        LOW(-40.0, 250.0),   // solo golpes fuertes
    }

    /**
     * Un sonido empieza cuando la senal supera el umbral (dB bajo el pico) tras >= gapMs de
     * silencio. El marker va preMs antes. El primero queda en 0.
     */
    fun detectMarkers(a: Audio, thrDb: Double = -50.0, gapMs: Double = 120.0, preMs: Double = 10.0): List<Int> {
        val rate = a.rate
        val w = max(1, Math.round(rate * 0.005).toInt())
        val nw = a.frames / w
        val env = DoubleArray(nw)
        val step = max(1, w / 8)
        val chs = min(2, a.channels.size)
        var pk = 0.0
        for (k in 0 until nw) {
            var m = 0.0
            var i = k * w
            while (i < (k + 1) * w) {
                for (c in 0 until chs) { val v = abs(a.channels[c][i]); if (v > m) m = v }
                i += step
            }
            env[k] = m
            if (m > pk) pk = m
        }
        if (pk <= 0.0) return listOf(0)
        val thr = pk * 10.0.pow(thrDb / 20.0)
        val minRun = max(1, ceil(gapMs / 5).toInt())
        val out = arrayListOf(0)
        var quiet = minRun                                  // arranca "en silencio" para que el primer sonido cuente
        for (k in 0 until nw) {
            if (env[k] >= thr) {
                if (quiet >= minRun) {
                    val at = max(0, k * w - Math.round(rate * preMs / 1000).toInt())
                    if (at > out.last()) out.add(at)
                }
                quiet = 0
            } else quiet++
        }
        return out
    }

    /** Detecta y deja cada marker en el mejor punto para cortar (sin clic). */
    fun detectAndSnap(a: Audio, s: Sensitivity): List<Int> =
        detectMarkers(a, s.thrDb, s.gapMs).map { if (it > 0) snapMarker(a, it, 3.0) else 0 }

    /** n partes iguales. */
    fun equalMarkers(frames: Int, n: Int): List<Int> {
        val count = max(1, min(DwpBuilder.MAX_REGIONS, n))
        val out = ArrayList<Int>()
        for (i in 0 until count) {
            val v = Math.round(i.toDouble() * frames / count).toInt()
            if (out.isEmpty() || v > out.last()) out.add(v)
        }
        return out
    }

    /** Mueve un punto al minimo de amplitud mas cercano (+-ms) para que el corte no haga clic. */
    fun snapMarker(a: Audio, at: Int, ms: Double = 4.0): Int {
        val r = Math.round(a.rate * ms / 1000).toInt()
        var best = max(0, min(a.frames - 1, at))
        var bv = Double.POSITIVE_INFINITY
        for (i in max(0, at - r)..min(a.frames - 1, at + r)) {
            var v = 0.0
            for (c in 0 until min(2, a.channels.size)) v += abs(a.channels[c][i])
            if (v < bv) { bv = v; best = i }
        }
        return best
    }

    /** Markers validos: dentro del audio, sin repetir y en orden. */
    fun clean(markers: Collection<Int>, frames: Int): List<Int> =
        markers.filter { it in 0 until frames }.distinct().sorted()

    /** Mueve todos los markers [deltaFrames] muestras (negativo = antes). */
    fun shift(markers: List<Int>, deltaFrames: Int, frames: Int): List<Int> =
        clean(markers.map { max(0, it + deltaFrames) }, frames)

    /**
     * Agrega un marker en [frame], o quita el que este a menos de [tolFrames] (como tocar la forma
     * de onda en el HTML). Con un audio sin markers, el primero siempre abre en 0.
     */
    fun toggle(a: Audio, markers: List<Int>, frame: Int, tolFrames: Int): List<Int> {
        var near = -1
        var bd = Int.MAX_VALUE
        for (m in markers) { val d = abs(m - frame); if (d < bd) { bd = d; near = m } }
        if (near >= 0 && bd <= tolFrames) return markers.filter { it != near }
        val at = snapMarker(a, frame, 4.0)
        return if (markers.isNotEmpty()) clean(markers + at, a.frames) else if (at > 0) listOf(0, at) else listOf(0)
    }

    /** Inicios de las regiones: los markers, mas el audio anterior al primero si [includeIntro]. */
    fun bounds(markers: List<Int>, includeIntro: Boolean): List<Int> =
        if (markers.isEmpty()) listOf(0)
        else if (includeIntro && markers[0] > 0) listOf(0) + markers
        else markers

    /** Frames de audio anterior al primer marker (se ignora salvo que se incluya). */
    fun introFrames(markers: List<Int>): Int = if (markers.isNotEmpty() && markers[0] > 0) markers[0] else 0

    /** Parte el audio en una muestra por region. */
    fun slices(a: Audio, markers: List<Int>, includeIntro: Boolean): List<Audio> {
        val b = bounds(markers, includeIntro)
        if (b.size > DwpBuilder.MAX_REGIONS) {
            throw IllegalArgumentException("${b.size} markers; the maximum is ${DwpBuilder.MAX_REGIONS} (one region per key)")
        }
        return b.mapIndexed { k, start ->
            val end = if (k + 1 < b.size) b[k + 1] else a.frames
            Audio(a.rate, Array(a.channels.size) { c -> a.channels[c].copyOfRange(start, end) })
        }
    }
}
