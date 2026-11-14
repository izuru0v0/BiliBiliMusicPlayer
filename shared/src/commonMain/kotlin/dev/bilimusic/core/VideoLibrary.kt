package dev.bilimusic.core

object VideoLibrary {
    private val videoPattern = Regex("BV[0-9A-Za-z]{10}")
    private val videoLink = Regex("https?://(?:www\\.|m\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]{10})(?:[/?#].*)?")

    fun track(input: String, title: String): Track? {
        val value = input.trim()
        val id = value.takeIf(videoPattern::matches)
            ?: videoLink.matchEntire(value)?.groupValues?.get(1)
            ?: return null
        return Track(id, title.trim().ifEmpty { id }, uri = "https://player.bilibili.com/player.html?bvid=$id&autoplay=1&danmaku=0")
    }

    fun shuffle(tracks: List<Track>): List<Track> = randomQueue(tracks)
    fun recent(ids: List<String>, id: String): List<String> = recordRecent(ids, id, 30)
}
