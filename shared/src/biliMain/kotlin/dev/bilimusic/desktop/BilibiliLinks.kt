package dev.bilimusic.desktop

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object BilibiliLinks {
    private val videoId = Regex("BV[0-9A-Za-z]{10}")

    fun parseVideoId(input: String): String? {
        val value = input.trim()
        if (videoId.matches(value)) return value
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("https", "http") ||
            uri.host?.lowercase() !in listOf("www.bilibili.com", "bilibili.com", "m.bilibili.com")) return null
        val id = uri.path?.removeSuffix("/")?.removePrefix("/video/") ?: return null
        return id.takeIf { videoId.matches(it) }
    }

    fun search(query: String): URI = URI(
        "https://search.bilibili.com/all?keyword=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())
    )

    fun player(id: String): URI {
        require(videoId.matches(id))
        return URI("https://player.bilibili.com/player.html?bvid=$id&autoplay=0&danmaku=0")
    }
}
