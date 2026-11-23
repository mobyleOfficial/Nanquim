package com.mobyle.nanquim.data.diagnostics

import android.content.ContentResolver
import android.net.Uri
import com.mobyle.nanquim.domain.diagnostics.DiagnosticsReport
import com.mobyle.nanquim.domain.diagnostics.StylusObservationRecorder
import com.mobyle.nanquim.domain.platform.ExternalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class DiagnosticsExporter(
    private val files: ExternalFiles,
    private val collector: DiagnosticsCollector,
    private val recorder: StylusObservationRecorder,
) {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    suspend fun export(uri: String) {
        val report = collector.collect(recorder.snapshot())
        val text = json.encodeToString(DiagnosticsReport.serializer(), report)
        withContext(Dispatchers.IO) {
            val stream = checkNotNull(files.openOutput(uri)) { "Cannot open $uri for writing" }
            stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(text) }
        }
    }
}
