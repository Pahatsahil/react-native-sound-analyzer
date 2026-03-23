package com.soundanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

@ReactModule(name = "SoundRecorder")
class SoundRecorderModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), AudioAnalyzer.SoundAnalyzerListener {

    override fun getName() = "SoundRecorder"

    private var audioAnalyzer: AudioAnalyzer = AudioAnalyzer()
    private var audioRecorder: AudioRecord? = null
    private var isRecording = false
    private var bufferSize = 0
    private var job: Job? = null

    // Enhanced recording properties
    private var outputFile: File? = null
    private var fileOutputStream: FileOutputStream? = null
    private var startTimeMillis: Long = 0
    private var audioFileSize: Long = 0
    private var audioFileType: String = "audio/wav"
    private var sampleRate: Int = 44100

    init {
        // Set this module as the listener for sound analysis
        audioAnalyzer.setListener(this)
    }

    // Implement the SoundAnalyzerListener interface
    override fun onSoundTypeDetected(soundType: String) {
        // Emit sound type detection event to React Native
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onSoundTypeDetected", Arguments.createMap().apply {
                putString("soundType", soundType)
                putDouble("timestamp", System.currentTimeMillis().toDouble())
                putDouble("volume", audioAnalyzer.volume)
                putDouble("noiseLevel", audioAnalyzer.noiseLevel)
            })
    }

    @ReactMethod
    fun startRecording(promise: Promise) {
        try {
            // 1. Check permissions
            if (ContextCompat.checkSelfPermission(
                    reactApplicationContext,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                promise.reject("PERMISSION_DENIED", "Microphone permission required")
                return
            }

            // 2. Create recordings directory
            val directory = File(
                reactApplicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                "recordings"
            )
            if (!directory.exists()) {
                val success = directory.mkdirs()
                if (!success) {
                    promise.reject("FILE_ERROR", "Failed to create recordings directory")
                    return
                }
            }

            // 3. Create output file
            try {
                createOutputFile()
            } catch (e: IOException) {
                promise.reject("FILE_ERROR", "Failed to create output file: ${e.localizedMessage}")
                return
            }

            // 4. Initialize AudioRecord
            try {
                sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (bufferSize <= 0) {
                    bufferSize = 4096 * 4
                }

                audioRecorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecorder?.release()
                    audioRecorder = null
                    promise.reject("INIT_FAILED", "Audio recorder initialization failed")
                    return
                }

                audioRecorder?.startRecording()
            } catch (e: Exception) {
                promise.reject("INIT_FAILED", "Failed to initialize audio recorder: ${e.localizedMessage}")
                return
            }

            // 5. Start recording and analysis
            isRecording = true
            startTimeMillis = System.currentTimeMillis()
            audioFileSize = 0

            job = CoroutineScope(Dispatchers.IO).launch {
                processAudioBuffer()
            }

            val result = Arguments.createMap().apply {
                putString("path", outputFile?.absolutePath ?: "")
                putString("type", audioFileType)
                putDouble("duration", 0.0)
                putDouble("sizeInKB", 0.0)
            }
            promise.resolve(result)

        } catch (e: Exception) {
            promise.reject("RECORD_ERROR", "Recording failed: ${e.localizedMessage}")
        }
    }

    @ReactMethod
    fun stopRecording(promise: Promise) {
        if (!isRecording) {
            promise.reject("NOT_RECORDING", "Not currently recording")
            return
        }

        isRecording = false
        job?.cancel()

        try {
            audioRecorder?.stop()
            audioRecorder?.release()
            audioRecorder = null
        } catch (e: Exception) {
            // Log the error but continue trying to save the file
        }

        try {
            // Update WAV header with final file size
            fileOutputStream?.close()
            fileOutputStream = null
            updateWavHeader(outputFile!!, audioFileSize)

            val duration = (System.currentTimeMillis() - startTimeMillis) / 1000.0

            val result = Arguments.createMap().apply {
                putString("path", outputFile?.absolutePath ?: "")
                putString("type", audioFileType)
                putDouble("duration", duration)
                putDouble("sizeInKB", audioFileSize.toDouble() / 1024.0)
            }

            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("FILE_ERROR", "Error finalizing audio file: ${e.localizedMessage}")
        }
    }

    @ReactMethod
    fun getIsRecording(promise: Promise) {
        promise.resolve(isRecording)
    }

    @ReactMethod
    fun getRecordingInfo(promise: Promise) {
        if (outputFile == null || !outputFile!!.exists()) {
            promise.reject("NO_FILE", "No recording file available")
            return
        }

        val duration = if (isRecording) {
            (System.currentTimeMillis() - startTimeMillis) / 1000.0
        } else {
            calculateWavDuration(outputFile!!, sampleRate)
        }

        val result = Arguments.createMap().apply {
            putString("path", outputFile?.absolutePath ?: "")
            putString("type", audioFileType)
            putDouble("duration", duration)
            putDouble("sizeInKB", (outputFile?.length()?.toDouble() ?: 0.0) / 1024.0)
        }

        promise.resolve(result)
    }

    @ReactMethod
    fun getCurrentAnalysis(promise: Promise) {
        val result = Arguments.createMap().apply {
            putDouble("volume", audioAnalyzer.volume)
            putDouble("noiseLevel", audioAnalyzer.noiseLevel)
            putString("soundType", audioAnalyzer.detectedSoundType)
            putBoolean("isRecording", isRecording)
        }
        promise.resolve(result)
    }

    private fun createOutputFile() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "audio_$timestamp.wav"

        val directory = File(
            reactApplicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "recordings"
        )
        if (!directory.exists()) {
            val success = directory.mkdirs()
            if (!success) {
                throw IOException("Failed to create recordings directory")
            }
        }

        outputFile = File(directory, fileName)
        try {
            fileOutputStream = FileOutputStream(outputFile)
            // Write WAV header (we'll update it when we finish recording)
            writeWavHeader(fileOutputStream!!, 0)
        } catch (e: IOException) {
            // Clean up resources on error
            fileOutputStream?.close()
            outputFile?.delete()
            throw e
        }
    }

    private suspend fun processAudioBuffer() {
        val buffer = ShortArray(bufferSize / 2)

        try {
            while (isRecording) {
                val bytesRead = audioRecorder?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    // Analyze audio for sound detection
                    audioAnalyzer.analyze(buffer)

                    // Send analysis events to React Native
                    sendAnalysisEvents()

                    // Write data to file
                    val bytes = shortArrayToByteArray(buffer)
                    fileOutputStream?.write(bytes, 0, bytes.size)
                    audioFileSize += bytes.size
                }
                // Small delay to prevent overwhelming the system
                delay(50) // 50ms delay = ~20 updates per second
            }
        } catch (e: IOException) {
            // Handle IO exceptions
        }
    }

    private fun sendAnalysisEvents() {
        val durationSecs = (System.currentTimeMillis() - startTimeMillis) / 1000.0

        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onAudioAnalysis", Arguments.createMap().apply {
                putDouble("volume", audioAnalyzer.volume)
                putDouble("noiseLevel", audioAnalyzer.noiseLevel)
                putString("soundType", audioAnalyzer.detectedSoundType)
                putString("path", outputFile?.absolutePath ?: "")
                putString("type", audioFileType)
                putDouble("duration", durationSecs)
                putDouble("sizeInKB", audioFileSize.toDouble() / 1024.0)
            })
    }

    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xff).toByte()
        }
        return bytes
    }

    // WAV header helpers
    private fun writeWavHeader(outputStream: FileOutputStream, fileLength: Long) {
        try {
            val header = ByteArray(44)
            val totalDataLen = fileLength + 36
            val bitDepth = 16
            val channels = 1 // Mono

            // RIFF header
            header[0] = 'R'.toByte()
            header[1] = 'I'.toByte()
            header[2] = 'F'.toByte()
            header[3] = 'F'.toByte()

            // Total file size minus 8 bytes
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = (totalDataLen shr 8 and 0xff).toByte()
            header[6] = (totalDataLen shr 16 and 0xff).toByte()
            header[7] = (totalDataLen shr 24 and 0xff).toByte()

            // WAVE header
            header[8] = 'W'.toByte()
            header[9] = 'A'.toByte()
            header[10] = 'V'.toByte()
            header[11] = 'E'.toByte()

            // fmt chunk
            header[12] = 'f'.toByte()
            header[13] = 'm'.toByte()
            header[14] = 't'.toByte()
            header[15] = ' '.toByte()

            // fmt chunk size (16 for PCM)
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0

            // Audio format (1 for PCM)
            header[20] = 1
            header[21] = 0

            // Number of channels
            header[22] = channels.toByte()
            header[23] = 0

            // Sample rate
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = (sampleRate shr 8 and 0xff).toByte()
            header[26] = (sampleRate shr 16 and 0xff).toByte()
            header[27] = (sampleRate shr 24 and 0xff).toByte()

            // Byte rate
            val byteRate = sampleRate * channels * bitDepth / 8
            header[28] = (byteRate and 0xff).toByte()
            header[29] = (byteRate shr 8 and 0xff).toByte()
            header[30] = (byteRate shr 16 and 0xff).toByte()
            header[31] = (byteRate shr 24 and 0xff).toByte()

            // Block align
            val blockAlign = channels * bitDepth / 8
            header[32] = blockAlign.toByte()
            header[33] = 0

            // Bits per sample
            header[34] = bitDepth.toByte()
            header[35] = 0

            // Data chunk
            header[36] = 'd'.toByte()
            header[37] = 'a'.toByte()
            header[38] = 't'.toByte()
            header[39] = 'a'.toByte()

            // Data size
            header[40] = (fileLength and 0xff).toByte()
            header[41] = (fileLength shr 8 and 0xff).toByte()
            header[42] = (fileLength shr 16 and 0xff).toByte()
            header[43] = (fileLength shr 24 and 0xff).toByte()

            outputStream.write(header, 0, 44)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun updateWavHeader(file: File, dataSize: Long) {
        var randomAccessFile: java.io.RandomAccessFile? = null
        try {
            randomAccessFile = java.io.RandomAccessFile(file, "rw")

            // Total file size minus 8 bytes
            val totalDataLen = dataSize + 36

            // Update RIFF chunk size
            randomAccessFile.seek(4)
            randomAccessFile.write((totalDataLen and 0xff).toInt())
            randomAccessFile.write((totalDataLen shr 8 and 0xff).toInt())
            randomAccessFile.write((totalDataLen shr 16 and 0xff).toInt())
            randomAccessFile.write((totalDataLen shr 24 and 0xff).toInt())

            // Update data chunk size
            randomAccessFile.seek(40)
            randomAccessFile.write((dataSize and 0xff).toInt())
            randomAccessFile.write((dataSize shr 8 and 0xff).toInt())
            randomAccessFile.write((dataSize shr 16 and 0xff).toInt())
            randomAccessFile.write((dataSize shr 24 and 0xff).toInt())
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            randomAccessFile?.close()
        }
    }

    private fun calculateWavDuration(wavFile: File, sampleRate: Int): Double {
        val fileSize = wavFile.length()
        // Subtract 44 bytes for the header
        val audioDataSize = fileSize - 44
        val bytesPerSecond = sampleRate * 2 // 16-bit mono = 2 bytes per sample
        return audioDataSize.toDouble() / bytesPerSecond
    }

    override fun invalidate() {
        // Clean up resources
        isRecording = false
        job?.cancel()

        try {
            audioRecorder?.stop()
            audioRecorder?.release()
            audioRecorder = null

            fileOutputStream?.close()
            fileOutputStream = null
        } catch (e: Exception) {
            // Log error but don't crash
        }

        super.invalidate()
    }
    }