package com.example.kotlindemos

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.SupportStreetViewPanoramaFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import kotlin.random.Random

class MapLoadTestActivity : SamplesBaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var countdown = 3
    private lateinit var statusTextView: TextView
    private lateinit var chosenMode: String
    private lateinit var location: NamedLatLng
    private var googleMap: GoogleMap? = null
    private var buildingsEnabled = false
    private var currentTilt = 0f
    private var baseZoom = 18.0f

    private var colocationWebView: WebView? = null
    private lateinit var btnRobustGl: Button
    private lateinit var btnColocationWebView: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val locationIndex = intent.getIntExtra("LOCATION_INDEX", 0)
        location = LOCATIONS.getOrElse(locationIndex) { LOCATIONS[0] }

        val rootLayout = FrameLayout(this)
        setContentView(rootLayout)

        val mapContainer = FrameLayout(this).apply {
            id = View.generateViewId()
        }
        rootLayout.addView(mapContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        chosenMode = intent.getStringExtra("CHOSEN_MODE") ?: listOf("NORMAL", "SATELLITE", "HYBRID", "TERRAIN", "STREET_VIEW").random()

        val supportsTilt = chosenMode != "STREET_VIEW"
        val supports3DBuildings = chosenMode == "NORMAL"

        countdown = 3
        if (supportsTilt) countdown += 2
        if (supports3DBuildings) countdown += 1

        buildingsEnabled = supports3DBuildings

        statusTextView = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        updateStatus()

        val textParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            setMargins(32, 120, 32, 32)
        }
        rootLayout.addView(statusTextView, textParams)

        if (chosenMode == "STREET_VIEW") {
            val fragment = SupportStreetViewPanoramaFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(mapContainer.id, fragment)
                .commit()
            fragment.getStreetViewPanoramaAsync { panorama ->
                panorama.setPosition(location.latLng)
                startCountdown()
            }
        } else {
            val fragment = SupportMapFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(mapContainer.id, fragment)
                .commit()
            fragment.getMapAsync { map ->
                this.googleMap = map
                map.isBuildingsEnabled = buildingsEnabled
                map.isTrafficEnabled = Random.nextBoolean()
                map.mapType = when (chosenMode) {
                    "SATELLITE" -> GoogleMap.MAP_TYPE_SATELLITE
                    "HYBRID" -> GoogleMap.MAP_TYPE_HYBRID
                    "TERRAIN" -> GoogleMap.MAP_TYPE_TERRAIN
                    else -> GoogleMap.MAP_TYPE_NORMAL
                }
                baseZoom = if (supports3DBuildings) 18.0f else 15.0f
                val cameraPosition = CameraPosition.Builder()
                    .target(location.latLng)
                    .zoom(baseZoom)
                    .tilt(0f)
                    .build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                startCountdown()

                if (supportsTilt) {
                    scheduleTiltAndBuildingsActions(map, supports3DBuildings)
                }
            }
        }

        statusTextView.bringToFront()
        setupColocationOverlay(rootLayout)
    }

    private fun setupColocationOverlay(rootLayout: FrameLayout) {
        val colocationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(24, 0, 24, 32)
        }
        rootLayout.addView(colocationContainer, params)

        btnRobustGl = Button(this).apply {
            textSize = 12f
            setPadding(16, 20, 16, 20)
            setOnClickListener {
                RobustGlThreadManager.toggle()
                updateColocationButtonStyles()
            }
        }
        colocationContainer.addView(btnRobustGl)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                12
            )
        }
        colocationContainer.addView(spacer)

        btnColocationWebView = Button(this).apply {
            textSize = 12f
            setPadding(16, 20, 16, 20)
            setOnClickListener {
                ColocationWebViewManager.isEnabled = !ColocationWebViewManager.isEnabled
                updateColocationButtonStyles()
                updateColocationWebView(rootLayout)
            }
        }
        colocationContainer.addView(btnColocationWebView)

        updateColocationButtonStyles()
        updateColocationWebView(rootLayout)

        colocationContainer.bringToFront()
    }

    private fun updateColocationButtonStyles() {
        if (!::btnRobustGl.isInitialized || !::btnColocationWebView.isInitialized) return
        val glActive = RobustGlThreadManager.isRunning
        btnRobustGl.text = if (glActive) {
            "Colocation run openGL with EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT: [ACTIVE]"
        } else {
            "Colocation run openGL with EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT"
        }
        btnRobustGl.setBackgroundColor(if (glActive) Color.parseColor("#E65100") else Color.parseColor("#CC37474F"))
        btnRobustGl.setTextColor(Color.WHITE)

        val webActive = ColocationWebViewManager.isEnabled
        btnColocationWebView.text = if (webActive) {
            "Colocation run WebView: [ACTIVE]"
        } else {
            "Colocation run WebView"
        }
        btnColocationWebView.setBackgroundColor(if (webActive) Color.parseColor("#00838F") else Color.parseColor("#CC37474F"))
        btnColocationWebView.setTextColor(Color.WHITE)
    }

    private fun updateColocationWebView(rootLayout: FrameLayout) {
        if (ColocationWebViewManager.isEnabled) {
            if (colocationWebView == null) {
                val wv = WebView(this).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    loadData(
                        "<html><body style='background:#1b1b1b;color:#00e676;font-family:sans-serif;font-size:10px;margin:4px;'>Colocation WebView (Active)</body></html>",
                        "text/html",
                        "UTF-8"
                    )
                }
                val wvParams = FrameLayout.LayoutParams(
                    (160 * resources.displayMetrics.density).toInt(),
                    (50 * resources.displayMetrics.density).toInt()
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = (70 * resources.displayMetrics.density).toInt()
                    rightMargin = 16
                }
                rootLayout.addView(wv, wvParams)
                wv.bringToFront()
                colocationWebView = wv
            }
        } else {
            colocationWebView?.let {
                rootLayout.removeView(it)
                it.destroy()
            }
            colocationWebView = null
        }
    }

    private fun scheduleTiltAndBuildingsActions(map: GoogleMap, supports3DBuildings: Boolean) {
        // Start tilt up and zoom out after 1 second
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val targetZoomOut = (baseZoom - 0.75f).coerceAtLeast(2.0f)
            val tiltedUp = CameraPosition.Builder(map.cameraPosition)
                .zoom(targetZoomOut)
                .tilt(60f)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(tiltedUp), 1000, object : GoogleMap.CancelableCallback {
                override fun onFinish() {
                    currentTilt = 60f
                    updateStatus()

                    if (supports3DBuildings) {
                        // Disable 3D buildings, wait 500ms, re-enable 3D buildings, wait 500ms, then tilt down
                        handler.postDelayed({
                            if (isFinishing || isDestroyed) return@postDelayed
                            map.isBuildingsEnabled = false
                            buildingsEnabled = false
                            updateStatus()

                            handler.postDelayed({
                                if (isFinishing || isDestroyed) return@postDelayed
                                map.isBuildingsEnabled = true
                                buildingsEnabled = true
                                updateStatus()

                                handler.postDelayed({
                                    tiltDown(map)
                                }, 500)
                            }, 500)
                        }, 500)
                    } else {
                        handler.postDelayed({
                            tiltDown(map)
                        }, 500)
                    }
                }

                override fun onCancel() {}
            })
        }, 1000)
    }

    private fun tiltDown(map: GoogleMap) {
        if (isFinishing || isDestroyed) return
        val tiltedDown = CameraPosition.Builder(map.cameraPosition)
            .zoom(baseZoom)
            .tilt(0f)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(tiltedDown), 1000, object : GoogleMap.CancelableCallback {
            override fun onFinish() {
                currentTilt = 0f
                updateStatus()
            }

            override fun onCancel() {}
        })
    }

    private fun updateStatus() {
        val buildingsStr = if (chosenMode == "NORMAL") {
            if (buildingsEnabled) "ON" else "OFF"
        } else {
            "N/A"
        }
        val tiltStr = if (chosenMode != "STREET_VIEW") "${currentTilt.toInt()}°" else "N/A"
        val robustGlStr = if (RobustGlThreadManager.isRunning) " | RobustGL: ON" else ""
        val webViewStr = if (ColocationWebViewManager.isEnabled) " | WebView: ON" else ""
        statusTextView.text = "Loading...\nLocation: ${location.name}\nMode: $chosenMode$robustGlStr$webViewStr\nBuildings: $buildingsStr\nTilt: $tiltStr\nRemaining: ${countdown}s"
    }

    private fun startCountdown() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                countdown--
                updateStatus()

                if (countdown <= 0) {
                    finish()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        googleMap?.stopAnimation()
        colocationWebView?.destroy()
        colocationWebView = null
        super.onDestroy()
    }

    companion object {
        val LOCATIONS = listOf(
            NamedLatLng("Eiffel Tower, Paris", LatLng(48.8584, 2.2945)),
            NamedLatLng("Times Square, New York", LatLng(40.7580, -73.9855)),
            NamedLatLng("Big Ben, London", LatLng(51.5007, -0.1246)),
            NamedLatLng("Shibuya Crossing, Tokyo", LatLng(35.6595, 139.7005)),
            NamedLatLng("Opera House, Sydney", LatLng(-33.8568, 151.2153)),
            NamedLatLng("Colosseum, Rome", LatLng(41.8902, 12.4922)),
            NamedLatLng("Pyramids of Giza, Cairo", LatLng(29.9792, 31.1342)),
            NamedLatLng("Christ the Redeemer, Rio", LatLng(-22.9519, -43.2105)),
            NamedLatLng("Golden Gate Bridge, San Francisco", LatLng(37.8199, -122.4783)),
            NamedLatLng("Taj Mahal, Agra", LatLng(27.1751, 78.0421)),
            NamedLatLng("Forbidden City, Beijing", LatLng(39.9169, 116.3970)),
            NamedLatLng("Red Square, Moscow", LatLng(55.7539, 37.6208)),
            NamedLatLng("Sagrada Familia, Barcelona", LatLng(41.4036, 2.1744)),
            NamedLatLng("Burj Khalifa, Dubai", LatLng(25.1972, 55.2744)),
            NamedLatLng("Acropolis, Athens", LatLng(37.9715, 23.7257)),
            NamedLatLng("Lincoln Memorial, Washington D.C.", LatLng(38.8893, -77.0502)),
            NamedLatLng("Space Needle, Seattle", LatLng(47.6205, -122.3493)),
            NamedLatLng("CN Tower, Toronto", LatLng(43.6426, -79.3871)),
            NamedLatLng("Leaning Tower, Pisa", LatLng(43.7230, 10.3966)),
            NamedLatLng("Brandenburg Gate, Berlin", LatLng(52.5163, 13.3777)),
            NamedLatLng("St. Mark's Basilica, Venice", LatLng(45.4346, 12.3396)),
            NamedLatLng("Table Mountain, Cape Town", LatLng(-33.9628, 18.4098)),
            NamedLatLng("Hollywood Sign, Los Angeles", LatLng(34.1341, -118.3215)),
            NamedLatLng("Millennium Park, Chicago", LatLng(41.8827, -87.6227)),
            NamedLatLng("Hagia Sophia, Istanbul", LatLng(41.0086, 28.9798)),
            NamedLatLng("Machu Picchu, Peru", LatLng(-13.1631, -72.5450)),
            NamedLatLng("Grand Canyon, USA", LatLng(36.0544, -112.1401)),
            NamedLatLng("Mount Everest, Nepal", LatLng(27.9881, 86.9250)),
            NamedLatLng("Niagara Falls, Canada", LatLng(43.0962, -79.0377)),
            NamedLatLng("Stonehenge, UK", LatLng(51.1789, -1.8262))
        )
    }
}

data class NamedLatLng(val name: String, val latLng: LatLng)
