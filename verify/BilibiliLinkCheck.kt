package dev.bilimusic.desktop
fun main() {
    val id = "BV1B7411m7LV"
    check(BilibiliLinks.parseVideoId(id) == id)
    check(BilibiliLinks.parseVideoId("https://www.bilibili.com/video/$id/?spm_id_from=333") == id)
    check(BilibiliLinks.parseVideoId("https://www.bilibili.com.evil.example/video/$id") == null)
    check(BilibiliLinks.parseVideoId("https://www.bilibili.com/video/BVinvalid") == null)
    check(BilibiliLinks.player(id).host == "player.bilibili.com")
    check(BilibiliLinks.search("音乐 & jazz").rawQuery.contains("%26"))
    println("PASS: BV input, Bilibili URL, rejected foreign host, player URL, search encoding")
}
