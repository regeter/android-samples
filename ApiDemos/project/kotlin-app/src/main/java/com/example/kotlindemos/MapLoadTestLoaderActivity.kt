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
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.random.Random

class MapLoadTestLoaderActivity : SamplesBaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentIndex = 0
    private var isTestingActive = false
    private var iterationCount = 0

    private var normalWeight = 70
    private var satelliteWeight = 5
    private var hybridWeight = 25
    private var terrainWeight = 0
    private var streetViewWeight = 0

    private lateinit var statusTextView: TextView
    private lateinit var toggleButton: Button
    private lateinit var btnRun10x: Button
    private lateinit var btnRunOnce: Button
    private lateinit var slidersContainer: LinearLayout
    private lateinit var btnRobustGl: Button
    private lateinit var btnColocationWebView: Button
    private var targetRemainingRuns = 0

    private val mapLoadTestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        iterationCount++
        currentIndex = (currentIndex + 1) % MapLoadTestActivity.LOCATIONS.size

        if (targetRemainingRuns > 0) {
            targetRemainingRuns--
            if (targetRemainingRuns <= 0) {
                isTestingActive = false
                targetRemainingRuns = 0
            }
        }

        updateUI()

        if (isTestingActive && !isFinishing && !isDestroyed) {
            startNextTest()
        }
    }

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

        // Colocation buttons section
        val colocationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
                bottomMargin = 24
            }
        }
        rootLayout.addView(colocationContainer)

        btnRobustGl = Button(this).apply {
            textSize = 12f
            setPadding(20, 24, 20, 24)
            setOnClickListener {
                RobustGlThreadManager.toggle()
                updateColocationButtons()
            }
        }
        colocationContainer.addView(btnRobustGl)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                16
            )
        }
        colocationContainer.addView(spacer)

        btnColocationWebView = Button(this).apply {
            textSize = 12f
            setPadding(20, 24, 20, 24)
            setOnClickListener {
                ColocationWebViewManager.isEnabled = !ColocationWebViewManager.isEnabled
                updateColocationButtons()
            }
        }
        colocationContainer.addView(btnColocationWebView)

        statusTextView = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }
        rootLayout.addView(statusTextView)

        toggleButton = Button(this).apply {
            textSize = 16f
            setPadding(32, 32, 32, 32)
            setOnClickListener {
                if (isTestingActive) {
                    isTestingActive = false
                    targetRemainingRuns = 0
                    handler.removeCallbacksAndMessages(null)
                    updateUI()
                } else {
                    isTestingActive = true
                    targetRemainingRuns = -1 // continuous
                    updateUI()
                    startNextTest()
                }
            }
        }
        rootLayout.addView(toggleButton)

        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                16
            )
        }
        rootLayout.addView(spacer1)

        btnRun10x = Button(this).apply {
            text = "Run Test 10x"
            textSize = 15f
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.parseColor("#1976D2")) // Blue
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isTestingActive && targetRemainingRuns > 0) {
                    isTestingActive = false
                    targetRemainingRuns = 0
                    handler.removeCallbacksAndMessages(null)
                    updateUI()
                } else {
                    isTestingActive = true
                    targetRemainingRuns = 10
                    updateUI()
                    startNextTest()
                }
            }
        }
        rootLayout.addView(btnRun10x)

        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                16
            )
        }
        rootLayout.addView(spacer2)

        btnRunOnce = Button(this).apply {
            text = "Run Test Once"
            textSize = 15f
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.parseColor("#7B1FA2")) // Purple
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isTestingActive && targetRemainingRuns == 1) {
                    isTestingActive = false
                    targetRemainingRuns = 0
                    handler.removeCallbacksAndMessages(null)
                    updateUI()
                } else {
                    isTestingActive = true
                    targetRemainingRuns = 1
                    updateUI()
                    startNextTest()
                }
            }
        }
        rootLayout.addView(btnRunOnce)

        updateColocationButtons()
        updateUI()
    }

    private fun updateColocationButtons() {
        if (!::btnRobustGl.isInitialized || !::btnColocationWebView.isInitialized) return
        val glActive = RobustGlThreadManager.isRunning
        btnRobustGl.text = if (glActive) {
            "Colocation run openGL with EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT: [ACTIVE]"
        } else {
            "Colocation run openGL with EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT"
        }
        btnRobustGl.setBackgroundColor(if (glActive) Color.parseColor("#E65100") else Color.parseColor("#37474F"))
        btnRobustGl.setTextColor(Color.WHITE)

        val webActive = ColocationWebViewManager.isEnabled
        btnColocationWebView.text = if (webActive) {
            "Colocation run WebView: [ACTIVE]"
        } else {
            "Colocation run WebView"
        }
        btnColocationWebView.setBackgroundColor(if (webActive) Color.parseColor("#00838F") else Color.parseColor("#37474F"))
        btnColocationWebView.setTextColor(Color.WHITE)
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
        val stateText = when {
            !isTestingActive -> "PAUSED"
            targetRemainingRuns > 0 -> "RUNNING ($targetRemainingRuns remaining)"
            else -> "ACTIVE (Continuous)"
        }
        val nextLocation = MapLoadTestActivity.LOCATIONS[currentIndex].name
        
        statusTextView.text = "Completed Iterations: $iterationCount\nStatus: $stateText\nNext Location: $nextLocation"
        
        if (isTestingActive) {
            slidersContainer.visibility = View.GONE
            if (targetRemainingRuns == -1) {
                toggleButton.text = "PAUSE TEST"
                toggleButton.setBackgroundColor(Color.RED)
            } else {
                toggleButton.text = "CANCEL TEST"
                toggleButton.setBackgroundColor(Color.parseColor("#D32F2F"))
            }

            if (targetRemainingRuns > 1) {
                btnRun10x.text = "STOP 10x TEST ($targetRemainingRuns left)"
                btnRun10x.setBackgroundColor(Color.RED)
            } else {
                btnRun10x.text = "Run Test 10x"
                btnRun10x.setBackgroundColor(Color.parseColor("#1976D2"))
            }

            if (targetRemainingRuns == 1) {
                btnRunOnce.text = "RUNNING ONCE..."
                btnRunOnce.setBackgroundColor(Color.RED)
            } else {
                btnRunOnce.text = "Run Test Once"
                btnRunOnce.setBackgroundColor(Color.parseColor("#7B1FA2"))
            }
        } else {
            slidersContainer.visibility = View.VISIBLE
            toggleButton.text = "START LOAD TEST"
            toggleButton.setBackgroundColor(Color.parseColor("#4CAF50")) // Green

            btnRun10x.text = "Run Test 10x"
            btnRun10x.setBackgroundColor(Color.parseColor("#1976D2"))

            btnRunOnce.text = "Run Test Once"
            btnRunOnce.setBackgroundColor(Color.parseColor("#7B1FA2"))
        }
        toggleButton.setTextColor(Color.WHITE)
        btnRun10x.setTextColor(Color.WHITE)
        btnRunOnce.setTextColor(Color.WHITE)
        updateColocationButtons()
    }

    override fun onResume() {
        super.onResume()
        updateColocationButtons()
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
                mapLoadTestLauncher.launch(intent)
            }
        }, 1000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
