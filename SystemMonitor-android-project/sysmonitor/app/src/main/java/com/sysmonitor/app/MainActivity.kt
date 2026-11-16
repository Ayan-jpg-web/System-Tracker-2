package com.sysmonitor.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.io.RandomAccessFile

// ---------- Data classes ----------

data class CpuSnapshot(val idle: Long, val total: Long)

data class CoreInfo(val index: Int, val usagePercent: Int, val freqMhz: Int?)

data class MemInfo(val totalMb: Long, val availMb: Long, val usedMb: Long, val lowMemory: Boolean, val thresholdMb: Long)

data class BatteryInfo(
    val percent: Int,
    val health: String,
    val status: String,
    val temperatureC: Float,
    val voltageMv: Int,
    val technology: String,
    val currentNowMa: Int?
)

data class GpuInfo(val vendor: String, val renderer: String, val version: String)

// ---------- CPU reading helpers ----------

object CpuReader {
    // Reads /proc/stat total line, returns (idle, total)
    fun readTotal(): CpuSnapshot? {
        return try {
            RandomAccessFile("/proc/stat", "r").use { reader ->
                val line = reader.readLine() ?: return null
                val parts = line.trim().split(Regex("\\s+"))
                // cpu user nice system idle iowait irq softirq steal guest guest_nice
                val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
                if (nums.size < 4) return null
                val idle = nums[3] + (nums.getOrElse(4) { 0L })
                val total = nums.sum()
                CpuSnapshot(idle, total)
            }
        } catch (e: Exception) {
            null
        }
    }

    // Reads per-core lines "cpu0 ... cpu1 ..." from /proc/stat
    fun readPerCore(): Map<Int, CpuSnapshot> {
        val result = mutableMapOf<Int, CpuSnapshot>()
        try {
            File("/proc/stat").forEachLine { line ->
                if (line.startsWith("cpu") && line.length > 3 && line[3].isDigit()) {
                    val parts = line.trim().split(Regex("\\s+"))
                    val idx = parts[0].removePrefix("cpu").toIntOrNull() ?: return@forEachLine
                    val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
                    if (nums.size >= 4) {
                        val idle = nums[3] + nums.getOrElse(4) { 0L }
                        val total = nums.sum()
                        result[idx] = CpuSnapshot(idle, total)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore, return what we have
        }
        return result
    }

    fun coreCount(): Int = Runtime.getRuntime().availableProcessors()

    fun coreFrequencyMhz(core: Int): Int? {
        return try {
            val f = File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
            if (!f.exists()) return null
            val khz = f.readText().trim().toLongOrNull() ?: return null
            (khz / 1000).toInt()
        } catch (e: Exception) {
            null
        }
    }
}

// ---------- GPU reading helper (best-effort via offscreen EGL context) ----------

object GpuReader {
    fun query(): GpuInfo {
        var display: EGLDisplay? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return unknown()
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return unknown()

            val attribList = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: return unknown()

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return unknown()

            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surface: EGLSurface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            EGL14.eglMakeCurrent(display, surface, surface, context)

            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown"
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "Unknown"

            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)

            return GpuInfo(vendor, renderer, glVersion)
        } catch (e: Exception) {
            return unknown()
        }
    }

    private fun unknown() = GpuInfo("Unavailable", "Unavailable", "Unavailable")
}

// ---------- Memory reading helper ----------

object MemReader {
    fun read(context: Context): MemInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        val availMb = info.availMem / (1024 * 1024)
        val usedMb = totalMb - availMb
        val thresholdMb = info.threshold / (1024 * 1024)
        return MemInfo(totalMb, availMb, usedMb, info.lowMemory, thresholdMb)
    }
}

// ---------- Battery reading helper ----------

object BatteryReader {
    fun read(context: Context): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val healthInt = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val health = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        val statusInt = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }

        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"

        var currentNowMa: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val microAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                currentNowMa = microAmps / 1000
            } catch (e: Exception) {
                currentNowMa = null
            }
        }

        return BatteryInfo(
            percent = percent,
            health = health,
            status = status,
            temperatureC = tempTenths / 10f,
            voltageMv = voltage,
            technology = technology,
            currentNowMa = currentNowMa
        )
    }
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(applicationContext)
                }
            }
        }
    }
}

@Composable
fun MonitorScreen(context: Context) {
    var overallCpuPercent by remember { mutableStateOf(0) }
    var cores by remember { mutableStateOf(listOf<CoreInfo>()) }
    var mem by remember { mutableStateOf<MemInfo?>(null) }
    var battery by remember { mutableStateOf<BatteryInfo?>(null) }
    var gpu by remember { mutableStateOf<GpuInfo?>(null) }

    // One-time GPU query
    LaunchedEffect(Unit) {
        gpu = GpuReader.query()
    }

    // Periodic refresh loop
    LaunchedEffect(Unit) {
        var prevTotal: CpuSnapshot? = CpuReader.readTotal()
        var prevPerCore: Map<Int, CpuSnapshot> = CpuReader.readPerCore()

        while (isActive) {
            delay(1200)

            val newTotal = CpuReader.readTotal()
            if (prevTotal != null && newTotal != null) {
                val idleDelta = newTotal.idle - prevTotal.idle
                val totalDelta = newTotal.total - prevTotal.total
                if (totalDelta > 0) {
                    val usage = (100 * (totalDelta - idleDelta) / totalDelta).toInt().coerceIn(0, 100)
                    overallCpuPercent = usage
                }
            }
            prevTotal = newTotal ?: prevTotal

            val newPerCore = CpuReader.readPerCore()
            val newCores = mutableListOf<CoreInfo>()
            val coreCount = CpuReader.coreCount()
            for (i in 0 until coreCount) {
                val prev = prevPerCore[i]
                val curr = newPerCore[i]
                var pct = 0
                if (prev != null && curr != null) {
                    val idleDelta = curr.idle - prev.idle
                    val totalDelta = curr.total - prev.total
                    if (totalDelta > 0) {
                        pct = (100 * (totalDelta - idleDelta) / totalDelta).toInt().coerceIn(0, 100)
                    }
                }
                newCores.add(CoreInfo(i, pct, CpuReader.coreFrequencyMhz(i)))
            }
            cores = newCores
            prevPerCore = newPerCore

            mem = MemReader.read(context)
            battery = BatteryReader.read(context)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("System Monitor", fontSize = 26.sp, fontWeight = FontWeight.Bold) }

        item {
            SectionCard(title = "CPU — $overallCpuPercent% overall") {
                cores.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Core ${c.index}")
                        Text("${c.usagePercent}%" + (c.freqMhz?.let { "  •  $it MHz" } ?: ""))
                    }
                    LinearProgressIndicator(
                        progress = c.usagePercent / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (cores.isEmpty()) Text("Reading CPU stats…")
            }
        }

        item {
            SectionCard(title = "Memory") {
                mem?.let { m ->
                    Text("Used: ${m.usedMb} MB / ${m.totalMb} MB")
                    LinearProgressIndicator(
                        progress = (m.usedMb.toFloat() / m.totalMb.toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Available: ${m.availMb} MB")
                    Text("Low-memory threshold: ${m.thresholdMb} MB")
                    if (m.lowMemory) {
                        Text("⚠ Device is in low-memory state", color = MaterialTheme.colorScheme.error)
                    }
                } ?: Text("Reading memory stats…")
            }
        }

        item {
            SectionCard(title = "Battery") {
                battery?.let { b ->
                    Text("Charge: ${b.percent}%  •  ${b.status}")
                    Text("Health: ${b.health}")
                    Text("Temperature: ${"%.1f".format(b.temperatureC)} °C")
                    Text("Voltage: ${b.voltageMv} mV")
                    Text("Technology: ${b.technology}")
                    b.currentNowMa?.let { Text("Current draw: $it mA") }
                } ?: Text("Reading battery stats…")
            }
        }

        item {
            SectionCard(title = "GPU") {
                gpu?.let { g ->
                    Text("Vendor: ${g.vendor}")
                    Text("Renderer: ${g.renderer}")
                    Text("GL Version: ${g.version}")
                    Text(
                        "Note: Android exposes no public API for live GPU load/usage. " +
                            "These are identity strings only; real-time GPU utilization would require root access to vendor-specific sysfs paths.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } ?: Text("Querying GPU…")
            }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}
