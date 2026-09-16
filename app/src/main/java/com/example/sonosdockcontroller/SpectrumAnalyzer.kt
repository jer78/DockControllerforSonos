package com.example.sonosdockcontroller

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ambient-mic spectrum analyzer. Picks up whatever the room's speaker is
 * actually producing through the device microphone (there's no way to tap
 * Sonos's own audio stream - playback happens entirely on the speaker
 * hardware, never on this device), runs it through an FFT, and buckets the
 * result into a handful of log-spaced bands for a visualizer. This is
 * explicitly NOT calibrated/accurate - room noise, mic placement and
 * distance from the speaker all affect it - it's a visual flourish only.
 */
class SpectrumAnalyzer(private val bandCount: Int = 20) {

    private var job: Job? = null

    private val _bands = MutableStateFlow(FloatArray(bandCount))
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    // Log-spaced center frequencies (~60Hz-16kHz) used both for bucketing
    // and as the labels drawn under each bar.
    val bandFrequencies: List<Int> = (0 until bandCount).map { i ->
        val t = if (bandCount <= 1) 0.0 else i / (bandCount - 1).toDouble()
        (MIN_FREQ_HZ * (MAX_FREQ_HZ / MIN_FREQ_HZ).pow(t)).roundToInt()
    }

    val isRunning: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        job = scope.launch(Dispatchers.Default) {
            val record = openAudioRecord() ?: return@launch
            val fftSize = FFT_SIZE
            val samples = ShortArray(fftSize)
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)

            try {
                record.startRecording()
                while (isActive) {
                    var offset = 0
                    while (offset < fftSize) {
                        val read = record.read(samples, offset, fftSize - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    if (offset < fftSize) continue

                    for (i in 0 until fftSize) {
                        // Hann window - reduces spectral leakage from the
                        // block edges so bars don't smear across bands.
                        val w = 0.5f - 0.5f * cos(2.0 * Math.PI * i / (fftSize - 1)).toFloat()
                        real[i] = (samples[i] / 32768f) * w
                        imag[i] = 0f
                    }

                    fft(real, imag)

                    _bands.value = bucketToBands(real, imag, SAMPLE_RATE, fftSize)
                }
            } catch (_: Exception) {
                // Mic went away mid-capture (device busy, permission revoked, etc.) - just stop.
            } finally {
                try { record.stop() } catch (_: Exception) {}
                try { record.release() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _bands.value = FloatArray(bandCount)
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val bufSize = maxOf(minBuf, FFT_SIZE * 4)

        // UNPROCESSED gives the closest thing to a raw signal - the default
        // MIC source runs through AGC/noise-suppression that would flatten
        // out the very dynamics a visualizer wants to show. Not every device
        // supports it, so fall back to MIC if it fails to initialize.
        for (source in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            try {
                val record = AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (_: Exception) {
                // Try the next source.
            }
        }
        return null
    }

    /** Log-spaced max-magnitude bucketing, with a rough dynamic-range compression for a livelier look. */
    private fun bucketToBands(real: FloatArray, imag: FloatArray, sampleRate: Int, fftSize: Int): FloatArray {
        val usableBins = fftSize / 2
        val nyquist = sampleRate / 2.0
        val hzPerBin = nyquist / usableBins
        val out = FloatArray(bandCount)

        for (b in 0 until bandCount) {
            val f0 = MIN_FREQ_HZ * (MAX_FREQ_HZ / MIN_FREQ_HZ).pow(b / bandCount.toDouble())
            val f1 = MIN_FREQ_HZ * (MAX_FREQ_HZ / MIN_FREQ_HZ).pow((b + 1) / bandCount.toDouble())
            val bin0 = (f0 / hzPerBin).toInt().coerceIn(0, usableBins - 1)
            val bin1 = (f1 / hzPerBin).toInt().coerceIn(bin0 + 1, usableBins)

            var peak = 0f
            for (i in bin0 until bin1) {
                val mag = hypot(real[i], imag[i])
                if (mag > peak) peak = mag
            }
            // Not calibrated to any real SPL/dB scale - just squashed into a
            // visually reasonable 0..1 range.
            out[b] = sqrt((peak / REFERENCE_MAGNITUDE).coerceIn(0f, 1f))
        }
        return out
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT. `real`/`imag` length must be a power of two. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curWr = 1f
                var curWi = 0f
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * curWr - imag[i + k + len / 2] * curWi
                    val vIm = real[i + k + len / 2] * curWi + imag[i + k + len / 2] * curWr
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nextWr = curWr * wr - curWi * wi
                    val nextWi = curWr * wi + curWi * wr
                    curWr = nextWr
                    curWi = nextWi
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FFT_SIZE = 2048 // power of two, ~46ms per frame at 44.1kHz
        private const val MIN_FREQ_HZ = 60.0
        private const val MAX_FREQ_HZ = 16000.0
        // Empirical-only reference used to squash raw FFT magnitude into a
        // 0..1 range - tune this if the bars sit too low/high in practice.
        private const val REFERENCE_MAGNITUDE = 40f
    }
}
