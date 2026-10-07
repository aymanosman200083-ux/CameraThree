package com.example.camerathree

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import androidx.compose.ui.geometry.Offset
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

val AccentYellow = Color(0xFFFFD60A)

enum class Look(val sat: Float, val con: Float, val warm: Float, val lift: Float) {
    S(1.22f, 1.05f, 0.00f, 4f),
    G(1.00f, 1.15f, -0.02f, -6f),
    IPHONE(1.08f, 1.00f, 0.05f, 10f)
}

fun colorMatrix(l: Look, night: Boolean): ColorMatrix {
    val s = ColorMatrix()
    s.setSaturation(l.sat)
    val c = l.con
    val t = (1f - c) * 128f + l.lift + (if (night) 8f else 0f)
    val m = ColorMatrix(floatArrayOf(
        c * (1f + l.warm), 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c * (1f - l.warm), 0f, t,
        0f, 0f, 0f, 1f, 0f))
    s.postConcat(m)
    return s
}

fun zoomLabel(v: Float, sel: Boolean, look: Look): String {
    val whole = v % 1f == 0f
    val base = if (whole) v.toInt().toString()
    else if (look == Look.IPHONE) "." + v.toString().substringAfter('.')
    else v.toString()
    return if (sel || look == Look.G) base + "x" else base
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val activity = this
        setContent {
            var granted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                )
            }
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted = it }
            LaunchedEffect(Unit) {
                if (!granted) launcher.launch(Manifest.permission.CAMERA)
            }
            if (granted) CameraApp() else Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

@Composable
fun RoundBtn(size: Dp, bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun Shutter(size: Dp, gap: Dp, ring: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).border(ring, Color.White, CircleShape)
            .padding(ring + gap).clip(CircleShape).background(Color.White)
            .clickable { onClick() }
    )
}

@Composable
fun ZoomRow(look: Look, zooms: List<Float>, zoom: Float, onPick: (Float) -> Unit) {
    val container = when (look) {
        Look.IPHONE -> Modifier
        Look.S -> Modifier.clip(CircleShape).background(Color(0x66000000)).padding(4.dp)
        Look.G -> Modifier.clip(CircleShape).background(Color(0x99000000)).padding(4.dp)
    }
    Row(
        container,
        horizontalArrangement = Arrangement.spacedBy(if (look == Look.IPHONE) 4.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (v in zooms) {
            val sel = abs(zoom - v) < 0.05f
            val bg = when (look) {
                Look.IPHONE -> if (sel) Color(0xAA000000) else Color.Transparent
                Look.S -> if (sel) Color(0x44FFFFFF) else Color.Transparent
                Look.G -> if (sel) Color.White else Color.Transparent
            }
            val fg = when (look) {
                Look.IPHONE -> if (sel) AccentYellow else Color.White
                Look.S -> Color.White
                Look.G -> if (sel) Color.Black else Color.White
            }
            var mod = Modifier.size(if (look == Look.G) 40.dp else 34.dp).clip(CircleShape).background(bg)
            if (sel && look == Look.S) mod = mod.border(1.5.dp, Color.White, CircleShape)
            Box(mod.clickable { onPick(v) }, contentAlignment = Alignment.Center) {
                Text(zoomLabel(v, sel, look), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
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
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(front) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            p.unbindAll()
            camera = p.bindToLifecycle(
                owner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture
            )
        }, ContextCompat.getMainExecutor(ctx))
    }
    LaunchedEffect(zoom, camera) {
        val z = camera?.cameraInfo?.zoomState?.value
        if (z != null) {
            camera?.cameraControl?.setZoomRatio(zoom.coerceIn(z.minZoomRatio, z.maxZoomRatio))
        }
    }
    LaunchedEffect(night, camera) {
        val info = camera?.cameraInfo
        if (info != null && info.exposureState.isExposureCompensationSupported) {
            camera?.cameraControl?.setExposureCompensationIndex(
                if (night) info.exposureState.exposureCompensationRange.upper / 2 else 0
            )
        }
    }

    val shoot = { takePhoto(ctx, capture, look, night) }
    val flip = { front = !front }
    val moonColor = if (night) AccentYellow else Color.White

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Spacer(Modifier.height(10.dp))

        // مفتاح التبديل S / G / تفاحة
        Row(
            Modifier.align(Alignment.CenterHorizontally)
                .clip(CircleShape).background(Color(0x33FFFFFF)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val items = listOf(Look.S to "S", Look.G to "G", Look.IPHONE to "🍎")
            for ((l, label) in items) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(if (look == l) Color.White else Color.Transparent)
                        .clickable { look = l; zoom = 1f },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        color = if (look == l) Color.Black else Color.White
                    )
                }
            }
        }

        // الشريط العلوي لكل واجهة
        when (look) {
            Look.IPHONE -> Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.clip(CircleShape).background(Color(0xCC2A2A2A))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) { Text("HEIC", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                RoundBtn(34.dp, Color(0xCC2A2A2A), {}) { Text("◉", color = AccentYellow, fontSize = 16.sp) }
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.clip(CircleShape).background(Color(0xCC2A2A2A))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("☾", color = moonColor, fontSize = 20.sp, modifier = Modifier.clickable { night = !night })
                    Text("⚡", color = Color.White, fontSize = 17.sp)
                    Text("⋯", color = Color.White, fontSize = 20.sp)
                }
            }
            Look.S -> Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚙", color = Color.White, fontSize = 19.sp)
                Text("⚡", color = Color.White, fontSize = 19.sp)
                Text("⏱", color = Color.White, fontSize = 19.sp)
                Text("3:4", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("☾", color = moonColor, fontSize = 21.sp, modifier = Modifier.clickable { night = !night })
                Text("✦", color = Color.White, fontSize = 19.sp)
            }
            Look.G -> Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚡", color = Color.White, fontSize = 20.sp)
                Text("☾", color = if (night) Color(0xFFA8C7FA) else Color.White, fontSize = 21.sp,
                    modifier = Modifier.clickable { night = !night })
                Text("⏲", color = Color.White, fontSize = 20.sp)
                Text("⚙", color = Color.White, fontSize = 20.sp)
            }
        }

        // المعاينة
        val corner = if (look == Look.G) 28.dp else 0.dp
        val hPad = if (look == Look.G) 8.dp else 0.dp
        val zooms = when (look) {
            Look.IPHONE -> listOf(0.5f, 1f, 2f, 4f, 8f)
            Look.S -> listOf(0.6f, 1f, 3f, 5f, 10f)
            Look.G -> listOf(0.5f, 1f, 2f, 5f)
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = hPad)
                .aspectRatio(3f / 4f).clip(RoundedCornerShape(corner))
        ) {
            AndroidView(
                factory = { previewView },
                update = { view ->
                    val paint = Paint()
                    paint.colorFilter = ColorMatrixColorFilter(colorMatrix(look, night))
                    view.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
                },
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, _, g, _ ->
                        zoom = (zoom * g).coerceIn(0.5f, 10f)
                    }
                }
            )
            if (look == Look.IPHONE) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val c = Color(0x55FFFFFF)
                    drawLine(c, Offset(w / 3f, 0f), Offset(w / 3f, h), 1.5f)
                    drawLine(c, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), 1.5f)
                    drawLine(c, Offset(0f, h / 3f), Offset(w, h / 3f), 1.5f)
                    drawLine(c, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), 1.5f)
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                ZoomRow(look, zooms, zoom) { zoom = it }
            }
        }

        // أزرار التحكم السفلية لكل واجهة
        Column(
            Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            when (look) {
                Look.IPHONE -> {
                    Shutter(78.dp, 5.dp, 3.dp) { shoot() }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        RoundBtn(46.dp, Color(0xFF3A3A3C), {}) {}
                        Row(
                            Modifier.clip(CircleShape).background(Color(0xFF1C1C1E)).padding(3.dp)
                        ) {
                            Box(Modifier.clip(CircleShape).padding(horizontal = 18.dp, vertical = 9.dp)) {
                                Text("VIDEO", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                Modifier.clip(CircleShape).background(Color(0xFF3A3A3C))
                                    .padding(horizontal = 18.dp, vertical = 9.dp)
                            ) {
                                Text("PHOTO", color = AccentYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        RoundBtn(46.dp, Color(0xFF1C1C1E), { flip() }) {
                            Text("⟳", color = Color.White, fontSize = 22.sp)
                        }
                    }
                }
                Look.S -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        val modes = listOf("Portrait", "Photo", "Video", "More")
                        for (m in modes) {
                            Text(
                                m, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = if (m == "Photo") Color(0xFFFFD54F) else Color(0xCCFFFFFF)
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        RoundBtn(50.dp, Color(0xFF333333), {}) {}
                        Shutter(76.dp, 4.dp, 3.dp) { shoot() }
                        RoundBtn(50.dp, Color(0x33FFFFFF), { flip() }) {
                            Text("⟳", color = Color.White, fontSize = 22.sp)
                        }
                    }
                }
                Look.G -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val modes = listOf("Night Sight", "Portrait", "Camera", "Video")
                        for (m in modes) {
                            val sel = m == "Camera"
                            Box(
                                Modifier.clip(CircleShape)
                                    .background(if (sel) Color(0xFFD3E3FD) else Color.Transparent)
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    m, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = if (sel) Color(0xFF041E49) else Color.White
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF333333)))
                        Shutter(86.dp, 5.dp, 4.dp) { shoot() }
                        RoundBtn(54.dp, Color(0xFF2B2B2B), { flip() }) {
                            Text("⟳", color = Color.White, fontSize = 23.sp)
                        }
                    }
                }
            }
        }
    }
}

fun takePhoto(ctx: android.content.Context, capture: ImageCapture, look: Look, night: Boolean) {
    val main = Handler(Looper.getMainLooper())
    capture.takePicture(
        Executors.newSingleThreadExecutor(),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bmp = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    val swap = rot == 90 || rot == 270
                    val w = if (swap) bmp.height else bmp.width
                    val h = if (swap) bmp.width else bmp.height
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val m = Matrix()
                    m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                    m.postRotate(rot.toFloat())
                    m.postTranslate(w / 2f, h / 2f)
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    paint.colorFilter = ColorMatrixColorFilter(colorMatrix(look, night))
                    Canvas(out).drawBitmap(bmp, m, paint)
                    val cv = ContentValues()
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, "CT_" + System.currentTimeMillis() + ".jpg")
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CameraThree")
                    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    ctx.contentResolver.openOutputStream(uri)!!.use {
                        out.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    main.post {
                        Toast.makeText(ctx, "تم الحفظ في DCIM/CameraThree", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    main.post {
                        Toast.makeText(ctx, "خطأ: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                main.post {
                    Toast.makeText(ctx, "فشل الالتقاط: " + exception.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    )
}
