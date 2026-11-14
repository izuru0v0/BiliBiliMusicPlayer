package dev.bilimusic.desktop

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

internal data class LoginCode(val url: String, val key: String)
internal class BiliAccount(val id: Long, val name: String, val cookie: String, val csrf: String, val avatarUrl: String)
internal data class BiliFolder(val id: Long, val title: String, val isDefault: Boolean = false) {
 override fun toString() = title
}
internal data class BiliVideo(val aid: Long, val bvid: String, val title: String,
 val author: String = "", val cover: String = "", val duration: String = "", val category: String = "",
 val views: Long? = null, val published: Long? = null
) {
 override fun toString() = title
}
internal data class BiliPage<T>(val items: List<T>, val hasMore: Boolean)
internal data class BiliCreator(val id: Long, val name: String, val avatar: String, val fans: Long)
internal data class BiliCollection(val id: Long, val name: String, val cover: String, val count: Int, val owner: Long = 0)
internal data class BiliPart(val page: Int, val title: String)
internal data class BiliAudioQuality(val id: Int, val label: String, val url: URI, val bandwidth: Long)
internal data class BiliAudio(
 val url: URI, val duration: Int, val parts: List<BiliPart> = emptyList(),
 val qualities: List<BiliAudioQuality> = emptyList(), val quality: BiliAudioQuality? = null
)
internal sealed interface LoginPoll {
 data object Waiting : LoginPoll
 data object Confirm : LoginPoll
 data object Expired : LoginPoll
 data class Success(val account: BiliAccount) : LoginPoll
}

internal class BiliRequestRestricted(val endpoint: String) :
 IllegalStateException(when (endpoint) {
  "/x/player/pagelist" -> "读取视频分 P 时被 B 站限制（412），请稍后再试。"
  "/x/web-interface/view" -> "读取视频资料时被 B 站限制（412）。"
  "/x/web-interface/nav" -> "获取播放签名时被 B 站限制（412）。"
  "/x/player/wbi/playurl" -> "获取音频地址时被 B 站限制（412）。"
  else -> "B 站暂时限制了请求（412），请稍后再试。"
 })

internal class BiliAccountClient {
 private val api = "https://api.bilibili.com"
 private val passport = "https://passport.bilibili.com"

 fun generate(): LoginCode {
  val data = request("$passport/x/passport-login/web/qrcode/generate").first.getJSONObject("data")
  return parseLoginCode(data)
 }

 fun poll(key: String): LoginPoll {
  val (result, headers) = request("$passport/x/passport-login/web/qrcode/poll?qrcode_key=${encode(key)}")
  val data = result.getJSONObject("data")
  return when (data.getInt("code")) {
   86101 -> LoginPoll.Waiting
   86090 -> LoginPoll.Confirm
   86038 -> LoginPoll.Expired
   0 -> {
    val cookies = headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
     .map { it.substringBefore(';').trim() }
     .filter { it.substringBefore('=') in setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5") }
     .associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val csrf = cookies["bili_jct"]?.takeIf { it.isNotBlank() } ?: error("登录会话缺少校验信息")
    check(!cookies["SESSDATA"].isNullOrBlank()) { "登录会话缺少身份信息" }
    val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    val profile = request("$api/x/web-interface/nav", cookie).first.getJSONObject("data")
    check(profile.optBoolean("isLogin")) { "B 站未确认登录，请重新扫码" }
    LoginPoll.Success(BiliAccount(profile.getLong("mid"), profile.getString("uname"), cookie, csrf, profile.optString("face")))
   }
   else -> error("扫码登录失败，请重新获取二维码")
  }
 }

 fun search(keyword: String, account: BiliAccount?): List<BiliVideo> = searchVideos(keyword, 1, account).items

 fun searchVideos(keyword: String, page: Int, account: BiliAccount?, order: String = "totalrank"): BiliPage<BiliVideo> {
  val data = searchPage("video", keyword, page, account, order)
  val raw = data.optJSONArray("result")
  return BiliPage(parseBiliVideos(raw), searchHasMore(data, page, raw?.length() ?: 0))
 }

 fun searchCreators(keyword: String, page: Int, account: BiliAccount?): BiliPage<BiliCreator> {
  val data = searchPage("bili_user", keyword, page, account)
  val raw = data.optJSONArray("result")
  val creators = (0 until (raw?.length() ?: 0)).mapNotNull { i ->
   val item = raw?.optJSONObject(i) ?: return@mapNotNull null
   val id = item.optLong("mid")
   if (id <= 0) null else BiliCreator(id, cleanBiliText(item.optString("uname")), item.optString("upic"), item.optLong("fans"))
  }
  return BiliPage(creators, searchHasMore(data, page, raw?.length() ?: 0))
 }

 private fun searchPage(type: String, keyword: String, page: Int, account: BiliAccount?, order: String = "totalrank"): JSONObject {
  val query = signedBiliQuery(
   mapOf("search_type" to type, "keyword" to keyword, "page" to page.toString(), "order" to order), signingKeys(account),
   java.time.Instant.now().epochSecond
  )
  return request("$api/x/web-interface/wbi/search/type?$query", account?.cookie).first.getJSONObject("data")
 }

 private fun searchHasMore(data: JSONObject, page: Int, count: Int): Boolean {
  val total = data.optInt("numPages").takeIf { it > 0 } ?: data.optJSONObject("pageinfo")?.optInt("numPages") ?: 0
  return if (total > 0) page < total else count >= data.optInt("pagesize", 20).coerceAtLeast(1)
 }

 fun creatorVideos(id: Long, page: Int, account: BiliAccount?): BiliPage<BiliVideo> {
  val query = signedBiliQuery(
   mapOf("mid" to "$id", "pn" to "$page", "ps" to "20", "order" to "pubdate"),
   signingKeys(account), java.time.Instant.now().epochSecond
  )
  val data = request("$api/x/space/wbi/arc/search?$query", account?.cookie).first.getJSONObject("data")
  val raw = data.optJSONObject("list")?.optJSONArray("vlist")
  return BiliPage(parseBiliVideos(raw), page.toLong() * 20 < (data.optJSONObject("page")?.optLong("count") ?: 0))
 }

 fun creatorCollections(id: Long, page: Int, account: BiliAccount?): BiliPage<BiliCollection> {
  val data = request("$api/x/polymer/web-space/seasons_series_list?mid=$id&page_num=$page&page_size=20", account?.cookie)
   .first.getJSONObject("data").getJSONObject("items_lists")
  val raw = data.optJSONArray("seasons_list")
  val items = (0 until (raw?.length() ?: 0)).mapNotNull {
   val meta = raw?.optJSONObject(it)?.optJSONObject("meta") ?: return@mapNotNull null
   val season = meta.optLong("season_id")
   if (season <= 0) null else BiliCollection(season, meta.optString("name"), meta.optString("cover"), meta.optInt("total"), id)
  }
  return BiliPage(items, page.toLong() * 20 < (data.optJSONObject("page")?.optLong("total") ?: 0))
 }

 fun collectedCollections(account: BiliAccount): List<BiliCollection> {
  val collections = linkedMapOf<Long, BiliCollection>()
  for (page in 1..500) {
   if (Thread.currentThread().isInterrupted) throw InterruptedException()
   val data = request("$api/x/v3/fav/folder/collected/list?up_mid=${account.id}&platform=web&pn=$page&ps=20", account.cookie)
    .first.getJSONObject("data")
   val list = data.optJSONArray("list") ?: return collections.values.toList()
   for (index in 0 until list.length()) {
    val item = list.getJSONObject(index)
    if (item.optInt("type") != 21) continue
    val id = item.optLong("fid").takeIf { it > 0 } ?: item.optLong("id")
    val owner = item.optJSONObject("upper")?.optLong("mid")?.takeIf { it > 0 } ?: item.optLong("mid")
    if (id > 0 && owner > 0) collections[id] = BiliCollection(id, item.optString("title"),
     item.optString("cover"), item.optInt("media_count"), owner)
   }
   if (list.length() == 0 || !data.optBoolean("has_more", page * 20 < data.optInt("count", page * 20))) {
    return collections.values.toList()
   }
  }
  error("未能完整读取 B 站收藏的合集")
 }

 fun collectionVideos(mid: Long, id: Long, page: Int, account: BiliAccount?): BiliPage<BiliVideo> {
  val data = request("$api/x/polymer/web-space/seasons_archives_list?mid=$mid&season_id=$id&page_num=$page&page_size=20", account?.cookie)
   .first.getJSONObject("data")
  return BiliPage(parseBiliVideos(data.optJSONArray("archives")), page.toLong() * 20 < (data.optJSONObject("page")?.optLong("total") ?: 0))
 }

 private data class VideoPart(val page: Int, val cid: Long, val title: String, val duration: Int)

 private data class CachedMetadata<T>(val value: T, val timestamp: Long)

 private class MetadataCache<T> {
  private val entries = LinkedHashMap<Pair<BiliAccount?, String>, CachedMetadata<T>>(16, .75f, true)

  fun get(account: BiliAccount?, bvid: String, fetch: () -> T): T {
   val key = account to bvid
   synchronized(entries) {
    entries[key]?.let {
     if (System.nanoTime() - it.timestamp < 120_000_000_000L) return it.value
     entries.remove(key)
    }
   }
   val value = fetch()
   synchronized(entries) {
    entries[key] = CachedMetadata(value, System.nanoTime())
    if (entries.size > 64) entries.remove(entries.keys.first())
   }
   return value
  }
 }

 private val partCache = MetadataCache<List<VideoPart>>()
 private val videoViews = MetadataCache<JSONObject>()

 private fun videoParts(bvid: String, account: BiliAccount?): List<VideoPart> =
  partCache.get(account, bvid) {
   val pages = request("$api/x/player/pagelist?bvid=${encode(bvid)}", account?.cookie,
    referer = "https://www.bilibili.com/video/$bvid/").first.getJSONArray("data")
   (0 until pages.length()).mapNotNull { index ->
    val item = pages.optJSONObject(index) ?: return@mapNotNull null
    val cid = item.optLong("cid")
    if (cid <= 0) null else VideoPart(item.optInt("page", index + 1), cid,
     item.optString("part"), item.optInt("duration"))
   }.also { check(it.isNotEmpty()) { "B 站未返回可播放的分 P" } }
  }

 fun parts(bvid: String, account: BiliAccount?): List<BiliPart> =
  videoParts(bvid, account).map { BiliPart(it.page, it.title) }

 private fun videoView(bvid: String, account: BiliAccount?): JSONObject =
  videoViews.get(account, bvid) {
   request("$api/x/web-interface/view?bvid=${encode(bvid)}", account?.cookie,
    referer = "https://www.bilibili.com/video/$bvid/").first.getJSONObject("data")
  }

 fun suggestions(term: String): List<String> {
  val data = request("https://s.search.bilibili.com/main/suggest?term=${encode(term)}", suggestResponse = true)
   .first.optJSONObject("result")?.optJSONArray("tag") ?: return emptyList()
  return (0 until data.length()).mapNotNull {
   data.optJSONObject(it)?.optString("value")?.trim()?.takeIf(String::isNotBlank)
  }.distinct().take(8)
 }

 fun audio(bvid: String, account: BiliAccount?, part: Int = 1, qualityId: Int? = null): BiliAudio {
  val pages = videoParts(bvid, account)
  val selectedPart = pages.firstOrNull { it.page == part } ?: error("B 站未返回该视频的分 P")
  val cid = selectedPart.cid
  val query = signedBiliQuery(
   mapOf("bvid" to bvid, "cid" to cid.toString(), "fnval" to "4048", "fnver" to "0", "qn" to "80", "fourk" to "1"),
   signingKeys(account), java.time.Instant.now().epochSecond
  )
  val dash = request("$api/x/player/wbi/playurl?$query", account?.cookie, referer = "https://www.bilibili.com/video/$bvid/")
   .first.getJSONObject("data").getJSONObject("dash")
  val candidates = mutableListOf<BiliAudioQuality>()
  val audio = dash.optJSONArray("audio")
  for (i in 0 until (audio?.length() ?: 0)) {
   val item = audio?.optJSONObject(i) ?: continue
   parseAudioQuality(item)?.let(candidates::add)
  }
  dash.optJSONObject("flac")?.optJSONObject("audio")?.let { parseAudioQuality(it, "Hi-Res 音轨") }
   ?.let(candidates::add)
  val qualities = candidates.distinctBy(BiliAudioQuality::id)
   .sortedWith(compareByDescending<BiliAudioQuality> { it.label.startsWith("Hi-Res") }.thenByDescending { it.bandwidth })
  val selected = qualities.firstOrNull { it.id == qualityId } ?: qualities.firstOrNull()
   ?: error("B 站没有返回可播放的音轨")
  val parts = pages.map { BiliPart(it.page, it.title) }
  return BiliAudio(selected.url, selectedPart.duration, parts, qualities, selected)
 }

 private fun parseAudioQuality(item: JSONObject, name: String = "AAC"): BiliAudioQuality? {
  val raw = item.optString("baseUrl").ifBlank { item.optString("base_url") }
  val uri = runCatching { URI(raw) }.getOrNull() ?: return null
  val host = uri.host?.lowercase() ?: return null
  if (uri.scheme != "https" || uri.userInfo != null ||
   listOf(".bilivideo.com", ".bilivideo.cn", ".hdslb.com", ".bilibili.com").none(host::endsWith)) return null
  val bandwidth = item.optLong("bandwidth")
  val label = if (bandwidth > 0) "$name · ${bandwidth / 1000} kbps" else name
  return BiliAudioQuality(item.optInt("id"), label, uri, bandwidth)
 }

 private data class SigningCache(val account: BiliAccount?, val keys: String, val fetchedAt: Long)
 private var signingCache: SigningCache? = null

 @Synchronized
 private fun signingKeys(account: BiliAccount?): String {
  val now = System.nanoTime()
  signingCache?.let {
   if (it.account === account && now - it.fetchedAt < 600_000_000_000L) return it.keys
  }
  val nav = request("$api/x/web-interface/nav", account?.cookie, allowAnonymousNav = true)
   .first.getJSONObject("data").getJSONObject("wbi_img")
  val keys = listOf("img_url", "sub_url").joinToString("") {
   URI(nav.getString(it)).path.substringAfterLast('/').substringBefore('.')
  }
  signingCache = SigningCache(account, keys, now)
  return keys
 }

 fun musicRanking(account: BiliAccount?): List<BiliVideo> {
  val data = request("$api/x/web-interface/ranking/v2?rid=3&type=all", account?.cookie)
   .first.getJSONObject("data")
  return parseBiliVideos(data.optJSONArray("list"))
 }

 fun followGroups(account: BiliAccount): List<BiliFolder> {
  val groups = request("$api/x/relation/tags", account.cookie).first.getJSONArray("data")
  return (0 until groups.length()).mapNotNull { index ->
   val group = groups.getJSONObject(index)
   val id = group.getLong("tagid")
   if (id > 0) BiliFolder(id, group.getString("name")) else null
  }
 }

 fun followGroupMembers(account: BiliAccount, groupId: Long, page: Int): BiliPage<BiliCreator> {
  val members = request("$api/x/relation/tag?tagid=$groupId&pn=$page&ps=20", account.cookie)
   .first.getJSONArray("data")
  val creators = (0 until members.length()).map { index ->
   val member = members.getJSONObject(index)
   BiliCreator(member.getLong("mid"), member.getString("uname"), member.optString("face"), -1)
  }
  return BiliPage(creators, members.length() == 20)
 }

 fun followState(account: BiliAccount, id: Long): Pair<Boolean, Set<Long>> {
  val relation = request("$api/x/relation?fid=$id", account.cookie).first.getJSONObject("data")
  val groups = relation.optJSONArray("tag")
  return (relation.optInt("attribute") and 2 != 0) to
   (0 until (groups?.length() ?: 0)).mapNotNull { groups?.optLong(it) }.toSet()
 }

 fun setFollow(account: BiliAccount, id: Long, follow: Boolean) {
  write("/x/relation/modify", account, mapOf("fid" to "$id", "act" to if (follow) "1" else "2", "re_src" to "11"))
 }

 fun copyFollowToGroup(account: BiliAccount, id: Long, groupId: Long) {
  write("/x/relation/tags/copyUsers", account, mapOf("fids" to "$id", "tagids" to "$groupId"))
 }

 fun restoreAccount(cookie: String, csrf: String): BiliAccount? {
  val response = request("$api/x/web-interface/nav", cookie, allowAnonymousNav = true).first
  val profile = response.optJSONObject("data") ?: return null
  if (!profile.optBoolean("isLogin")) return null
  return BiliAccount(profile.getLong("mid"), profile.getString("uname"), cookie, csrf, profile.optString("face"))
 }

 fun createFolder(account: BiliAccount, title: String) =
  write("/x/v3/fav/folder/add", account, mapOf("title" to title, "privacy" to "1")).getJSONObject("data").getLong("id")

 fun renameFolder(account: BiliAccount, folder: BiliFolder, title: String) {
  val data = request("$api/x/v3/fav/folder/info?media_id=${folder.id}", account.cookie).first.getJSONObject("data")
  write("/x/v3/fav/folder/edit", account, mapOf(
   "media_id" to "${folder.id}", "title" to title, "intro" to data.optString("intro"),
   "cover" to data.optString("cover"), "privacy" to if (data.getInt("attr") and 1 != 0) "1" else "0"))
 }

 fun deleteFolder(account: BiliAccount, folder: BiliFolder) =
  write("/x/v3/fav/folder/del", account, mapOf("media_ids" to "${folder.id}"))

 fun folders(account: BiliAccount): List<BiliFolder> {
  val list = request("$api/x/v3/fav/folder/created/list-all?up_mid=${account.id}", account.cookie)
   .first.getJSONObject("data").optJSONArray("list") ?: return emptyList()
  return (0 until list.length()).map { index ->
   val folder = list.getJSONObject(index)
   BiliFolder(folder.getLong("id"), folder.getString("title"), folder.optInt("attr", 2) and 2 == 0)
  }
 }

 fun videos(account: BiliAccount, folder: BiliFolder): List<BiliVideo> {
  val videos = linkedMapOf<Long, BiliVideo>()
  for (page in 1..500) {
   if (Thread.currentThread().isInterrupted) throw InterruptedException()
   val data = request("$api/x/v3/fav/resource/list?media_id=${folder.id}&platform=web&pn=$page&ps=20", account.cookie)
    .first.getJSONObject("data")
   val items = data.optJSONArray("medias")
   if (items != null) for (index in 0 until items.length()) {
    val item = items.getJSONObject(index)
    val aid = item.optLong("id")
    val bvid = BilibiliLinks.parseVideoId(item.optString("bvid"))
    if (aid > 0 && bvid != null && item.optInt("type") == 2) {
     videos[aid] = BiliVideo(aid, bvid, item.optString("title", bvid))
    }
   }
   if (!data.optBoolean("has_more")) return videos.values.toList()
   check(items != null && items.length() > 0) { "B 站返回了不完整的收藏夹数据" }
  }
  error("收藏夹分页超过读取上限")
 }

 fun add(account: BiliAccount, folder: BiliFolder, bvid: String) {
  val aid = request("$api/x/web-interface/view?bvid=${encode(bvid)}", account.cookie)
   .first.getJSONObject("data").getLong("aid")
  write("/x/v3/fav/resource/deal", account, mapOf("rid" to "$aid", "type" to "2", "add_media_ids" to "${folder.id}", "del_media_ids" to ""))
 }

 fun remove(account: BiliAccount, folder: BiliFolder, bvid: String) {
  val aid = videoView(bvid, account).getLong("aid")
  write("/x/v3/fav/resource/batch-del", account, mapOf("resources" to "${aid}:2", "media_id" to "${folder.id}", "platform" to "web"))
 }

 private fun write(path: String, account: BiliAccount, fields: Map<String, String>): JSONObject {
  val form = (fields + ("csrf" to account.csrf)).entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
  return request(api + path, account.cookie, form).first
 }

 private fun request(url: String, cookie: String? = null, form: String? = null, allowAnonymousNav: Boolean = false, suggestResponse: Boolean = false, referer: String = "https://www.bilibili.com/"): Pair<JSONObject, Map<String?, List<String>>> {
  val uri = URI(url)
  val connection = uri.toURL().openConnection() as HttpURLConnection
  try {
   connection.connectTimeout = 8000
   connection.readTimeout = 12000
   connection.instanceFollowRedirects = false
   connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
   connection.setRequestProperty("Referer", referer)
   if (cookie != null) connection.setRequestProperty("Cookie", cookie)
   if (form != null) {
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.setRequestProperty("Origin", "https://www.bilibili.com")
    connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
   }
   val status = connection.responseCode
   if (status == 412) throw BiliRequestRestricted(uri.path)
   check(status == 200) { "B 站请求失败（HTTP $status）" }
   val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
   if (suggestResponse) return body to connection.headerFields
   val code = body.getInt("code")
   if (code == -412) throw BiliRequestRestricted(uri.path)
   check(code == 0 || (allowAnonymousNav && code == -101 && body.optJSONObject("data")?.has("wbi_img") == true)) { "B 站请求失败（错误码 $code）" }
   return body to connection.headerFields
  } finally {
   connection.disconnect()
  }
 }

 private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}


internal fun parseLoginCode(data: JSONObject): LoginCode {
 val payload = data.optString("url").trim()
 val key = data.optString("qrcode_key").trim()
 check(payload.isNotEmpty() && payload.length <= 2048 && key.isNotEmpty()) {
  "B 站返回的二维码数据不完整，请重试"
 }
 return LoginCode(payload, key)
}


private fun parseBiliVideos(items: org.json.JSONArray?): List<BiliVideo> {
 if (items == null) return emptyList()
 return (0 until items.length()).mapNotNull { index ->
  val item = items.optJSONObject(index) ?: return@mapNotNull null
  val bvid = BilibiliLinks.parseVideoId(item.optString("bvid")) ?: return@mapNotNull null
  val title = cleanBiliText(item.optString("title", bvid))
  BiliVideo(item.optLong("aid"), bvid, title.ifBlank { bvid },
   item.optString("author").ifBlank { item.optJSONObject("owner")?.optString("name").orEmpty() },
   item.optString("pic").ifBlank { item.optString("cover") }, item.optString("duration").ifBlank { item.optString("length") }, item.optString("typename"),
   listOf(item.optLong("play", -1), item.optJSONObject("stat")?.optLong("view", -1) ?: -1)
    .firstOrNull { it >= 0 },
   listOf(item.optLong("pubdate"), item.optLong("created")).firstOrNull { it > 0 })
 }.distinctBy(BiliVideo::bvid)
}

internal fun signedBiliQuery(values: Map<String, String>, keys: String, timestamp: Long): String {
 val order = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13)
 check(keys.length >= 64) { "B 站搜索签名信息不完整" }
 val mixin = order.map { keys[it] }.joinToString("")
 val params = (values + ("wts" to timestamp.toString())).toSortedMap()
 val query = params.entries.joinToString("&") { (key, value) ->
  "$key=${URLEncoder.encode(value.filterNot { it in "!'()*" }, Charsets.UTF_8.name()).replace("+", "%20")}"
 }
 val digest = java.security.MessageDigest.getInstance("MD5").digest((query + mixin).toByteArray(Charsets.UTF_8))
 return "$query&w_rid=${digest.toHexString()}"
}


private fun cleanBiliText(value: String): String = value.replace(Regex("<[^>]*>"), "")
 .replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
