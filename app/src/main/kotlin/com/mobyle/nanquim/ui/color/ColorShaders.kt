package com.mobyle.nanquim.ui.color

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

@RequiresApi(33)
class ColorShaders {
    val ring = RuntimeShader(HSB + RING)

    val disc = RuntimeShader(HSB + DISC)

    fun setRing(
        centerX: Float,
        centerY: Float,
    ) {
        ring.setFloatUniform("center", centerX, centerY)
    }

    fun setDisc(
        centerX: Float,
        centerY: Float,
        radius: Float,
        hue: Float,
        scale: Float,
        zoomCenterX: Float,
        zoomCenterY: Float,
    ) {
        disc.setFloatUniform("center", centerX, centerY)
        disc.setFloatUniform("radius", radius)
        disc.setFloatUniform("hue", hue)
        disc.setFloatUniform("scale", scale)
        disc.setFloatUniform("zoomCenter", zoomCenterX, zoomCenterY)
    }

    private companion object {
        const val HSB = """
            half3 hsbToRgb(float hue, float saturation, float brightness) {
                float chroma = brightness * saturation;
                float sector = hue / 60.0;
                float second = chroma * (1.0 - abs(mod(sector, 2.0) - 1.0));
                float3 rgb;
                if (sector < 1.0) { rgb = float3(chroma, second, 0.0); }
                else if (sector < 2.0) { rgb = float3(second, chroma, 0.0); }
                else if (sector < 3.0) { rgb = float3(0.0, chroma, second); }
                else if (sector < 4.0) { rgb = float3(0.0, second, chroma); }
                else if (sector < 5.0) { rgb = float3(second, 0.0, chroma); }
                else { rgb = float3(chroma, 0.0, second); }
                return half3(clamp(rgb + (brightness - chroma), 0.0, 1.0));
            }
            float hueAt(float2 up) {
                float angle = atan(up.x, up.y) * 57.29577951308232;
                return mod(angle + 360.0, 360.0);
            }
        """

        const val RING = """
            uniform float2 center;
            half4 main(float2 position) {
                float2 offset = position - center;
                return half4(hsbToRgb(hueAt(float2(offset.x, -offset.y)), 1.0, 1.0), 1.0);
            }
        """

        const val DISC = """
            uniform float2 center;
            uniform float radius;
            uniform float hue;
            uniform float scale;
            uniform float2 zoomCenter;
            float discToSquare(float along, float across) {
                float base = 2.0 + along * along - across * across;
                float twoRootTwo = 2.8284271247461903 * along;
                return clamp(0.5 * sqrt(max(base + twoRootTwo, 0.0)) - 0.5 * sqrt(max(base - twoRootTwo, 0.0)), -1.0, 1.0);
            }
            half4 main(float2 position) {
                float2 view = (position - center) / radius;
                view.y = -view.y;
                float2 point = zoomCenter + view / scale;
                float pointLength = length(point);
                if (pointLength > 1.0) { point = point / pointLength; }
                float saturation = clamp((discToSquare(point.x, point.y) + 1.0) / 2.0, 0.0, 1.0);
                float brightness = clamp((discToSquare(point.y, point.x) + 1.0) / 2.0, 0.0, 1.0);
                return half4(hsbToRgb(hue, saturation, brightness), 1.0);
            }
        """
    }
}
