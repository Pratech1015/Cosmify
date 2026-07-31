package com.prismco.cosmify.models

import com.cosmify.innertube.models.YTItem

data class ItemsPage(
    val items: List<YTItem>,
    val continuation: String?,
)
