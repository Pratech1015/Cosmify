package com.cosmify.innertube.pages

import com.cosmify.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
