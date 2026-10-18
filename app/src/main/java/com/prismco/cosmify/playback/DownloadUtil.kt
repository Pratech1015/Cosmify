package com.prismco.cosmify.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.cosmify.innertube.YouTube
import com.prismco.cosmify.constants.AudioQuality
import com.prismco.cosmify.constants.AudioQualityKey
import com.prismco.cosmify.constants.UseYtDlpKey
import com.prismco.cosmify.db.MusicDatabase
import com.prismco.cosmify.db.entities.FormatEntity
import com.prismco.cosmify.di.DownloadCache
import com.prismco.cosmify.di.PlayerCache
import com.prismco.cosmify.utils.enumPreference
import com.prismco.cosmify.utils.preference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val useYtDlp by preference(context, UseYtDlpKey, true)
    private val songUrlCache = HashMap<String, Pair<String, Long>>()
    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient.Builder()
                        .proxy(YouTube.proxy)
                        .build()
                )
            )
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        val length = if (dataSpec.length >= 0) dataSpec.length else 1

        if (playerCache.isCached(mediaId, dataSpec.position, length)) {
            return@Factory dataSpec
        }

        songUrlCache[mediaId]?.takeIf { it.second < System.currentTimeMillis() }?.let {
            return@Factory dataSpec.withUri(it.first.toUri())
        }

        val playedFormat = runBlocking(Dispatchers.IO) { database.format(mediaId).first() }

        if (useYtDlp) {
            val ytDlpStream = runBlocking(Dispatchers.IO) {
                YtDlpStreamResolver.resolveAudioStream(context, mediaId, audioQuality).getOrNull()
            }
            if (ytDlpStream != null) {
                database.query {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = ytDlpStream.itag,
                            mimeType = ytDlpStream.mimeType.split(";")[0],
                            codecs = ytDlpStream.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "opus",
                            bitrate = ytDlpStream.bitrate,
                            sampleRate = ytDlpStream.sampleRate,
                            contentLength = ytDlpStream.contentLength ?: 5000000L,
                            loudnessDb = null
                        )
                    )
                }
                songUrlCache[mediaId] = ytDlpStream.url to (System.currentTimeMillis() + ytDlpStream.expiresInSeconds * 1000L)
                return@Factory dataSpec.withUri(ytDlpStream.url.toUri())
            }
        }

        val playerResponse = runBlocking(Dispatchers.IO) {
            YouTube.player(mediaId)
        }.getOrNull()

        val format = playerResponse?.let { resp ->
            if (resp.playabilityStatus.status == "OK") {
                if (playedFormat != null) {
                    resp.streamingData?.adaptiveFormats?.find { it.itag == playedFormat.itag }
                } else {
                    resp.streamingData?.adaptiveFormats
                        ?.filter { it.isAudio }
                        ?.maxByOrNull {
                            it.bitrate * when (audioQuality) {
                                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                                AudioQuality.HIGH -> 1
                                AudioQuality.LOW -> -1
                            } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
                        }
                }
            } else null
        }

        if (format != null && format.url != null) {
            val finalFormat = format.let {
                // Specify range to avoid YouTube's throttling
                it.copy(url = "${it.url}&range=0-${it.contentLength ?: 10000000}")
            }

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = finalFormat.itag,
                        mimeType = finalFormat.mimeType.split(";")[0],
                        codecs = finalFormat.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "opus",
                        bitrate = finalFormat.bitrate,
                        sampleRate = finalFormat.audioSampleRate,
                        contentLength = finalFormat.contentLength ?: 5000000L,
                        loudnessDb = playerResponse?.playerConfig?.audioConfig?.loudnessDb
                    )
                )
            }

            songUrlCache[mediaId] = finalFormat.url to (System.currentTimeMillis() + (playerResponse?.streamingData?.expiresInSeconds ?: 21600L) * 1000L)
            return@Factory dataSpec.withUri(finalFormat.url.toUri())
        }

        // Fallback to yt-dlp if InnerTube had no stream
        val fallbackYtDlp = runBlocking(Dispatchers.IO) {
            YtDlpStreamResolver.resolveAudioStream(context, mediaId, audioQuality).getOrNull()
        } ?: throw PlaybackException("No stream available for download", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)

        database.query {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = fallbackYtDlp.itag,
                    mimeType = fallbackYtDlp.mimeType.split(";")[0],
                    codecs = fallbackYtDlp.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "opus",
                    bitrate = fallbackYtDlp.bitrate,
                    sampleRate = fallbackYtDlp.sampleRate,
                    contentLength = fallbackYtDlp.contentLength ?: 5000000L,
                    loudnessDb = null
                )
            )
        }

        songUrlCache[mediaId] = fallbackYtDlp.url to (System.currentTimeMillis() + fallbackYtDlp.expiresInSeconds * 1000L)
        dataSpec.withUri(fallbackYtDlp.url.toUri())
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager = DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
        maxParallelDownloads = 3
        addListener(
            ExoDownloadService.TerminalStateNotificationHelper(
                context = context,
                notificationHelper = downloadNotificationHelper,
                nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
            )
        )
    }
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    fun getDownload(songId: String?): Flow<Download?> = downloads.map { it[songId] }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    downloads.update { map ->
                        map.toMutableMap().apply {
                            set(download.request.id, download)
                        }
                    }
                }
            }
        )
    }
}