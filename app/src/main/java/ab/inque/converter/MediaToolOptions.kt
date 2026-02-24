package ab.inque.converter

data class MediaToolOptions(
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val speedFactor: Float = 1.0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false
) {
    val hasTrim: Boolean get() = trimEndMs > trimStartMs
    val hasSpeed: Boolean get() = speedFactor != 1.0f
    val hasFlip: Boolean get() = flipHorizontal || flipVertical
    val hasAnyTool: Boolean get() = hasTrim || hasSpeed || hasFlip
}
