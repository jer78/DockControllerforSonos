package io.github.jer78.dockcontroller

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

    // Auto-gain ceiling (see bucketToBands) - reset each start() so a
    // session doesn't inherit a stale calibration from before it was paused.
    private var agcCeiling = INITIAL_AGC_CEILING

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
        agcCeiling = INITIAL_AGC_CEILING
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

    /**
     * Log-spaced max-magnitude bucketing. Two adjustments keep this looking
     * like a "real" analyzer instead of a mostly-flat row with one or two
     * bars twitching:
     *  - Frequency compensation: real audio (and the mic's own response)
     *    rolls off hard at higher frequencies, so raw FFT magnitude alone
     *    leaves treble bars almost always near-zero while only bass moves.
     *    Boosted by sqrt(frequency) so the whole row visibly reacts.
     *  - Auto-gain: instead of a fixed magnitude-to-1.0 reference (which has
     *    to guess the room's actual volume/mic gain and was sitting too high
     *    in practice, making every bar look tiny), track a decaying ceiling
     *    of recent loudness and normalize against that. Rises instantly on a
     *    loud passage, relaxes slowly after, so bars use the full height
     *    regardless of how loud the room/mic actually reads.
     */
    private fun bucketToBands(real: FloatArray, imag: FloatArray, sampleRate: Int, fftSize: Int): FloatArray {
        val usableBins = fftSize / 2
        val nyquist = sampleRate / 2.0
        val hzPerBin = nyquist / usableBins
        val boostedPeaks = FloatArray(bandCount)
        var frameMax = 0f

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
            val freqBoost = sqrt(f0 / MIN_FREQ_HZ).toFloat()
            val boosted = peak * freqBoost
            boostedPeaks[b] = boosted
            if (boosted > frameMax) frameMax = boosted
        }

        agcCeiling = (if (frameMax > agcCeiling) {
            frameMax
        } else {
            agcCeiling * AGC_DECAY + frameMax * (1f - AGC_DECAY)
        }).coerceAtLeast(MIN_AGC_CEILING)

        val out = FloatArray(bandCount)
        for (b in 0 until bandCount) {
            out[b] = sqrt((boostedPeaks[b] / agcCeiling).coerceIn(0f, 1f))
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
        private const val FFT_SIZE = 1024 // power of two, ~23ms per frame at 44.1kHz - kept small for a snappy, real-time-feeling visualizer over frequency precision
        private const val MIN_FREQ_HZ = 60.0
        private const val MAX_FREQ_HZ = 16000.0
        // Auto-gain tuning (see bucketToBands): seed ceiling before any real
        // audio has been seen, floor so near-silence doesn't get amplified
        // into noise-driven bars, and how fast the ceiling relaxes back down
        // after a loud passage (per ~23ms frame, so 0.992 is roughly a 3s decay).
        private const val INITIAL_AGC_CEILING = 8f
        private const val MIN_AGC_CEILING = 1.5f
        private const val AGC_DECAY = 0.992f
    }
}
