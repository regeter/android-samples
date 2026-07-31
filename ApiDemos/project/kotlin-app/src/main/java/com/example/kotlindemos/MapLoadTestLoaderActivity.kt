package com.example.kotlindemos

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.random.Random

class MapLoadTestLoaderActivity : SamplesBaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentIndex = 0
    private var isTestingActive = false
    private var iterationCount = 0

    private var normalWeight = 50
    private var satelliteWeight = 15
    private var hybridWeight = 15
    private var terrainWeight = 15
    private var streetViewWeight = 5

    private lateinit var statusTextView: TextView
    private lateinit var toggleButton: Button
    private lateinit var slidersContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }
        setContentView(scrollView)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }
        scrollView.addView(rootLayout)

        val titleTextView = TextView(this).apply {
            text = "Map Setup Load Tester"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(titleTextView)

        val subtitleTextView = TextView(this).apply {
            text = "Configure map setup weight distributions below"
            setTextColor(Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(subtitleTextView)

        slidersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(slidersContainer)

        // Add Sliders
        slidersContainer.addView(createSlider(this, "Normal Map Weight", normalWeight) { normalWeight = it })
        slidersContainer.addView(createSlider(this, "Satellite Map Weight", satelliteWeight) { satelliteWeight = it })
        slidersContainer.addView(createSlider(this, "Hybrid Map Weight", hybridWeight) { hybridWeight = it })
        slidersContainer.addView(createSlider(this, "Terrain Map Weight", terrainWeight) { terrainWeight = it })
        slidersContainer.addView(createSlider(this, "Street View Weight", streetViewWeight) { streetViewWeight = it })

        statusTextView = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 48)
        }
        rootLayout.addView(statusTextView)

        toggleButton = Button(this).apply {
            textSize = 16f
            setPadding(32, 32, 32, 32)
            setOnClickListener {
                isTestingActive = !isTestingActive
                updateUI()
                if (isTestingActive) {
                    startNextTest()
                } else {
                    handler.removeCallbacksAndMessages(null)
                }
            }
        }
        rootLayout.addView(toggleButton)

        updateUI()
    }

    private fun createSlider(
        context: Context,
        labelPrefix: String,
        initialProgress: Int,
        onProgressChanged: (Int) -> Unit
    ): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(context).apply {
            text = "$labelPrefix: $initialProgress%"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        container.addView(label)

        val seekBar = SeekBar(context).apply {
            max = 100
            progress = initialProgress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$labelPrefix: $progress%"
                    onProgressChanged(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(seekBar)

        return container
    }

    private fun selectModeBasedOnWeights(): String {
        val normal = normalWeight
        val satellite = satelliteWeight
        val hybrid = hybridWeight
        val terrain = terrainWeight
        val streetView = streetViewWeight

        val total = normal + satellite + hybrid + terrain + streetView
        if (total == 0) return "NORMAL"

        val randomVal = Random.nextInt(total)
        var runningSum = 0

        runningSum += normal
        if (randomVal < runningSum) return "NORMAL"

        runningSum += satellite
        if (randomVal < runningSum) return "SATELLITE"

        runningSum += hybrid
        if (randomVal < runningSum) return "HYBRID"

        runningSum += terrain
        if (randomVal < runningSum) return "TERRAIN"

        return "STREET_VIEW"
    }

    private fun updateUI() {
        val stateText = if (isTestingActive) "ACTIVE" else "PAUSED"
        val nextLocation = MapLoadTestActivity.LOCATIONS[currentIndex].name
        
        statusTextView.text = "Completed Iterations: $iterationCount\nStatus: $stateText\nNext Location: $nextLocation"
        
        if (isTestingActive) {
            slidersContainer.visibility = View.GONE
            toggleButton.text = "PAUSE TEST"
            toggleButton.setBackgroundColor(Color.RED)
        } else {
            slidersContainer.visibility = View.VISIBLE
            toggleButton.text = "START LOAD TEST"
            toggleButton.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
        }
        toggleButton.setTextColor(Color.WHITE)
    }

    override fun onResume() {
        super.onResume()
        if (isTestingActive) {
            startNextTest()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startNextTest() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isTestingActive && !isFinishing && !isDestroyed) {
                val chosenMode = selectModeBasedOnWeights()
                val intent = Intent(this, MapLoadTestActivity::class.java).apply {
                    putExtra("LOCATION_INDEX", currentIndex)
                    putExtra("CHOSEN_MODE", chosenMode)
                }
                startActivityForResult(intent, REQUEST_CODE)
            }
        }, 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            iterationCount++
            currentIndex = (currentIndex + 1) % MapLoadTestActivity.LOCATIONS.size
            updateUI()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CODE = 9999
    }
}
