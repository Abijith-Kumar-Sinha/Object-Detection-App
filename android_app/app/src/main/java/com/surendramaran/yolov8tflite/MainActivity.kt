package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent

    private var lastSpeakTime = 0L
    private val SPEAK_COOLDOWN = 2500L // ms (important for stability)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // -------- Text to Speech --------
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(1.0f)
            }
        }

        // -------- Speech Recognition --------
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        binding.viewFinder.setOnClickListener { startListening() }

        detector = Detector(this, Constants.MODEL_PATH, Constants.LABELS_PATH, this)
        detector.setup()

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) startCamera()
        else requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    // ---------------- CAMERA ----------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            bindCamera(providerFuture.get())
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val rotation = binding.viewFinder.display.rotation

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        val analyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analyzer.setAnalyzer(cameraExecutor) { image ->
            val bitmap = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(image.planes[0].buffer)
            image.close()

            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply {
                    postRotate(image.imageInfo.rotationDegrees.toFloat())
                }, true
            )

            detector.detect(rotated)
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analyzer
        )

        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    // ---------------- DETECTION (OPTION 3) ----------------

    override fun onDetect(boxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.setResults(boxes)
            binding.overlay.invalidate()

            if (boxes.isEmpty()) return@runOnUiThread

            val now = System.currentTimeMillis()
            if (now - lastSpeakTime < SPEAK_COOLDOWN) return@runOnUiThread

            val frameWidth = binding.viewFinder.width
            val spokenSet = mutableSetOf<String>()
            val phrases = mutableListOf<String>()

            for (box in boxes) {
                val label = box.clsName.lowercase()

                val centerX = ((box.x1 + box.x2) / 2f) * frameWidth

                val direction = when {
                    centerX < frameWidth * 0.35f -> "on the left"
                    centerX > frameWidth * 0.65f -> "on the right"
                    else -> "ahead"
                }

                val phrase = "$label $direction"

                if (phrase !in spokenSet) {
                    spokenSet.add(phrase)
                    phrases.add(phrase)
                }
            }

            if (phrases.isNotEmpty()) {
                val sentence = "I see " + phrases.joinToString(", ")
                speak(sentence)
                lastSpeakTime = now
            }
        }
    }

    override fun onEmptyDetect() {}

    // ---------------- VOICE ----------------

    private fun startListening() {
        speak("Listening")

        speechRecognizer.setRecognitionListener(object :
            android.speech.RecognitionListener {

            override fun onResults(results: Bundle) {
                val text = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: return

                handleVoiceCommand(text)
            }

            override fun onError(error: Int) {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(speechIntent)
    }

    private fun handleVoiceCommand(command: String) {
        when {
            command.contains("stop") -> tts.stop()
            command.contains("repeat") -> speak("Please move the camera to refresh the scene")
        }
    }

    private fun speak(text: String) {
        if (!tts.isSpeaking) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // ---------------- PERMISSIONS ----------------

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (it[Manifest.permission.CAMERA] == true) startCamera()
        }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
        tts.shutdown()
        speechRecognizer.destroy()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
