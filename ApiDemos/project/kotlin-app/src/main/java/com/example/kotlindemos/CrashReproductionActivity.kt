package com.example.kotlindemos

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.MapsInitializer.Renderer
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import kotlin.random.Random
import com.example.common_ui.R as CommonUiR

class CrashReproductionActivity : SamplesBaseActivity(), OnMapReadyCallback {

    private lateinit var statusTextView: TextView
    private var map: GoogleMap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isTourActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Prevent the display from going to sleep while this activity is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize Maps SDK with LATEST renderer BEFORE inflating the map fragment
        MapsInitializer.initialize(this, Renderer.LATEST, null)

        setContentView(CommonUiR.layout.basic_demo)

        val rootLayout = findViewById<ConstraintLayout>(CommonUiR.id.map_container)
        statusTextView = TextView(this).apply {
            text = "3D Buildings: ON"
            setBackgroundColor(Color.parseColor("#80000000"))
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            textSize = 18f
        }
        val layoutParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topToBottom = CommonUiR.id.top_bar
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = 32
            marginStart = 32
        }
        rootLayout.addView(statusTextView, layoutParams)

        val mapFragment = supportFragmentManager
            .findFragmentById(CommonUiR.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onResume() {
        super.onResume()
        val googleMap = map
        if (googleMap != null && !isTourActive) {
            isTourActive = true
            moveCameraRandomly(googleMap)
        }
    }

    override fun onPause() {
        super.onPause()
        isTourActive = false
        handler.removeCallbacksAndMessages(null)
        map?.stopAnimation()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        this.map = googleMap

        // Explicitly enable 3D buildings rendering
        googleMap.isBuildingsEnabled = true
        updateStatusText(true)

        // Apply a custom dynamic JSON style initially
        applyDynamicStyle(googleMap)

        // Focus on Sennhof initially
        val sennhof = LatLng(47.4678, 8.757970)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(sennhof, 18.0f))
        
        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                scheduleNextAction(5000) {
                    moveCameraRandomly(googleMap)
                }
            }
        }

        isTourActive = true
        moveCameraRandomly(googleMap)
    }

    private fun scheduleNextAction(delayMs: Long, action: () -> Unit) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isTourActive) {
                action()
            }
        }, delayMs)
    }

    private fun applyDynamicStyle(googleMap: GoogleMap) {
        val featureTypes = listOf(
            "landscape.man_made", "poi.business", "poi.government", "poi.medical",
            "poi.park", "poi.place_of_worship", "poi.school", "poi.sports_complex",
            "transit.station.airport", "transit.station.bus", "transit.station.rail",
            "landscape.natural", "administrative.locality", "administrative.neighborhood",
            "poi.attraction", "water", "transit.line"
        )
        
        // Randomly decide if we want all 16 styles to hit the array limit, or fewer (e.g. 5 to 15)
        val numStyles = if (Math.random() > 0.5) 16 else Random.nextInt(5, 16)
        
        val jsonBuilder = StringBuilder("[\n")
        for (i in 0 until numStyles) {
            val feature = featureTypes[i]
            // Generate a random hex color for this feature
            val colorStr = String.format("#%06X", Random.nextInt(0xFFFFFF + 1))
            
            jsonBuilder.append("""  { "featureType": "$feature", "elementType": "geometry.fill", "stylers": [ { "color": "$colorStr" } ] }""")
            if (i < numStyles - 1) {
                jsonBuilder.append(",\n")
            } else {
                jsonBuilder.append("\n")
            }
        }
        jsonBuilder.append("]")
        
        // Apply the newly generated JSON string directly as the style
        googleMap.setMapStyle(MapStyleOptions(jsonBuilder.toString()))
    }

    private fun updateStatusText(enabled: Boolean) {
        statusTextView.text = if (enabled) "3D Buildings: ON" else "3D Buildings: OFF"
        statusTextView.setTextColor(if (enabled) Color.GREEN else Color.RED)
    }

    private fun moveCameraRandomly(googleMap: GoogleMap) {
        if (!isTourActive) return

        // Update the dynamic style each time we move to stress the graphics engine
        applyDynamicStyle(googleMap)

        // Randomly enable or disable 3D buildings (approx 30% chance each time)
        if (Math.random() > 0.7) {
            val enabled = !googleMap.isBuildingsEnabled
            googleMap.isBuildingsEnabled = enabled
            updateStatusText(enabled)
        }

        // Sennhof: 47.4678, 8.757970
        // Kollbrunn: 47.45779, 8.7746837
        // Bias towards Sennhof using a cubic power function (t mostly close to 0)
        val t = Math.pow(Math.random(), 3.0)
        val randomLat = 47.4678 + t * (47.45779 - 47.4678)
        val randomLng = 8.757970 + t * (8.7746837 - 8.757970)

        // Zoom levels where 3D buildings typically appear are ~17.5+ 
        // We ensure 50% of the time the zoom level is highly likely to show 3D buildings (e.g. 18.0 - 19.5)
        // The other 50% of the time, the zoom is further out (e.g. 15.0 - 17.5)
        val randomZoom = if (Math.random() < 0.5) {
            18.0f + (Math.random() * 1.5f).toFloat()
        } else {
            15.0f + (Math.random() * 2.5f).toFloat()
        }
        
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(LatLng(randomLat, randomLng), randomZoom)

        // Animate the camera and listen for completion to trigger the next movement
        googleMap.animateCamera(cameraUpdate, 3000, object : GoogleMap.CancelableCallback {
            override fun onFinish() {
                scheduleNextAction(1000) {
                    tiltCamera(googleMap)
                }
            }

            override fun onCancel() {
                scheduleNextAction(5000) {
                    moveCameraRandomly(googleMap)
                }
            }
        })
    }

    private fun tiltCamera(googleMap: GoogleMap) {
        if (!isTourActive) return
        val currentPosition = googleMap.cameraPosition
        val tiltedUp = CameraPosition.Builder(currentPosition).tilt(60f).build()
        googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(tiltedUp), 1000, object : GoogleMap.CancelableCallback {
            override fun onFinish() {
                scheduleNextAction(1000) {
                    val tiltedDown = CameraPosition.Builder(googleMap.cameraPosition).tilt(0f).build()
                    googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(tiltedDown), 1000, object : GoogleMap.CancelableCallback {
                        override fun onFinish() {
                            scheduleNextAction(1000) {
                                moveCameraRandomly(googleMap)
                            }
                        }

                        override fun onCancel() {
                            scheduleNextAction(5000) {
                                moveCameraRandomly(googleMap)
                            }
                        }
                    })
                }
            }

            override fun onCancel() {
                scheduleNextAction(5000) {
                    moveCameraRandomly(googleMap)
                }
            }
        })
    }
}