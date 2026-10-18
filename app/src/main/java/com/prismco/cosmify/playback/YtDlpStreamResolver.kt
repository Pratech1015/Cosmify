package com.prismco.cosmify.playback

import android.content.Context
import com.prismco.cosmify.constants.AudioQuality
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

data class YtDlpStreamInfo(
    val url: String,
    val itag: Int = 140,
    val mimeType: String = "audio/mp4; codecs=\"mp4a.40.2\"",
    val bitrate: Int = 128000,
    val sampleRate: Int = 44100,
    val contentLength: Long? = null,
    val durationSeconds: Int? = null,
    val expiresInSeconds: Long = 21600L,
)

object YtDlpStreamResolver {
    @Volatile
    private var isInitialized = false
    private val initMutex = Mutex()
    private val streamCache = ConcurrentHashMap<String, Pair<YtDlpStreamInfo, Long>>()

    fun init(context: Context) {
        if (isInitialized) return
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            isInitialized = true
            Timber.i("YoutubeDL initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize YoutubeDL")
        }
    }

    suspend fun ensureInitialized(context: Context): Boolean {
        if (isInitialized) return true
        return initMutex.withLock {
            if (isInitialized) return@withLock true
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    isInitialized = true
                    Timber.i("YoutubeDL initialized successfully via ensureInitialized")
                    true
                } catch (e: Exception) {
                    Timber.e(e, "Failed to initialize YoutubeDL")
                    false
                }
            }
        }
    }

    suspend fun resolveAudioStream(
        context: Context,
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO
    ): Result<YtDlpStreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            streamCache[videoId]?.let { (info, expireTime) ->
                if (expireTime > now) {
                    return@runCatching info
                } else {
                    streamCache.remove(videoId)
                }
            }

            if (!ensureInitialized(context)) {
                error("YoutubeDL could not be initialized")
            }

            val formatSpec = when (quality) {
                AudioQuality.HIGH -> "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio/best"
                AudioQuality.LOW -> "worstaudio/worst"
                AudioQuality.AUTO -> "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio/best"
            }

            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("-f", formatSpec)
                addOption("-g")
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                addOption("--no-warnings")
            }

            val response = YoutubeDL.getInstance().execute(request)
            val output = response.out.trim()
            val url = output.lines().firstOrNull { it.startsWith("http") }?.trim()
                ?: error("yt-dlp returned no stream URL: $output")

            val isWebm = url.contains("mime=audio%2Fwebm") || url.contains("mime=audio/webm") || url.contains("ext=webm")
            val streamInfo = YtDlpStreamInfo(
                url = url,
                itag = if (isWebm) 251 else 140,
                mimeType = if (isWebm) "audio/webm; codecs=\"opus\"" else "audio/mp4; codecs=\"mp4a.40.2\"",
                bitrate = if (isWebm) 160000 else 128000,
                sampleRate = 48000,
                contentLength = 5000000L,
                expiresInSeconds = 21600L
            )

            val expireAt = now + (streamInfo.expiresInSeconds * 1000L) - 60000L
            streamCache[videoId] = streamInfo to expireAt
            streamInfo
        }
    }
}
