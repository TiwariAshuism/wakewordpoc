# ✅ TFLite Kotlin Integration Checklist

## 1. UPDATE BUILD.GRADLE.KTS

**File:** `gradle/libs.versions.toml`

Add:
```toml
[versions]
tflite = "2.13.0"
tflite-support = "0.4.4"

[libraries]
tensorflow-lite = { group = "org.tensorflow", name = "tensorflow-lite", version.ref = "tflite" }
tensorflow-lite-support = { group = "org.tensorflow", name = "tensorflow-lite-support", version.ref = "tflite-support" }
```

Then in `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
}
```

---

## 2. CRITICAL: AUDIO FORMAT REQUIREMENTS

### ❌ CURRENT (WRONG):
```kotlin
setAudioSamplingRate(44100)                    // ❌ Wrong rate
setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // ❌ Wrong codec
setOutputFormat(MediaRecorder.OutputFormat.MPEG_4) // ❌ Wrong format
```

### ✅ CORRECT FOR TFLite:
```kotlin
setAudioSamplingRate(16000)                      // ✓ Model expects 16kHz
setAudioEncoder(MediaRecorder.AudioEncoder.PCM_16BIT) // ✓ Raw PCM
setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)    // ✓ or RAW
```

---

## 3. DATA FLOW PIPELINE

```
[AudioRecord: 16kHz PCM] 
    ↓ (16,000 samples = 1 second)
[Convert SHORT → FLOAT32]
    ↓ (normalize -1.0 to 1.0)
[Extract MEL-Spectrogram]
    ↓ (40 bins × 64 time steps)
[Input to TFLite model]
    ↓
[OUTPUT] → [Probability Float] (0.0 - 1.0)
```

---

## 4. MODEL INPUT/OUTPUT VALIDATION

### Expected Input Shape:
- **Shape:** `[1, 40, 64]`
- **Type:** `FLOAT32`
- **Range:** `[-80.0, 0.0]` (dB scale)
- **Size:** 1 × 40 × 64 × 4 bytes = **10.24 KB**

### Expected Output:
- **Shape:** `[1, 1]`
- **Type:** `FLOAT32`
- **Range:** `[0.0, 1.0]` (probability)

---

## 5. KEY CODE FIXES

### Audio Recording Fix:
```kotlin
// BEFORE: Recording AAC at 44.1kHz
recorder = MediaRecorder(this).apply {
    setAudioSource(MediaRecorder.AudioSource.MIC)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    setAudioSamplingRate(44100)
}

// AFTER: Recording PCM at 16kHz for TFLite
recorder = MediaRecorder(this).apply {
    setAudioSource(MediaRecorder.AudioSource.MIC)
    setOutputFormat(MediaRecorder.OutputFormat.RAW_AMR)  // or AMR_NB
    setAudioEncoder(MediaRecorder.AudioEncoder.PCM_16BIT)
    setAudioSamplingRate(16000)  // ✓ MUST be 16kHz
}
```

### MEL-Spectrogram Extraction (Missing!):
```kotlin
// You MUST convert PCM audio → MEL-SPECTROGRAM
private fun extractMelSpectrogram(pcmData: ShortArray): Array<Array<FloatArray>>? {
    try {
        // 1. Convert SHORT → FLOAT32 (-1.0 to 1.0)
        val float32Audio = FloatArray(pcmData.size) { i ->
            pcmData[i] / 32768f
        }
        
        // 2. Extract MEL-SPECTROGRAM (40 bins, 64 time steps)
        val mel = computeMelSpectrogram(float32Audio)
        
        // 3. Return as [1, 40, 64] for TFLite
        return arrayOf(mel)
    } catch (e: Exception) {
        return null
    }
}

private fun computeMelSpectrogram(audio: FloatArray): Array<FloatArray> {
    // TODO: Implement FFT → MEL conversion
    // Size: 40 MEL bins × 64 time steps
    return Array(40) { FloatArray(64) }
}
```

### TFLite Model Loading Fix:
```kotlin
// BEFORE: Loading Porcupine model
porcupineManager = PorcupineManager.Builder()
    .setAccessKey(accessKey)
    .setKeywordPaths(arrayOf(keywordPath))
    .build(applicationContext)

// AFTER: Loading TFLite model
interpreter = Interpreter(loadModelFile("model_student_int8.tflite"))
interpreter.allocate(4)  // 4 threads

// Input/Output buffers
val input = arrayOf(FloatArray(40 * 64))  // [1, 40, 64]
val output = mapOf(0 to FloatArray(1))     // [1, 1] probability
interpreter.runForMultipleInputsOutputs(arrayOf(input), output)
```

---

## 6. CRITICAL VALIDATION

### What to Check:
- [ ] Model file: `assets/model_student_int8.tflite` exists
- [ ] Input shape matches: `[1, 40, 64]`
- [ ] Audio sample rate: **16kHz** (not 44.1kHz)
- [ ] MEL-extraction implemented and tested
- [ ] TFLite interpreter properly allocated
- [ ] Output threshold set (e.g., > 0.5 = detection)

### Test Before Deployment:
```kotlin
// Load test audio
val testAudio = ShortArray(16000) { (Math.random() * 32768).toShort() }
val mel = extractMelSpectrogram(testAudio)

// Run inference
interpreter.runForMultipleInputsOutputs(arrayOf(mel), output)

// Check output
val probability = (output[0] as FloatArray)[0]
Log.d("TFLite", "Detection probability: $probability")
```

---

## 7. COMPARISON: PORCUPINE vs TFLite

| Aspect | Porcupine (Current) | TFLite (Target) |
|--------|-------------------|-----------------|
| **Model Size** | ~500KB .ppn | 400KB .tflite |
| **Audio Rate** | 16kHz (good) | 16kHz (match!) |
| **Data Format** | Custom | MEL-SPECTROGRAM |
| **Setup Complexity** | Simple (1 library) | More complex (FFT needed) |
| **Inference Speed** | ~50ms | ~15ms |
| **Cost** | Free tier + $$ | Free (on-device) |
| **Customization** | Limited | Full control |

---

## 8. NEXT STEPS

1. **Add TFLite dependencies** → Rebuild
2. **Create new class:** `TFLiteWakeWordDetector.kt`
3. **Implement MEL-extraction** → Test with sample audio
4. **Export models** from notebook → Place in assets/
5. **Update WakeWordService.kt** → Replace Porcupine with TFLite
6. **Test on device** → Check logcat for inference output

---

## SUMMARY OF REQUIRED CHANGES

```
WakeWordService.kt
├── Change: Audio sample rate from 44100 → 16000
├── Change: Audio encoder PCM_16BIT (was AAC)
├── Add: MEL-spectrogram extraction
├── Add: TFLite interpreter initialization
└── Change: Replace porcupineManager with TFLite detector

New Files:
├── TFLiteWakeWordDetector.kt
├── MelSpectrogramProcessor.kt
└── assets/model_student_int8.tflite
```
