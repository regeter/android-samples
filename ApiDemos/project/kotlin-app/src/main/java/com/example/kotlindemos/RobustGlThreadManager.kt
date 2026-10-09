package com.example.kotlindemos

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

/**
 * Manages an isolated OpenGL thread that creates and maintains an EGLContext
 * initialized with EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT (0x30BF = 1)
 * based on QualcommGLcontextbleedcrash.
 */
object RobustGlThreadManager {
    private const val TAG = "ROBUST_GL_THREAD"

    const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    const val EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT = 0x30BF
    const val EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT = 0x31BD
    const val EGL_LOSE_CONTEXT_ON_RESET_EXT = 0x31BE

    @Volatile
    var isRunning = false
        private set

    private var workerThread: Thread? = null

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        workerThread = Thread({
            var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT

            try {
                Log.i(TAG, "Starting robust GL thread with EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT...")
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                    throw RuntimeException("eglGetDisplay failed: ${EGL14.eglGetError()}")
                }

                val version = IntArray(2)
                if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                    throw RuntimeException("eglInitialize failed: ${EGL14.eglGetError()}")
                }

                val configAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                )

                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] == 0 || configs[0] == null
                ) {
                    throw RuntimeException("eglChooseConfig failed: ${EGL14.eglGetError()}")
                }
                val eglConfig = configs[0]

                val surfaceAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
                )

                eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    throw RuntimeException("eglCreatePbufferSurface failed: ${EGL14.eglGetError()}")
                }

                val candidates = listOf(
                    intArrayOf(
                        EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT, 1,
                        EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT, EGL_LOSE_CONTEXT_ON_RESET_EXT,
                        EGL14.EGL_NONE
                    ),
                    intArrayOf(
                        EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT, 1,
                        EGL14.EGL_NONE
                    )
                )

                for (attribList in candidates) {
                    eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribList, 0)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        Log.i(TAG, "Robust EGL context successfully created!")
                        break
                    }
                }

                if (eglContext == EGL14.EGL_NO_CONTEXT) {
                    throw RuntimeException("eglCreateContext failed with EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT: ${EGL14.eglGetError()}")
                }

                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    throw RuntimeException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
                }

                Log.i(TAG, "Robust EGLContext is now CURRENT on thread. Thread running passively...")

                while (isRunning) {
                    Thread.sleep(500)
                }

            } catch (_: InterruptedException) {
                Log.i(TAG, "Robust GL thread interrupted.")
            } catch (t: Throwable) {
                Log.e(TAG, "Error in robust GL thread: ${t.message}", t)
            } finally {
                Log.i(TAG, "Tearing down robust GL context...")
                try {
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (eglContext != EGL14.EGL_NO_CONTEXT) {
                            EGL14.eglDestroyContext(eglDisplay, eglContext)
                        }
                        if (eglSurface != EGL14.EGL_NO_SURFACE) {
                            EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Cleanup exception: ${t.message}")
                }
                isRunning = false
            }
        }, "RobustGlThread").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
    }

    @Synchronized
    fun toggle(): Boolean {
        if (isRunning) {
            stop()
        } else {
            start()
        }
        return isRunning
    }
}
