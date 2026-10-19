package ab.inque.converter.pipeline

import ab.inque.converter.MediaToolOptions
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException

/**
 * Matroska (MKV) export via FFmpeg-Kit. Video is encoded with MediaCodec to MP4 first,
 * then stream-copied into MKV — no GPL encoders required.
 */
object FfmpegVideoExporter {

    fun muxWebmWithAudio(
        videoOnly: File,
        audioSource: File,
        output: File,
        toolOptions: MediaToolOptions = MediaToolOptions(),
        progressCallback: (Float) -> Unit = {}
    ): File {
        require(videoOnly.exists() && videoOnly.length() > 0) { "Video-only WebM missing: ${videoOnly.absolutePath}" }
        require(audioSource.exists()) { "Audio source missing: ${audioSource.absolutePath}" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        progressCallback(0.15f)

        val args = mutableListOf(
            "-y",
            "-i", videoOnly.absolutePath
        )
        if (toolOptions.hasTrim) {
            val startSec = toolOptions.trimStartMs / 1000.0
            val endSec = toolOptions.trimEndMs / 1000.0
            args.addAll(listOf("-ss", startSec.toString(), "-to", endSec.toString()))
        }
        args.addAll(listOf("-i", audioSource.absolutePath))
        if (toolOptions.hasSpeed && toolOptions.speedFactor != 1.0f) {
            val tempo = toolOptions.speedFactor.coerceIn(0.5f, 2.0f)
            args.addAll(listOf("-filter:a", "atempo=$tempo"))
        }
        args.addAll(
            listOf(
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
                output.absolutePath
            )
        )

        var lastError = ""
        for (audioCodec in listOf("libopus", "libvorbis")) {
            if (output.exists()) output.delete()
            val codecArgs = args.toMutableList()
            val outIdx = codecArgs.indexOf(output.absolutePath)
            codecArgs.add(outIdx, "128k")
            codecArgs.add(outIdx, "-b:a")
            codecArgs.add(outIdx, audioCodec)
            codecArgs.add(outIdx, "-c:a")
            val session = FFmpegKit.executeWithArguments(codecArgs.toTypedArray())
            progressCallback(0.9f)
            if (ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0L) {
                progressCallback(1f)
                return output
            }
            lastError = session.allLogsAsString
        }
        throw IOException("FFmpeg WebM audio mux failed: $lastError")
    }

    fun remuxToMkv(
        input: File,
        output: File,
        progressCallback: (Float) -> Unit = {}
    ): File {
        require(input.exists() && input.length() > 0) { "Input video missing or empty: ${input.absolutePath}" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        progressCallback(0.15f)
        val session = FFmpegKit.executeWithArguments(
            arrayOf("-y", "-i", input.absolutePath, "-c", "copy", output.absolutePath)
        )
        progressCallback(0.9f)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException(
                "FFmpeg MKV remux failed (code ${session.returnCode}): ${session.allLogsAsString}"
            )
        }
        if (!output.exists() || output.length() == 0L) {
            throw IOException("FFmpeg did not produce MKV output")
        }
        progressCallback(1f)
        return output
    }

    fun remuxToAvi(
        input: File,
        output: File,
        progressCallback: (Float) -> Unit = {}
    ): File {
        require(input.exists() && input.length() > 0) { "Input video missing or empty: ${input.absolutePath}" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        progressCallback(0.15f)
        var session = FFmpegKit.executeWithArguments(
            arrayOf("-y", "-i", input.absolutePath, "-c", "copy", output.absolutePath)
        )
        if (!ReturnCode.isSuccess(session.returnCode)) {
            session = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y", "-i", input.absolutePath,
                    "-c:v", "mpeg4", "-q:v", "5",
                    "-c:a", "mp3",
                    output.absolutePath
                )
            )
        }
        progressCallback(0.9f)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException(
                "FFmpeg AVI export failed (code ${session.returnCode}): ${session.allLogsAsString}"
            )
        }
        if (!output.exists() || output.length() == 0L) {
            throw IOException("FFmpeg did not produce AVI output")
        }
        progressCallback(1f)
        return output
    }

    fun muxVideoAudioToMkv(
        video: File,
        audio: File,
        output: File,
        progressCallback: (Float) -> Unit = {}
    ): File {
        require(video.exists() && audio.exists()) { "Missing video or audio for MKV mux" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        progressCallback(0.15f)
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-y",
                "-i", video.absolutePath,
                "-i", audio.absolutePath,
                "-c", "copy",
                "-map", "0:v:0",
                "-map", "1:a:0",
                output.absolutePath
            )
        )
        progressCallback(0.9f)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException(
                "FFmpeg MKV mux failed (code ${session.returnCode}): ${session.allLogsAsString}"
            )
        }
        if (!output.exists() || output.length() == 0L) {
            throw IOException("FFmpeg did not produce MKV output")
        }
        progressCallback(1f)
        return output
    }
}
