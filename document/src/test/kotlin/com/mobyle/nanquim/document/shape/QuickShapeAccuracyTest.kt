package com.mobyle.nanquim.document.shape

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class QuickShapeAccuracyTest {
    @Test
    fun syntheticSetIsRecognizedAtLeast95Percent() {
        val strokes = SyntheticShapeStrokes.generate(count = 500, seed = SEED)
        val accuracy = score(strokes.map { it.label to it.points }, "synthetic")
        assertTrue(accuracy >= REQUIRED, "accuracy $accuracy")
    }

    @Test
    fun aShakierHandStaysAbove95Percent() {
        val strokes = SyntheticShapeStrokes.generate(count = 500, seed = SEED + 1, noise = 2.0)
        val accuracy = score(strokes.map { it.label to it.points }, "synthetic, twice the wobble")
        assertTrue(accuracy >= REQUIRED, "accuracy $accuracy")
    }

    @Test
    fun recordedSetIsRecognizedAtLeast95Percent() {
        val file = File(goldensDirectory(), "quickshape/recorded.json")
        assumeTrue(file.isFile, "no recorded set yet")
        val set = ShapeTraceSet.decode(file.readText())
        val accuracy = score(set.traces.map { it.label to it.shapePoints() }, "recorded")
        assertTrue(accuracy >= REQUIRED, "accuracy $accuracy")
    }

    private fun score(
        strokes: List<Pair<String, List<ShapePoint>>>,
        name: String,
    ): Double {
        val confusion = sortedMapOf<String, MutableMap<String, Int>>()
        var correct = 0
        for ((label, points) in strokes) {
            val recognized = ShapeTraceSet.labelOf(QuickShape.recognize(points).shape?.kind) ?: "none"
            confusion.getOrPut(label) { sortedMapOf() }.merge(recognized, 1, Int::plus)
            if (recognized == label) correct++
        }
        val accuracy = correct.toDouble() / strokes.size
        println("QuickShape $name set: $correct / ${strokes.size} = ${"%.1f".format(accuracy * 100)} %")
        confusion.forEach { (label, row) -> println("  $label → $row") }
        return accuracy
    }

    private fun goldensDirectory(): File {
        val folders = generateSequence(File("").absoluteFile) { it.parentFile }
        return folders.map { File(it, "goldens") }.first { it.isDirectory }
    }

    private companion object {
        const val SEED = 20261020
        const val REQUIRED = 0.95
    }
}
