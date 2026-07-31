package com.prismco.cosmify.lyrics

import android.content.Context
import com.cosmify.kugou.KuGou
import com.prismco.cosmify.constants.EnableKugouKey
import com.prismco.cosmify.utils.dataStore
import com.prismco.cosmify.utils.get

object KuGouLyricsProvider : LyricsProvider {
    override val name = "Kugou"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableKugouKey] ?: true

    override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int): Result<String> =
        KuGou.getLyrics(title, artist, duration)

    override suspend fun getAllLyrics(id: String, title: String, artist: String, duration: Int, callback: (String) -> Unit) {
        KuGou.getAllLyrics(title, artist, duration, callback)
    }
}
