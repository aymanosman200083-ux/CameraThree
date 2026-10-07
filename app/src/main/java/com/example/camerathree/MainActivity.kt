package com.example.camerathree

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs

enum class Look(val sat: Float, val con: Float, val warm: Float, val lift: Float) {
    S(1.22f, 1.05f, 0.00f, 4f),
    G(1.00f, 1.15f, -0.02f, -6f),
    IPHONE(1.08f, 1.00f, 0.05f, 10f)
}

data class UiStyle(
    val modes: List<String>, val active: Int, val accent: Color,
    val zooms: List<Float>, val corner: Dp, val top: List<String>, val shutter: Dp
)

fun styleOf(l: Look) = when (l) {
    Look.S -> UiStyle(listOf("NIGHT", "VIDEO", "PHOTO", "PORTRAIT"), 2, Color.White,
        listOf(0.5f, 1f, 2f), 0.dp, listOf("⚡", "⏱", "3:4", "🌙"), 72.dp)
    Look.G -> UiStyle(listOf("Video", "Camera", "Portrait", "Night Sight"), 1, Color(0xFF8AB4F8),
        listOf(0.7f, 1f, 2f), 24.dp, listOf("⚡", "⏲", "⚙", "🌙"), 80.dp)
    Look.IPHONE -> UiStyle(listOf("VIDEO", "PHOTO", "PORTRAIT"), 1, Color(0xFFFFD60A),
        listOf(0.5f, 1f, 2f, 3f), 0.dp, listOf("⚡", "⌃", "◎", "🌙"), 76.dp)
}

fun colorMatrix(l: Look, night: Boolean): ColorMatrix {
    val s = ColorMatrix().apply { setSaturation(l.sat) }
    val c = l.con
    val t = (1f - c) * 128f + l.lift + if (night) 8f else 0f
    val m = ColorMatrix(floatArrayOf(
        c * (1 + l.warm), 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c * (1 - l.warm), 0f, t,
        0f, 0f, 0f, 1f, 0f))
    s.postConcat(m)
    return s
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var granted by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
            if (granted) CameraApp() else Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

@Composable
fun CameraApp() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var look by remember { mutableStateOf(Look.IPHONE) }
    var front by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val st = styleOf(look)
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    LaunchedEffect(front) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            p.unbindAll()
            camera = p.bindToLifecycle(owner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview, capture)
        }, ContextCompat.getMainExecutor(ctx))
    }
    LaunchedEffect(zoom, camera) {
        val z = camera?.cameraInfo?.zoomState?.value
        if (z != null) camera?.cameraControl?.setZoomRatio(zoom.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }
    LaunchedEffect(night, camera) {
        val info = camera?.cameraInfo
        if (info != null && info.exposureState.isExposureCompensationSupported) {
            camera?.cameraControl?.setExposureCompensationIndex(
                if (night) info.exposureState.exposureCompensationRange.upper / 2 else 0)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            update = {
                val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix(look, night)) }
                it.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
            },
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(st.corner))
                .pointerInput(Unit) { detectTransformGestures { _, _, g, _ -> zoom = (zoom * g).coerceIn(0.5f, 10f) } }
        )

        Row(Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
            .clip(CircleShape).background(Color(0x66000000)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Look.S to "S", Look.G to "G", Look.IPHONE to "🍎").forEach { (l, label) ->
                Box(Modifier.size(40.dp).clip(CircleShape)
                    .background(if (look == l) Color.White else Color.Transparent)
                    .clickable { look = l },
                    contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = if (look == l) Color.Black else Color.White)
                }
            }
        }

        Row(Modifier.align(Alignment.TopCenter).padding(top = 92.dp).fillMaxWidth().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            st.top.forEach { icon ->
                Text(icon, color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.clickable { if (icon == "🌙") night = !night })
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                st.zooms.forEach { v ->
                    val on = abs(zoom - v) < 0.05f
                    Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0x66000000))
                        .clickable { zoom = v }, contentAlignment = Alignment.Center) {
                        Text(if (v % 1f == 0f) "${v.toInt()}x" else "$v", fontSize = 13.sp,
                            color = if (on) st.accent else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                st.modes.forEachIndexed { i, m ->
                    Text(m, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = if (i == st.active) st.accent else Color(0xAAFFFFFF))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF333333)))
                Box(Modifier.size(st.shutter).border(4.dp, Color.White, CircleShape)
                    .padding(6.dp).clip(CircleShape).background(Color.White)
                    .clickable { takePhoto(ctx, capture, look, night) })
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x66FFFFFF))
                    .clickable { front = !front }, contentAlignment = Alignment.Center) {
                    Text("⟳", color = Color.White, fontSize = 24.sp)
                }
            }
        }
    }
}

fun takePhoto(ctx: android.content.Context, capture: ImageCapture, look: Look, night: Boolean) {
    val main = Handler(Looper.getMainLooper())
    capture.takePicture(Executors.newSingleThreadExecutor(), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                val bmp = image.toBitmap()
                val rot = image.imageInfo.rotationDegrees
                image.close()
                val swap = rot == 90 || rot == 270
                val w = if (swap) bmp.height else bmp.width
                val h = if (swap) bmp.width else bmp.height
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val m = Matrix().apply {
                    postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                    postRotate(rot.toFloat())
                    postTranslate(w / 2f, h / 2f)
                }
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colorMatrix(look, night)) }
                Canvas(out).drawBitmap(bmp, m, paint)
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "CT_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CameraThree")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                main.post { Toast.makeText(ctx, "تم الحفظ في DCIM/CameraThree", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                main.post { Toast.makeText(ctx, "خطأ: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
        override fun onError(e: ImageCaptureException) {
            main.post { Toast.makeText(ctx, "فشل الالتقاط: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    })
}
