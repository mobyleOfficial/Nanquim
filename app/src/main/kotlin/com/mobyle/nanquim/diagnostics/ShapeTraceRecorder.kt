package com.mobyle.nanquim.diagnostics

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import com.mobyle.nanquim.document.shape.ShapeTrace
import com.mobyle.nanquim.document.shape.ShapeTraceSet
import java.io.File
import java.io.IOException
import kotlin.math.roundToLong

class ShapeTraceRecorder(
    private val file: File,
    private val device: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var traces: List<ShapeTrace> by mutableStateOf(emptyList())
        private set

    var loaded: Boolean by mutableStateOf(false)
        private set

    private val writeLock = Mutex()

    suspend fun load() {
        if (loaded) return
        val stored =
            withContext(io) {
                try {
                    if (file.isFile) ShapeTraceSet.decode(file.readText()).traces else emptyList()
                } catch (exception: SerializationException) {
                    file.renameTo(File(file.parentFile, "${file.name}.unreadable"))
                    emptyList()
                } catch (exception: IllegalArgumentException) {
                    file.renameTo(File(file.parentFile, "${file.name}.unreadable"))
                    emptyList()
                }
            }
        traces = stored + traces
        loaded = true
    }

    fun add(
        label: String,
        recordedBy: String,
        samples: List<DoubleArray>,
    ) {
        require(label in ShapeTraceSet.LABELS) { "Unknown label $label" }
        val points =
            samples.map { (x, y, pressure, time) ->
                listOf(round(x, 10.0), round(y, 10.0), round(pressure, 1000.0), round(time, 1.0))
            }
        traces = traces + ShapeTrace(label, recordedBy.trim(), points)
        save()
    }

    fun removeLast(): Boolean {
        if (traces.isEmpty()) return false
        traces = traces.dropLast(1)
        save()
        return true
    }

    fun clear() {
        traces = emptyList()
        save()
    }

    fun counts(): Map<String, Int> = ShapeTraceSet.LABELS.associateWith { label -> traces.count { it.label == label } }

    suspend fun export(
        contentResolver: ContentResolver,
        uri: Uri,
    ) {
        val text = ShapeTraceSet.encode(snapshot())
        withContext(io) {
            val stream = checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "Cannot open $uri for writing" }
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
    }

    private fun snapshot() = ShapeTraceSet(device = device, traces = traces)

    private fun save() {
        val set = snapshot()
        scope.launch {
            writeLock.withLock {
                withContext(io) {
                    file.parentFile?.mkdirs()
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.writeText(ShapeTraceSet.encode(set))
                    if (!temporary.renameTo(file)) {
                        file.delete()
                        if (!temporary.renameTo(file)) throw IOException("Cannot save ${file.name}")
                    }
                }
            }
        }
    }

    private fun round(
        value: Double,
        scale: Double,
    ): Double = (value * scale).roundToLong() / scale
}
