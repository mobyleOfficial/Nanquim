package com.mobyle.nanquim.diagnostics

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class DiagnosticsExporter(
    private val contentResolver: ContentResolver,
    private val collector: DiagnosticsCollector,
    private val recorder: StylusObservationRecorder,
) {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    suspend fun export(uri: Uri) {
        val report = collector.collect(recorder.snapshot())
        val text = json.encodeToString(DiagnosticsReport.serializer(), report)
        withContext(Dispatchers.IO) {
            val stream = checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "Cannot open $uri for writing" }
            stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(text) }
        }
    }
}
