package dev.bilimusic.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.*
import dev.bilimusic.desktop.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal data class Song(val video: BiliVideo, val part: Int = 0, val partTitle: String = "") {
    val title get() = partTitle.ifBlank { video.title }
    val key get() = "${video.bvid}:${part.coerceAtLeast(1)}"
}

internal class MusicState(private val context: Context, private val scope: CoroutineScope) {
    private val api = BiliAccountClient()
    private val preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val credentials = AndroidLoginStore(context)

    var screen by mutableStateOf("首页")
        private set
    var theme by mutableStateOf(preferences.getInt("theme", 0).coerceIn(0, 2))
        private set
    var account by mutableStateOf<BiliAccount?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var songs by mutableStateOf(emptyList<BiliVideo>())
        private set
    var creators by mutableStateOf(emptyList<BiliCreator>())
        private set
    var collections by mutableStateOf(emptyList<BiliCollection>())
        private set
    var folders by mutableStateOf(emptyList<BiliFolder>())
        private set
    var groups by mutableStateOf(emptyList<BiliFolder>())
        private set
    var folder by mutableStateOf<BiliFolder?>(null)
        private set
    var creator by mutableStateOf<BiliCreator?>(null)
        private set
    var collection by mutableStateOf<BiliCollection?>(null)
        private set
    var inGroup by mutableStateOf(false)
        private set
    var qr by mutableStateOf<LoginCode?>(null)
        private set
    var loginStatus by mutableStateOf("")
        private set
    var qrExpired by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var users by mutableStateOf(false)
        private set
    var order by mutableStateOf("totalrank")
        private set
    var history by mutableStateOf(readArray("search").let { items -> (0 until items.length()).map(items::getString) })
        private set
    var suggestions by mutableStateOf(emptyList<String>())
        private set
    var recent by mutableStateOf(readRecent())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var queue by mutableStateOf(emptyList<Song>())
        private set
    var current by mutableStateOf<Song?>(null)
        private set
    var parts by mutableStateOf(emptyList<BiliPart>())
        private set
    var preparing by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var duration by mutableIntStateOf(0)
        private set
    var position by mutableIntStateOf(0)
        private set
    var mode by mutableIntStateOf(0)
        private set
    var volume by mutableFloatStateOf(1f)
        private set
    private var previousVolume = 1f
    private var page = 1
    private var viewJob: Job? = null
    private var suggestionJob: Job? = null
    private var loginJob: Job? = null
    private var playerJob: Job? = null
    private var queueJob: Job? = null
    private var player: MediaPlayer? = null
    private val visited = mutableSetOf<String>()
    private var ranking = emptyList<BiliVideo>()
    private var previousRecommendations = emptySet<String>()

    private var selectedFolderIds by mutableStateOf(emptySet<String>())
    val imported get() = folders.filter { it.id.toString() in selectedFolderIds }
    var groupId by mutableStateOf<Long?>(null)
        private set

    private fun applyAccount(value: BiliAccount?) {
        account = value
        selectedFolderIds = value?.let { preferences.getStringSet("folders.${it.id}", emptySet()).orEmpty().toSet() }.orEmpty()
        groupId = value?.let { preferences.getLong("group.${it.id}", 0).takeIf { id -> id > 0 } }
    }

    fun start() {
        navigate("首页")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    credentials.read()?.let { (cookie, csrf) -> api.restoreAccount(cookie, csrf) }
                }
            }.onSuccess {
                if (account == null && it != null && screen != "登录") {
                    applyAccount(it)
                    refreshFolders()
                }
            }
        }
        scope.launch {
            while (isActive) {
                if (duration > 0) position = runCatching { player?.currentPosition ?: position }.getOrDefault(position)
                delay(250)
            }
        }
    }

    fun navigate(target: String) {
        if (target != "登录") loginJob?.cancel()
        viewJob?.cancel()
        busy = false
        screen = target
        message = ""
        hasMore = false
        page = 1
        songs = emptyList()
        creators = emptyList()
        collections = emptyList()
        when (target) {
            "首页" -> refreshHome()
            "歌单" -> refreshFolders()
            "UP主" -> if (groupId != null) loadPage()
            "登录" -> beginLogin()
        }
    }

    fun refreshHome(force: Boolean = false) {
        val signedIn = account
        val savedFolders = imported
        load {
            val pool = withContext(Dispatchers.IO) {
                val excluded = savedFolders.flatMap { api.videos(requireNotNull(signedIn), it) }.map(BiliVideo::bvid).toSet()
                val ranked = if (force || ranking.isEmpty()) api.musicRanking(signedIn) else ranking
                ranked to ranked.filterNot { it.bvid in excluded }
            }
            ranking = pool.first.distinctBy(BiliVideo::bvid)
            val (previous, fresh) = pool.second.distinctBy(BiliVideo::bvid).partition { it.bvid in previousRecommendations }
            songs = (fresh.shuffled() + previous.shuffled()).take(10)
            previousRecommendations = songs.map(BiliVideo::bvid).toSet()
        }
    }

    fun type(value: String) {
        query = value
        suggestions = emptyList()
        suggestionJob?.cancel()
        if (value.isBlank()) return
        suggestionJob = scope.launch {
            delay(280)
            try {
                suggestions = withContext(Dispatchers.IO) { api.suggestions(value) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { }
        }
    }

    fun search(value: String = query, people: Boolean = users, sort: String = order) {
        val keyword = value.trim()
        if (keyword.isBlank()) return
        query = keyword
        suggestionJob?.cancel()
        suggestions = emptyList()
        users = people
        order = sort
        history = (listOf(keyword) + history.filterNot { it == keyword }).take(20)
        preferences.edit().putString("search", JSONArray(history).toString()).apply()
        navigate("搜索结果")
        loadPage()
    }

    fun clearHistory() {
        history = emptyList()
        preferences.edit().remove("search").apply()
    }

    fun openFolder(value: BiliFolder) {
        folder = value
        navigate("收藏夹")
        val signedIn = account ?: return
        load { songs = withContext(Dispatchers.IO) { api.videos(signedIn, value) } }
    }

    fun openCreator(value: BiliCreator, showCollections: Boolean = false) {
        creator = value
        inGroup = false
        navigate(if (showCollections) "合集列表" else "投稿")
        loadPage()
        val signedIn = account ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.followState(signedIn, value.id) } }.onSuccess {
                if (account === signedIn && creator?.id == value.id) inGroup = groupId in it.second
            }
        }
    }

    fun openCollection(value: BiliCollection) {
        collection = value
        navigate("合集")
        loadPage()
    }

    fun loadPage() {
        if (busy) return
        val target = screen
        val signedIn = account
        val keyword = query
        val selectedOrder = order
        val number = page
        val group = groupId
        val person = creator?.id
        val series = collection?.id
        when {
            target == "搜索结果" && users ->
                load { val batch = withContext(Dispatchers.IO) { api.searchCreators(keyword, number, signedIn) }; creators = (creators + batch.items).distinctBy(BiliCreator::id); advance(batch.hasMore) }
            target == "搜索结果" ->
                load { val batch = withContext(Dispatchers.IO) { api.searchVideos(keyword, number, signedIn, selectedOrder) }; append(batch) }
            target == "UP主" && signedIn != null && group != null ->
                load { val batch = withContext(Dispatchers.IO) { api.followGroupMembers(signedIn, group, number) }; creators = (creators + batch.items).distinctBy(BiliCreator::id); advance(batch.hasMore) }
            target == "投稿" && person != null ->
                load { append(withContext(Dispatchers.IO) { api.creatorVideos(person, number, signedIn) }) }
            target == "合集列表" && person != null ->
                load { val batch = withContext(Dispatchers.IO) { api.creatorCollections(person, number, signedIn) }; collections = (collections + batch.items).distinctBy(BiliCollection::id); advance(batch.hasMore) }
            target == "合集" && person != null && series != null ->
                load { append(withContext(Dispatchers.IO) { api.collectionVideos(person, series, number, signedIn) }) }
        }
    }

    private fun append(batch: BiliPage<BiliVideo>) {
        songs = (songs + batch.items).distinctBy(BiliVideo::bvid)
        advance(batch.hasMore)
    }

    private fun advance(more: Boolean) { page++; hasMore = more }

    fun beginLogin() {
        loginJob?.cancel()
        qr = null
        qrExpired = false
        loginStatus = "正在获取二维码"
        loginJob = scope.launch {
            try {
                val code = withContext(Dispatchers.IO) { api.generate() }
                qr = code
                loginStatus = "请用 B 站 App 扫码登录"
                while (isActive) {
                    delay(2000)
                    when (val result = withContext(Dispatchers.IO) { api.poll(code.key) }) {
                        LoginPoll.Waiting -> Unit
                        LoginPoll.Confirm -> loginStatus = "请在 B 站 App 确认登录"
                        LoginPoll.Expired -> { qrExpired = true; loginStatus = "二维码已过期"; return@launch }
                        is LoginPoll.Success -> {
                            val saved = withContext(Dispatchers.IO) { runCatching { credentials.save(result.account) }.isSuccess }
                            applyAccount(result.account)
                            navigate("首页")
                            refreshFolders()
                            if (!saved) message = "登录状态未能保存"
                            return@launch
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                qrExpired = true
                loginStatus = "无法获取登录状态，请重试"
            }
        }
    }

    fun logout() {
        credentials.clear()
        applyAccount(null)
        folders = emptyList()
        groups = emptyList()
        queueJob?.cancel()
        navigate("首页")
    }

    fun refreshFolders() {
        val signedIn = account ?: return
        action {
            val result = withContext(Dispatchers.IO) { api.folders(signedIn) }
            if (account === signedIn) folders = result
        }
    }

    fun selectFolder(value: BiliFolder, visible: Boolean = true) {
        val signedIn = account ?: return
        val ids = selectedFolderIds.toMutableSet()
        if (visible) ids.add(value.id.toString()) else ids.remove(value.id.toString())
        preferences.edit().putStringSet("folders.${signedIn.id}", ids).apply()
        selectedFolderIds = ids.toSet()
        if (!visible && folder?.id == value.id && screen == "收藏夹") navigate("歌单")
    }

    fun createFolder(title: String) = folderAction { signedIn ->
        val id = withContext(Dispatchers.IO) { api.createFolder(signedIn, title) }
        val result = withContext(Dispatchers.IO) { api.folders(signedIn) }
        if (account === signedIn) {
            folders = result
            result.firstOrNull { it.id == id }?.let { selectFolder(it) }
        }
    }

    fun renameFolder(value: BiliFolder, title: String) = folderAction { signedIn ->
        withContext(Dispatchers.IO) { api.renameFolder(signedIn, value, title) }
        refreshFolders()
    }

    fun deleteFolder(value: BiliFolder) = folderAction { signedIn ->
        withContext(Dispatchers.IO) { api.deleteFolder(signedIn, value) }
        if (account === signedIn) { selectFolder(value, false); refreshFolders() }
    }

    fun changeSong(value: BiliFolder, video: BiliVideo, remove: Boolean) = folderAction { signedIn ->
        withContext(Dispatchers.IO) {
            if (remove) api.remove(signedIn, value, video.bvid) else api.add(signedIn, value, video.bvid)
        }
        if (account === signedIn && screen == "收藏夹" && folder?.id == value.id) openFolder(value)
    }

    fun refreshGroups() = folderAction { signedIn ->
        val result = withContext(Dispatchers.IO) { api.followGroups(signedIn) }
        if (account === signedIn) groups = result
    }

    fun selectGroup(group: BiliFolder) {
        val signedIn = account ?: return
        preferences.edit().putLong("group.${signedIn.id}", group.id).apply()
        groupId = group.id
        navigate("UP主")
    }

    fun toggleFollow(value: BiliCreator) = folderAction { signedIn ->
        val id = groupId ?: return@folderAction
        val added = withContext(Dispatchers.IO) {
            val (following, groupIds) = api.followState(signedIn, value.id)
            if (following && id in groupIds) { api.setFollow(signedIn, value.id, false); false }
            else {
                if (!following) api.setFollow(signedIn, value.id, true)
                api.copyFollowToGroup(signedIn, value.id, id)
                true
            }
        }
        if (account === signedIn && creator?.id == value.id) inGroup = added
    }

    private fun folderAction(block: suspend (BiliAccount) -> Unit) {
        val signedIn = account ?: return navigate("登录")
        action { block(signedIn) }
    }

    fun theme(value: Int) { theme = value; preferences.edit().putInt("theme", value).apply() }

    fun playVideo(video: BiliVideo) {
        queueJob?.cancel()
        queue = (songs.ifEmpty { listOf(video) }).map(::Song)
        visited.clear()
        play(Song(video))
    }

    fun playRecent(song: Song) {
        queueJob?.cancel()
        queue = recent
        visited.clear()
        play(song)
    }

    fun enqueue(video: BiliVideo, next: Boolean = false) {
        val signedIn = account
        action {
            val additions = withContext(Dispatchers.IO) { expand(video, signedIn) }
            val index = if (next) queue.indexOfFirst { it.key == current?.key } + 1 else queue.size
            queue = queue.take(index) + additions + queue.drop(index)
        }
    }

    fun shuffle(kind: String) {
        val signedIn = account
        val visible = songs
        val saved = imported
        val group = groupId
        val author = creator
        queueJob?.cancel()
        queueJob = scope.launch {
            try {
                val mixed = withContext(Dispatchers.IO) {
                    val candidates = when (kind) {
                        "歌单" -> saved.flatMap { api.videos(requireNotNull(signedIn), it) }
                        "B站" -> api.musicRanking(signedIn)
                        "UP主" -> if (signedIn == null || group == null) emptyList() else
                            allPages { api.followGroupMembers(signedIn, group, it) }
                                .distinctBy(BiliCreator::id).flatMap { person -> allPages { api.creatorVideos(person.id, it, signedIn) } }
                        "投稿" -> if (author == null) emptyList() else allPages { api.creatorVideos(author.id, it, signedIn) }
                        else -> visible
                    }
                    candidates.distinctBy(BiliVideo::bvid).flatMap { video ->
                        ensureActive()
                        runCatching { expand(video, signedIn) }.getOrElse { listOf(Song(video)) }
                    }.distinctBy(Song::key).shuffled()
                }
                val first = mixed.firstOrNull() ?: run { message = "没有可播放的歌曲"; return@launch }
                queue = mixed
                mode = 2
                visited.clear()
                play(first)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) { showError(error) }
        }
    }

    fun enqueueCreator() {
        val value = creator ?: return
        val signedIn = account
        action {
            val added = withContext(Dispatchers.IO) {
                allPages { api.creatorVideos(value.id, it, signedIn) }.distinctBy(BiliVideo::bvid)
                    .flatMap { expand(it, signedIn) }
            }
            queue = (queue + added).distinctBy(Song::key)
        }
    }

    private suspend fun <T> allPages(request: (Int) -> BiliPage<T>): List<T> {
        val items = mutableListOf<T>()
        for (index in 1..500) {
            currentCoroutineContext().ensureActive()
            val batch = request(index)
            items.addAll(batch.items)
            if (!batch.hasMore || batch.items.isEmpty()) return items
        }
        error("无法完整读取列表")
    }

    private fun expand(video: BiliVideo, signedIn: BiliAccount?): List<Song> {
        val segments = api.parts(video.bvid, signedIn)
        return if (segments.size > 1) segments.map { Song(video, it.page, it.title) } else listOf(Song(video))
    }

    fun play(song: Song) {
        playerJob?.cancel()
        releasePlayer()
        current = song
        preparing = true
        parts = emptyList()
        visited.add(song.key)
        val signedIn = account
        playerJob = scope.launch {
            try {
                val audio = withContext(Dispatchers.IO) { api.audio(song.video.bvid, signedIn, song.part.coerceAtLeast(1)) }
                parts = audio.parts
                val engine = MediaPlayer()
                player = engine
                engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                engine.setVolume(volume, volume)
                engine.setDataSource(context, Uri.parse(audio.url.toString()), mapOf(
                    "Referer" to "https://www.bilibili.com/", "User-Agent" to "Mozilla/5.0 (Android)"))
                engine.setOnPreparedListener {
                    if (player !== it) return@setOnPreparedListener
                    runCatching {
                        duration = it.duration.coerceAtLeast(0)
                        it.start()
                        preparing = false
                        playing = true
                        record(song)
                    }.onFailure {
                        releasePlayer()
                        message = "无法启动此音轨"
                    }
                }
                engine.setOnCompletionListener { if (player === it) { playing = false; next(automatic = true) } }
                engine.setOnErrorListener { source, _, _ ->
                    if (player === source) { releasePlayer(); message = "无法播放此音轨" }
                    true
                }
                engine.prepareAsync()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) { releasePlayer(); showError(error) }
        }
    }

    fun togglePlayback() {
        if (preparing) return
        if (duration == 0) { current?.let(::play) ?: queue.firstOrNull()?.let(::play); return }
        player?.let { engine ->
            runCatching { if (playing) engine.pause() else engine.start() }
                .onSuccess { playing = !playing }
                .onFailure { releasePlayer(); message = "播放状态已失效，请重试" }
        }
    }

    fun seek(value: Int) {
        if (duration > 0) runCatching { player?.seekTo(value.coerceIn(0, duration)) }
    }

    fun volume(value: Float) { volume = value; player?.setVolume(value, value) }

    fun mute() {
        if (volume > 0f) { previousVolume = volume; volume(0f) } else volume(previousVolume)
    }

    fun cycleMode() { mode = (mode + 1) % 3; visited.clear(); current?.let { visited.add(it.key) } }

    fun clearQueue() { queue = emptyList(); visited.clear() }

    fun previous() {
        val index = queue.indexOfFirst { it.key == current?.key }
        queue.getOrNull(index - 1)?.let(::play)
    }

    fun next(automatic: Boolean = false) {
        val song = current
        if (automatic && mode == 1 && song != null) { play(song); return }
        if (mode == 2) { queue.filterNot { it.key in visited }.randomOrNull()?.let(::play); return }
        val index = queue.indexOfFirst { it.key == song?.key }
        val base = if (index < 0) queue.indexOfFirst { it.video.bvid == song?.video?.bvid } else index
        if (song != null && (queue.getOrNull(base)?.part == 0 || base < 0 && song.part == 0)) {
            parts.firstOrNull { it.page > song.part.coerceAtLeast(1) }?.let {
                play(Song(song.video, it.page, it.title))
                return
            }
        }
        queue.getOrNull(base + 1)?.let(::play)
    }

    private fun releasePlayer() {
        preparing = false
        player?.release()
        player = null
        duration = 0
        position = 0
        playing = false
    }

    fun dispose() {
        playerJob?.cancel()
        releasePlayer()
    }

    private fun record(song: Song) {
        recent = (listOf(song) + recent.filterNot { it.key == song.key }).take(80)
        val payload = JSONArray()
        recent.forEach { item ->
            payload.put(JSONObject().put("id", item.video.bvid).put("title", item.video.title)
                .put("author", item.video.author).put("cover", item.video.cover)
                .put("part", item.part).put("partTitle", item.partTitle))
        }
        preferences.edit().putString("recentSongs", payload.toString()).apply()
    }

    private fun readArray(key: String) = runCatching {
        JSONArray(preferences.getString(key, "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    private fun readRecent(): List<Song> {
        val items = readArray("recentSongs")
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = BilibiliLinks.parseVideoId(item.optString("id")) ?: return@mapNotNull null
            Song(BiliVideo(0, id, item.optString("title"), item.optString("author"), item.optString("cover")),
                item.optInt("part"), item.optString("partTitle"))
        }
    }

    private fun load(block: suspend () -> Unit) {
        viewJob?.cancel()
        viewJob = scope.launch {
            busy = true
            message = ""
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { if (isActive) busy = false }
        }
    }

    private fun action(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
        }
    }

    private fun showError(error: Exception) {
        message = (error as? IllegalStateException)?.message ?: "B 站请求失败，请检查网络后重试"
    }
}
