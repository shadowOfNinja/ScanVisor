package com.example.scanvisorapp

import androidx.compose.ui.platform.LocalLifecycleOwner
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview // <-- MUST HAVE THIS FOR CAMERA PREVIEW
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import android.graphics.Matrix
import com.example.scanvisorapp.BuildConfig
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min


val VisorBlue = Color(0xFF00E5FF)
val VisorBlueGlow = Color(0x6600E5FF)
val VisorOrange = Color(0xFFFF9800)
val DarkHudBg = Color(0xD90A1118)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraPermissionGate {
                ScanVisorHUD()
            }
        }
    }
}

@Composable
fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasPermission = it }
    )

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) content() else Box(Modifier.fillMaxSize().background(Color.Black))
}

@Composable
fun ScanVisorHUD() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isPaused by remember { mutableStateOf(false) }
    var capturedFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var scanCompleted by remember { mutableStateOf(false) }

    val fillProgress = remember { Animatable(0f) }
    var resultText by remember { mutableStateOf("") }
    var isAnalyzing by remember { mutableStateOf(false) }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    fun cancelScan() {
        isScanning = false
        isPaused = false
        scanCompleted = false
        isAnalyzing = false
        capturedFrame = null
        resultText = ""
        coroutineScope.launch { fillProgress.snapTo(0f) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (scanCompleted) {
                            cancelScan()
                            return@detectTapGestures
                        }

                        // 1. Capture current frame & Pause feed
                        isScanning = true
                        imageCapture?.takePicture(
                            Executors.newSingleThreadExecutor(),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    // Apply sensor rotation degrees to keep the bitmap upright
                                    val rotationDegrees = image.imageInfo.rotationDegrees.toFloat()
                                    val rawBitmap = image.toBitmap()
                                    val rotatedBitmap = rawBitmap.rotate(rotationDegrees)

                                    capturedFrame = rotatedBitmap
                                    isPaused = true
                                    image.close()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    exception.printStackTrace()
                                }
                            }
                        )

                        // 2. Animate fill bar left-to-right over 2.5s
                        val fillJob = coroutineScope.launch {
                            fillProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 2500, easing = LinearEasing)
                            )
                        }

                        // Wait for release
                        val released = tryAwaitRelease()

                        if (!released || fillProgress.value < 1f) {
                            fillJob.cancel()
                            cancelScan()
                        } else {
                            // 3. Scan line reached 100% -> Run AI Vision Analysis
                            scanCompleted = true
                            isScanning = false
                            isAnalyzing = true

                            coroutineScope.launch {
                                capturedFrame?.let { bmp ->
                                    val text = analyzeFrameDirectHttp(bmp)
                                    resultText = text
                                    isAnalyzing = false
                                } ?: run {
                                    resultText = "ERROR >> Frame capture failed."
                                    isAnalyzing = false
                                }

                                /*resultText = "QUERYING SUPPORTED MODELS..."
                                val modelsList = fetchAvailableModels()
                                resultText = modelsList
                                isAnalyzing = false*/
                            }
                        }
                    }
                )
            }
    ) {
        // Camera Viewfinder or Frozen Image Frame
        if (isPaused && capturedFrame != null) {
            Image(
                bitmap = capturedFrame!!.asImageBitmap(),
                contentDescription = "Frozen Frame",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CameraFeed(onImageCaptureReady = { capture -> imageCapture = capture })
        }

        // HUD Vignette Mask (Dimmed Outside Center)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color.Black.copy(alpha = 0.55f))
            val reticleRadius = 140.dp.toPx()
            drawCircle(
                color = Color.Transparent,
                radius = reticleRadius,
                center = center,
                blendMode = BlendMode.Clear
            )
        }

        // Center Scanning Reticle & Fill Bar
        ScanReticleOverlay(
            progress = fillProgress.value,
            isScanning = isScanning,
            scanCompleted = scanCompleted,
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.Center)
        )

        // Bottom Lore Overlay Box
        if (scanCompleted) {
            LoreBoxOverlay(
                text = resultText,
                isLoading = isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
            )
        } else {
            Text(
                text = if (isScanning) "HOLD TO COMPLETE SCAN..." else "PRESS & HOLD TO SCAN TARGET",
                color = VisorBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 340.dp)
            )
        }
    }
}

@Composable
fun ScanReticleOverlay(
    progress: Float,
    isScanning: Boolean,
    scanCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    val currentColor = when {
        scanCompleted -> VisorOrange
        isScanning -> VisorBlue
        else -> VisorBlue.copy(alpha = 0.6f)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerPt = Offset(w / 2f, h / 2f)

        // Center Aiming Reticle Brackets
        val lineLen = 30f
        val stroke = 4f

        drawCircle(color = currentColor, radius = w * 0.38f, style = Stroke(width = 2f))

        // Horizontal Scanning Bar Fill (Underneath Reticle Center)
        val barTop = centerPt.y + (h * 0.18f)
        val barWidth = w * 0.7f
        val barLeft = centerPt.x - (barWidth / 2f)
        val barHeight = 12f

        // Bar background outline
        drawRect(
            color = currentColor.copy(alpha = 0.3f),
            topLeft = Offset(barLeft, barTop),
            size = Size(barWidth, barHeight),
            style = Stroke(width = 2f)
        )

        // Animated Left-To-Right Fill Progress
        if (progress > 0f) {
            drawRect(
                color = currentColor,
                topLeft = Offset(barLeft, barTop),
                size = Size(barWidth * progress, barHeight)
            )
        }
    }
}

@Composable
fun LoreBoxOverlay(text: String, isLoading: Boolean, modifier: Modifier = Modifier) {
    var animatedText by remember { mutableStateOf("") }

    LaunchedEffect(text) {
        animatedText = ""
        if (text.isNotEmpty()) {
            for (i in 1..text.length) {
                animatedText = text.take(i)
                delay(12)
            }
        }
    }

    Column(
        modifier = modifier
            .background(DarkHudBg, shape = RoundedCornerShape(8.dp))
            .border(2.dp, VisorOrange, shape = RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "SCAN LOG RESULT",
            color = VisorOrange,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (isLoading) {
            Text(
                text = "ANALYZING MOLECULAR STRUCTURE...",
                color = VisorBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        } else {
            Text(
                text = animatedText,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

fun Bitmap.rotate(degrees: Float): Bitmap {
    if (degrees == 0f) return this
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

@Composable
fun CameraFeed(onImageCaptureReady: (ImageCapture) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Use .setSurfaceProvider() method directly on Preview.Builder
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                onImageCaptureReady(capture)

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

fun scaleBitmapForAnalysis(bitmap: Bitmap, maxDimension: Int = 1080): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= maxDimension && height <= maxDimension) return bitmap

    val ratio = min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
    val newWidth = (width * ratio).toInt()
    val newHeight = (height * ratio).toInt()

    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

suspend fun analyzeFrameDirectHttp(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
    // List models in order of preference
    val candidateModels = listOf(
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.6-flash"
    )
    val apiKey = com.example.scanvisorapp.BuildConfig.GEMINI_API_KEY

    // Downscale frame to prevent massive Base64 payloads and HTTP timeouts
    val scaledBitmap = scaleBitmapForAnalysis(bitmap, maxDimension = 1080)
    val byteArrayOutputStream = ByteArrayOutputStream()
    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, byteArrayOutputStream)
    val base64Image = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)

    val promptText = "You are the Scan Visor from the Metroid Prime video game series, operating for Samus Aran. When given an object name or description, you must analyze it and return a classified database entry.\n" +
            "\n" +
            " \n" +
            "\n" +
            "You must categorize the target strictly using one of the following classification groups based on these rules:\n" +
            "\n" +
            "- BIOFORM: Creatures, sentient entities, or living biological organisms (e.g., animals, plants, humans).\n" +
            "\n" +
            "- MECHANOID: Purely mechanical constructs or artificial drones operating on pre-programmed logic.\n" +
            "\n" +
            "- BIO-MECHANOID: A fusion of organic tissue and mechanical hardware (cyborgs, engineered horrors).\n" +
            "\n" +
            "- XENOTECH: Advanced infrastructure, machinery, legacy technology, or digital systems.\n" +
            "\n" +
            "- STRUCTURAL MATERIAL: Environmental assets like walls, shields, or physical barriers.\n" +
            "\n" +
            "- ENERGY MATRIX: Fields, force fields, raw volatile materials, or power sources.\n" +
            "\n" +
            " \n" +
            "\n" +
            "Output Format Requirements:\n" +
            "\n" +
            "You must strictly follow this Markdown structure:\n" +
            "\n" +
            " \n" +
            "\n" +
            "### **DATA ENTRY: [CATEGORY GROUP]**\n" +
            "\n" +
            " \n" +
            "\n" +
            "> [A 1-2 sentence concise operational summary of the target.]\n" +
            "\n" +
            " \n" +
            "\n" +
            "[sentence detailing behavioral, functional, or mechanical traits.]\n" +
            "\n" +
            "[sentence detailing vulnerabilities, composition, or operational state.]\n" +
            "\n" +
            "[sentence  detailing tactical utility or interaction context.]\n" +
            "\n" +
            " \n" +
            "\n" +
            "Maintain a detached, analytical, clinical sci-fi tone suitable for a powered armor HUD. Do not break character."

    val jsonPayload = JSONObject().apply {
        put("contents", JSONArray().apply {
            put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", promptText) })
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                })
            })
        })
    }

    var lastErrorMessage = ""

    for (modelName in candidateModels) {
        for (attempt in 1..2) {
            try {
                val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 20000 // 20s timeout limit
                    readTimeout = 20000
                }

                OutputStreamWriter(connection.outputStream).use { it.write(jsonPayload.toString()) }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseText)
                    val candidates = jsonResponse.getJSONArray("candidates")
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    return@withContext parts.getJSONObject(0).getString("text")
                }

                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                lastErrorMessage = "HTTP $responseCode ($modelName) >> $errorText"

                if (responseCode == 503) {
                    delay(1500L * attempt)
                    continue
                } else if (responseCode == 404) {
                    break // Move to next model candidate if endpoint is missing
                } else {
                    return@withContext lastErrorMessage
                }
            } catch (e: Exception) {
                lastErrorMessage = "SCAN TIMEOUT / FAILURE ($modelName) >> ${e.localizedMessage}"
                delay(1000L) // Brief pause before retry on socket timeout
            }
        }
    }

    return@withContext lastErrorMessage
}

suspend fun fetchAvailableModels(): String = withContext(Dispatchers.IO) {
    try {
        val apiKey = com.example.scanvisorapp.BuildConfig.GEMINI_API_KEY
        val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"

        val url = URL(endpointUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(responseText)
            val modelsArray = jsonResponse.optJSONArray("models") ?: return@withContext "NO MODELS FOUND"

            val modelNames = mutableListOf<String>()
            for (i in 0 until modelsArray.length()) {
                val modelObj = modelsArray.getJSONObject(i)
                val name = modelObj.getString("name") // Format: "models/gemini-2.5-flash"
                val methods = modelObj.optJSONArray("supportedGenerationMethods")

                // Keep models that support generateContent
                if (methods != null) {
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j) == "generateContent") {
                            modelNames.add(name.replace("models/", ""))
                            break
                        }
                    }
                }
            }
            return@withContext "AVAILABLE MODELS:\n" + modelNames.joinToString("\n")
        } else {
            val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No details"
            return@withContext "FETCH FAILED [HTTP $responseCode]:\n$errorText"
        }
    } catch (e: Exception) {
        return@withContext "DIAGNOSTIC ERROR: ${e.localizedMessage}"
    }
}

suspend fun analyzeFrameWithGemini(bitmap: Bitmap): String {
    return try {
        val generativeModel = GenerativeModel(
            modelName = "gemini-1.5-flash-8b",
            apiKey = com.example.scanvisorapp.BuildConfig.GEMINI_API_KEY
        )

        val prompt = "You are the Scan Visor from the Metroid Prime video game series, operating for Samus Aran. When given an object name or description, you must analyze it and return a classified database entry.\n" +
                "\n" +
                " \n" +
                "\n" +
                "You must categorize the target strictly using one of the following classification groups based on these rules:\n" +
                "\n" +
                "- BIOFORM: Creatures, sentient entities, or living biological organisms (e.g., animals, plants, humans).\n" +
                "\n" +
                "- MECHANOID: Purely mechanical constructs or artificial drones operating on pre-programmed logic.\n" +
                "\n" +
                "- BIO-MECHANOID: A fusion of organic tissue and mechanical hardware (cyborgs, engineered horrors).\n" +
                "\n" +
                "- XENOTECH: Advanced infrastructure, machinery, legacy technology, or digital systems.\n" +
                "\n" +
                "- STRUCTURAL MATERIAL: Environmental assets like walls, shields, or physical barriers.\n" +
                "\n" +
                "- ENERGY MATRIX: Fields, force fields, raw volatile materials, or power sources.\n" +
                "\n" +
                " \n" +
                "\n" +
                "Output Format Requirements:\n" +
                "\n" +
                "You must strictly follow this Markdown structure:\n" +
                "\n" +
                " \n" +
                "\n" +
                "### **DATA ENTRY: [CATEGORY GROUP]**\n" +
                "\n" +
                " \n" +
                "\n" +
                "> [A 1-2 sentence concise operational summary of the target.]\n" +
                "\n" +
                " \n" +
                "\n" +
                "[sentence detailing behavioral, functional, or mechanical traits.]\n" +
                "\n" +
                "[sentence detailing vulnerabilities, composition, or operational state.]\n" +
                "\n" +
                "[sentence  detailing tactical utility or interaction context.]\n" +
                "\n" +
                " \n" +
                "\n" +
                "Maintain a detached, analytical, clinical sci-fi tone suitable for a powered armor HUD. Do not break character."

        val response = generativeModel.generateContent(
            content {
                image(bitmap)
                text(prompt)
            }
        )

        response.text ?: "NO DATA RETURNED FROM SCAN LOG."
    } catch (e: Exception) {
        "SCAN FAILURE >> ${e.localizedMessage ?: "Unknown Error"}"
    }
}