package com.example.kotlindemos

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.SupportStreetViewPanoramaFragment
import com.google.android.gms.maps.model.LatLng
import kotlin.random.Random

class MapLoadTestActivity : SamplesBaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var countdown = 3
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val locationIndex = intent.getIntExtra("LOCATION_INDEX", 0)
        val location = LOCATIONS.getOrElse(locationIndex) { LOCATIONS[0] }

        val rootLayout = FrameLayout(this).apply {
            id = View.generateViewId()
        }
        setContentView(rootLayout)

        val chosenMode = intent.getStringExtra("CHOSEN_MODE") ?: listOf("NORMAL", "SATELLITE", "HYBRID", "TERRAIN", "STREET_VIEW").random()
        val buildingsEnabled = Random.nextBoolean()

        statusTextView = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            text = "Loading...\nLocation: ${location.name}\nMode: $chosenMode\nBuildings: ${if (buildingsEnabled) "ON" else "OFF"}\nRemaining: ${countdown}s"
        }

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
                .replace(rootLayout.id, fragment)
                .commit()
            fragment.getStreetViewPanoramaAsync { panorama ->
                panorama.setPosition(location.latLng)
                startCountdown()
            }
        } else {
            val fragment = SupportMapFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(rootLayout.id, fragment)
                .commit()
            fragment.getMapAsync { googleMap ->
                googleMap.isBuildingsEnabled = buildingsEnabled
                googleMap.isTrafficEnabled = Random.nextBoolean()
                googleMap.mapType = when (chosenMode) {
                    "SATELLITE" -> GoogleMap.MAP_TYPE_SATELLITE
                    "HYBRID" -> GoogleMap.MAP_TYPE_HYBRID
                    "TERRAIN" -> GoogleMap.MAP_TYPE_TERRAIN
                    else -> GoogleMap.MAP_TYPE_NORMAL
                }
                val zoom = if (buildingsEnabled && chosenMode == "NORMAL") 18.0f else 15.0f
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(location.latLng, zoom))
                startCountdown()
            }
        }

        statusTextView.bringToFront()
    }

    private fun startCountdown() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                countdown--
                val currentText = statusTextView.text.toString()
                statusTextView.text = currentText.substringBefore("Remaining: ") + "Remaining: ${countdown}s"

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
