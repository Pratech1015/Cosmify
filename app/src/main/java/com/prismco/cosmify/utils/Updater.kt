package com.prismco.cosmify.utils

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject

object Updater {
    private val client = HttpClient()
    var lastCheckTime = -1L
        private set

    suspend fun getLatestVersionName(): Result<String> = runCatching {
        val response = client.get("https://api.github.com/repos/Pratech1015/Cosmify/releases/latest").bodyAsText()
        val json = JSONObject(response)
        val tagName = json.optString("tag_name").trim()
        val name = json.optString("name").trim()
        val rawVersion = tagName.ifEmpty { name }
        if (rawVersion.isEmpty()) error("Could not find release version in response")
        val versionName = rawVersion.removePrefix("v").removePrefix("V")
        lastCheckTime = System.currentTimeMillis()
        versionName
    }
}
