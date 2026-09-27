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

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time logcat reader that monitors process output for Qualcomm Adreno shader link failures
 * and GmmRenderTarget GL context resets in maps_core_dynamite.
 *
 * Catches the critical first non-fatal reset warning:
 *   "GL context is unusable. This may be due to an invalid shader, or may be due to bad GL state. Resetting GL context to attempt to recover."
 * And the fatal second hit within 10,000ms:
 *   "GL context is unusable again after <N>ms"
 */
class LogcatResetDetector(
    private val onFirstHit: (line: String, timestamp: Long) -> Unit,
    private val onFatalCrash: (line: String, timestamp: Long) -> Unit,
    private val onLogLine: ((line: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "LogcatResetDetector"
    }

    private var job: Job? = null
    private var process: java.lang.Process? = null
    private val isRunning = AtomicBoolean(false)
    val hasFirstHit = AtomicBoolean(false)
    val hasFatalCrash = AtomicBoolean(false)
    var firstHitTime: Long = 0L
        private set
    var firstHitLine: String = ""
        private set

    fun start(scope: CoroutineScope) {
        if (isRunning.getAndSet(true)) return

        job = scope.launch(Dispatchers.IO) {
            try {
                // Clear stale logs before monitoring
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
                }

                val myPid = Process.myPid().toString()
                // Stream logcat for current process
                val cmd = arrayOf("logcat", "-v", "time", "--pid=$myPid")
                val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                process = proc

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                while (isActive && isRunning.get()) {
                    val line = reader.readLine() ?: break
                    processLine(line)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Logcat detector exception: ${e.message}", e)
                }
            } finally {
                cleanup()
            }
        }
    }

    private fun processLine(line: String) {
        // Forward relevant log lines to UI console
        if (isRelevantLogLine(line)) {
            onLogLine?.invoke(line)
        }

        // Check for fatal second hit
        if (line.contains("GL context is unusable again after", ignoreCase = true) ||
            (line.contains("UnusableGlContextException", ignoreCase = true) && line.contains("again after", ignoreCase = true))
        ) {
            if (!hasFatalCrash.getAndSet(true)) {
                val now = SystemClock.elapsedRealtime()
                Log.e(TAG, "FATAL CRASH HIT: $line")
                onFatalCrash(line, now)
            }
            return
        }

        // Check for first hit (non-fatal recovery reset)
        val isFirstReset = line.contains("Resetting GL context to attempt to recover", ignoreCase = true) ||
                (line.contains("GL context is unusable", ignoreCase = true) && !line.contains("again after", ignoreCase = true)) ||
                line.contains("glLinkProgram failed", ignoreCase = true)

        if (isFirstReset) {
            if (!hasFirstHit.getAndSet(true)) {
                firstHitTime = SystemClock.elapsedRealtime()
                firstHitLine = line
                Log.w(TAG, "FIRST HIT DETECTED! GmmRenderTarget 10-second recovery trap window active: $line")
                onFirstHit(line, firstHitTime)
            }
        }
    }

    private fun isRelevantLogLine(line: String): Boolean {
        return line.contains("GL context", ignoreCase = true) ||
                line.contains("GmmRenderTarget", ignoreCase = true) ||
                line.contains("GmmConfigurable", ignoreCase = true) ||
                line.contains("glLinkProgram", ignoreCase = true) ||
                line.contains("UnusableGlContext", ignoreCase = true) ||
                line.contains("Adreno", ignoreCase = true) ||
                line.contains("maps_core", ignoreCase = true) ||
                line.contains("forceEGLContextReset", ignoreCase = true)
    }

    fun resetDetector() {
        hasFirstHit.set(false)
        hasFatalCrash.set(false)
        firstHitTime = 0L
        firstHitLine = ""
    }

    fun stop() {
        isRunning.set(false)
        job?.cancel()
        job = null
        cleanup()
    }

    private fun cleanup() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
    }
}
