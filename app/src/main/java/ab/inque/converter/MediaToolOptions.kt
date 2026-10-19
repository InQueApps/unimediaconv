package ab.inque.converter

data class MediaToolOptions(
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val speedFactor: Float = 1.0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** User-selected rotation in degrees: 0, 90, 180, or 270. Applied to every frame (video) or image. */
    val rotationDegrees: Int = 0,
    /** When true, video → image exports every frame in a ZIP instead of a single thumbnail. */
    val exportAllVideoFrames: Boolean = false,
    /** When true, video → image captures the frame at [extractFrameMs]. */
    val extractFrame: Boolean = false,
    val extractFrameMs: Long = 0
) {
    val hasTrim: Boolean get() = trimEndMs > trimStartMs
    val hasSpeed: Boolean get() = speedFactor != 1.0f
    val hasFlip: Boolean get() = flipHorizontal || flipVertical
    val hasRotation: Boolean get() = rotationDegrees in listOf(90, 180, 270)
    val hasExtractFrame: Boolean get() = extractFrame
    val hasAnyTool: Boolean get() = hasTrim || hasSpeed || hasFlip || hasRotation ||
        exportAllVideoFrames || hasExtractFrame
}
