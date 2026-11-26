# Chromatic Scale Generator (Android)

Generates a chromatic scale from numbered samples (`1.wav`, `2.wav`, …), like the PC version
(`chromatic_gen.py`, which used `wxPython` and `Parselmouth`), but as an Android app.

**Languages:** English, Español, Português (Portugal) and Português (Brasil). The app asks for the language the
first time it opens, and the **Language** button at the top lets you change it at any time.

## Features

- **Auto-trim**: removes the silence at the start and end of every sample (anything below −45 dB relative to the
  sample's peak, keeping a 5 ms margin) so notes play without delay. Silences inside a sample are left alone.
- **Volume normalization**: every sample ends up with the same RMS level (the loudest common level that keeps all of
  them below clipping, at most −18 dBFS), so no vowel sounds louder or quieter than another.
- **DWP maker**: creates a DirectWave program (`.dwp`) from `1.wav`, `2.wav`, … using the same logic as
  `DWP Converter.html`: one region per sample from C5 upward (one semitone each, up to 128 regions), optional automatic
  loop with crossfade on sustained sounds, and four quality levels. It does not need Praat. The `.dwp` is saved in the
  chosen folder. The core (`DwpBuilder.kt`) was checked byte for byte against the HTML's JavaScript.

- **Slicex**: load a WAV with Slicex markers (or any WAV) and its regions replace `1.wav`, `2.wav`, … for both the
  chromatic scale and the DWP. Each marker opens a region until the next one. Tap the waveform to add or remove a
  marker, detect sounds automatically (3 sensitivities), split into equal parts, move all markers by some
  milliseconds, include the audio before the first marker, or restore the file's markers. Detection, splitting and
  snapping to the quietest point give the same results as `DWP Converter.html`. WAV files only (PCM or float, up to 60 MB).

- **Generate Chromatic With Markers** (*Generar Chromatic Con Markers*): same chromatic scale, but saved as
  `chromatic_markers.wav` with a marker (WAV `cue ` chunk) at the start of every note, so you can open it in Slicex
  and get one region per note. The marker positions come from the Praat script itself (`MARKER <sample>` lines), so
  they match the audio exactly, including the gaps. The plain `chromatic.wav` button is unchanged.

Auto-trim and normalization apply both to the chromatic scale and to the DWP.

## How the port works

| PC | Android |
| --- | --- |
| `wxPython` (`app_ui.py`) | Native Kotlin UI (`MainActivity.kt`, `res/layout/activity_main.xml`) |
| `Parselmouth` (Python + Praat) | The **same Praat source code Parselmouth uses**, compiled with the NDK (C++) and called through JNI |
| `parselmouth.praat.call(...)` | A Praat script with those same commands: `app/src/main/assets/chromatic_scale.praat` |
| `DirPickerCtrl` | Android's folder picker (no storage permissions needed) |

Android has no Python and cannot run Parselmouth as is, so `scripts/fetch_praat.sh` downloads Parselmouth's source
code (which includes Praat 6.4.16 and `fmt`, pinned to fixed commits) and `app/src/main/cpp/CMakeLists.txt` builds
Praat for Android reusing Parselmouth's own `CMakeLists` (no GUI, audio or network).
`praat_core.cpp` does what `parselmouth.praat.run` does: it initializes Praat and runs a script, capturing its output.

The app copies the samples from the chosen folder into its private storage, processes them with Praat, and copies
`chromatic.wav` and `pitched_samples/` back to that same folder.

## Building the APK

GitHub Actions does it (`.github/workflows/build.yml`): on every push to `main`/`master`, on every pull request, or
manually from **Actions → Build APK → Run workflow**. It produces a single **universal APK**,
`ChromaticScaleGenerator-universal.apk` (`armeabi-v7a` + `arm64-v8a`, so it works on any phone), in the
`ChromaticScaleGenerator-universal-apk` artifact.

Pushing a `v*` tag (for example `v1.0`) also publishes a Release with the APK.
The first build takes a while (it compiles all of Praat); later ones use `ccache`.

Locally: `scripts/fetch_praat.sh && ./gradlew assembleRelease` (needs JDK 17, the Android SDK and NDK 27.2.12479018).
The APK is signed with the debug key, so they can be installed directly (it is not suitable for Google Play).

## Testing the core on a PC

`native-test/` builds the same bridge and the same Praat script on Linux/macOS and checks that `chromatic.wav` has
the right order, gaps and pitch:

```bash
scripts/fetch_praat.sh
cmake -S app/src/main/cpp -B build-host -DCHROMATIC_HOST_TEST=ON && cmake --build build-host -j
python3 native-test/make_samples.py /tmp/samples
mkdir -p /tmp/out
build-host/chromatic_host_test app/src/main/assets/chromatic_scale.praat /tmp/samples /tmp/out 0 0 24 0.1 1 1
python3 native-test/check_output.py /tmp/out 0 0 24 0.1 1
```

## License

GPL-3.0 or later (same as the original program, Parselmouth and Praat).
