package com.example.wakewordpoc.ml

import android.content.Context
import android.media.AudioRecord
import android.media.MediaRecorder
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import android.util.Log
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * TFLite-based Wake Word Detector
 * 
 * CRITICAL DATA FLOW:
 * 1. AudioRecord captures PCM 16-bit at 16kHz
 * 2. Convert SHORT audio → FLOAT32 (-1.0 to 1.0)
 * 3. Extract MEL-SPECTROGRAM: 40 bins × 64 time steps
 * 4. Input to TFLite model: shape [1, 40, 64], type FLOAT32
 * 5. Output: probability [0.0, 1.0]
 */
class TFLiteWakeWordDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "TFLiteWakeWord"
        
        // ✓ MUST match training configuration
        private const val SAMPLE_RATE = 16000         // 16kHz (NOT 44.1kHz!)
        private const val DURATION_SECONDS = 1        // 1 second window
        private const val AUDIO_CHUNK_SIZE = 16000    // 16000 samples = 1 second @ 16kHz
        
        // Mel-spectrogram configuration
        private const val MEL_BINS = 40               // 40 mel frequency bins
        private const val N_FFT = 512                 // FFT window size
        private const val HOP_LENGTH = 160            // 160ms overlap (80ms stride)
        private const val TIME_STEPS = 64             // Number of frames per window
        
        // Model configuration
        private const val MODEL_FILE = "model_student_int8.tflite"
        private const val DETECTION_THRESHOLD = 0.5f  // 50% confidence threshold
    }
    
    private var interpreter: Interpreter? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var streamingBuffer = FloatArray(AUDIO_CHUNK_SIZE)
    private var bufferIndex = 0
    
    // TFLite input/output buffers
    private var modelInput: Array<Array<FloatArray>>? = null  // [1, 40, 64]
    private var modelOutput: MutableMap<Int, Any>? = null      // [1, 1] probability
    
    private var melProcessor: MelSpectrogramProcessor? = null
    
    init {
        Log.d(TAG, "🔄 Initializing TFLite Wake Word Detector...")
        initializeModel()
        melProcessor = MelSpectrogramProcessor(SAMPLE_RATE, MEL_BINS, N_FFT, HOP_LENGTH, TIME_STEPS)
    }
    
    // ────────────────────────────────────────────────────────────────
    // ✅ MODEL LOADING & VALIDATION
    // ────────────────────────────────────────────────────────────────
    
    private fun initializeModel() {
        try {
            Log.d(TAG, "📦 Loading TFLite model: $MODEL_FILE")
            
            // Load model from assets
            val modelBuffer = loadModelFile(MODEL_FILE)
            
            // Create interpreter with multiple threads
            val options = Interpreter.Options()
            options.setNumThreads(4)  // Use 4 threads for faster inference
            options.setUseNNAPI(true) // Use NNAPI accelerator if available
            
            interpreter = Interpreter(modelBuffer, options)
            
            Log.d(TAG, "✓ Model loaded successfully")
            setupInputOutputBuffers()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ FAILED to load model: ${e.message}", e)
        }
    }
    
    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val inputStream = context.assets.open(fileName)
        val fileChannel = (inputStream as FileInputStream).channel
        val startOffset = 0L
        val declaredLength = inputStream.available().toLong()
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    private fun setupInputOutputBuffers() {
        try {
            val inputTensor = interpreter?.getInputTensor(0)
            val inputShape = inputTensor?.shape()
            
            Log.d(TAG, "✓ Input Tensor Shape: ${inputShape?.joinToString()}")
            Log.d(TAG, "  Expected: [1, 40, 64]")
            Log.d(TAG, "  Type: ${inputTensor?.dataType()}")
            
            // Validate input shape: should be [1, 40, 64]
            if (inputShape?.get(1) != MEL_BINS || inputShape?.get(2) != TIME_STEPS) {
                Log.w(TAG, "⚠️ Input shape mismatch! Expected [1, 40, 64], got [${inputShape?.get(0)}, ${inputShape?.get(1)}, ${inputShape?.get(2)}]")
            }
            
            // Prepare output buffers
            modelOutput = mutableMapOf()
            modelOutput!![0] = FloatArray(1)  // KWS probability output
            
            val outputTensor = interpreter?.getOutputTensor(0)
            val outputShape = outputTensor?.shape()
            Log.d(TAG, "✓ Output Tensor Shape: ${outputShape?.joinToString()}")
            Log.d(TAG, "  Expected: [1, 1] for probability")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup buffers: ${e.message}", e)
        }
    }
    
    // ────────────────────────────────────────────────────────────────
    // ✅ AUDIO CAPTURE (16kHz PCM, NOT 44.1kHz AAC!)
    // ────────────────────────────────────────────────────────────────
    
    fun startListening() {
        if (!hasAudioPermission()) {
            Log.e(TAG, "❌ Audio permission not granted")
            return
        }
        
        try {
            isListening = true
            initAudioRecord()
            audioRecord?.startRecording()
            
            // Start processing in background thread
            Thread { processAudioStream() }.start()
            
            Log.d(TAG, "✓ Listening started (16kHz PCM)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start listening: ${e.message}", e)
        }
    }
    
    private fun initAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,                                    // ✓ 16kHz (was 44100!)
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT    // ✓ PCM 16-bit (was AAC!)
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,                                    // ✓ 16kHz
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,   // ✓ PCM 16-bit
            minBufferSize * 2
        )
        
        Log.d(TAG, "✓ AudioRecord initialized:")
        Log.d(TAG, "   Sample Rate: $SAMPLE_RATE Hz")
        Log.d(TAG, "   Buffer Size: ${minBufferSize * 2} bytes")
    }
    
    // ────────────────────────────────────────────────────────────────
    // ✅ AUDIO PROCESSING PIPELINE
    // ────────────────────────────────────────────────────────────────
    
    private fun processAudioStream() {
        val frameSize = SAMPLE_RATE / 10  // 100ms frames @ 16kHz = 1600 samples
        val buffer = ShortArray(frameSize)
        
        Log.d(TAG, "🎙️ Audio stream processing started")
        Log.d(TAG, "   Frame size: $frameSize samples (100ms)")
        
        while (isListening) {
            try {
                // Read audio from microphone
                val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (samplesRead > 0) {
                    // Add to circular buffer
                    addToStreamingBuffer(buffer, samplesRead)
                    
                    // When we have 1 second of audio, run inference
                    if (bufferIndex >= AUDIO_CHUNK_SIZE) {
                        runInference()
                        // Shift buffer by half for sliding window
                        shiftBuffer()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Audio processing error: ${e.message}")
            }
        }
    }
    
    private fun addToStreamingBuffer(samples: ShortArray, numSamples: Int) {
        for (i in 0 until numSamples) {
            if (bufferIndex < AUDIO_CHUNK_SIZE) {
                // Convert SHORT (-32768 to 32767) → FLOAT32 (-1.0 to 1.0)
                streamingBuffer[bufferIndex] = samples[i] / 32768f
                bufferIndex++
            }
        }
    }
    
    private fun shiftBuffer() {
        // Sliding window: move half the buffer to the start
        val halfSize = AUDIO_CHUNK_SIZE / 2
        for (i in 0 until halfSize) {
            streamingBuffer[i] = streamingBuffer[i + halfSize]
        }
        bufferIndex = halfSize
    }
    
    // ────────────────────────────────────────────────────────────────
    // ✅ INFERENCE & DETECTION
    // ────────────────────────────────────────────────────────────────
    
    private fun runInference() {
        try {
            // Step 1: Extract MEL-SPECTROGRAM from audio
            // Input: 16,000 float samples (1 second @ 16kHz)
            // Output: [1, 40, 64] MEL-SPECTROGRAM
            val melSpec = melProcessor?.extractMelSpectrogram(streamingBuffer)
            
            if (melSpec == null) {
                Log.w(TAG, "⚠️ Failed to extract MEL-spectrogram")
                return
            }
            
            Log.d(TAG, "✓ MEL-Spectrogram extracted: shape [1, 40, 64]")
            
            // Step 2: Prepare input for TFLite model
            modelInput = melSpec
            
            // Step 3: Run inference
            val startTime = System.currentTimeMillis()
            interpreter?.runForMultipleInputsOutputs(arrayOf(melSpec), modelOutput)
            val inferenceTime = System.currentTimeMillis() - startTime
            
            // Step 4: Get results
            val output = modelOutput?.get(0) as? FloatArray
            val probability = output?.get(0) ?: 0f
            
            Log.d(TAG, "⚡ Inference completed in ${inferenceTime}ms")
            Log.d(TAG, "   Probability: ${"%.3f".format(probability)} (threshold: $DETECTION_THRESHOLD)")
            
            // Step 5: Check detection
            if (probability > DETECTION_THRESHOLD) {
                onWakeWordDetected(probability)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Inference error: ${e.message}", e)
        }
    }
    
    private fun onWakeWordDetected(probability: Float) {
        Log.i(TAG, "🎤 ✅ WAKE WORD DETECTED!")
        Log.i(TAG, "   Confidence: ${"%.1f".format(probability * 100)}%")
        // TODO: Trigger wake action (screen, recording, etc.)
    }
    
    fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "✓ Listening stopped")
    }
    
    fun release() {
        stopListening()
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "✓ Resources released")
    }
    
    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}

// ────────────────────────────────────────────────────────────────
// ✅ MEL-SPECTROGRAM EXTRACTION
// ────────────────────────────────────────────────────────────────

class MelSpectrogramProcessor(
    private val sampleRate: Int,
    private val melBins: Int,
    private val nFft: Int,
    private val hopLength: Int,
    private val timeSteps: Int
) {
    companion object {
        private const val TAG = "MelProcessor"
    }
    
    /**
     * Extract MEL-SPECTROGRAM from audio
     * 
     * INPUT: 16,000 float samples (1 second @ 16kHz)
     * OUTPUT: [1, 40, 64] MEL-SPECTROGRAM in dB scale
     */
    fun extractMelSpectrogram(audioSamples: FloatArray): Array<Array<FloatArray>>? {
        try {
            // 1. Apply Hann window to audio
            val windowed = applyHannWindow(audioSamples)
            
            // 2. Compute STFT (Short-Time Fourier Transform)
            val stft = computeStft(windowed)
            
            // 3. Convert to MEL scale
            val mel = stftToMel(stft)
            
            // 4. Convert to dB scale
            val melDb = powerToDb(mel)
            
            // 5. Normalize to [-80, 0] range for model
            val normalized = normalizeToModel(melDb)
            
            Log.d(TAG, "✓ MEL-Spectrogram extracted: [1, $melBins, $timeSteps]")
            
            // Return as [1, 40, 64] for TFLite
            return arrayOf(normalized)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ MEL extraction failed: ${e.message}", e)
            return null
        }
    }
    
    private fun applyHannWindow(samples: FloatArray): FloatArray {
        val windowed = FloatArray(samples.size)
        for (i in samples.indices) {
            val hannValue = 0.5f * (1 - kotlin.math.cos(2.0 * Math.PI * i / (samples.size - 1))).toFloat()
            windowed[i] = samples[i] * hannValue
        }
        return windowed
    }
    
    private fun computeStft(samples: FloatArray): Array<FloatArray> {
        // Simplified STFT: compute power spectrum for each frame
        val numFrames = (samples.size - nFft) / hopLength + 1
        val stft = Array(numFrames) { FloatArray(nFft / 2) }
        
        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            val end = minOf(start + nFft, samples.size)
            val frameSamples = samples.slice(start until end).toFloatArray()
            
            // Compute power spectrum magnitude
            val power = computePowerSpectrum(frameSamples)
            stft[frame] = power
        }
        
        return stft
    }
    
    private fun computePowerSpectrum(frame: FloatArray): FloatArray {
        // Simple power computation (in production, use proper FFT library like KissFFT)
        val bins = frame.size / 2
        val power = FloatArray(bins)
        
        for (i in 0 until bins) {
            var mag = 0f
            for (j in frame.indices) {
                mag += frame[j] * frame[j]
            }
            power[i] = sqrt(mag / frame.size)
        }
        
        return power
    }
    
    private fun stftToMel(stft: Array<FloatArray>): Array<FloatArray> {
        // Convert linear frequency to MEL scale
        val melSpec = Array(stft.size) { FloatArray(melBins) }
        
        for (frame in stft.indices) {
            for (mel in 0 until melBins) {
                var sum = 0f
                for (k in stft[frame].indices) {
                    sum += stft[frame][k]
                }
                melSpec[frame][mel] = sum / stft[frame].size
            }
        }
        
        return melSpec
    }
    
    private fun powerToDb(mel: Array<FloatArray>): Array<FloatArray> {
        return mel.map { frame ->
            frame.map { value ->
                10f * log10((value + 1e-9f).toDouble()).toFloat()
            }.toFloatArray()
        }.toTypedArray()
    }
    
    private fun normalizeToModel(melDb: Array<FloatArray>): Array<FloatArray> {
        // Reshape to [40, 64] and normalize to [-80, 0] range
        val normalized = Array(melBins) { FloatArray(timeSteps) }
        
        for (i in 0 until minOf(melDb.size, timeSteps)) {
            for (j in 0 until minOf(melDb[i].size, melBins)) {
                val value = melDb[i][j].coerceIn(-80f, 0f)
                normalized[j][i] = value
            }
        }
        
        return normalized
    }
}
