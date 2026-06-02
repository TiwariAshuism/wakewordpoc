package com.example.wakewordpoc.ml

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.wakewordpoc.WakeWordConfig
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

class TFLiteWakeWordDetector(
    private val context: Context,
    private val onDetected: (Float) -> Unit,
) {
    private var stage1: ModelRunner? = null
    private var stage2: ModelRunner? = null
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val listening = AtomicBoolean(false)
    private var lastDetectionMs = 0L
    private var audioWindows = 0

    fun startListening() {
        if (listening.get()) return
        if (!hasAudioPermission()) {
            throw IllegalStateException("Microphone permission is missing")
        }

        ensureModels()
        WakeWordConfig.setMlStatus(context, "Starting AudioRecord")
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioRecord buffer is unavailable: $minBuffer" }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer * 2, READ_SAMPLES * 2),
        )
        audioRecord?.startRecording()
        Log.i(TAG, "AudioRecord started sampleRate=$SAMPLE_RATE minBuffer=$minBuffer")
        WakeWordConfig.setMlStatus(context, "Listening: mic active")
        listening.set(true)
        worker = Thread(::processLoop, "HeyM-TFLite-Cascade").apply { start() }
    }

    fun stopListening() {
        listening.set(false)
        worker?.interrupt()
        worker = null
        audioRecord?.runCatchingStop()
        audioRecord?.release()
        audioRecord = null
    }

    fun release() {
        stopListening()
        stage1?.close()
        stage2?.close()
        stage1 = null
        stage2 = null
    }

    private fun processLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val readBuffer = ShortArray(READ_SAMPLES)
        val window = FloatArray(WINDOW_SAMPLES)
        var writeIndex = 0
        var collected = 0
        var samplesSinceInference = 0
        var totalSamples = 0L

        while (listening.get()) {
            val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break
            if (read <= 0) {
                Log.w(TAG, "AudioRecord read=$read")
                WakeWordConfig.setMlStatus(context, "Mic read issue: $read")
                continue
            }

            for (i in 0 until read) {
                window[writeIndex] = readBuffer[i] / 32768f
                writeIndex = (writeIndex + 1) % WINDOW_SAMPLES
            }
            collected = minOf(WINDOW_SAMPLES, collected + read)
            samplesSinceInference += read
            totalSamples += read
            if (totalSamples % (SAMPLE_RATE * 2L) == 0L) {
                val msg = "Mic reading ${totalSamples / SAMPLE_RATE}s"
                Log.i(TAG, msg)
                WakeWordConfig.setMlStatus(context, msg, audioWindows = audioWindows)
            }

            if (collected == WINDOW_SAMPLES && samplesSinceInference >= INFERENCE_STRIDE_SAMPLES) {
                samplesSinceInference = 0
                audioWindows += 1
                val ordered = FloatArray(WINDOW_SAMPLES)
                for (i in ordered.indices) {
                    ordered[i] = window[(writeIndex + i) % WINDOW_SAMPLES]
                }
                runCatching { runCascade(ordered) }
                    .onFailure { Log.e(TAG, "Cascade inference failed", it) }
            }
        }
    }

    private fun runCascade(audio: FloatArray) {
        val mel = MelFeatureExtractor.extract(audio)
        val s1 = requireNotNull(stage1).score(mel)
        if (s1 < STAGE1_THRESHOLD) {
            val msg = "Window $audioWindows Stage1 rejected ${"%.3f".format(s1)} < $STAGE1_THRESHOLD"
            Log.i(TAG, msg)
            WakeWordConfig.setMlStatus(
                context,
                msg,
                stage1Score = s1,
                audioWindows = audioWindows,
            )
            return
        }

        val s2 = requireNotNull(stage2).score(mel)
        val scoreMsg = "Window $audioWindows Stage1 ${"%.3f".format(s1)} Stage2 ${"%.3f".format(s2)}"
        Log.i(TAG, scoreMsg)
        WakeWordConfig.setMlStatus(
            context,
            scoreMsg,
            stage1Score = s1,
            stage2Score = s2,
            audioWindows = audioWindows,
        )

        val now = System.currentTimeMillis()
        val passesStage2 = s2 >= STAGE2_THRESHOLD
        val passesDebugStage1 = DEBUG_TRIGGER_FROM_STAGE1 && s1 >= DEBUG_STAGE1_TRIGGER_THRESHOLD
        if ((passesStage2 || passesDebugStage1) && now - lastDetectionMs > DETECTION_COOLDOWN_MS) {
            lastDetectionMs = now
            val confidence = if (passesStage2) s2 else s1
            val source = if (passesStage2) "stage2" else "stage1-debug"
            Log.i(TAG, "DETECTED Hey M source=$source confidence=${"%.3f".format(confidence)}")
            WakeWordConfig.setMlStatus(
                context,
                "DETECTED $source ${"%.3f".format(confidence)}",
                stage1Score = s1,
                stage2Score = s2,
                audioWindows = audioWindows,
            )
            onDetected(confidence)
        } else if (s2 < STAGE2_THRESHOLD) {
            Log.i(TAG, "Stage2 rejected ${"%.3f".format(s2)} < $STAGE2_THRESHOLD")
        } else {
            Log.i(TAG, "Detection ignored during cooldown")
        }
    }

    private fun ensureModels() {
        if (stage1 != null && stage2 != null) return
        stage1 = ModelRunner(context, WakeWordConfig.STAGE1_MODEL_ASSET)
        stage2 = ModelRunner(context, WakeWordConfig.STAGE2_MODEL_ASSET)
        WakeWordConfig.setMlStatus(context, "Models loaded")
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun AudioRecord.runCatchingStop() {
        runCatching { stop() }
    }

    private class ModelRunner(
        private val context: Context,
        private val assetName: String,
    ) {
        private val interpreter: Interpreter
        private val inputType: TensorType
        private val outputType: TensorType
        private val inputScale: Float
        private val inputZeroPoint: Int
        private val outputScale: Float
        private val outputZeroPoint: Int

        init {
            interpreter = Interpreter(
                loadModel(assetName),
                Interpreter.Options().apply {
                    setNumThreads(2)
                    setUseXNNPACK(true)
                },
            )
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            validateInputShape(input, assetName)
            inputType = TensorType.from(input.dataType().name)
            outputType = TensorType.from(output.dataType().name)
            input.quantizationParams().let {
                inputScale = it.scale
                inputZeroPoint = it.zeroPoint
            }
            output.quantizationParams().let {
                outputScale = it.scale
                outputZeroPoint = it.zeroPoint
            }
            Log.i(TAG, "Loaded $assetName input=${input.dataType()} output=${output.dataType()}")
        }

        fun score(mel: Array<Array<Array<FloatArray>>>): Float {
            return when (inputType) {
                TensorType.FLOAT32 -> scoreFloat(mel)
                TensorType.INT8 -> scoreInt8(mel)
            }
        }

        fun close() {
            interpreter.close()
        }

        private fun scoreFloat(mel: Array<Array<Array<FloatArray>>>): Float {
            val output = when (outputType) {
                TensorType.FLOAT32 -> Array(1) { FloatArray(1) }
                TensorType.INT8 -> Array(1) { ByteArray(1) }
            }
            interpreter.run(mel, output)
            return readOutput(output)
        }

        private fun scoreInt8(mel: Array<Array<Array<FloatArray>>>): Float {
            val output = when (outputType) {
                TensorType.FLOAT32 -> Array(1) { FloatArray(1) }
                TensorType.INT8 -> Array(1) { ByteArray(1) }
            }
            interpreter.run(quantizeMel(mel), output)
            return readOutput(output)
        }

        private fun readOutput(output: Any): Float {
            return when (outputType) {
                TensorType.FLOAT32 -> (output as Array<FloatArray>)[0][0]
                TensorType.INT8 -> {
                    val raw = (output as Array<ByteArray>)[0][0].toInt()
                    (raw - outputZeroPoint) * outputScale
                }
            }
        }

        private fun quantizeMel(mel: Array<Array<Array<FloatArray>>>): Array<Array<Array<ByteArray>>> =
            Array(1) {
                Array(N_MELS) { melBin ->
                    Array(EXPECTED_FRAMES) { frame ->
                        ByteArray(1) { quantize(mel[0][melBin][frame][0]) }
                    }
                }
            }

        private fun quantize(value: Float): Byte {
            val scale = inputScale.takeIf { it > 0f } ?: 1f
            val q = round(value / scale + inputZeroPoint)
                .toInt()
                .coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())
            return q.toByte()
        }

        private fun loadModel(modelName: String): MappedByteBuffer {
            val afd = context.assets.openFd(modelName)
            FileInputStream(afd.fileDescriptor).use { input ->
                return input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength,
                )
            }
        }

        private fun validateInputShape(tensor: Tensor, assetName: String) {
            val expected = intArrayOf(1, N_MELS, EXPECTED_FRAMES, 1)
            val actual = tensor.shape()
            require(actual.contentEquals(expected)) {
                "$assetName input must be [1, 40, 97, 1], got ${actual.joinToString()}"
            }
        }
    }

    private enum class TensorType {
        FLOAT32,
        INT8;

        companion object {
            fun from(name: String): TensorType = when (name) {
                "FLOAT32" -> FLOAT32
                "INT8" -> INT8
                else -> throw IllegalArgumentException("Unsupported tensor type: $name")
            }
        }
    }

    private object MelFeatureExtractor {
        private val hann = FloatArray(N_FFT) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (N_FFT - 1))).toFloat()
        }
        private val filters = buildMelFilters()
        private val fftReal = FloatArray(N_FFT)
        private val fftImag = FloatArray(N_FFT)

        fun extract(audio: FloatArray): Array<Array<Array<FloatArray>>> {
            val melPower = Array(N_MELS) { FloatArray(EXPECTED_FRAMES) }
            val power = FloatArray(N_FFT / 2 + 1)

            for (frame in 0 until EXPECTED_FRAMES) {
                val offset = frame * HOP_LENGTH
                computePowerSpectrum(audio, offset, power)
                for (mel in 0 until N_MELS) {
                    var sum = 0f
                    val filter = filters[mel]
                    for (bin in filter.indices) {
                        sum += power[bin] * filter[bin]
                    }
                    melPower[mel][frame] = sum.coerceAtLeast(1e-10f)
                }
            }

            var maxDb = -120f
            val melDb = Array(N_MELS) { FloatArray(EXPECTED_FRAMES) }
            for (mel in 0 until N_MELS) {
                for (frame in 0 until EXPECTED_FRAMES) {
                    val db = 10f * log10(melPower[mel][frame])
                    melDb[mel][frame] = db
                    if (db > maxDb) maxDb = db
                }
            }

            var minValue = Float.MAX_VALUE
            var maxValue = -Float.MAX_VALUE
            for (mel in 0 until N_MELS) {
                for (frame in 0 until EXPECTED_FRAMES) {
                    val value = melDb[mel][frame] - maxDb
                    melDb[mel][frame] = value
                    minValue = minOf(minValue, value)
                    maxValue = maxOf(maxValue, value)
                }
            }

            val range = (maxValue - minValue).coerceAtLeast(1e-6f)
            return Array(1) {
                Array(N_MELS) { mel ->
                    Array(EXPECTED_FRAMES) { frame ->
                        FloatArray(1) { (melDb[mel][frame] - minValue) / range }
                    }
                }
            }
        }

        private fun computePowerSpectrum(audio: FloatArray, offset: Int, out: FloatArray) {
            for (i in 0 until N_FFT) {
                fftReal[i] = audio[offset + i] * hann[i]
                fftImag[i] = 0f
            }
            fft(fftReal, fftImag)
            for (k in out.indices) {
                val real = fftReal[k]
                val imag = fftImag[k]
                out[k] = (real * real + imag * imag) / N_FFT
            }
        }

        private fun fft(real: FloatArray, imag: FloatArray) {
            var j = 0
            for (i in 1 until N_FFT) {
                var bit = N_FFT shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tempReal = real[i]
                    real[i] = real[j]
                    real[j] = tempReal
                    val tempImag = imag[i]
                    imag[i] = imag[j]
                    imag[j] = tempImag
                }
            }

            var len = 2
            while (len <= N_FFT) {
                val angle = -2.0 * PI / len
                val wLenReal = cos(angle).toFloat()
                val wLenImag = sin(angle).toFloat()
                var i = 0
                while (i < N_FFT) {
                    var wReal = 1f
                    var wImag = 0f
                    for (k in 0 until len / 2) {
                        val even = i + k
                        val odd = even + len / 2
                        val oddReal = real[odd] * wReal - imag[odd] * wImag
                        val oddImag = real[odd] * wImag + imag[odd] * wReal

                        real[odd] = real[even] - oddReal
                        imag[odd] = imag[even] - oddImag
                        real[even] += oddReal
                        imag[even] += oddImag

                        val nextReal = wReal * wLenReal - wImag * wLenImag
                        wImag = wReal * wLenImag + wImag * wLenReal
                        wReal = nextReal
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun buildMelFilters(): Array<FloatArray> {
            val melMin = hzToMel(FMIN)
            val melMax = hzToMel(FMAX)
            val melPoints = FloatArray(N_MELS + 2) { i ->
                melMin + (melMax - melMin) * i / (N_MELS + 1)
            }
            val hzPoints = melPoints.map { melToHz(it) }
            val binPoints = hzPoints.map {
                (((N_FFT + 1) * it / SAMPLE_RATE).toInt()).coerceIn(0, N_FFT / 2)
            }

            return Array(N_MELS) { mel ->
                val filter = FloatArray(N_FFT / 2 + 1)
                val left = binPoints[mel]
                val center = binPoints[mel + 1]
                val right = binPoints[mel + 2]

                for (bin in left until center) {
                    filter[bin] = (bin - left).toFloat() / (center - left).coerceAtLeast(1)
                }
                for (bin in center until right) {
                    filter[bin] = (right - bin).toFloat() / (right - center).coerceAtLeast(1)
                }
                filter
            }
        }

        private fun hzToMel(hz: Float): Float =
            (2595.0 * log10(1.0 + hz / 700.0)).toFloat()

        private fun melToHz(mel: Float): Float =
            (700.0 * (10.0.pow(mel / 2595.0) - 1.0)).toFloat()
    }

    companion object {
        private const val TAG = "TFLiteWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SAMPLES = 16000
        private const val READ_SAMPLES = 1600
        private const val INFERENCE_STRIDE_SAMPLES = 8000
        private const val N_MELS = 40
        private const val N_FFT = 512
        private const val HOP_LENGTH = 160
        private const val EXPECTED_FRAMES = 97
        private const val FMIN = 80f
        private const val FMAX = 7600f
        private const val STAGE1_THRESHOLD = 0.05f
        private const val STAGE2_THRESHOLD = 0.20f
        private const val DEBUG_TRIGGER_FROM_STAGE1 = true
        private const val DEBUG_STAGE1_TRIGGER_THRESHOLD = 0.20f
        private const val DETECTION_COOLDOWN_MS = 1500L
    }
}
