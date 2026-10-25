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
import com.mobyle.nanquim.canvas.input.TouchEvent
import com.mobyle.nanquim.canvas.input.TouchSession
import com.mobyle.nanquim.canvas.input.TouchSessionSet
import java.io.File
import java.io.IOException

class TouchSessionRecorder(
    private val file: File,
    private val device: String,
    private val xdpi: Float,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var sessions: List<TouchSession> by mutableStateOf(emptyList())
        private set

    var loaded: Boolean by mutableStateOf(false)
        private set

    private val writeLock = Mutex()

    suspend fun load() {
        if (loaded) return
        val stored =
            withContext(io) {
                try {
                    if (file.isFile) TouchSessionSet.decode(file.readText()).sessions else emptyList()
                } catch (exception: SerializationException) {
                    setAside()
                } catch (exception: IllegalArgumentException) {
                    setAside()
                }
            }
        sessions = stored + sessions
        loaded = true
    }

    private fun setAside(): List<TouchSession> {
        file.renameTo(File(file.parentFile, "${file.name}.unreadable"))
        return emptyList()
    }

    fun add(
        label: String,
        recordedBy: String,
        events: List<TouchEvent>,
    ): Boolean {
        require(label in TouchSessionSet.LABELS) { "Unknown label $label" }
        val start = events.firstOrNull()?.timeMillis ?: return false
        sessions = sessions + TouchSession(label, recordedBy.trim(), events.map { it.copy(timeMillis = it.timeMillis - start) })
        save()
        return true
    }

    fun removeLast(): Boolean {
        if (sessions.isEmpty()) return false
        sessions = sessions.dropLast(1)
        save()
        return true
    }

    fun clear() {
        sessions = emptyList()
        save()
    }

    fun counts(): Map<String, Int> = TouchSessionSet.LABELS.associateWith { label -> sessions.count { it.label == label } }

    suspend fun export(
        contentResolver: ContentResolver,
        uri: Uri,
    ) {
        val text = TouchSessionSet.encode(snapshot())
        withContext(io) {
            val stream = checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "Cannot open $uri for writing" }
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
    }

    private fun snapshot() = TouchSessionSet(device = device, xdpi = xdpi, sessions = sessions)

    private fun save() {
        val set = snapshot()
        scope.launch {
            writeLock.withLock {
                withContext(io) {
                    file.parentFile?.mkdirs()
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.writeText(TouchSessionSet.encode(set))
                    if (!temporary.renameTo(file)) {
                        file.delete()
                        if (!temporary.renameTo(file)) throw IOException("Cannot save ${file.name}")
                    }
                }
            }
        }
    }
}
