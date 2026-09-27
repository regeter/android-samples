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

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Mode B — Standalone Synthetic Adreno EGL/GLSL Compiler Stress Engine.
 *
 * Spawns N background worker threads using android.opengl.EGL14.
 * Each worker creates its own EGLDisplay, EGLConfig, EGLContext (ES 2.0), and a 64x64 pbuffer surface.
 * In a tight loop, each worker compiles and links synthetic vertex and fragment shaders that
 * mirror `GmmConfigurableTextureStyleIdShader` (declaring `uStyleColorMap[16]` and `uStyleTextureMap[32]`,
 * 64 vec4 uniforms, unpacking loops, and texture blend branches).
 * Every iteration injects a unique constant seed to bypass driver in-memory cache deduplication
 * and churn `/data/user_de/0/<pkg>/code_cache/com.android.opengl.shaders_cache`.
 */
class AdrenoShaderStressEngine(
    private val onLog: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AdrenoShaderStress"

        fun getShaderCacheInfo(context: Context): Pair<Long, Int> {
            val codeCache = context.codeCacheDir ?: return Pair(0L, 0)
            val shaderCache = File(codeCache, "com.android.opengl.shaders_cache")
            return calculateDirStats(shaderCache)
        }

        fun getCodeCacheTotalInfo(context: Context): Pair<Long, Int> {
            val codeCache = context.codeCacheDir ?: return Pair(0L, 0)
            return calculateDirStats(codeCache)
        }

        fun clearShaderCache(context: Context): Boolean {
            val codeCache = context.codeCacheDir ?: return false
            var deleted = false
            val shaderCache = File(codeCache, "com.android.opengl.shaders_cache")
            if (shaderCache.exists()) {
                deleted = shaderCache.deleteRecursively()
            }
            // Also attempt to remove any driver blob cache in codeCacheDir
            codeCache.listFiles()?.forEach { file ->
                if (file.name.contains("shader", ignoreCase = true) ||
                    file.name.contains("opengl", ignoreCase = true) ||
                    file.name.contains("qgl", ignoreCase = true)
                ) {
                    file.deleteRecursively()
                    deleted = true
                }
            }
            return deleted
        }

        private fun calculateDirStats(file: File): Pair<Long, Int> {
            if (!file.exists()) return Pair(0L, 0)
            if (file.isFile) return Pair(file.length(), 1)
            var size = 0L
            var count = 0
            file.walkTopDown().forEach { f ->
                if (f.isFile) {
                    size += f.length()
                    count++
                }
            }
            return Pair(size, count)
        }
    }

    private val isRunning = AtomicBoolean(false)
    private val totalProgramsLinked = AtomicLong(0)
    private val totalLinkErrors = AtomicLong(0)
    private val workerThreads = mutableListOf<Thread>()

    val programsLinkedCount: Long
        get() = totalProgramsLinked.get()

    val linkErrorsCount: Long
        get() = totalLinkErrors.get()

    val active: Boolean
        get() = isRunning.get()

    @Synchronized
    fun start(numThreads: Int = 3) {
        if (isRunning.get()) {
            Log.w(TAG, "AdrenoShaderStressEngine already running.")
            return
        }
        isRunning.set(true)
        workerThreads.clear()

        log("Starting $numThreads synthetic EGL/GLSL compiler stress threads...")

        for (i in 0 until numThreads) {
            val thread = Thread({
                runStressWorker(workerIndex = i)
            }, "AdrenoStressWorker-$i")
            workerThreads.add(thread)
            thread.start()
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.get()) return
        log("Stopping synthetic compiler stress engine...")
        isRunning.set(false)
        for (thread in workerThreads) {
            thread.interrupt()
        }
        workerThreads.clear()
        log("Stress engine stopped. Total programs linked: ${totalProgramsLinked.get()}")
    }

    private fun runStressWorker(workerIndex: Int) {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "Worker $workerIndex: eglGetDisplay failed")
            return
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "Worker $workerIndex: eglInitialize failed")
            return
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || configs[0] == null) {
            Log.e(TAG, "Worker $workerIndex: eglChooseConfig failed")
            EGL14.eglTerminate(eglDisplay)
            return
        }
        val eglConfig = configs[0]!!

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val eglContext: EGLContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Worker $workerIndex: eglCreateContext failed")
            EGL14.eglTerminate(eglDisplay)
            return
        }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 64,
            EGL14.EGL_HEIGHT, 64,
            EGL14.EGL_NONE
        )
        val eglSurface: EGLSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Worker $workerIndex: eglCreatePbufferSurface failed")
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            return
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Worker $workerIndex: eglMakeCurrent failed")
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            return
        }

        log("Worker $workerIndex: EGL initialized. Starting GLSL compiler churn loop.")

        var iteration = 0L
        try {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                iteration++
                val seed = (iteration * 31 + workerIndex * 10007L)

                val vShaderSrc = buildVertexShaderSource(seed)
                val fShaderSrc = buildFragmentShaderSource(seed)

                val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vShaderSrc)
                val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fShaderSrc)

                if (vShader != 0 && fShader != 0) {
                    val program = GLES20.glCreateProgram()
                    if (program != 0) {
                        GLES20.glAttachShader(program, vShader)
                        GLES20.glAttachShader(program, fShader)
                        GLES20.glLinkProgram(program)

                        val linkStatus = IntArray(1)
                        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)

                        if (linkStatus[0] != 0) {
                            totalProgramsLinked.incrementAndGet()
                        } else {
                            totalLinkErrors.incrementAndGet()
                            val infoLog = GLES20.glGetProgramInfoLog(program)
                            Log.w(TAG, "Worker $workerIndex synthetic link failure: $infoLog")
                        }

                        GLES20.glDetachShader(program, vShader)
                        GLES20.glDetachShader(program, fShader)
                        GLES20.glDeleteProgram(program)
                    }
                }

                if (vShader != 0) GLES20.glDeleteShader(vShader)
                if (fShader != 0) GLES20.glDeleteShader(fShader)

                if (iteration % 50 == 0L) {
                    GLES20.glFlush()
                }
            }
        } catch (e: InterruptedException) {
            // normal termination
        } catch (e: Exception) {
            Log.e(TAG, "Worker $workerIndex error in stress loop: ${e.message}", e)
        } finally {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            Log.d(TAG, "Worker $workerIndex: EGL context torn down.")
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            Log.w(TAG, "Shader compile failed: $log")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun buildVertexShaderSource(seed: Long): String {
        return """
            precision highp float;
            #define MAX_STYLE_ENTRIES 64
            #define NUM_UNIFORMS_PER_STYLE_TEXTURE 2
            // Unique mutation to invalidate LLVM/Adreno compiler disk cache:
            // SEED_$seed
            uniform mat4 uMVPMatrix;
            uniform ivec4 uStyleColorMap[16];
            uniform ivec4 uStyleTextureMap[32];
            uniform ivec4 uStyleColors[MAX_STYLE_ENTRIES];
            uniform ivec4 uStyleTextures[MAX_STYLE_ENTRIES * NUM_UNIFORMS_PER_STYLE_TEXTURE];
            uniform vec2 uTileCoords;
            uniform float uTileScale;
            uniform float uTileSizeFactor;
            uniform float uCameraZoomFract;
            uniform vec4 uCameraZoomFractPrecomputations;
            attribute vec4 aPosition;
            attribute vec4 aStyleInfo;
            varying vec4 vColor;
            varying vec2 vTexCoord;

            vec4 Ivec2ToRgba(ivec2 col) {
                vec4 rgba;
                rgba.a = floor(float(col.x) * (1.0 / 256.0)) * (1.0 / 255.0);
                if (rgba.a != 0.0) {
                    rgba.r = fract(float(col.x) * (1.0 / 256.0)) * (256.0 / 255.0);
                    rgba.b = fract(float(col.y) * (1.0 / 256.0)) * (256.0 / 255.0);
                    rgba.g = floor(float(col.y) * (1.0 / 256.0)) * (1.0 / 255.0);
                }
                return rgba;
            }

            void main() {
                int styleIndex = int(aStyleInfo.r);
                ivec4 styleEntry = ivec4(0);
                ivec4 tex0 = ivec4(0);
                ivec4 tex1 = ivec4(0);
                for (int i = 0; i < MAX_STYLE_ENTRIES; i++) {
                    if (styleIndex == i) {
                        styleEntry = uStyleColors[i];
                        tex0 = uStyleTextures[i * NUM_UNIFORMS_PER_STYLE_TEXTURE];
                        tex1 = uStyleTextures[i * NUM_UNIFORMS_PER_STYLE_TEXTURE + 1];
                    }
                }
                int mapIdx = int(mod(aStyleInfo.b, 16.0));
                styleEntry = styleEntry + uStyleColorMap[mapIdx];
                tex0 = tex0 + uStyleTextureMap[mapIdx * 2];
                tex1 = tex1 + uStyleTextureMap[mapIdx * 2 + 1];

                ivec2 styleColor = int(aStyleInfo.g) == 1 ? styleEntry.zw : styleEntry.xy;
                vColor = Ivec2ToRgba(styleColor);
                vTexCoord = aPosition.xy * uTileScale + uTileCoords;

                float SEED_F = ${seed % 1000000L}.0 * 0.000001;
                gl_Position = uMVPMatrix * aPosition + vec4(SEED_F, SEED_F * 0.5, 0.0, 0.0);
            }
        """.trimIndent()
    }

    private fun buildFragmentShaderSource(seed: Long): String {
        return """
            precision mediump float;
            // SEED_FRAG_$seed
            varying vec4 vColor;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture0;
            uniform sampler2D uTexture1;
            uniform int uBlendMode;

            void main() {
                vec4 texColor = texture2D(uTexture0, vTexCoord);
                if (uBlendMode == 1) {
                    texColor = mix(texColor, texture2D(uTexture1, vTexCoord), 0.5);
                } else if (uBlendMode == 2) {
                    texColor = texColor * vColor;
                } else if (uBlendMode == 3) {
                    texColor = texColor + texture2D(uTexture1, vTexCoord) * 0.25;
                }
                float SEED_F = ${seed % 1000000L}.0 * 0.000001;
                gl_FragColor = texColor * vColor + vec4(SEED_F, 0.0, SEED_F * 0.25, 0.0);
            }
        """.trimIndent()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        onLog?.invoke(message)
    }
}
