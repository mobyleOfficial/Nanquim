package com.mobyle.nanquim.document.layer

data class VideoLayerContent(
    val file: String,
    val timeMicroseconds: Long,
    val durationMicroseconds: Long,
) {
    init {
        require(isSafeVideoFileName(file)) { "Unsafe video file name $file" }
    }

    companion object {
        const val FOLDER = "videos/"

        fun isSafeVideoFileName(name: String): Boolean =
            name.isNotEmpty() && name.length <= MAXIMUM_FILE_NAME && !name.startsWith(".") &&
                name.none { it == '/' || it == '\\' || it.code < 0x20 } && name != ".."

        private const val MAXIMUM_FILE_NAME = 128
    }
}

fun DocumentState.addVideoLayer(
    name: String,
    content: VideoLayerContent,
): Pair<DocumentState, Int> {
    val (state, id) = addRasterLayer(name)
    return state.replaceRaster(state.raster(id)!!.copy(video = content)) to id
}

fun DocumentState.setVideoTime(
    layerId: Int,
    timeMicroseconds: Long,
): DocumentState {
    val layer = raster(layerId) ?: throw LayerOperationException("Layer $layerId is not a raster layer")
    val video = layer.video ?: throw LayerOperationException("Layer $layerId is not a video layer")
    return replaceRaster(layer.copy(video = video.copy(timeMicroseconds = timeMicroseconds.coerceIn(0, video.durationMicroseconds))))
}

fun DocumentState.rasterizeVideo(layerId: Int): DocumentState {
    val layer = raster(layerId) ?: throw LayerOperationException("Layer $layerId is not a raster layer")
    if (layer.video == null) throw LayerOperationException("Layer $layerId is not a video layer")
    return replaceRaster(layer.copy(video = null))
}

fun DocumentState.videoLayers(): Map<Int, VideoLayerContent> =
    allNodes().filterIsInstance<RasterLayer>().mapNotNull { layer -> layer.video?.let { layer.id to it } }.toMap()
