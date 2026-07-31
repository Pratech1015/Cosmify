package com.prismco.cosmify.models

import com.cosmify.innertube.models.YTItem
import com.prismco.cosmify.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
