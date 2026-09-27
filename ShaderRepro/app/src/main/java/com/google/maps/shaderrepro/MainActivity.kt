/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.maps.shaderrepro

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), OnMapsSdkInitializedCallback {

    companion object {
        private const val TAG = "ShaderCrashRepro"

        // Four hotspots rich in textured areas and hatched polygon boundaries
        private val HOTSPOTS = listOf(
            LatLng(37.7694, -122.4862) to 15.5f, // Golden Gate Park, SF
            LatLng(40.7812, -73.9665) to 15.5f,  // Central Park, NYC
            LatLng(51.5073, -0.1657) to 15.5f,   // Hyde Park, London
            LatLng(34.1526, 77.5771) to 14.5f    // Jammu/Kashmir border hatched area
        )

        private const val MAPVIEW_CYCLE_MS = 3500L
        private const val TRAP_WINDOW_MS = 10000L

        private const val WEBGL_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  body, html { margin:0; padding:0; overflow:hidden; width:100%; height:100%; background:#111; }
  canvas { width:100%; height:100%; display:block; }
</style>
</head>
<body>
<canvas id="c"></canvas>
<script>
  const canvas = document.getElementById('c');
  const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
  let frame = 0;
  function loop() {
    frame++;
    if (gl) {
      try {
        const vs = gl.createShader(gl.VERTEX_SHADER);
        gl.shaderSource(vs, "attribute vec4 p; uniform float u; void main(){ gl_Position=p+vec4(sin(u)*0.01,0,0,0); }");
        gl.compileShader(vs);
        const fs = gl.createShader(gl.FRAGMENT_SHADER);
        gl.shaderSource(fs, "precision mediump float; uniform float u; void main(){ gl_FragColor=vec4(sin(u),cos(u),0.5,1.0); }");
        gl.compileShader(fs);
        const p = gl.createProgram();
        gl.attachShader(p, vs);
        gl.attachShader(p, fs);
        gl.linkProgram(p);
        gl.useProgram(p);
        gl.clearColor((frame%100)/100.0, 0.2, 0.4, 1.0);
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.deleteProgram(p);
        gl.deleteShader(vs);
        gl.deleteShader(fs);
      } catch(e) {}
    }
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);
</script>
</body>
</html>
"""
    }

    private enum class Mode {
        MODE_A, // Ads + WebGL
        MODE_B, // Synthetic EGL/GLSL Stress
        MODE_C  // Combined A + B
    }

    // UI Elements
    private lateinit var tvAlertBanner: TextView
    private lateinit var tvLoopCount: TextView
    private lateinit var tvMapViewAge: TextView
    private lateinit var tvProgramsLinked: TextView
    private lateinit var tvShaderCache: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var btnStartStop: Button
    private lateinit var btnTrimMemory: Button
    private lateinit var btnClearCache: Button
    private lateinit var btnClearLog: TextView
    private lateinit var mapContainer: FrameLayout
    private lateinit var adsAndWebContainer: View
    private lateinit var adSlot1: FrameLayout
    private lateinit var adSlot2: FrameLayout
    private lateinit var webViewWebGL: WebView
    private lateinit var tvConsoleLog: TextView
    private lateinit var logScrollView: ScrollView

    // Engines & Detectors
    private lateinit var stressEngine: AdrenoShaderStressEngine
    private lateinit var logcatDetector: LogcatResetDetector

    // State
    private var selectedMode = Mode.MODE_C
    private val isLoopRunning = AtomicBoolean(false)
    private var loopCount = 0
    private var currentMapView: MapView? = null
    private var currentGoogleMap: GoogleMap? = null
    private var currentMapViewStartTime = 0L

    // Trap Lock Window State
    private val isTrapLocked = AtomicBoolean(false)
    private var trapWindowStartTime = 0L
    private var firstHitDetectedLine = ""

    // Coroutine Jobs
    private var mapLifecycleJob: Job? = null
    private var hudUpdateJob: Job? = null
    private var adsStressJob: Job? = null

    // AdViews
    private var adView1: AdView? = null
    private var adView2: AdView? = null

    private var resizeToggle = false
    private var hotspotIndex = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize Maps SDK with LATEST renderer to load maps_core_dynamite (enableTexturedAreas = true)
        MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST, this)

        // 2. Initialize AdMob for Mode A
        try {
            MobileAds.initialize(this) { status ->
                log("MobileAds initialized: ${status.adapterStatusMap.keys}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MobileAds initialization error: ${e.message}")
        }

        bindViews()
        setupEngines()
        setupListeners()
        setupAdsAndWebView()
        updateShaderCacheTelemetry()
    }

    private fun bindViews() {
        tvAlertBanner = findViewById(R.id.tvAlertBanner)
        tvLoopCount = findViewById(R.id.tvLoopCount)
        tvMapViewAge = findViewById(R.id.tvMapViewAge)
        tvProgramsLinked = findViewById(R.id.tvProgramsLinked)
        tvShaderCache = findViewById(R.id.tvShaderCache)
        rgMode = findViewById(R.id.rgMode)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnTrimMemory = findViewById(R.id.btnTrimMemory)
        btnClearCache = findViewById(R.id.btnClearCache)
        btnClearLog = findViewById(R.id.btnClearLog)
        mapContainer = findViewById(R.id.mapContainer)
        adsAndWebContainer = findViewById(R.id.adsAndWebContainer)
        adSlot1 = findViewById(R.id.adSlot1)
        adSlot2 = findViewById(R.id.adSlot2)
        webViewWebGL = findViewById(R.id.webViewWebGL)
        tvConsoleLog = findViewById(R.id.tvConsoleLog)
        logScrollView = findViewById(R.id.logScrollView)
    }

    private fun setupEngines() {
        stressEngine = AdrenoShaderStressEngine { logMsg ->
            log("[StressEngine] $logMsg")
        }

        logcatDetector = LogcatResetDetector(
            onFirstHit = { line, timestamp ->
                onFirstHitResetCaught(line, timestamp)
            },
            onFatalCrash = { line, timestamp ->
                onFatalCrashHit(line, timestamp)
            },
            onLogLine = { line ->
                log("[Logcat] $line")
            }
        )
    }

    private fun setupListeners() {
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            selectedMode = when (checkedId) {
                R.id.rbModeA -> Mode.MODE_A
                R.id.rbModeB -> Mode.MODE_B
                else -> Mode.MODE_C
            }
            log("Contention mode switched to: $selectedMode")
            updateModeVisibility()
        }

        btnStartStop.setOnClickListener {
            if (isLoopRunning.get()) {
                stopReproLoop()
            } else {
                startReproLoop()
            }
        }

        btnTrimMemory.setOnClickListener {
            log("[TrimMemory] Forcing TRIM_MEMORY_RUNNING_CRITICAL")
            onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }

        btnClearCache.setOnClickListener {
            val cleared = AdrenoShaderStressEngine.clearShaderCache(this)
            log("[Cache] Shader disk cache cleared: $cleared")
            updateShaderCacheTelemetry()
        }

        btnClearLog.setOnClickListener {
            tvConsoleLog.text = ""
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAdsAndWebView() {
        // Embed Google Mobile Ads test banners
        try {
            adView1 = AdView(this).apply {
                adUnitId = "ca-app-pub-3940256099942544/6300978111"
                setAdSize(AdSize.BANNER)
            }
            adSlot1.addView(adView1)

            adView2 = AdView(this).apply {
                adUnitId = "ca-app-pub-3940256099942544/6300978111"
                setAdSize(AdSize.BANNER)
            }
            adSlot2.addView(adView2)
        } catch (e: Exception) {
            Log.e(TAG, "AdView setup error: ${e.message}")
        }

        // Setup hardware-accelerated WebGL WebView
        webViewWebGL.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            loadDataWithBaseURL("https://local.shaderstress", WEBGL_HTML, "text/html", "UTF-8", null)
        }

        updateModeVisibility()
    }

    private fun updateModeVisibility() {
        when (selectedMode) {
            Mode.MODE_B -> {
                adsAndWebContainer.visibility = View.GONE
            }
            Mode.MODE_A, Mode.MODE_C -> {
                adsAndWebContainer.visibility = View.VISIBLE
            }
        }
    }

    // =========================================================================
    // Repro Loop Controller
    // =========================================================================

    private fun startReproLoop() {
        if (isLoopRunning.getAndSet(true)) return

        btnStartStop.text = getString(R.string.btn_stop)
        btnStartStop.setBackgroundColor(Color.parseColor("#C62828"))
        tvAlertBanner.setBackgroundColor(Color.parseColor("#2E7D32"))
        tvAlertBanner.text = "STATUS: RUNNING | Trap Armed & Monitoring Logcat"

        loopCount = 0
        isTrapLocked.set(false)
        logcatDetector.resetDetector()
        logcatDetector.start(lifecycleScope)

        log("Repro loop started in $selectedMode")

        // Start synthetic shader stress if Mode B or Mode C
        if (selectedMode == Mode.MODE_B || selectedMode == Mode.MODE_C) {
            stressEngine.start(numThreads = 3)
        }

        // Start Ads & WebGL contention pass if Mode A or Mode C
        if (selectedMode == Mode.MODE_A || selectedMode == Mode.MODE_C) {
            startAdsStressJob()
        }

        // Start Smart MapView Lifecycle & Trap loop
        startMapViewLifecycleLoop()

        // Start HUD Telemetry updater
        startHudTelemetry()
    }

    private fun stopReproLoop() {
        if (!isLoopRunning.getAndSet(false)) return

        btnStartStop.text = getString(R.string.btn_start)
        btnStartStop.setBackgroundColor(Color.parseColor("#1976D2"))
        tvAlertBanner.setBackgroundColor(Color.parseColor("#424242"))
        tvAlertBanner.text = "STATUS: STOPPED | Repro loop idle"

        mapLifecycleJob?.cancel()
        mapLifecycleJob = null
        hudUpdateJob?.cancel()
        hudUpdateJob = null
        adsStressJob?.cancel()
        adsStressJob = null

        stressEngine.stop()
        logcatDetector.stop()
        isTrapLocked.set(false)

        destroyCurrentMapView()
        log("Repro loop stopped.")
    }

    // =========================================================================
    // Smart MapView Lifecycle & 10-Second Reset Window Trap
    // =========================================================================

    private fun startMapViewLifecycleLoop() {
        mapLifecycleJob = lifecycleScope.launch {
            while (isActive && isLoopRunning.get()) {
                loopCount++
                tvLoopCount.text = "Loops: $loopCount"

                // Create and initialize new MapView
                createNewMapView()

                val cycleStartTime = SystemClock.elapsedRealtime()
                var lastPauseResumePulseTime = cycleStartTime
                var lastResizePulseTime = cycleStartTime

                while (isActive && isLoopRunning.get()) {
                    val now = SystemClock.elapsedRealtime()

                    // Check if 1st hit occurred and trap is locked!
                    if (isTrapLocked.get()) {
                        val elapsedInTrap = now - trapWindowStartTime
                        if (elapsedInTrap < TRAP_WINDOW_MS) {
                            // CRITICAL TRAP MODE:
                            // Keep GmmRenderTarget ALIVE and hammer it with 50ms camera jitters and layout resizes!
                            executeTrapWindowStressTick()
                            delay(50)
                            continue
                        } else {
                            // Trap window expired without second hit
                            isTrapLocked.set(false)
                            log("[Trap] 10-second recovery window elapsed. Unlocking MapView recreation cycle.")
                            tvAlertBanner.setBackgroundColor(Color.parseColor("#2E7D32"))
                            tvAlertBanner.text = "STATUS: RUNNING | Trap Rearmed after 10s Window"
                        }
                    }

                    // Normal cycle: surface resize pulse every 250ms
                    if (now - lastResizePulseTime >= 250L) {
                        lastResizePulseTime = now
                        pulseMapViewResize()
                    }

                    // Normal cycle: lifecycle onPause/onResume pulse every 1000ms (keeps GmmRenderTarget!)
                    if (now - lastPauseResumePulseTime >= 1000L) {
                        lastPauseResumePulseTime = now
                        pulseMapViewLifecycle()
                    }

                    // Normal cycle: cycle camera between textured area hotspots every 400ms
                    cycleMapCamera()

                    // End cycle after MAPVIEW_CYCLE_MS unless locked in trap
                    if (now - cycleStartTime >= MAPVIEW_CYCLE_MS) {
                        break
                    }

                    delay(150)
                }

                // If not locked in trap, tear down MapView and cycle to fresh instance
                if (!isTrapLocked.get()) {
                    destroyCurrentMapView()
                }
            }
        }
    }

    private fun createNewMapView() {
        destroyCurrentMapView()

        val mapView = MapView(this)
        currentMapView = mapView
        currentMapViewStartTime = SystemClock.elapsedRealtime()

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        mapContainer.addView(mapView, params)

        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()

        mapView.getMapAsync { googleMap ->
            if (currentMapView === mapView) {
                currentGoogleMap = googleMap
                configureGoogleMap(googleMap)
            }
        }
    }

    private fun configureGoogleMap(map: GoogleMap) {
        try {
            map.isBuildingsEnabled = true
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            cycleMapCamera()
        } catch (e: Exception) {
            Log.w(TAG, "Map configuration warning: ${e.message}")
        }
    }

    private fun cycleMapCamera() {
        val map = currentGoogleMap ?: return
        val target = HOTSPOTS[hotspotIndex % HOTSPOTS.size]
        hotspotIndex++

        try {
            val update = CameraUpdateFactory.newLatLngZoom(target.first, target.second)
            map.moveCamera(update)
        } catch (_: Exception) {
        }
    }

    private fun pulseMapViewResize() {
        val mv = currentMapView ?: return
        val parentW = mapContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val parentH = mapContainer.height.takeIf { it > 0 } ?: (220 * resources.displayMetrics.density).toInt()

        val delta = if (resizeToggle) 2 else -2
        resizeToggle = !resizeToggle

        val params = FrameLayout.LayoutParams(parentW + delta, parentH + delta)
        mv.layoutParams = params
        mv.requestLayout()
    }

    private suspend fun pulseMapViewLifecycle() {
        val mv = currentMapView ?: return
        try {
            mv.onPause()
            delay(50)
            mv.onResume()
        } catch (_: Exception) {
        }
    }

    private fun executeTrapWindowStressTick() {
        val map = currentGoogleMap
        val mv = currentMapView ?: return

        // 1. Rapid micro zoom jitter to trigger immediate GL-Map redraw
        if (map != null) {
            val target = HOTSPOTS[hotspotIndex % HOTSPOTS.size]
            val jitterZoom = target.second + (if (resizeToggle) 0.08f else -0.08f)
            try {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(target.first, jitterZoom))
            } catch (_: Exception) {
            }
        }

        // 2. +/- 1px layout resize to invoke GLViewThreadImpl.surfaceResized()
        val parentW = mapContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val parentH = mapContainer.height.takeIf { it > 0 } ?: (220 * resources.displayMetrics.density).toInt()
        val delta = if (resizeToggle) 1 else -1
        resizeToggle = !resizeToggle

        val params = FrameLayout.LayoutParams(parentW + delta, parentH + delta)
        mv.layoutParams = params
        mv.requestLayout()
    }

    private fun destroyCurrentMapView() {
        currentGoogleMap = null
        currentMapView?.let { mv ->
            try {
                mv.onPause()
                mv.onStop()
                mv.onDestroy()
            } catch (_: Exception) {
            }
            mapContainer.removeView(mv)
        }
        currentMapView = null
    }

    // =========================================================================
    // Logcat Detector Event Handlers
    // =========================================================================

    private fun onFirstHitResetCaught(line: String, timestamp: Long) {
        runOnUiThread {
            firstHitDetectedLine = line
            trapWindowStartTime = timestamp
            isTrapLocked.set(true)

            tvAlertBanner.setBackgroundColor(Color.parseColor("#B71C1C"))
            tvAlertBanner.text = "⚠️ 1ST HIT DETECTED! GmmRenderTarget Recovery Caught!\n" +
                    "LOCKING MapView for 10s Window to trigger second hit!"

            log("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            log("[TRAP TRIGGERED] First GmmRenderTarget context reset detected!")
            log("[TRAP TRIGGERED] Log: $line")
            log("[TRAP TRIGGERED] Locking current MapView to keep lastGLResetMs alive!")
            log("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        }
    }

    private fun onFatalCrashHit(line: String, timestamp: Long) {
        runOnUiThread {
            isTrapLocked.set(false)
            tvAlertBanner.setBackgroundColor(Color.parseColor("#FFD600"))
            tvAlertBanner.setTextColor(Color.BLACK)
            tvAlertBanner.text = "💥 CRASH REPRODUCED!\n$line"

            log("==================================================================")
            log("💥 [SUCCESS] FATAL CRASH REPRODUCED WITHIN 10-SECOND WINDOW!")
            log("💥 [SUCCESS] $line")
            log("==================================================================")
        }
    }

    // =========================================================================
    // Mode A: Ads & WebGL Stress Coroutine
    // =========================================================================

    private fun startAdsStressJob() {
        adsStressJob = lifecycleScope.launch {
            var toggle = false
            var loadAdCounter = 0

            while (isActive && isLoopRunning.get()) {
                toggle = !toggle
                loadAdCounter++

                // Periodically reload AdViews
                if (loadAdCounter % 15 == 0) {
                    try {
                        val adRequest = AdRequest.Builder().build()
                        adView1?.loadAd(adRequest)
                        adView2?.loadAd(adRequest)
                    } catch (_: Exception) {
                    }
                }

                // Micro-resize ad slots to trigger layout passes on parent RenderThread
                val h = if (toggle) 52 else 50
                adSlot1.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * resources.displayMetrics.density).toInt())
                adSlot2.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * resources.displayMetrics.density).toInt())
                adSlot1.requestLayout()
                adSlot2.requestLayout()

                delay(300)
            }
        }
    }

    // =========================================================================
    // HUD Telemetry & State Poller
    // =========================================================================

    private fun startHudTelemetry() {
        hudUpdateJob = lifecycleScope.launch {
            var counter = 0
            while (isActive && isLoopRunning.get()) {
                val now = SystemClock.elapsedRealtime()

                // MapView age
                if (currentMapViewStartTime > 0L) {
                    val ageSec = (now - currentMapViewStartTime) / 1000f
                    tvMapViewAge.text = String.format(Locale.US, "MapView Age: %.1fs", ageSec)
                }

                // Synthetic programs linked
                tvProgramsLinked.text = "Shaders: ${stressEngine.programsLinkedCount}"

                // Trap countdown banner update if locked
                if (isTrapLocked.get()) {
                    val remainingMs = maxOf(0L, TRAP_WINDOW_MS - (now - trapWindowStartTime))
                    val remainingSec = remainingMs / 1000f
                    tvAlertBanner.text = String.format(
                        Locale.US,
                        "⚠️ 1ST HIT DETECTED! GmmRenderTarget Trap Locked: %.1fs left\n%s",
                        remainingSec,
                        firstHitDetectedLine.takeLast(70)
                    )
                }

                // Shader disk cache telemetry updated every ~1 sec
                counter++
                if (counter % 10 == 0) {
                    updateShaderCacheTelemetry()
                }

                delay(100)
            }
        }
    }

    private fun updateShaderCacheTelemetry() {
        val (bytes, files) = AdrenoShaderStressEngine.getShaderCacheInfo(this)
        val formattedSize = formatBytes(bytes)
        tvShaderCache.text = "Shader Disk Cache: $formattedSize ($files files in com.android.opengl.shaders_cache)"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val formatted = "[$time] $message\n"
        runOnUiThread {
            tvConsoleLog.append(formatted)
            logScrollView.post {
                logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onMapsSdkInitialized(renderer: MapsInitializer.Renderer) {
        log("Maps SDK Initialized with Renderer: $renderer (Expected: LATEST)")
    }

    override fun onResume() {
        super.onResume()
        currentMapView?.onResume()
        adView1?.resume()
        adView2?.resume()
    }

    override fun onPause() {
        currentMapView?.onPause()
        adView1?.pause()
        adView2?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        stopReproLoop()
        destroyCurrentMapView()
        adView1?.destroy()
        adView2?.destroy()
        super.onDestroy()
    }
}
