package com.mobyle.nanquim.document.color

import java.util.Random

object PaletteExtractor {
    const val MAXIMUM_SIDE = 256
    const val ITERATIONS = 10
    const val DUPLICATE_DISTANCE = 0.02
    const val DEFAULT_COUNT = 30

    const val LIVE_SIDE = 128
    private const val SEED = 0x4E414E5155494DL

    private const val NEUTRAL_CHROMA = 0.03
    private const val HUE_SECTOR_DEGREES = 30.0

    fun extract(
        argb: IntArray,
        width: Int,
        height: Int,
        count: Int = DEFAULT_COUNT,
        initial: List<OklabColor> = emptyList(),
        iterations: Int = ITERATIONS,
        maximumSide: Int = MAXIMUM_SIDE,
    ): List<ColorValue> {
        require(width > 0 && height > 0 && argb.size >= width * height) { "Bad image size" }
        val points = downscale(argb, width, height, maximumSide)
        if (points.isEmpty() || count <= 0) return emptyList()
        val centers =
            if (initial.size >=
                count.coerceAtMost(points.size)
            ) {
                initial.take(count).toMutableList()
            } else {
                seedCenters(points, count)
            }
        val sizes = cluster(points, centers, iterations)
        val kept = mutableListOf<OklabColor>()
        for (index in centers.indices.sortedByDescending { sizes[it] }) {
            if (sizes[index] == 0) continue
            val center = centers[index]
            if (kept.none { it.distanceTo(center) < DUPLICATE_DISTANCE }) kept += center
        }
        return sortForPalette(kept).map { it.toColorValue() }
    }

    fun centersOf(colors: List<ColorValue>): List<OklabColor> =
        colors.map { StrictOklab.fromSrgb(it.red.toDouble(), it.green.toDouble(), it.blue.toDouble()) }

    internal fun downscale(
        argb: IntArray,
        width: Int,
        height: Int,
        maximumSide: Int = MAXIMUM_SIDE,
    ): List<OklabColor> {
        val factor = maxOf(1, (maxOf(width, height) + maximumSide - 1) / maximumSide)
        val points = ArrayList<OklabColor>(((width + factor - 1) / factor) * ((height + factor - 1) / factor))
        for (top in 0 until height step factor) {
            for (left in 0 until width step factor) {
                var red = 0L
                var green = 0L
                var blue = 0L
                var counted = 0
                for (y in top until minOf(top + factor, height)) {
                    for (x in left until minOf(left + factor, width)) {
                        val pixel = argb[y * width + x]
                        if ((pixel ushr 24) < 128) continue
                        red += (pixel shr 16) and 0xFF
                        green += (pixel shr 8) and 0xFF
                        blue += pixel and 0xFF
                        counted++
                    }
                }
                if (counted == 0) continue
                points += StrictOklab.fromSrgb(red / (255.0 * counted), green / (255.0 * counted), blue / (255.0 * counted))
            }
        }
        return points
    }

    private fun seedCenters(
        points: List<OklabColor>,
        count: Int,
    ): MutableList<OklabColor> {
        val random = Random(SEED)
        val flat = flatten(points)
        val centers = mutableListOf(points[random.nextInt(points.size)])
        val nearest = DoubleArray(points.size) { squared(flat, it, centers[0]) }
        while (centers.size < count) {
            val total = nearest.sum()
            if (total <= 0.0) break
            var target = random.nextDouble() * total
            var chosen = points.size - 1
            for (index in points.indices) {
                target -= nearest[index]
                if (target <= 0.0) {
                    chosen = index
                    break
                }
            }
            val center = points[chosen]
            centers += center
            for (index in points.indices) nearest[index] = minOf(nearest[index], squared(flat, index, center))
        }
        return centers
    }

    private fun cluster(
        points: List<OklabColor>,
        centers: MutableList<OklabColor>,
        iterations: Int,
    ): IntArray {
        val flat = flatten(points)
        val count = centers.size
        val middle = DoubleArray(count * 3)
        val assignment = IntArray(points.size)
        var sizes = IntArray(count)
        repeat(iterations.coerceAtLeast(1)) {
            for (center in 0 until count) {
                middle[center * 3] = centers[center].lightness
                middle[center * 3 + 1] = centers[center].a
                middle[center * 3 + 2] = centers[center].b
            }
            for (index in points.indices) {
                val l = flat[index * 3]
                val a = flat[index * 3 + 1]
                val b = flat[index * 3 + 2]
                var best = 0
                var bestDistance = Double.MAX_VALUE
                for (center in 0 until count) {
                    val dl = l - middle[center * 3]
                    val da = a - middle[center * 3 + 1]
                    val db = b - middle[center * 3 + 2]
                    val distance = dl * dl + da * da + db * db
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = center
                    }
                }
                assignment[index] = best
            }
            val sums = DoubleArray(count * 3)
            sizes = IntArray(count)
            for (index in points.indices) {
                val cluster = assignment[index]
                sums[cluster * 3] += flat[index * 3]
                sums[cluster * 3 + 1] += flat[index * 3 + 1]
                sums[cluster * 3 + 2] += flat[index * 3 + 2]
                sizes[cluster]++
            }
            for (center in 0 until count) {
                val size = sizes[center]
                if (size >
                    0
                ) {
                    centers[center] = OklabColor(sums[center * 3] / size, sums[center * 3 + 1] / size, sums[center * 3 + 2] / size)
                }
            }
        }
        return sizes
    }

    private fun flatten(points: List<OklabColor>): DoubleArray {
        val flat = DoubleArray(points.size * 3)
        for (index in points.indices) {
            flat[index * 3] = points[index].lightness
            flat[index * 3 + 1] = points[index].a
            flat[index * 3 + 2] = points[index].b
        }
        return flat
    }

    private fun squared(
        flat: DoubleArray,
        index: Int,
        center: OklabColor,
    ): Double {
        val dl = flat[index * 3] - center.lightness
        val da = flat[index * 3 + 1] - center.a
        val db = flat[index * 3 + 2] - center.b
        return dl * dl + da * da + db * db
    }

    private fun sortForPalette(colors: List<OklabColor>): List<OklabColor> =
        colors.sortedWith(
            compareBy<OklabColor> { color ->
                val chroma = StrictMath.sqrt(color.a * color.a + color.b * color.b)
                if (chroma < NEUTRAL_CHROMA) -1 else hueSector(color)
            }.thenBy { it.lightness },
        )

    private fun hueSector(color: OklabColor): Int {
        val degrees = StrictMath.toDegrees(StrictMath.atan2(color.b, color.a))
        return (((degrees % 360.0) + 360.0) % 360.0 / HUE_SECTOR_DEGREES).toInt()
    }

    private fun OklabColor.toColorValue(): ColorValue {
        val (red, green, blue) = StrictOklab.toSrgb(this)
        return ColorValue.fromRgb(red.toFloat(), green.toFloat(), blue.toFloat())
    }
}

internal object StrictOklab {
    fun fromSrgb(
        red: Double,
        green: Double,
        blue: Double,
    ): OklabColor {
        val r = decode(red)
        val g = decode(green)
        val b = decode(blue)
        val l = StrictMath.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = StrictMath.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = StrictMath.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return OklabColor(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    fun toSrgb(color: OklabColor): Triple<Double, Double, Double> {
        val l = cube(color.lightness + 0.3963377774 * color.a + 0.2158037573 * color.b)
        val m = cube(color.lightness - 0.1055613458 * color.a - 0.0638541728 * color.b)
        val s = cube(color.lightness - 0.0894841775 * color.a - 1.2914855480 * color.b)
        return Triple(
            encode(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
            encode(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
            encode(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
        )
    }

    private fun cube(value: Double) = value * value * value

    private fun decode(value: Double): Double {
        val v = value.coerceIn(0.0, 1.0)
        return if (v <= 0.04045) v / 12.92 else StrictMath.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun encode(value: Double): Double {
        val v = value.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) v * 12.92 else 1.055 * StrictMath.pow(v, 1.0 / 2.4) - 0.055
    }
}
