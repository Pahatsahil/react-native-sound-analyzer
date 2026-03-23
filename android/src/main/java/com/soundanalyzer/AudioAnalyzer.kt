package com.soundanalyzer

import android.os.Handler
import android.os.Looper
import kotlin.math.*

class AudioAnalyzer {
    interface SoundAnalyzerListener {
        fun onSoundTypeDetected(soundType: String)
    }

    private var listener: SoundAnalyzerListener? = null
    private val fft = FFT(1024)

    // Analysis properties
    var volume: Double = 0.0
        private set
    var noiseLevel: Double = 0.0
        private set
    var detectedSoundType: String = "Silent"
        private set

    // For throttling sound type notifications
    private var lastSoundType: String = "Silent"
    private var lastNotificationTime: Long = 0
    private val notificationCooldown = 500L // 500ms cooldown

    fun setListener(listener: SoundAnalyzerListener?) {
        this.listener = listener
    }

    fun analyze(buffer: ShortArray) {
        // Calculate volume (RMS)
        val sum = buffer.map { it.toDouble() * it.toDouble() }.sum()
        this.volume = sqrt(sum / buffer.size)

        // Prepare data for FFT
        val bufferSize = minOf(buffer.size, 1024)
        val real = DoubleArray(1024) { i ->
            if (i < bufferSize) buffer[i].toDouble() else 0.0
        }
        val imag = DoubleArray(1024) { 0.0 }

        // Perform FFT
        fft.fft(real, imag)

        // Calculate frequency domain magnitude
        val magnitude = DoubleArray(512) { i ->
            sqrt(real[i] * real[i] + imag[i] * imag[i])
        }

        this.noiseLevel = magnitude.average()

        // Frequency analysis for scream detection
        val sampleRate = 44100
        val highFreqStart = (3000 * 1024 / sampleRate).coerceAtMost(511)
        val highFreqEnd = (8000 * 1024 / sampleRate).coerceAtMost(511)

        val highFreqEnergy = if (highFreqEnd > highFreqStart) {
            magnitude.slice(highFreqStart..highFreqEnd).average()
        } else {
            0.0
        }

        // Detect sound types (only 5 kinds)
        val newSoundType = when {
            this.volume < 500 -> "Silent"
            highFreqEnergy > noiseLevel * 3 && volume > 5000 -> "Screaming"
            volume > 3000 && noiseLevel > 2000 -> "Speaking+BackgroundNoise"
            noiseLevel > 2000 -> "BackgroundNoise"
            else -> "Normal"
        }

        this.detectedSoundType = newSoundType

        // Notify listener if needed
        val currentTime = System.currentTimeMillis()
        if (newSoundType != lastSoundType && (currentTime - lastNotificationTime >= notificationCooldown)) {
            listener?.let { l ->
                Handler(Looper.getMainLooper()).post {
                    l.onSoundTypeDetected(newSoundType)
                }
            }
            lastNotificationTime = currentTime
        }

        lastSoundType = newSoundType
    }
}

class FFT(private val n: Int) {
    private val cos: DoubleArray
    private val sin: DoubleArray

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "n must be a power of 2" }
        cos = DoubleArray(n / 2)
        sin = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            cos[i] = kotlin.math.cos(-2.0 * Math.PI * i / n)
            sin[i] = kotlin.math.sin(-2.0 * Math.PI * i / n)
        }
    }

    fun fft(real: DoubleArray, imag: DoubleArray) {
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            val j = Integer.reverse(i).ushr(32 - bits)
            if (j > i) {
                val tempReal = real[i]
                val tempImag = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tempReal
                imag[j] = tempImag
            }
        }

        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val tableStep = n / size
            for (i in 0 until n step size) {
                var k = 0
                for (j in i until i + halfSize) {
                    val tReal = cos[k] * real[j + halfSize] - sin[k] * imag[j + halfSize]
                    val tImag = sin[k] * real[j + halfSize] + cos[k] * imag[j + halfSize]
                    real[j + halfSize] = real[j] - tReal
                    imag[j + halfSize] = imag[j] - tImag
                    real[j] += tReal
                    imag[j] += tImag
                    k += tableStep
                }
            }
            size *= 2
        }
    }
}