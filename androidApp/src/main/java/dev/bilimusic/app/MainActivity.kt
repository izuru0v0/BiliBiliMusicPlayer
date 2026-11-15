package dev.bilimusic.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.bilimusic.desktop.BiliFolder
import dev.bilimusic.desktop.BiliVideo
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MusicApp() }
    }
}

private data class Palette(val name: String, val accent: Color, val bg: Color, val surface: Color)
private val palettes = listOf(
    Palette("黄黑色", Color(0xFFF8D347), Color(0xFF101011), Color(0xFF19191B)),
    Palette("墨绿色", Color(0xFF94D8AF), Color(0xFF0C1814), Color(0xFF16261F)),
    Palette("墨蓝色", Color(0xFF8EC1F5), Color(0xFF0D1724), Color(0xFF182536)),
)
private val tabs = listOf("首页", "歌单", "UP主", "最近")
private val modes = listOf("顺序", "单曲循环", "随机")

private val LocalEnglish = compositionLocalOf { false }

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    MaterialText(dev.bilimusic.core.musicLabel(text, LocalEnglish.current), modifier,
        color = color, style = style, maxLines = maxLines, overflow = overflow, textAlign = textAlign)
}

@Composable
private fun MusicApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { MusicState(context.applicationContext, scope) }
    val palette = palettes[state.theme]
    var picker by remember { mutableStateOf("") }
    var selectedSong by remember { mutableStateOf<BiliVideo?>(null) }
    var managedFolder by remember { mutableStateOf<BiliFolder?>(null) }
    var nameDialog by remember { mutableStateOf("") }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun confirm(title: String, action: () -> Unit) {
        confirmTitle = title
        confirmAction = action
    }

    var settingsOrigin by remember { mutableStateOf("首页") }
    fun backFromSettings() {
        if (state.screen != "设置") state.navigate("设置")
        else state.navigate(settingsOrigin)
    }
    LaunchedEffect(state) { state.start() }
    DisposableEffect(state) { onDispose { state.dispose() } }
    BackHandler(state.screen != "首页") {
        if (state.screen in listOf("设置", "主题切换", "语言")) {
            backFromSettings()
            return@BackHandler
        }
        state.navigate(when (state.screen) {
            "收藏夹" -> "歌单"
            "合集" -> "合集列表"
            "投稿", "合集列表" -> "UP主"
            "搜索结果" -> "搜索"
            else -> "首页"
        })
    }

    CompositionLocalProvider(LocalEnglish provides state.english) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = palette.accent, onPrimary = palette.bg, background = palette.bg,
        surface = palette.surface, surfaceContainer = palette.surface,
    )) {
        Surface(Modifier.fillMaxSize(), color = palette.bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val settingsPage = state.screen in listOf("设置", "主题切换", "语言")
                    if (settingsPage) TextButton(onClick = ::backFromSettings) { Text("‹ 返回") }
                    Text(if (settingsPage) state.screen else "B站音乐播放器", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge)
                    if (!settingsPage) {
                    TextButton(onClick = { settingsOrigin = tabs.firstOrNull { it == state.screen } ?: "首页"; state.navigate("设置") }) { Text("设置") }
                    TextButton(onClick = {
                        if (state.account == null) state.navigate("登录")
                        else confirm("确定退出 B 站账号？", state::logout)
                    }) {
                        Text(state.account?.name ?: "登录", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    }
                }
                PlayerContent(state, Modifier.weight(1f),
                    openPicker = { picker = it },
                    selectSong = { video, kind -> selectedSong = video; picker = kind },
                    manage = { managedFolder = it; picker = "管理收藏夹" },
                    confirm = ::confirm)
                PlayerBar(state) { picker = it }
                if (state.screen !in listOf("设置", "主题切换", "语言")) NavigationBar(containerColor = palette.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = state.screen == tab || tab == "首页" && state.screen in listOf("搜索", "搜索结果") ||
                                tab == "歌单" && state.screen == "收藏夹" ||
                                tab == "UP主" && state.screen in listOf("投稿", "合集", "合集列表"),
                            onClick = { state.navigate(tab) },
                            icon = { NavigationIcon(tab) },
                            label = { Text(tab) },
                        )
                    }
                }
            }
        }

        when (picker) {
            "歌单来源" -> ChoiceDialog("添加歌单", onDismiss = { picker = "" }) {
                TextButton(onClick = { state.refreshFolders(); picker = "导入" }) { Text("导入 B 站收藏夹") }
                TextButton(onClick = { picker = ""; managedFolder = null; nameDialog = "新建 B 站收藏夹" }) {
                    Text("新建 B 站收藏夹")
                }
            }
            "导入", "添加到歌单" -> {
                val importing = picker == "导入"
                val choices = if (importing) state.folders.filterNot { it in state.imported } else state.imported
                ChoiceDialog(if (importing) "导入 B 站收藏夹" else "添加到 B 站收藏夹", { picker = "" }) {
                    if (choices.isEmpty()) Text(if (importing) "没有可导入的收藏夹" else "请先在歌单页导入收藏夹")
                    choices.forEach { folder ->
                        TextButton(onClick = {
                            if (importing) state.selectFolder(folder)
                            else { selectedSong?.let { state.changeSong(folder, it, false) }; picker = "" }
                        }) { Text(folder.title) }
                    }
                }
            }
            "分组" -> ChoiceDialog("选择关注分组", { picker = "" }) {
                if (state.groups.isEmpty()) Text("暂无可选分组，可先在 B 站创建自定义关注分组")
                state.groups.forEach { group ->
                    TextButton(onClick = { state.selectGroup(group); picker = "" }) { Text(group.title) }
                }
            }
            "管理收藏夹" -> managedFolder?.let { folder ->
                ChoiceDialog(folder.title, { picker = "" }) {
                    TextButton(onClick = { picker = ""; nameDialog = "重命名 B 站收藏夹" }) { Text("重命名") }
                    TextButton(onClick = { state.selectFolder(folder, false); picker = "" }) { Text("从应用移除") }
                    if (!folder.isDefault) TextButton(onClick = {
                        picker = ""
                        confirm("确定在 B 站删除「${folder.title}」？") { state.deleteFolder(folder) }
                    }) { Text("在 B 站删除") }
                }
            }
            "歌曲操作" -> selectedSong?.let { video ->
                ChoiceDialog(video.title, { picker = "" }) {
                    TextButton(onClick = { state.enqueue(video, next = true); picker = "" }) { Text("下一首播放") }
                    TextButton(onClick = { state.enqueue(video); picker = "" }) { Text("加入队列") }
                    TextButton(onClick = { picker = "添加到歌单" }) { Text("添加到歌单") }
                    if (state.screen == "收藏夹") state.folder?.let { folder ->
                        TextButton(onClick = {
                            picker = ""
                            confirm("确定从此 B 站收藏夹删除歌曲？") { state.changeSong(folder, video, true) }
                        }) { Text("从此收藏夹删除") }
                    }
                }
            }
            "播放队列" -> ChoiceDialog("播放队列", { picker = "" }) {
                TextButton(onClick = state::cycleMode) { Text(modes[state.mode]) }
                TextButton(onClick = state::clearQueue) { Text("清空队列") }
                state.queue.forEach { song ->
                    TextButton(onClick = { state.play(song); picker = "" }) { Text(song.title) }
                }
            }
            "分 P 队列" -> ChoiceDialog("分 P 队列", { picker = "" }) {
                state.current?.let { song ->
                    state.parts.forEach { part ->
                        TextButton(onClick = { state.play(Song(song.video, part.page, part.title)); picker = "" }) {
                            Text("P${part.page} · ${part.title}")
                        }
                    }
                }
            }
            "音量" -> ChoiceDialog("音量", { picker = "" }) {
                Slider(value = state.volume, onValueChange = state::volume)
                TextButton(onClick = state::mute) { Text(if (state.volume == 0f) "恢复音量" else "静音") }
            }
        }
        if (nameDialog.isNotEmpty()) {
            val folder = managedFolder
            var input by remember(nameDialog) { mutableStateOf(folder?.title.orEmpty()) }
            AlertDialog(
                onDismissRequest = { nameDialog = "" },
                title = { Text(nameDialog) },
                text = { OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("歌单名称") }) },
                confirmButton = {
                    TextButton(enabled = input.isNotBlank(), onClick = {
                        val name = input.trim()
                        if (folder == null) state.createFolder(name) else state.renameFolder(folder, name)
                        nameDialog = ""
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { nameDialog = "" }) { Text("取消") } },
            )
        }
        confirmAction?.let { action ->
            AlertDialog(
                onDismissRequest = { confirmAction = null },
                title = { Text(confirmTitle) },
                confirmButton = { TextButton(onClick = { confirmAction = null; action() }) { Text("确认") } },
                dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("取消") } },
            )
        }
    }
}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerContent(
    state: MusicState,
    modifier: Modifier,
    openPicker: (String) -> Unit,
    selectSong: (BiliVideo, String) -> Unit,
    manage: (BiliFolder) -> Unit,
    confirm: (String, () -> Unit) -> Unit,
) {
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    LaunchedEffect(state.screen, state.users, state.order) {
        list.requestScrollToItem(0)
    }
    LaunchedEffect(state.screen, state.hasMore, state.busy) {
        if (state.hasMore && !state.busy) snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 2
        }.distinctUntilChanged().collect { if (it) state.loadPage() }
    }
    LazyColumn(
        modifier.fillMaxWidth(), state = list,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.screen in listOf("首页", "搜索", "搜索结果")) item {
            OutlinedTextField(
                value = state.query, onValueChange = { state.type(it); if (state.screen == "首页") state.navigate("搜索") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索 B 站音乐或 UP 主") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); state.search() }),
                trailingIcon = { TextButton(onClick = { focus.clearFocus(); state.search() }) { Text("搜索") } },
            )
        }
        when (state.screen) {
            "首页" -> {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (state.english) "What would you like to hear today?" else "今天想要听点什么呢老大♡(｡•̀ᴗ-)✧",
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(16.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { state.shuffle("歌单") }) { Text("我的歌单混播 →") }
                                Button(
                                    onClick = { state.shuffle("B站") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.background,
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) { Text("B 站随机音乐 →") }
                            }
                        }
                    }
                }
                item { Section("新歌推荐", "刷新") { state.refreshHome(force = true) } }
                songRows(state.songs, state::playVideo, selectSong)
            }
            "搜索" -> {
                if (state.suggestions.isNotEmpty()) {
                    item { Section("搜索建议") }
                    items(state.suggestions) { word -> TextButton(onClick = { focus.clearFocus(); state.search(word) }) { Text(word) } }
                }
                item { Section("搜索历史", "清空", state::clearHistory) }
                items(state.history) { word -> TextButton(onClick = { focus.clearFocus(); state.search(word) }) { Text(word) } }
            }
            "搜索结果" -> {
                item {
                    Row {
                        FilterChip(!state.users, { state.search(people = false) }, { Text("视频") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(state.users, { state.search(people = true) }, { Text("用户") })
                    }
                }
                if (!state.users) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("综合" to "totalrank", "最新" to "pubdate", "收藏" to "stow", "播放" to "click")
                                .forEach { (title, sort) ->
                                    FilterChip(state.order == sort, { state.search(sort = sort) }, { Text(title) })
                                }
                        }
                    }
                    songRows(state.songs, state::playVideo, selectSong)
                } else items(state.creators, key = { it.id }) { creator ->
                    TextButton(onClick = { state.openCreator(creator) }) {
                        Cover(creator.avatar, "▣")
                        Spacer(Modifier.width(10.dp))
                        Text(creator.name)
                    }
                }
            }
            "歌单" -> {
                item { Section("我的歌单", "+") {
                    if (state.account == null) state.navigate("登录") else openPicker("歌单来源")
                } }
                if (state.imported.isEmpty()) item { Text("暂无已导入的 B 站收藏夹") }
                items(state.imported, key = { it.id }) { folder ->
                    Card(Modifier.fillMaxWidth().clickable { state.openFolder(folder) }) {
                        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(folder.title, Modifier.weight(1f))
                            TextButton(onClick = { manage(folder) }) { Text("⋯") }
                        }
                    }
                }
            }
            "收藏夹", "合集" -> {
                item { Section(if (state.screen == "收藏夹") state.folder?.title.orEmpty() else state.collection?.name.orEmpty(),
                    "随机播放") { state.shuffle("当前") } }
                songRows(state.songs, state::playVideo, selectSong)
            }
            "UP主" -> {
                item { Section("UP 主", "选择关注分组") {
                    if (state.account == null) state.navigate("登录")
                    else { state.refreshGroups(); openPicker("分组") }
                } }
                if (state.groupId != null) item {
                    TextButton(onClick = { state.shuffle("UP主") }) { Text("随机播放全部 UP 主投稿") }
                }
                items(state.creators, key = { it.id }) { creator ->
                    TextButton(onClick = { state.openCreator(creator) }) {
                        Cover(creator.avatar, "▣")
                        Spacer(Modifier.width(10.dp))
                        Text(creator.name)
                    }
                }
            }
            "投稿", "合集列表" -> {
                item { Section(state.creator?.name.orEmpty(), if (state.inGroup) "取消关注" else "关注／加入分组") {
                    val creator = state.creator ?: return@Section
                    when {
                        state.account == null -> state.navigate("登录")
                        state.groupId == null -> { state.refreshGroups(); openPicker("分组") }
                        state.inGroup -> confirm("确定在 B 站取消关注「${creator.name}」？") { state.toggleFollow(creator) }
                        else -> state.toggleFollow(creator)
                    }
                } }
                item {
                    Row {
                        TextButton(onClick = { state.creator?.let { state.openCreator(it) } }) { Text("投稿") }
                        TextButton(onClick = { state.creator?.let { state.openCreator(it, true) } }) { Text("合集") }
                        TextButton(onClick = { state.shuffle("投稿") }) { Text("随机播放") }
                        TextButton(onClick = state::enqueueCreator) { Text("全部加入队列") }
                    }
                }
                if (state.screen == "投稿") songRows(state.songs, state::playVideo, selectSong)
                else items(state.collections, key = { it.id }) { series ->
                    TextButton(onClick = { state.openCollection(series) }) { Text(series.name) }
                }
            }
            "最近" -> {
                item { Section("最近播放") }
                if (state.recent.isEmpty()) item { Text("暂无播放记录") }
                items(state.recent, key = Song::key) { song ->
                    SongRow(song.title, song.video.author, song.video.cover, { state.playRecent(song) },
                        { selectSong(song.video, "添加到歌单") }, { selectSong(song.video, "歌曲操作") })
                }
            }
            "登录" -> item { QrLogin(state) }
            "设置" -> {
                item {
                Card(Modifier.fillMaxWidth().clickable { state.navigate("主题切换") }) {
                    Row(Modifier.padding(16.dp)) {
                        Text("主题切换", Modifier.weight(1f))
                        Text("›")
                    }
                }
            }
                item {
                    Card(Modifier.fillMaxWidth().clickable { state.navigate("语言") }) {
                        Row(Modifier.padding(16.dp)) {
                            Text("语言", Modifier.weight(1f))
                            Text("›")
                        }
                    }
                }
            }
            "语言" -> item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!state.english, { state.changeLanguage(false) }, { Text("简体中文") })
                    FilterChip(state.english, { state.changeLanguage(true) }, { Text("English") })
                }
            }
            "主题切换" -> item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palettes.forEachIndexed { index, palette ->
                        FilterChip(
                            selected = state.theme == index,
                            onClick = { state.theme(index) },
                            label = { Text(palette.name, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }
        }
        if (state.message.isNotBlank()) item { Text(state.message, color = MaterialTheme.colorScheme.error) }
        if (state.hasMore && !state.busy) item {
            TextButton(onClick = state::loadPage) { Text("继续加载") }
        }
    }
}

private fun LazyListScope.songRows(
    songs: List<BiliVideo>,
    play: (BiliVideo) -> Unit,
    action: (BiliVideo, String) -> Unit,
) {
    items(songs.distinctBy(BiliVideo::bvid), key = { "video:${it.bvid}" }) { video ->
        val metadata = listOfNotNull(video.author.takeIf(String::isNotBlank), video.views?.let { "播放 $it" },
            video.duration.takeIf(String::isNotBlank), video.published?.takeIf { it in 1..253402300799L }?.let {
                java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            }).joinToString(" · ")
        SongRow(video.title, metadata, video.cover, { play(video) },
            { action(video, "添加到歌单") }, { action(video, "歌曲操作") })
    }
}

@Composable
private fun SongRow(title: String, subtitle: String, cover: String, play: () -> Unit, save: () -> Unit, more: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = play)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, "♫")
            Column(Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = save) { Text("♡") }
            IconButton(onClick = more) { Text("⋯") }
        }
    }
}

@Composable
private fun Cover(url: String, placeholder: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, url) {
        value = null
        if (url.isNotBlank()) value = withContext(Dispatchers.IO) { AndroidCovers.load(context, url) }
    }
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) Text(placeholder, color = MaterialTheme.colorScheme.primary)
        else Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun NavigationIcon(tab: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 2f * u)
        fun point(x: Int, y: Int) = Offset(x * u, y * u)
        fun line(x: Int, y: Int, endX: Int, endY: Int) =
            drawLine(color, point(x, y), point(endX, endY), stroke.width)
        when (tab) {
            "首页" -> drawPath(Path().apply {
                moveTo(4 * u, 11 * u)
                lineTo(12 * u, 4 * u)
                lineTo(20 * u, 11 * u)
                lineTo(20 * u, 20 * u)
                lineTo(4 * u, 20 * u)
                close()
            }, color, style = stroke)
            "歌单" -> {
                line(4, 6, 20, 6)
                line(4, 12, 20, 12)
                line(4, 18, 15, 18)
            }
            "UP主" -> {
                drawRoundRect(color, point(3, 8), Size(18 * u, 12 * u), CornerRadius(3 * u), style = stroke)
                line(9, 8, 6, 4)
                line(15, 8, 18, 4)
                line(8, 14, 10, 13)
                line(14, 13, 16, 14)
            }
            else -> {
                drawCircle(color, 8 * u, point(12, 12), style = stroke)
                line(12, 7, 12, 12)
                line(12, 12, 16, 14)
            }
        }
    }
}

@Composable
private fun Section(title: String, action: String = "", onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (action.isNotEmpty()) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun QrLogin(state: MusicState) {
    val bitmap = remember(state.qr) {
        state.qr?.let { code ->
            val size = 360
            val matrix = QRCodeWriter().encode(code.url, BarcodeFormat.QR_CODE, size, size)
            Bitmap.createBitmap(IntArray(size * size) { index ->
                if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap != null) Image(bitmap, "B 站登录二维码", Modifier.size(220.dp))
        Text(state.loginStatus, Modifier.padding(12.dp))
        if (state.qrExpired) Button(onClick = state::beginLogin) { Text("重新获取二维码") }
    }
}

@Composable
private fun PlayerBar(state: MusicState, open: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            val song = state.current
            Text(song?.title ?: "尚未播放音乐", Modifier.fillMaxWidth(),
                textAlign = if (song == null) TextAlign.Center else TextAlign.Start,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            var drag by remember(state.current) { mutableStateOf<Float?>(null) }
            if (state.current != null) {
                if (state.preparing) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        Text("加载中…", style = MaterialTheme.typography.labelSmall)
                    }
                } else Slider(
                    value = drag ?: state.position.coerceIn(0, state.duration).toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { state.seek(it.toInt()) }; drag = null },
                    enabled = state.duration > 0,
                    valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${timestamp(drag?.toInt() ?: state.position)} / ${timestamp(state.duration)}",
                    style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = state::previous) { Text("⏮") }
                IconButton(onClick = state::togglePlayback, enabled = !state.preparing) { Text(if (state.playing) "Ⅱ" else "▶") }
                IconButton(onClick = { state.next() }) { Text("⏭") }
                TextButton(onClick = { open("音量") }) { Text(if (state.volume == 0f) "静音" else "音量") }
                TextButton(onClick = { open("分 P 队列") }, enabled = state.parts.size > 1) { Text("分 P") }
                TextButton(onClick = { open("播放队列") }) { Text("队列") }
            }
        }
    }
}

private fun timestamp(value: Int): String = "%02d:%02d".format(value / 60000, (value / 1000) % 60)

@Composable
private fun ChoiceDialog(title: String, onDismiss: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            val scroll = androidx.compose.foundation.rememberScrollState()
            Column(Modifier.heightIn(max = 360.dp).then(Modifier.verticalScroll(scroll)), content = body)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
