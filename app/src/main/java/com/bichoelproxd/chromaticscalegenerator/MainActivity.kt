package com.bichoelproxd.chromaticscalegenerator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bichoelproxd.chromaticscalegenerator.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

/** Pantalla principal: los mismos controles que la ventana de PC (app_ui.py). */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var treeUri: Uri? = null

    @Volatile
    private var busy = false

    /** Audio cargado con sus markers (logica de Slicex). Si hay uno, sus regiones reemplazan a 1.wav, 2.wav... */
    private class SlicexState(
        val fileName: String,
        val audio: Audio,
        val original: List<Int>,
        var markers: List<Int>,
        var includeIntro: Boolean = false,
    )

    private var slicex: SlicexState? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSlicex(uri)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Con edge-to-edge (Android 15+) el contenido no debe quedar debajo de las barras del sistema.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.startNoteChoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, NOTES
        )
        binding.startOctaveChoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, OCTAVES
        )

        // Idioma: se pregunta la primera vez que se abre la app y se puede cambiar con el boton.
        val currentLanguage = LanguageManager.savedTag(this) ?: LanguageManager.suggestedTag()
        binding.languageButton.text =
            getString(R.string.language) + ": " + LanguageManager.nativeNameOf(currentLanguage)
        binding.languageButton.setOnClickListener { showLanguageDialog(cancelable = true) }
        if (LanguageManager.savedTag(this) == null) {
            showLanguageDialog(cancelable = false)
        }

        binding.dwpQualityChoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.dwp_quality_options)
        )
        binding.dwpQualityChoice.setSelection(1) // Estandar

        binding.slicexSens.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.slicex_sensitivity_options)
        )
        binding.slicexSens.setSelection(1) // Normal
        binding.slicexPick.setOnClickListener { pickAudio.launch(arrayOf("audio/*", "application/octet-stream")) }
        binding.slicexRemove.setOnClickListener { unloadSlicex() }
        binding.slicexDetect.setOnClickListener { slicexDetect() }
        binding.slicexEqual.setOnClickListener { slicexEqual() }
        binding.slicexBack.setOnClickListener { slicexNudge(-1) }
        binding.slicexFwd.setOnClickListener { slicexNudge(1) }
        binding.slicexClear.setOnClickListener { setSlicexMarkers(emptyList()) }
        binding.slicexRestore.setOnClickListener { slicex?.let { setSlicexMarkers(it.original) } }
        binding.slicexIntro.setOnCheckedChangeListener { _, checked ->
            val st = slicex ?: return@setOnCheckedChangeListener
            if (checked == st.includeIntro) return@setOnCheckedChangeListener
            if (Slicex.bounds(st.markers, checked).size > DwpBuilder.MAX_REGIONS) {
                binding.slicexIntro.isChecked = false
                setStatus(getString(R.string.error_too_many_samples, Slicex.bounds(st.markers, checked).size, DwpBuilder.MAX_REGIONS))
            } else {
                st.includeIntro = checked
                refreshSlicex()
            }
        }
        binding.waveform.onTap = { frame, tol ->
            val st = slicex
            if (st != null && !busy) setSlicexMarkers(Slicex.toggle(st.audio, st.markers, frame, tol))
        }

        binding.dirPicker.setOnClickListener { pickFolder.launch(treeUri) }
        binding.chromGenerate.setOnClickListener { generate() }
        binding.dwpCreate.setOnClickListener { createDwp() }
        binding.pitchedCheck.setOnCheckedChangeListener { _, checked ->
            // Igual que en PC: las muestras con tono solo se guardan si "Pitched" esta activado.
            binding.samplesCheck.isEnabled = checked
        }

        // Recordar la carpeta elegida la ultima vez
        getPreferences(MODE_PRIVATE).getString(PREF_TREE_URI, null)?.let { saved ->
            val uri = Uri.parse(saved)
            val stillGranted = contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
            if (stillGranted) showFolder(uri)
        }

        // Inicializar Praat en segundo plano para que el primer "Generar" no tenga que esperar.
        praatThread("praat-init") {
            try {
                val version = PraatBridge.ensureInitialized(applicationContext)
                runOnUiThread { setStatus(getString(R.string.status_praat_ready, version)) }
            } catch (e: Throwable) {
                runOnUiThread { setStatus(getString(R.string.error_prefix, e.message ?: e.toString())) }
            }
        }
    }

    /** Lista de idiomas. AppCompat vuelve a crear la pantalla ya traducida al elegir uno. */
    private fun showLanguageDialog(cancelable: Boolean) {
        val current = LanguageManager.savedTag(this) ?: LanguageManager.suggestedTag()
        val names = LanguageManager.LANGUAGES.map { it.nativeName }.toTypedArray()
        val checked = LanguageManager.LANGUAGES.indexOfFirst { it.tag == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_language)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                dialog.dismiss()
                LanguageManager.choose(this, LanguageManager.LANGUAGES[which].tag)
            }
            .setCancelable(cancelable)
            .show()
    }

    private fun onFolderPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Si no se puede recordar el permiso, la carpeta funciona igual en esta sesion.
        }
        getPreferences(MODE_PRIVATE).edit().putString(PREF_TREE_URI, uri.toString()).apply()
        showFolder(uri)
    }

    private fun showFolder(uri: Uri) {
        treeUri = uri
        binding.dirPath.text = try {
            SampleFolder(this, uri).displayName
        } catch (_: Exception) {
            uri.toString()
        }
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.chromGenerate.isEnabled = !value
        binding.dwpCreate.isEnabled = !value
        binding.slicexPick.isEnabled = !value
        binding.slicexRemove.isEnabled = !value && slicex != null
        binding.dirPicker.isEnabled = !value
        binding.languageButton.isEnabled = !value
        binding.progressBar.visibility = if (value) View.VISIBLE else View.INVISIBLE
        if (value) {
            binding.progressBar.isIndeterminate = true
        }
    }

    // ------------------------------------------------------------------ Slicex

    private fun loadSlicex(uri: Uri) {
        if (busy) return
        setStatus(getString(R.string.status_slicex_loading))
        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: "audio.wav"
        praatThread("slicex-load") {
            try {
                val wav = readWavFile(uri)
                val st = SlicexState(name, wav.audio, wav.markers, wav.markers)
                runOnUiThread {
                    slicex = st
                    if (binding.dwpNameInput.text.toString() == DEFAULT_DWP_NAME) {
                        binding.dwpNameInput.setText(DwpBuilder.cleanName(name))
                    }
                    binding.slicexPanel.visibility = View.VISIBLE
                    binding.slicexRemove.isEnabled = true
                    binding.slicexIntro.isChecked = false
                    refreshSlicex()
                    setStatus(getString(R.string.status_ready))
                }
            } catch (e: Throwable) {
                val message = errorText(e)
                runOnUiThread { setStatus(getString(R.string.error_prefix, message)) }
            }
        }
    }

    /** Lee y decodifica el WAV; los bytes crudos quedan libres apenas termina (el heap de Android es chico). */
    private fun readWavFile(uri: Uri): AudioPrep.WavData {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException(getString(R.string.error_slicex_read))
        if (bytes.size > MAX_AUDIO_BYTES) throw IllegalStateException(getString(R.string.error_file_too_big))
        return try {
            AudioPrep.parseWavWithMarkers(bytes)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException(getString(R.string.error_slicex_read))
        }
    }

    private fun unloadSlicex() {
        slicex = null
        binding.slicexPanel.visibility = View.GONE
        binding.slicexRemove.isEnabled = false
        binding.slicexIntro.visibility = View.GONE
        binding.slicexInfo.text = getString(R.string.slicex_none)
        binding.slicexCaption.text = getString(R.string.slicex_example)
        binding.waveform.setContent(null, emptyList())
        setStatus(getString(R.string.status_ready))
    }

    /** Cambia los markers; si dejarian mas de 128 regiones, se ignora el cambio. */
    private fun setSlicexMarkers(markers: List<Int>) {
        val st = slicex ?: return
        val clean = Slicex.clean(markers, st.audio.frames)
        val count = Slicex.bounds(clean, st.includeIntro).size
        if (count > DwpBuilder.MAX_REGIONS) {
            setStatus(getString(R.string.error_too_many_samples, count, DwpBuilder.MAX_REGIONS))
            return
        }
        st.markers = clean
        refreshSlicex()
    }

    private fun refreshSlicex() {
        val st = slicex ?: return
        val a = st.audio
        val bounds = Slicex.bounds(st.markers, st.includeIntro)
        val plan = DwpBuilder.plan(bounds.size)
        binding.waveform.setContent(a, bounds.mapIndexed { k, f -> WaveformView.Mark(f, plan[k].name) })
        binding.slicexInfo.text = getString(
            R.string.slicex_info, st.fileName, String.format(Locale.US, "%.3f", a.frames / a.rate.toDouble()), a.rate, st.markers.size
        )
        binding.slicexCaption.text = if (st.markers.isEmpty()) {
            getString(R.string.slicex_no_markers)
        } else {
            getString(R.string.slicex_regions, plan.size, plan.first().name, plan.last().name)
        }
        val intro = Slicex.introFrames(st.markers)
        if (intro > 0) {
            binding.slicexIntro.visibility = View.VISIBLE
            binding.slicexIntro.text = getString(
                R.string.slicex_include_intro, String.format(Locale.US, "%.3f", intro / a.rate.toDouble())
            )
        } else {
            binding.slicexIntro.visibility = View.GONE
            if (st.includeIntro) { st.includeIntro = false; binding.slicexIntro.isChecked = false }
        }
        binding.slicexClear.isEnabled = st.markers.isNotEmpty()
        binding.slicexRestore.isEnabled = st.markers != st.original
    }

    private fun slicexDetect() {
        val st = slicex ?: return
        val s = when (binding.slicexSens.selectedItemPosition) {
            0 -> Slicex.Sensitivity.HIGH
            2 -> Slicex.Sensitivity.LOW
            else -> Slicex.Sensitivity.NORMAL
        }
        setSlicexMarkers(Slicex.detectAndSnap(st.audio, s))
    }

    private fun slicexEqual() {
        val st = slicex ?: return
        val n = binding.slicexParts.text.toString().trim().toIntOrNull()
        if (n == null || n < 2 || n > DwpBuilder.MAX_REGIONS) {
            setStatus(getString(R.string.error_bad_parts, DwpBuilder.MAX_REGIONS))
            return
        }
        setSlicexMarkers(Slicex.equalMarkers(st.audio.frames, n))
    }

    private fun slicexNudge(direction: Int) {
        val st = slicex ?: return
        val ms = binding.slicexMs.text.toString().trim().toDoubleOrNull()
        if (ms == null || ms < 1 || ms > 2000) {
            setStatus(getString(R.string.error_bad_ms))
            return
        }
        val delta = Math.round(st.audio.rate * ms / 1000).toInt() * direction
        setSlicexMarkers(Slicex.shift(st.markers, delta, st.audio.frames))
    }

    /** Regiones del audio cargado (de a una), o null si se usan los 1.wav, 2.wav... */
    private fun slicexSource(): AudioPrep.Source? {
        val st = slicex ?: return null
        return Slicex.source(st.audio, st.markers, st.includeIntro)
    }

    /** Deja las muestras en [sampleDir]: las regiones del audio cargado (una a una) o la copia de la carpeta. */
    private fun stageSamples(folder: SampleFolder, sampleDir: File, source: AudioPrep.Source?): Int {
        if (source == null) return folder.copySamplesTo(sampleDir)
        sampleDir.deleteRecursively()
        sampleDir.mkdirs()
        for (i in 0 until source.count) AudioPrep.writeWav(File(sampleDir, "${i + 1}.wav"), source.load(i))
        return source.count
    }

    /** Texto de un error; la falta de memoria se explica en lugar de mostrar el mensaje tecnico. */
    private fun errorText(e: Throwable): String =
        if (e is OutOfMemoryError) getString(R.string.error_out_of_memory) else (e.message ?: e.toString())

    private fun generate() {
        if (busy) return

        val uri = treeUri
        if (uri == null) {
            setStatus(getString(R.string.error_no_folder))
            return
        }
        val semitones = binding.rangeInput.text.toString().trim().toIntOrNull()
        if (semitones == null || semitones < 1) {
            setStatus(getString(R.string.error_bad_range))
            return
        }
        // Aceptar coma o punto como separador decimal
        val gap = binding.gapInput.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (gap == null || gap < 0.0 || gap.isNaN() || gap.isInfinite()) {
            setStatus(getString(R.string.error_bad_gap))
            return
        }

        val params = ChromaticGenerator.Params(
            startNote = binding.startNoteChoice.selectedItemPosition.coerceAtLeast(0),
            startOctaveIndex = binding.startOctaveChoice.selectedItemPosition.coerceAtLeast(0),
            semitones = semitones,
            gapSeconds = gap,
            pitched = binding.pitchedCheck.isChecked,
            dumpSamples = binding.samplesCheck.isChecked && binding.pitchedCheck.isChecked,
        )

        val trimmed = binding.trimCheck.isChecked
        val normalized = binding.normalizeCheck.isChecked
        val slices = try { slicexSource() } catch (e: IllegalArgumentException) { setStatus(getString(R.string.error_prefix, e.message ?: "")); return }

        setBusy(true)
        setStatus(getString(R.string.status_copying))

        praatThread("chromatic-generate") {
            val workRoot = File(cacheDir, "work")
            try {
                val folder = SampleFolder(applicationContext, uri)
                if (!folder.canAccess()) {
                    throw IllegalStateException(getString(R.string.error_folder_access))
                }

                val sampleDir = File(workRoot, "samples")
                val outputDir = File(workRoot, "output")
                outputDir.deleteRecursively()
                outputDir.mkdirs()

                if (stageSamples(folder, sampleDir, slices) == 0) {
                    throw IllegalStateException(getString(R.string.error_no_samples))
                }

                if (trimmed || normalized) {
                    runOnUiThread { setStatus(getString(R.string.status_preparing)) }
                    AudioPrep.prepareFolder(sampleDir, trimmed, normalized)
                }

                ChromaticGenerator.generate(applicationContext, params, sampleDir, outputDir) { done, total ->
                    runOnUiThread {
                        if (total > 0) {
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.max = total
                            binding.progressBar.progress = done
                        }
                        setStatus(getString(R.string.status_working, done, total))
                    }
                }

                runOnUiThread { setStatus(getString(R.string.status_saving)) }
                folder.copyResultsFrom(outputDir)

                val doneText = if (params.dumpSamples) R.string.status_done_pitched else R.string.status_done
                runOnUiThread {
                    setStatus(getString(doneText))
                    setBusy(false)
                }
            } catch (e: Throwable) {
                val message = errorText(e)
                runOnUiThread {
                    setStatus(getString(R.string.error_prefix, message))
                    setBusy(false)
                }
            } finally {
                workRoot.deleteRecursively()
            }
        }
    }

    /**
     * Crea un programa de DirectWave (.dwp) con las muestras 1.wav, 2.wav, ... (misma logica que
     * "DWP Converter.html"): una region por muestra, empezando en C5. No usa Praat.
     */
    private fun createDwp() {
        if (busy) return
        val uri = treeUri
        if (uri == null) {
            setStatus(getString(R.string.error_no_folder))
            return
        }
        val trimmed = binding.trimCheck.isChecked
        val normalized = binding.normalizeCheck.isChecked
        val programName = binding.dwpNameInput.text.toString()
        val loop = binding.dwpLoopCheck.isChecked
        val quality = binding.dwpQualityChoice.selectedItemPosition
        val options = DwpBuilder.Options(
            loop = loop,
            rateDiv = when (quality) { 2 -> 2; 3 -> 4; else -> 1 },
            loopBits32 = quality == 0,
            keepLevels = normalized,
        )
        val slices = try { slicexSource() } catch (e: IllegalArgumentException) { setStatus(getString(R.string.error_prefix, e.message ?: "")); return }

        setBusy(true)
        setStatus(getString(R.string.status_copying))

        // El armado del DWP es Kotlin puro, pero se hace en un hilo con pila grande por la misma
        // razon que Praat (arreglos grandes y bucles de correlacion).
        praatThread("dwp-create") {
            val workRoot = File(cacheDir, "work")
            try {
                val folder = SampleFolder(applicationContext, uri)
                if (!folder.canAccess()) {
                    throw IllegalStateException(getString(R.string.error_folder_access))
                }
                val sampleDir = File(workRoot, "samples")
                val outputDir = File(workRoot, "output")
                outputDir.deleteRecursively()
                outputDir.mkdirs()

                val source: AudioPrep.Source = if (slices != null) {
                    slices
                } else {
                    if (folder.copySamplesTo(sampleDir) == 0) {
                        throw IllegalStateException(getString(R.string.error_no_samples))
                    }
                    val files = AudioPrep.listNumbered(sampleDir)
                    if (files.isEmpty()) {
                        throw IllegalStateException(getString(R.string.error_no_samples))
                    }
                    AudioPrep.fileSource(files)
                }
                if (source.count > DwpBuilder.MAX_REGIONS) {
                    throw IllegalStateException(
                        getString(R.string.error_too_many_samples, source.count, DwpBuilder.MAX_REGIONS)
                    )
                }

                // Se mide el volumen de todas las muestras y despues se arma cada region de a una:
                // nunca hay mas de una muestra en memoria.
                runOnUiThread { setStatus(getString(R.string.status_preparing)) }
                val prepared = AudioPrep.prepare(source, trimmed, normalized)

                val template = DwpTemplateLoader.load(applicationContext)
                val target = File(outputDir, DwpBuilder.cleanName(programName) + ".dwp")
                val result = target.outputStream().buffered().use { out ->
                    DwpBuilder.build(prepared.count, prepared::get, programName, template, out, options, cacheDir) { done, total ->
                        runOnUiThread {
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.max = total
                            binding.progressBar.progress = done
                            setStatus(getString(R.string.status_dwp_working, done, total))
                        }
                    }
                }

                runOnUiThread { setStatus(getString(R.string.status_saving)) }
                folder.copyDwpFrom(target)

                runOnUiThread {
                    setStatus(
                        getString(R.string.status_dwp_done, target.name, result.regions.size, result.loops)
                    )
                    setBusy(false)
                }
            } catch (e: Throwable) {
                val message = errorText(e)
                runOnUiThread {
                    setStatus(getString(R.string.error_prefix, message))
                    setBusy(false)
                }
            } finally {
                workRoot.deleteRecursively()
            }
        }
    }

    /**
     * Praat usa recursion profunda al interpretar scripts; el hilo por defecto de Android tiene
     * poca pila, asi que Praat se ejecuta siempre en hilos con 16 MB de pila.
     */
    private fun praatThread(name: String, block: () -> Unit) {
        Thread(null, { block() }, name, PRAAT_STACK_BYTES).start()
    }

    companion object {
        private const val PRAAT_STACK_BYTES = 16L * 1024 * 1024
        private const val PREF_TREE_URI = "tree_uri"
        private const val MAX_AUDIO_BYTES = 60 * 1024 * 1024
        private const val DEFAULT_DWP_NAME = "Program"
        private val NOTES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val OCTAVES = listOf("2", "3", "4")
    }
}
