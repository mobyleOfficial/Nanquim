package com.mobyle.nanquim.engine

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES31
import android.util.Log
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Compiles variants of selection_blur and composite_layers straight through the driver, to find
 * which construct crashes the Samsung Xclipse GLES compiler. Each test must run in its own process
 * (Test Orchestrator), because the crash kills the process.
 */
class SamplerArrayCompileProbeTest {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE

    @Before
    fun makeContextCurrent() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes =
            intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        context =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        Log.i(TAG, "Driver: ${GLES31.glGetString(GLES31.GL_RENDERER)} | ${GLES31.glGetString(GLES31.GL_VERSION)}")
    }

    @After
    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    @Test fun a_selectionBlurAsShipped() =
        compiles(
            "A selection_blur as shipped",
            selectionBlur(separateSamplers = false, boolUniforms = true),
        )

    @Test fun b_selectionBlurSeparateSamplers() =
        compiles("B selection_blur separate samplers", selectionBlur(separateSamplers = true, boolUniforms = true))

    @Test fun c_selectionBlurIntInsteadOfBool() =
        compiles("C selection_blur int uniforms", selectionBlur(separateSamplers = false, boolUniforms = false))

    @Test fun d_minimalSamplerArray() =
        compiles(
            "D minimal sampler array",
            """
            |#version 310 es
            |precision highp float;
            |layout(binding = 1) uniform highp sampler2D pages[3];
            |layout(location = 0) out vec4 fragmentColor;
            |void main() {
            |  ivec2 p = ivec2(gl_FragCoord.xy);
            |  fragmentColor = texelFetch(pages[0], p, 0) + texelFetch(pages[1], p, 0) + texelFetch(pages[2], p, 0);
            |}
            """.trimMargin(),
        )

    @Test fun e_compositeLayersAsShipped() = compiles("E composite_layers as shipped", compositeLayers(separateSamplers = false))

    @Test fun f_compositeLayersSeparateSamplers() =
        compiles(
            "F composite_layers separate samplers",
            compositeLayers(separateSamplers = true),
        )

    private fun compiles(
        name: String,
        source: String,
    ) {
        Log.i(TAG, "Compiling $name")
        val shader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER)
        GLES31.glShaderSource(shader, source)
        GLES31.glCompileShader(shader)
        val status = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
        val infoLog = GLES31.glGetShaderInfoLog(shader)
        GLES31.glDeleteShader(shader)
        Log.i(TAG, "Result $name: ${if (status[0] != 0) "compiled" else "failed: $infoLog"}")
        assertTrue("$name: $infoLog", status[0] != 0)
    }

    private fun selectionBlur(
        separateSamplers: Boolean,
        boolUniforms: Boolean,
    ): String {
        val flag = if (boolUniforms) "bool" else "int"
        val truth = if (boolUniforms) "" else " != 0"
        val samplers =
            if (separateSamplers) {
                "layout(binding = 1) uniform highp sampler2D page0;\n" +
                    "layout(binding = 2) uniform highp sampler2D page1;\n" +
                    "layout(binding = 3) uniform highp sampler2D page2;"
            } else {
                "layout(binding = 1) uniform highp sampler2D pages[3];"
            }

        fun page(index: Int) = if (separateSamplers) "page$index" else "pages[$index]"
        return """
            |#version 310 es
            |precision highp float;
            |precision highp int;
            |const int kMaximumHalfWidth = 100;
            |$samplers
            |uniform ivec2 slotOrigins[3];
            |uniform $flag hasTiles[3];
            |uniform $flag horizontal;
            |uniform ivec2 tileOrigin;
            |uniform ivec2 canvasSize;
            |uniform int halfWidth;
            |uniform float weights[101];
            |layout(location = 0) out vec4 fragmentColor;
            |float valueAt(int position, ivec2 local) {
            |  int tile = position < 0 ? 0 : (position >= 256 ? 2 : 1);
            |  int inTile = position - (tile - 1) * 256;
            |  ivec2 texel = horizontal$truth ? ivec2(inTile, local.y) : ivec2(local.x, inTile);
            |  ivec2 canvasPixel = tileOrigin + (horizontal$truth ? ivec2(position, local.y) : ivec2(local.x, position));
            |  if (any(lessThan(canvasPixel, ivec2(0))) || any(greaterThanEqual(canvasPixel, canvasSize))) {
            |    return 0.0;
            |  }
            |  if (tile == 0) {
            |    return hasTiles[0]$truth ? texelFetch(${page(0)}, slotOrigins[0] + texel, 0).r : 0.0;
            |  }
            |  if (tile == 1) {
            |    return hasTiles[1]$truth ? texelFetch(${page(1)}, slotOrigins[1] + texel, 0).r : 0.0;
            |  }
            |  return hasTiles[2]$truth ? texelFetch(${page(2)}, slotOrigins[2] + texel, 0).r : 0.0;
            |}
            |void main() {
            |  ivec2 local = ivec2(gl_FragCoord.xy);
            |  int center = horizontal$truth ? local.x : local.y;
            |  float sum = weights[0] * valueAt(center, local);
            |  for (int offset = 1; offset <= halfWidth; ++offset) {
            |    sum += weights[offset] * (valueAt(center - offset, local) + valueAt(center + offset, local));
            |  }
            |  fragmentColor = vec4(vec3(sum), 1.0);
            |}
            """.trimMargin()
    }

    private fun compositeLayers(separateSamplers: Boolean): String {
        val samplers =
            if (separateSamplers) {
                (0 until LAYERS).joinToString("\n") { "layout(binding = ${it + 1}) uniform highp sampler2D atlasPage$it;" }
            } else {
                "layout(binding = 1) uniform highp sampler2D atlasPages[$LAYERS];"
            }
        val steps =
            (0 until LAYERS).joinToString("\n") {
                val page = if (separateSamplers) "atlasPage$it" else "atlasPages[$it]"
                "  if (layerCount > $it) result = over(tinted(texelFetch($page, layerSlotOrigin[$it] + local, 0), layerTint[$it]) * layerOpacity[$it], result);"
            }
        return """
            |#version 310 es
            |precision highp float;
            |precision highp int;
            |$samplers
            |uniform int layerCount;
            |uniform ivec2 layerSlotOrigin[$LAYERS];
            |uniform float layerOpacity[$LAYERS];
            |uniform vec4 layerTint[$LAYERS];
            |uniform ivec2 targetOrigin;
            |uniform int sampleStride;
            |layout(location = 0) out vec4 fragmentColor;
            |vec4 tinted(vec4 color, vec4 tint) {
            |  return vec4(mix(color.rgb, tint.rgb * color.a, tint.a), color.a);
            |}
            |vec4 over(vec4 source, vec4 destination) {
            |  return source + destination * (1.0 - source.a);
            |}
            |void main() {
            |  ivec2 local = (ivec2(gl_FragCoord.xy) - targetOrigin) * sampleStride;
            |  vec4 result = vec4(0.0);
            |$steps
            |  fragmentColor = result;
            |}
            """.trimMargin()
    }

    private companion object {
        const val TAG = "SamplerArrayProbe"
        const val EGL_OPENGL_ES3_BIT = 0x40
        const val LAYERS = 15
    }
}
