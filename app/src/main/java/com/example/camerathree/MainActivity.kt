package com.example.camerathree

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
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
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Yellow = Color(0xFFFFD60A)
val SBlue = Color(0xFF2F7BFF)
val GYellow = Color(0xFFF7C04A)
val HEADER = 108.dp
val RATIOS = listOf(3f / 4f, 9f / 16f, 1f)
val RATIO_LABELS = listOf("3:4", "9:16", "1:1")
val EFFECT_NAMES = listOf("تلقائي", "زاهي", "ناعم")

enum class Look(val sat: Float, val con: Float, val warm: Float, val lift: Float) {
    S(1.22f, 1.05f, 0.00f, 4f),
    G(1.00f, 1.15f, -0.02f, -6f),
    IPHONE(1.08f, 1.00f, 0.05f, 10f)
}

// معاينة تقريبية فقط، المعالجة الكاملة بتتطبق على الصورة المحفوظة
fun colorMatrix(l: Look, night: Boolean, effect: Int): ColorMatrix {
    val s = ColorMatrix()
    val k = if (effect == 1) 1.25f else if (effect == 2) 0.85f else 1f
    s.setSaturation(l.sat * k)
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

// ---------- معالجة الصورة الكاملة ----------
class Params(
    val target: Double, val contrast: Double, val lift: Double, val black: Double,
    val hlStart: Double, val hlSlope: Double, val sat: Float, val vib: Float,
    val wbR: Float, val wbG: Float, val wbB: Float, val clarity: Float, val sharp: Float
)

fun paramsOf(l: Look, effect: Int, hdr: Boolean): Params {
    var b = when (l) {
        Look.IPHONE -> Params(0.47, 0.18, 0.05, 0.0, 0.82, 0.75, 0.12f, 0.6f, 1.03f, 1.0f, 0.97f, 0.15f, 0.35f)
        Look.S -> Params(0.50, 0.30, 0.04, 0.0, 0.90, 0.90, 0.24f, 0.3f, 1.0f, 1.0f, 1.03f, 0.12f, 0.70f)
        Look.G -> Params(0.43, 0.38, 0.0, 0.03, 0.80, 0.70, 0.06f, 0.5f, 0.99f, 1.0f, 1.02f, 0.45f, 0.45f)
    }
    if (effect == 1) {
        b = Params(b.target, b.contrast + 0.10, b.lift, b.black, b.hlStart, b.hlSlope,
            b.sat * 1.6f + 0.06f, b.vib, b.wbR, b.wbG, b.wbB, b.clarity, b.sharp)
    } else if (effect == 2) {
        b = Params(b.target, b.contrast - 0.08, b.lift + 0.02, b.black, b.hlStart, b.hlSlope,
            b.sat * 0.7f, b.vib, b.wbR, b.wbG, b.wbB, b.clarity * 0.4f, b.sharp * 0.4f)
    }
    if (hdr) {
        b = Params(b.target + 0.02, b.contrast - 0.04, b.lift + 0.05, b.black, b.hlStart, b.hlSlope * 0.8,
            b.sat, b.vib, b.wbR, b.wbG, b.wbB, b.clarity * 1.3f, b.sharp)
    }
    return b
}

fun lumOf(c: Int): Float =
    0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)

fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(w * h)
    val out = FloatArray(w * h)
    val d = (2 * r + 1).toFloat()
    for (y in 0 until h) {
        val row = y * w
        var acc = 0f
        for (i in -r..r) acc += a[row + i.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            tmp[row + x] = acc / d
            acc += a[row + minOf(x + r + 1, w - 1)] - a[row + maxOf(x - r, 0)]
        }
    }
    for (x in 0 until w) {
        var acc = 0f
        for (i in -r..r) acc += tmp[i.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) {
            out[y * w + x] = acc / d
            acc += tmp[minOf(y + r + 1, h - 1) * w + x] - tmp[maxOf(y - r, 0) * w + x]
        }
    }
    return out
}

fun enhance(bmp: Bitmap, look: Look, night: Boolean, effect: Int, hdr: Boolean) {
    val p = paramsOf(look, effect, hdr)
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)

    var sum = 0.0
    var n = 0
    var i = 0
    while (i < px.size) {
        sum += lumOf(px[i])
        n++
        i += 37
    }
    val mean = (sum / n / 255.0).coerceIn(0.05, 0.95)
    val target = p.target + (if (night) 0.05 else 0.0)
    val gamma = (Math.log(target) / Math.log(mean)).coerceIn(0.7, 1.4)

    val maxV = p.hlStart + (1.0 - p.hlStart) * p.hlSlope
    val lut = IntArray(256)
    for (x in 0..255) {
        var v = Math.pow(x / 255.0, gamma)
        val s = v * v * (3.0 - 2.0 * v)
        v += (s - v) * p.contrast
        v += p.lift * (1.0 - v) * (1.0 - v)
        v = (v - p.black) / (1.0 - p.black)
        if (v > p.hlStart) v = p.hlStart + (v - p.hlStart) * p.hlSlope
        v /= maxV
        lut[x] = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
    }

    val sw = w / 4
    val sh = h / 4
    var small = FloatArray(sw * sh)
    for (sy in 0 until sh) {
        for (sx in 0 until sw) {
            var a = 0f
            for (dy in 0..3) {
                val row = (sy * 4 + dy) * w + sx * 4
                for (dx in 0..3) a += lumOf(px[row + dx])
            }
            small[sy * sw + sx] = a / 16f
        }
    }
    small = boxBlur(small, sw, sh, 12)

    val res = IntArray(w * h)
    for (y in 0 until h) {
        val fy = ((y + 0.5f) / 4f - 0.5f).coerceIn(0f, (sh - 1).toFloat())
        val y0 = fy.toInt()
        val y1 = minOf(y0 + 1, sh - 1)
        val ty = fy - y0
        for (x in 0 until w) {
            val idx = y * w + x
            val c = px[idx]
            var r = minOf(255f, ((c shr 16) and 255) * p.wbR)
            var g = minOf(255f, ((c shr 8) and 255) * p.wbG)
            var b = minOf(255f, (c and 255) * p.wbB)

            val dl = if (x > 0) idx - 1 else idx
            val dr = if (x < w - 1) idx + 1 else idx
            val du = if (y > 0) idx - w else idx
            val dd = if (y < h - 1) idx + w else idx
            val rawL = lumOf(c)
            val detail = 4f * rawL - lumOf(px[dl]) - lumOf(px[dr]) - lumOf(px[du]) - lumOf(px[dd])

            val fx = ((x + 0.5f) / 4f - 0.5f).coerceIn(0f, (sw - 1).toFloat())
            val x0 = fx.toInt()
            val x1 = minOf(x0 + 1, sw - 1)
            val tx = fx - x0
            val top = small[y0 * sw + x0] * (1f - tx) + small[y0 * sw + x1] * tx
            val bot = small[y1 * sw + x0] * (1f - tx) + small[y1 * sw + x1] * tx
            val blur = top * (1f - ty) + bot * ty

            var add = (rawL - blur) * p.clarity + detail * p.sharp * 0.25f
            add = add.coerceIn(-40f, 40f)
            r = (r + add).coerceIn(0f, 255f)
            g = (g + add).coerceIn(0f, 255f)
            b = (b + add).coerceIn(0f, 255f)

            r = lut[r.toInt()].toFloat()
            g = lut[g.toInt()].toFloat()
            b = lut[b.toInt()].toFloat()

            val mx = maxOf(r, maxOf(g, b))
            val mn = minOf(r, minOf(g, b))
            val sat = if (mx > 0f) (mx - mn) / mx else 0f
            val f = 1f + p.sat * (1f - p.vib * sat)
            val ll = 0.299f * r + 0.587f * g + 0.114f * b
            val ro = (ll + (r - ll) * f).coerceIn(0f, 255f).toInt()
            val go = (ll + (g - ll) * f).coerceIn(0f, 255f).toInt()
            val bo = (ll + (b - ll) * f).coerceIn(0f, 255f).toInt()
            res[idx] = (255 shl 24) or (ro shl 16) or (go shl 8) or bo
        }
    }
    bmp.setPixels(res, 0, w, 0, 0, w, h)
}

fun flashLabel(f: Int): String = when (f) { 0 -> "⚡A"; 1 -> "⚡"; else -> "⚡✕" }
fun timerLabel(t: Int): String = if (t == 0) "⏱" else "⏱" + t

fun zoomText(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() + "x" else v.toString() + "x"

fun loadThumb(ctx: Context, uri: Uri): Bitmap? {
    return try {
        val o = BitmapFactory.Options()
        o.inSampleSize = 8
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    } catch (e: Exception) { null }
}

fun openGallery(ctx: Context, uri: Uri?) {
    try {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "لا يوجد تطبيق معرض", Toast.LENGTH_SHORT).show()
    }
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
                ActivityResultContracts.RequestMultiplePermissions()
            ) { res -> granted = res[Manifest.permission.CAMERA] == true }
            LaunchedEffect(Unit) {
                val audioOk = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                if (!granted || !audioOk) {
                    launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                }
            }
            if (granted) CameraApp() else Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

// ---------- مكونات مشتركة ----------
@Composable
fun Ic(t: String, color: Color = Color.White, size: Int = 19, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).clickable { onClick() }.padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(t, color = color, fontSize = size.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun IconBtn(icon: ImageVector, tint: Color, size: Dp = 24.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size)) }
}

@Composable
fun FlashBtn(flash: Int, onClick: () -> Unit) {
    val icon = when (flash) {
        0 -> Icons.Filled.FlashAuto
        1 -> Icons.Filled.FlashOn
        else -> Icons.Filled.FlashOff
    }
    IconBtn(icon, if (flash == 1) Yellow else Color.White) { onClick() }
}

@Composable
fun TimerBtn(timer: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Timer, null, tint = if (timer > 0) Yellow else Color.White, modifier = Modifier.size(24.dp))
        if (timer > 0) {
            Text(
                timer.toString(), color = Yellow, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
            )
        }
    }
}

@Composable
fun BoxedLabel(text: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, tint, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) { Text(text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun RingIcon(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Color(0xCC1C1C1E))
            .border(1.dp, Color(0xFF4A4A4C), CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
fun LookSwitcher(look: Look, onPick: (Look) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color(0xFF111111))
            .border(1.dp, Color(0xFF3A3A3C), CircleShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val items = listOf(Look.S to "S", Look.G to "G", Look.IPHONE to "iPhone")
        for ((l, label) in items) {
            val sel = look == l
            val bg = if (!sel) Color.Transparent else when (l) {
                Look.S -> SBlue
                Look.G -> GYellow
                Look.IPHONE -> Color(0xFFD1D1D6)
            }
            val fg = if (!sel) Color.White else if (l == Look.S) Color.White else Color.Black
            Box(
                Modifier.clip(CircleShape).background(bg).clickable { onPick(l) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) { Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun Thumb(bmp: Bitmap?, size: Dp, shape: Shape, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(shape).background(Color(0xFF333333)).clickable { onClick() }) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun ShutterBtn(size: Dp, ring: Dp, video: Boolean, rec: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).border(ring, Color.White, CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val inner = if (rec) size * 0.42f else size - ring * 2 - 8.dp
        val shape: Shape = if (rec) RoundedCornerShape(8.dp) else CircleShape
        Box(
            Modifier.size(inner).clip(shape)
                .background(if (video) Color(0xFFFF3B30) else Color.White)
        )
    }
}

@Composable
fun ModeText(label: String, sel: Boolean, selColor: Color, size: Int, onClick: () -> Unit) {
    Box(Modifier.clickable { onClick() }.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(
            label, color = if (sel) selColor else Color(0xFFDDDDDD), fontSize = size.sp,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun ModePill(label: String, sel: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (sel) GYellow else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label, color = if (sel) Color.Black else Color.White,
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ZoomRow(look: Look, zooms: List<Float>, zoom: Float, onPick: (Float) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color(0x99000000)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (v in zooms) {
            val sel = abs(zoom - v) < 0.05f
            var mod = Modifier.size(40.dp).clip(CircleShape)
            if (sel) {
                mod = if (look == Look.G) mod.background(GYellow)
                else mod.background(Color(0xFF111111)).border(1.5.dp, Color.White, CircleShape)
            }
            val fg = if (sel && look == Look.G) Color.Black else Color.White
            Box(mod.clickable { onPick(v) }, contentAlignment = Alignment.Center) {
                Text(zoomText(v), color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SettingRow(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.width(220.dp).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            if (on) "تشغيل" else "إيقاف",
            color = if (on) Yellow else Color(0xFF8E8E93), fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SettingsPanel(
    grid: Boolean, hq: Boolean, mirror: Boolean,
    onGrid: () -> Unit, onHq: () -> Unit, onMirror: () -> Unit, modifier: Modifier
) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xEE1C1C1E)).padding(vertical = 6.dp)) {
        SettingRow("خطوط الشبكة", grid, onGrid)
        SettingRow("جودة عالية", hq, onHq)
        SettingRow("عكس السيلفي", mirror, onMirror)
    }
}

@Composable
fun CameraApp() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var look by remember { mutableStateOf(Look.IPHONE) }
    var front by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var hdr by remember { mutableStateOf(false) }
    var pro by remember { mutableStateOf(false) }
    var ev by remember { mutableStateOf(0) }
    var zoom by remember { mutableStateOf(1f) }
    var flash by remember { mutableStateOf(0) }
    var timer by remember { mutableStateOf(0) }
    var grid by remember { mutableStateOf(false) }
    var ratioIdx by remember { mutableStateOf(0) }
    var hq by remember { mutableStateOf(true) }
    var mirror by remember { mutableStateOf(false) }
    var effect by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var isVideo by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recSeconds by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf(0) }
    var focusPt by remember { mutableStateOf<Offset?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val videoCapture = remember {
        val rec = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        VideoCapture.withOutput(rec)
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
            val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            camera = try {
                p.bindToLifecycle(owner, sel, preview, capture, videoCapture)
            } catch (e: Exception) {
                p.unbindAll()
                p.bindToLifecycle(owner, sel, preview, capture)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }
    LaunchedEffect(zoom, camera) {
        val z = camera?.cameraInfo?.zoomState?.value
        if (z != null) {
            camera?.cameraControl?.setZoomRatio(zoom.coerceIn(z.minZoomRatio, z.maxZoomRatio))
        }
    }
    LaunchedEffect(night, camera, pro, ev) {
        val info = camera?.cameraInfo
        if (info != null && info.exposureState.isExposureCompensationSupported) {
            val r = info.exposureState.exposureCompensationRange
            val idx = (if (pro) ev else 0) + (if (night) r.upper / 2 else 0)
            camera?.cameraControl?.setExposureCompensationIndex(idx.coerceIn(r.lower, r.upper))
        }
    }
    LaunchedEffect(flash) {
        capture.flashMode = when (flash) {
            0 -> ImageCapture.FLASH_MODE_AUTO
            1 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }
    LaunchedEffect(recording) {
        recSeconds = 0
        while (recording != null) {
            delay(1000)
            recSeconds++
        }
    }
    LaunchedEffect(focusPt) {
        if (focusPt != null) {
            delay(1200)
            focusPt = null
        }
    }
    LaunchedEffect(lastUri) {
        val u = lastUri
        if (u != null) {
            thumb = withContext(Dispatchers.IO) { loadThumb(ctx, u) }
        }
    }

    val ratio = RATIOS[ratioIdx]
    val full = ratio < 0.6f
    val zooms = when (look) {
        Look.G -> listOf(0.5f, 1f, 2f, 5f)
        else -> listOf(0.5f, 1f, 2f)
    }
    val previewMod = if (full) Modifier.fillMaxSize()
    else Modifier.padding(top = HEADER).fillMaxWidth().aspectRatio(ratio)

    val isRec = recording != null
    val unavailable: (String) -> Unit = { name ->
        Toast.makeText(ctx, "وضع " + name + " غير متاح حالياً", Toast.LENGTH_SHORT).show()
    }

    val doCapture: () -> Unit = {
        takePhoto(ctx, capture, look, night, effect, hdr, ratio, hq, mirror && front) { uri -> lastUri = uri }
    }
    val onShutter: () -> Unit = {
        if (isVideo) {
            val r = recording
            if (r != null) {
                r.stop()
                recording = null
            } else {
                recording = startRecording(ctx, videoCapture) { recording = null }
            }
        } else if (countdown == 0) {
            if (timer > 0) {
                scope.launch {
                    for (i in timer downTo 1) {
                        countdown = i
                        delay(1000)
                    }
                    countdown = 0
                    doCapture()
                }
            } else {
                doCapture()
            }
        }
    }
    val onFlip: () -> Unit = { if (!isRec) front = !front }
    val cycleTimer: () -> Unit = { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
    val cycleEffect: () -> Unit = {
        effect = (effect + 1) % 3
        Toast.makeText(ctx, "التأثير: " + EFFECT_NAMES[effect], Toast.LENGTH_SHORT).show()
    }
    val modePhoto: () -> Unit = { if (!isRec) { isVideo = false; night = false; pro = false } }
    val modeVideo: () -> Unit = { if (!isRec) { isVideo = true; pro = false } }
    val modePro: () -> Unit = { if (!isRec) { isVideo = false; night = false; pro = true } }
    val modeNight: () -> Unit = { if (!isRec) { isVideo = false; pro = false; night = !night } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            update = { view ->
                val paint = Paint()
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix(look, night, effect))
                view.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
            },
            modifier = previewMod
        )

        Box(
            previewMod
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, g, _ ->
                        zoom = (zoom * g).coerceIn(0.5f, 10f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { off ->
                        focusPt = off
                        try {
                            val pt = previewView.meteringPointFactory.createPoint(off.x, off.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                        } catch (e: Exception) {
                        }
                    })
                }
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                if (grid) {
                    val w = size.width
                    val h = size.height
                    val c = Color(0x88FFFFFF)
                    drawLine(c, Offset(w / 3f, 0f), Offset(w / 3f, h), 1.5f)
                    drawLine(c, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), 1.5f)
                    drawLine(c, Offset(0f, h / 3f), Offset(w, h / 3f), 1.5f)
                    drawLine(c, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), 1.5f)
                }
                val fp = focusPt
                if (fp != null) {
                    val s = 70.dp.toPx()
                    if (look == Look.IPHONE) {
                        drawRect(Yellow, Offset(fp.x - s / 2f, fp.y - s / 2f), Size(s, s), style = Stroke(2.dp.toPx()))
                    } else {
                        drawCircle(Color.White, s / 2f, fp, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            // ---------- الشريط العلوي ----------
            Box(Modifier.fillMaxWidth().height(HEADER)) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) {
                    LookSwitcher(look) { l -> look = l; zoom = 1f; expanded = false; showSettings = false }
                }
                when (look) {
                    Look.IPHONE -> {
                        Box(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 8.dp)) {
                            val fi = when (flash) {
                                0 -> Icons.Filled.FlashAuto
                                1 -> Icons.Filled.FlashOn
                                else -> Icons.Filled.FlashOff
                            }
                            RingIcon(fi, if (flash == 1) Yellow else Color.White) { flash = (flash + 1) % 3 }
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 8.dp)) {
                            RingIcon(Icons.Filled.MotionPhotosOn, if (night) Yellow else Color.White) { night = !night }
                        }
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 58.dp)) {
                            IconBtn(
                                if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                Color(0xFFBDBDBD), 22.dp
                            ) { expanded = !expanded }
                        }
                    }
                    Look.S -> Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(50.dp).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBtn(Icons.Outlined.Settings, Color.White) { showSettings = !showSettings }
                        FlashBtn(flash) { flash = (flash + 1) % 3 }
                        BoxedLabel(RATIO_LABELS[ratioIdx], Color.White) { ratioIdx = (ratioIdx + 1) % 3 }
                        BoxedLabel("HDR", if (hdr) Yellow else Color.White) { hdr = !hdr }
                        IconBtn(Icons.Outlined.Tune, if (effect != 0) Yellow else Color.White) { cycleEffect() }
                    }
                    Look.G -> Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(50.dp).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FlashBtn(flash) { flash = (flash + 1) % 3 }
                        BoxedLabel("HDR+", if (hdr) GYellow else Color.White) { hdr = !hdr }
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable { cycleTimer() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.CenterFocusStrong, null,
                                tint = if (timer > 0) GYellow else Color.White, modifier = Modifier.size(24.dp)
                            )
                            if (timer > 0) {
                                Text(
                                    timer.toString(), color = GYellow, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
                                )
                            }
                        }
                        IconBtn(Icons.Outlined.Settings, Color.White) { showSettings = !showSettings }
                    }
                }
            }

            // ---------- المعاينة ومفاتيح فوقها ----------
            val slotMod = if (full) Modifier.fillMaxWidth().weight(1f)
            else Modifier.fillMaxWidth().aspectRatio(ratio)
            Box(slotMod) {
                if (countdown > 0) {
                    Text(
                        countdown.toString(), color = Color.White, fontSize = 96.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center)
                    )
                }
                if (isRec) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(12.dp)
                            .clip(CircleShape).background(Color(0xFFFF3B30))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            String.format("%02d:%02d", recSeconds / 60, recSeconds % 60),
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (look == Look.IPHONE && expanded) {
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                            .clip(CircleShape).background(Color(0xCC2A2A2A)).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Ic(RATIO_LABELS[ratioIdx], Color.White, 14) { ratioIdx = (ratioIdx + 1) % 3 }
                        Ic(timerLabel(timer), if (timer > 0) Yellow else Color.White, 16) { cycleTimer() }
                        Ic("▦", if (grid) Yellow else Color.White, 18) { grid = !grid }
                        Ic("HDR", if (hdr) Yellow else Color.White, 13) { hdr = !hdr }
                        Ic(if (hq) "HQ" else "STD", Color.White, 13) { hq = !hq }
                    }
                }
                if (look == Look.S && pro) {
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                            .clip(CircleShape).background(Color(0xCC1C1C1E)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Ic("−", Color.White, 22) { ev = (ev - 1).coerceIn(-6, 6) }
                        Text(
                            "EV " + (if (ev > 0) "+" else "") + ev, color = Color.White,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                        Ic("+", Color.White, 22) { ev = (ev + 1).coerceIn(-6, 6) }
                    }
                }
                if (showSettings) {
                    SettingsPanel(
                        grid, hq, mirror,
                        { grid = !grid }, { hq = !hq }, { mirror = !mirror },
                        Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                    )
                }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                    ZoomRow(look, zooms, zoom) { zoom = it }
                }
            }

            // ---------- الأزرار السفلية ----------
            val ctrlMod = if (full) Modifier.fillMaxWidth().background(Color(0x66000000)).padding(bottom = 12.dp)
            else Modifier.fillMaxWidth().weight(1f)
            Column(
                ctrlMod,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (full) Arrangement.spacedBy(10.dp) else Arrangement.SpaceEvenly
            ) {
                val thumbShape: Shape = if (look == Look.IPHONE) RoundedCornerShape(10.dp) else CircleShape
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Thumb(thumb, 56.dp, thumbShape) { openGallery(ctx, lastUri) }
                    ShutterBtn(78.dp, 3.dp, isVideo, isRec) { onShutter() }
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .border(1.5.dp, Color(0x99FFFFFF), CircleShape).clickable { onFlip() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Cached, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                when (look) {
                    Look.S -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        ModeText("PHOTO", !isVideo && !night && !pro, SBlue, 14) { modePhoto() }
                        ModeText("VIDEO", isVideo, SBlue, 14) { modeVideo() }
                        ModeText("PRO", pro, SBlue, 14) { modePro() }
                        ModeText("NIGHT", night && !isVideo && !pro, SBlue, 14) { modeNight() }
                    }
                    Look.G -> Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModePill("Photo", !isVideo && !night) { modePhoto() }
                        ModePill("Portrait", false) { unavailable("Portrait") }
                        ModePill("Night Sight", night && !isVideo) { modeNight() }
                        ModePill("Video", isVideo) { modeVideo() }
                    }
                    Look.IPHONE -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ModeText("SLO-MO", false, Yellow, 11) { unavailable("SLO-MO") }
                        ModeText("VIDEO", isVideo, Yellow, 11) { modeVideo() }
                        ModeText("PHOTO", !isVideo, Yellow, 11) { modePhoto() }
                        ModeText("PORTRAIT", false, Yellow, 11) { unavailable("PORTRAIT") }
                        ModeText("PANO", false, Yellow, 11) { unavailable("PANO") }
                    }
                }
            }
        }
    }
}

fun startRecording(ctx: Context, vc: VideoCapture<Recorder>, onFinish: () -> Unit): Recording? {
    return try {
        val cv = ContentValues()
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, "CT_VID_" + System.currentTimeMillis())
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/CameraThree")
        val opts = MediaStoreOutputOptions.Builder(
            ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(cv).build()
        var pending = vc.output.prepareRecording(ctx, opts)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }
        pending.start(ContextCompat.getMainExecutor(ctx)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                onFinish()
                Toast.makeText(
                    ctx,
                    if (ev.hasError()) "فشل حفظ الفيديو" else "تم حفظ الفيديو في DCIM/CameraThree",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, "تعذر بدء التسجيل: " + e.message, Toast.LENGTH_LONG).show()
        null
    }
}

fun takePhoto(
    ctx: Context, capture: ImageCapture, look: Look, night: Boolean, effect: Int, hdr: Boolean,
    ratio: Float, hq: Boolean, mirrorFront: Boolean, onSaved: (Uri) -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    capture.takePicture(
        Executors.newSingleThreadExecutor(),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bmp = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    main.post { Toast.makeText(ctx, "جاري المعالجة...", Toast.LENGTH_SHORT).show() }
                    val swap = rot == 90 || rot == 270
                    val w = if (swap) bmp.height else bmp.width
                    val h = if (swap) bmp.width else bmp.height
                    var cw = w
                    var ch = h
                    if (w.toFloat() / h.toFloat() > ratio) cw = (h * ratio).toInt() else ch = (w / ratio).toInt()
                    val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                    val m = Matrix()
                    m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                    m.postRotate(rot.toFloat())
                    if (mirrorFront) m.postScale(-1f, 1f)
                    m.postTranslate(cw / 2f, ch / 2f)
                    Canvas(out).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
                    bmp.recycle()
                    enhance(out, look, night, effect, hdr)
                    val cv = ContentValues()
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, "CT_" + System.currentTimeMillis() + ".jpg")
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CameraThree")
                    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    ctx.contentResolver.openOutputStream(uri)!!.use {
                        out.compress(Bitmap.CompressFormat.JPEG, if (hq) 95 else 80, it)
                    }
                    main.post {
                        onSaved(uri)
                        Toast.makeText(ctx, "تم الحفظ في DCIM/CameraThree", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    main.post { Toast.makeText(ctx, "خطأ: " + e.message, Toast.LENGTH_LONG).show() }
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
