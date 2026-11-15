package dev.bilimusic.desktop

import dev.bilimusic.core.*
import java.awt.*
import java.io.File
import java.util.Properties
import javax.swing.*

internal var bg=Color(16,16,17)
internal var panelColor=Color(25,25,27)
internal val desktopFont = GraphicsEnvironment.getLocalGraphicsEnvironment()
 .availableFontFamilyNames.firstOrNull { it.equals("Microsoft YaHei UI", ignoreCase = true) } ?: "SansSerif"
private data class PlayerTheme(val accent: Color, val background: Color, val surface: Color)
private val themes = linkedMapOf(
 "黄黑色" to PlayerTheme(Color(248, 211, 71), Color(16, 16, 17), Color(25, 25, 27)),
 "墨绿色" to PlayerTheme(Color(148, 216, 175), Color(12, 24, 20), Color(22, 38, 31)),
 "墨蓝色" to PlayerTheme(Color(142, 193, 245), Color(13, 23, 36), Color(24, 37, 54))
)
internal var accent = themes.values.first().accent

private fun applyPalette(theme: PlayerTheme) {
 accent = theme.accent
 bg = theme.background
 panelColor = theme.surface
}

private fun tintedSurface(strength: Double) = Color(
 (panelColor.red + (accent.red - panelColor.red) * strength).toInt(),
 (panelColor.green + (accent.green - panelColor.green) * strength).toInt(),
 (panelColor.blue + (accent.blue - panelColor.blue) * strength).toInt()
)
private const val defaultGreeting = "今天想要听点什么呢老大♡(｡•̀ᴗ-)✧"
class DesktopApp {
 private val frame=JFrame("B站音乐播放器")
 private val root=JPanel(BorderLayout(18,18))
 private val playlist=JComboBox<String>()
 private val storage=File(System.getProperty("bilimusic.data",System.getProperty("user.home")+"/.bilimusic"),"library.properties")
 private var tracks=listOf<Track>()
 private var names = mutableListOf<String>()
 private var english = false
 private fun tr(text: String) = dev.bilimusic.core.musicLabel(text, english)
 private var themeName = themes.keys.first()
 @Volatile private var cacheDirectory = File(storage.parentFile, "cache")
 private val thumbnailExecutor = java.util.concurrent.Executors.newFixedThreadPool(3) { task ->
  Thread(task, "BiliMusic thumbnail").apply { isDaemon = true }
 }
 private val loginStore = if (System.getProperty("os.name").startsWith("Windows"))
  DesktopLoginStore(File(storage.parentFile, "login.dpapi")) else null
 private val remoteFolders = linkedMapOf<String, BiliFolder>()
 private val selectedFolders = Properties()
 private val savedCollections = linkedMapOf<String, List<BiliCollection>>()
 private fun libraryCollections() = savedCollections[accountPanel?.signedInAccount?.id.toString()].orEmpty()
 private val biliClient = BiliAccountClient()
 private var searchHistory = emptyList<String>()
 private var greeting = defaultGreeting
 private val greetingLabel = GreetingLabel()
 private var audioSession: DesktopAudioSession? = null
 private var playbackWorker: SwingWorker<Unit, Unit>? = null
 private var activeDuration = 0
 private var playerVolume = 100
 private var activeAudio: BiliAudio? = null
 private var playbackMode = 0
 private var partPlaybackMode: Int? = null
 private val visitedQueue = mutableSetOf<String>()
 private val visitedParts = mutableSetOf<Int>()
 private val collectionStatus = JLabel("", SwingConstants.CENTER).apply {
  font = Font(desktopFont, Font.BOLD, 12)
  isVisible = false
 }
 private val collectionStatusTimer = Timer(4500) { collectionStatus.isVisible = false }.apply {
  isRepeats = false
 }
 private var collectionShufflePending = false
 private val deferredCollectionParts = mutableSetOf<String>()

 private fun reportCollectionStatus(message: String, pending: Boolean = false) {
  collectionStatusTimer.stop()
  collectionStatus.text = message
  collectionStatus.foreground = accent
  collectionStatus.isVisible = message.isNotEmpty()
  if (!pending && message.isNotEmpty()) collectionStatusTimer.restart()
 }

 private val timeProgress = JSlider(0, 0)
 private val audioLoading = AudioLoadingIndicator()
 private val progressCards = JPanel(CardLayout()).apply {
  isOpaque = false
  add(timeProgress, "progress")
  add(audioLoading, "loading")
 }
 private var scrubbing = false
 private var seekPending = false
 private val elapsedLabel = JLabel("00:00")
 private val durationLabel = JLabel("00:00")
 private val playCover = JLabel("♫", SwingConstants.CENTER)
 private var coverRequest: SwingWorker<Icon?, Unit>? = null
 private val nowPlaying = ScrollingTitle("尚未播放音乐")
 private val nowDetail = JLabel(" ")
 private val browsePages = setOf("搜索结果", "UP主", "UP主详情", "合集详情", "我的合集", "音乐库", "最近播放")
 private val browser = PagedBrowser(::button, ::requestError, ::tr)
 private val recommendationGrid = JPanel(GridLayout(0, 2, 10, 8)).apply { isOpaque = false }
 private val recommendationMessage = JLabel("", SwingConstants.CENTER)
 private var homeRecommendations = emptyList<BiliVideo>()
 private var recommendationWorker: SwingWorker<List<BiliVideo>, Unit>? = null
 private var shuffleWorker: SwingWorker<List<Track>, Unit>? = null
 private var creatorQueueWorker: SwingWorker<List<Track>, Unit>? = null
 private var recommendationsOpened = false
 private val knownVideos = mutableMapOf<String, BiliVideo>()
 private val followGroups = Properties()
 private val groupMembers = mutableSetOf<Long>()
 private var previousDiscover = emptySet<String>()
 private var rankingCache = emptyList<BiliVideo>()
 private var updateSidebar: () -> Unit = {}
 private val members=mutableMapOf<String,Set<String>>()
 private var recent=listOf<String>()
 private var queue = emptyList<Track>()
  set(value) {
   if (field != value) visitedQueue.clear()
   field = value
  }
 private var current:Track?=null
 private var page="首页"
 private var loading=false
 private var mainPages:JPanel?=null
 private var accountPanel:BiliAccountPanel?=null
 private val navButtons=mutableMapOf<String,JButton>()
 init {
  UIManager.put("ToolTipUI", PlayerToolTipFactory::class.java.name)
  applyPalette(themes.values.first())
  load()
  frame.iconImages = listOf(16, 32, 48, 64).map { size ->
   java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
    val graphics = createGraphics()
    try {
     graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
     graphics.color = Color(24, 24, 27)
     graphics.fillRoundRect(0, 0, size, size, size / 4, size / 4)
     graphics.scale(size / 28.0, size / 28.0)
     PlaybackIcon("music").paintIcon(JLabel().apply { foreground = Color(248, 211, 71) }, graphics, 3, 3)
    } finally { graphics.dispose() }
   }
  }
  frame.title = tr("B站音乐播放器")
  frame.isUndecorated = true
  frame.defaultCloseOperation=JFrame.DISPOSE_ON_CLOSE
  frame.addWindowListener(object : java.awt.event.WindowAdapter() {
   override fun windowClosed(e: java.awt.event.WindowEvent) {
    stopAudio()
    browser.stop()
    recommendationWorker?.cancel(true)
    creatorQueueWorker?.cancel(true)
    collectionQueueWorker?.cancel(true)
    collectionStatusTimer.stop()
    shuffleWorker?.cancel(true)
    folderRequest?.cancel(true)
    thumbnailExecutor.shutdownNow()
   }
  })
  root.background=bg;root.border=BorderFactory.createEmptyBorder(24,24,24,24)
  root.layout=BorderLayout(28,20)
  fun label(text:String,size:Int=14,color:Color=Color(232,231,226),bold:Boolean=false)=JLabel(tr(text)).apply{font=Font(desktopFont,if(bold) Font.BOLD else Font.PLAIN,size);foreground=color;alignmentX=0f}
  fun clearPanel(layout:LayoutManager)=JPanel(layout).apply{isOpaque=false}
  fun stack()=clearPanel(GridLayout(0,1,0,8))

  val nav = RoundedPanel(24)
  nav.layout = BorderLayout()
  nav.background = Color(26, 25, 23)
  nav.border = BorderFactory.createEmptyBorder(22, 16, 16, 16)
  nav.preferredSize = Dimension(238, 600)
  val brand=JPanel();brand.isOpaque=false;brand.layout=BoxLayout(brand,BoxLayout.Y_AXIS)
  val titleLabel = label("B站音乐播放器", 20, Color.WHITE, true)
  brand.add(clearPanel(BorderLayout(6, 0)).apply {
   alignmentX = 0f
   maximumSize = Dimension(Int.MAX_VALUE, 42)
   add(titleLabel, BorderLayout.CENTER)
   add(playerControl("settings", "设置") { page = "设置"; refresh() }.apply {
    preferredSize = Dimension(32, 36)
   }, BorderLayout.EAST)
  })
  brand.add(Box.createVerticalStrut(30))
  fun sidebarRow(text: String, action: () -> Unit) = button(text, action).apply {
   horizontalAlignment = SwingConstants.LEFT
   preferredSize = Dimension(1, 46)
   minimumSize = Dimension(1, 46)
   maximumSize = Dimension(Int.MAX_VALUE, 46)
   alignmentX = 0f
  }
  for (name in listOf("首页", "最近播放", "UP主")) {
   val row = sidebarRow(name) {
    when (name) {
     "UP主" -> showFavoriteCreators()
     else -> { page = name; refresh() }
    }
   }
   val iconName = mapOf("首页" to "home", "最近播放" to "recent", "UP主" to "creator")[name]
   if (iconName != null) { row.icon = PlaybackIcon(iconName); row.iconTextGap = 10 }
   navButtons[name] = row
   brand.add(row)
   brand.add(Box.createVerticalStrut(6))
  }
  brand.add(Box.createVerticalStrut(24))
  val playlistHeader = clearPanel(BorderLayout()).apply {
   alignmentX = 0f
   maximumSize = Dimension(Int.MAX_VALUE, 36)
   add(label("我的歌单", 15, Color(212, 209, 199), true), BorderLayout.WEST)
   add(button("") { choosePlaylistAction() }.apply {
    icon = AddPlaylistIcon()
    accessibleContext.accessibleName = "创建歌单"
    toolTipText = "创建歌单"
    preferredSize = Dimension(36, 36)
    border = BorderFactory.createEmptyBorder()
   }, BorderLayout.EAST)
  }
  brand.add(playlistHeader)
  brand.add(Box.createVerticalStrut(8))
  val sidebarPlaylists = JPanel().apply {
   isOpaque = false
   layout = GridLayout(0, 1, 0, 4)
   border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
   alignmentX = 0f
  }
  updateSidebar = {
   sidebarPlaylists.removeAll()
   names.forEach { name ->
    val folder = remoteFolders.getValue(name)
    sidebarPlaylists.add(sidebarRow(name) { openFolder(name) }.apply {
     icon = PlaybackIcon("folder")
     iconTextGap = 10
     componentPopupMenu = roundedPopup().apply {
      add(popupItem("重命名") { renameRemoteFolder(folder) })
      add(popupItem("从侧栏移除") { hideRemoteFolder(folder) })
      if (!folder.isDefault) add(popupItem("在 B 站删除收藏夹") { deleteRemoteFolder(folder) })
     }
    })
   }
   val collections = libraryCollections()
   if (collections.isNotEmpty()) sidebarPlaylists.add(sidebarRow("合集") { showLibraryCollections() }.apply {
    icon = PlaybackIcon("folder")
    iconTextGap = 10
   })
   sidebarPlaylists.maximumSize = Dimension(Int.MAX_VALUE, sidebarPlaylists.preferredSize.height)
   sidebarPlaylists.revalidate()
   sidebarPlaylists.repaint()
  }
  updateSidebar()
  brand.add(sidebarPlaylists)
  nav.add(themeScroll(JScrollPane(brand)).apply {
   horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
  }, BorderLayout.CENTER)
  val accountButton = button("登录B站账号") { openAccountPage() }
  val avatar = JLabel(ProfileAvatar(null), SwingConstants.CENTER)
  val nickname = label("", 14, Color(232, 231, 226), true)
  val accountFooter = clearPanel(CardLayout())
  var avatarWorker: SwingWorker<Icon, Unit>? = null
  val signOut = button("") {
   if (!confirmSignOut()) return@button
   runCatching { loginStore?.clear() }.onFailure {
    showPlaylistNotice("退出登录失败", "无法清除本机保存的登录凭据")
    return@button
   }
   avatarWorker?.cancel(true)
   avatarWorker = null
   avatar.icon = ProfileAvatar(null)
   nickname.text = ""
   nickname.toolTipText = null
   accountPanel?.signOut()
   groupMembers.clear()
   folderRequest?.cancel(true)
   folderRequest = null
   pendingFolder = null
   applyFolders(emptyList())
   (accountFooter.layout as CardLayout).show(accountFooter, "guest")
  }.apply {
   icon = SignOutIcon()
   toolTipText = "退出登录"
   accessibleContext.accessibleName = "退出登录"
   preferredSize = Dimension(40, 40)
   border = BorderFactory.createEmptyBorder()
  }
  val signedIn = clearPanel(BorderLayout(9, 0))
  signedIn.add(avatar, BorderLayout.WEST)
  signedIn.add(nickname, BorderLayout.CENTER)
  signedIn.add(signOut, BorderLayout.EAST)
  accountFooter.add(accountButton, "guest")
  accountFooter.add(signedIn, "signedIn")
  nav.add(accountFooter, BorderLayout.SOUTH)
  root.add(nav,BorderLayout.WEST)
  val center=clearPanel(BorderLayout(0,24))
  val top=clearPanel(BorderLayout(20,0));top.preferredSize=Dimension(800,52)
  val searchBox = RoundedPanel(32)
  searchBox.background = Color(40, 39, 36)
  searchBox.layout = BorderLayout(10, 0)
  searchBox.border = BorderFactory.createEmptyBorder(0, 12, 0, 16)
  searchBox.preferredSize = Dimension(386, 52)
  val search = SearchField()
  search.accessibleContext.accessibleName = "搜索音乐"
  val searchPopup = JPopupMenu().apply {
   isOpaque = false
   border = BorderFactory.createEmptyBorder()
   isLightWeightPopupEnabled = true
  }
  val searchAction = {
   searchPopup.isVisible = false
   val keyword = search.text.trim()
   if (keyword.isEmpty()) search.requestFocusInWindow()
   else searchInApp(keyword)
   Unit
  }
  search.addActionListener { searchAction() }
  installSearchHints(search, searchPopup)
  val searchButton = JButton(SearchIcon()).apply {
   accessibleContext.accessibleName = "搜索音乐"
   toolTipText = "搜索 B 站音乐"
   preferredSize = Dimension(30, 30)
   isContentAreaFilled = false
   isBorderPainted = false
   cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
   addActionListener { searchAction() }
  }
  searchBox.add(searchButton, BorderLayout.WEST)
  searchBox.add(search, BorderLayout.CENTER)
  top.add(searchBox, BorderLayout.WEST)
  val windowTools = clearPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
  fun windowControl(symbol: String, description: String, action: () -> Unit) =
   button(symbol, action).apply {
    preferredSize = Dimension(42, 44)
    font = font.deriveFont(22f)
    border = BorderFactory.createEmptyBorder()
    toolTipText = tr(description)
    accessibleContext.accessibleName = tr(description)
   }
  windowTools.add(windowControl("−", "最小化") { frame.extendedState = frame.extendedState or Frame.ICONIFIED })
  windowTools.add(windowControl("□", "最大化或还原") { toggleMaximized() })
  windowTools.add(windowControl("×", "关闭") { frame.dispose() })
  top.add(windowTools, BorderLayout.EAST)
  val dragArea = clearPanel(BorderLayout())
  dragArea.toolTipText = "拖动窗口"
  top.add(dragArea, BorderLayout.CENTER)
  installWindowDrag(dragArea)
  installWindowDrag(titleLabel)
  center.add(top, BorderLayout.NORTH)
  val home=JPanel();home.isOpaque=false;home.layout=BoxLayout(home,BoxLayout.Y_AXIS)
  val hero = RoundedPanel(28).apply {
   layout = BorderLayout(16, 0)
   name = "theme.hero"
   background = tintedSurface(.065)
   border = BorderFactory.createEmptyBorder(0, 24, 0, 24)
   alignmentX = 0f
   minimumSize = Dimension(1, 192)
   maximumSize = Dimension(Int.MAX_VALUE, 192)
   preferredSize = Dimension(850, 192)
   greetingLabel.text = if (english && greeting == defaultGreeting) "What would you like to hear today?" else greeting
   greetingLabel.font = Font(desktopFont, Font.BOLD, 27)
   greetingLabel.foreground = accent
   greetingLabel.putClientProperty("html.disable", true)
   greetingLabel.alignmentX = 0f
   val bannerContent = clearPanel(BorderLayout()).apply {
    border = BorderFactory.createEmptyBorder(38, 0, 22, 0)
    add(greetingLabel, BorderLayout.NORTH)
    add(clearPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
     add(button("我的歌单混播 →") {
      val folders = remoteFolders.values.toList()
      val collections = libraryCollections()
      startShuffle { account ->
       if (account == null) emptyList()
       else folders.flatMap { biliClient.videos(account, it).map(BiliVideo::asTrack) } +
        collections.flatMap { allCollectionTracks(it, account) }
      }
     }.apply {
      background = accent
      foreground = bg
     })
     add(button("B 站随机音乐 →") {
      val cached = rankingCache
      startShuffle { account -> (cached.ifEmpty { biliClient.musicRanking(account) }).map(BiliVideo::asTrack) }
     })
    }, BorderLayout.SOUTH)
   }
   add(bannerContent, BorderLayout.CENTER)
   add(SoundArtwork().apply { preferredSize = Dimension(158, 116) }, BorderLayout.EAST)
  }
  home.add(hero)
  home.add(Box.createVerticalStrut(28))
  val recommendationSection = clearPanel(BorderLayout(0, 12)).apply {
   alignmentX = 0f
   maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
   add(clearPanel(BorderLayout()).apply {
    add(label("新歌推荐", 22, Color(242, 240, 232), true), BorderLayout.WEST)
    add(button("刷新") { loadHomeRecommendations(true) }, BorderLayout.EAST)
   }, BorderLayout.NORTH)
   recommendationMessage.font = Font(desktopFont, Font.PLAIN, 14)
   recommendationMessage.foreground = Color(181, 176, 160)
   recommendationMessage.putClientProperty("html.disable", true)
   add(recommendationMessage, BorderLayout.SOUTH)
   add(recommendationGrid, BorderLayout.CENTER)
  }
  home.add(recommendationSection)
  val pages = clearPanel(CardLayout())
  pages.add(home, "home")
  pages.add(browser, "results")
  val settings = clearPanel(BorderLayout())
  settings.add(clearPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
   add(button("← 返回") { page = "首页"; refresh() })
   add(label("设置", 22, Color(242, 240, 232), true))
  }, BorderLayout.NORTH)
  settings.add(clearPanel(BorderLayout()).apply {
   add(clearPanel(GridLayout(0, 1, 0, 10)).apply {
    border = BorderFactory.createEmptyBorder(22, 0, 0, 0)
    fun entry(title: String, iconName: String, action: () -> Unit) = button(title, action).apply {
     icon = PlaybackIcon(iconName)
     horizontalAlignment = SwingConstants.LEFT
     iconTextGap = 16
     preferredSize = Dimension(1, 65)
     layout = BorderLayout()
     add(JLabel("›").apply {
      foreground = Color(208, 205, 195)
      font = Font(desktopFont, Font.BOLD, 23)
     }, BorderLayout.EAST)
    }
    add(entry("主题切换", "settings", ::chooseTheme))
    add(entry("语言", "settings", ::chooseLanguage))
   }, BorderLayout.NORTH)
  }, BorderLayout.CENTER)
  pages.add(settings, "settings")
  val account = BiliAccountPanel(panelColor, Color(232, 231, 226), bg, ::button) {
   nickname.text = it.name
   nickname.toolTipText = it.name
   avatar.icon = ProfileAvatar(null)
   (accountFooter.layout as CardLayout).show(accountFooter, "signedIn")
   avatarWorker?.cancel(true)
   avatarWorker = object : SwingWorker<Icon, Unit>() {
    override fun doInBackground(): Icon = ProfileAvatar(loadBiliAvatar(it.avatarUrl))
    override fun done() {
     if (avatarWorker !== this) return
     avatarWorker = null
     runCatching { get() }.onSuccess { avatar.icon = it }
    }
   }.also { worker -> worker.execute() }
   runCatching { loginStore?.save(it) }.onFailure {
    showPlaylistNotice("登录状态未保存", "当前仍可使用，重启后需要重新扫码")
   }
   page = "首页"
   refresh()
   updateSidebar()
   loadRemoteFolders(it)
  }
  accountPanel = account
  pages.add(account, "account")
  mainPages = pages
  names.forEach { playlist.addItem(it) }
  playlist.addActionListener { if (!loading && page == "音乐库") refresh() }
  center.add(pages, BorderLayout.CENTER)
  root.add(center, BorderLayout.CENTER)
  val bottom=RoundedPanel(22);bottom.background=Color(28,28,29);bottom.layout=BorderLayout(22,0);bottom.border=BorderFactory.createEmptyBorder(18,22,18,22);bottom.preferredSize=Dimension(1200,106)
  val now = stack()
  nowPlaying.text = tr("尚未播放音乐")
  nowPlaying.font = Font(desktopFont, Font.BOLD, 15)
  nowPlaying.foreground = Color(238, 235, 223)
  nowPlaying.putClientProperty("html.disable", true)
  nowDetail.font = Font(desktopFont, Font.PLAIN, 12)
  nowDetail.foreground = Color(171, 168, 155)
  now.add(nowPlaying)
  now.add(nowDetail)
  now.preferredSize = Dimension(270, 50)
  playCover.preferredSize = Dimension(60, 60)
  playCover.background = Color(47, 42, 28)
  playCover.foreground = accent
  playCover.font = Font("Dialog", Font.BOLD, 24)
  playCover.isOpaque = true
  bottom.add(clearPanel(BorderLayout(12, 0)).apply {
   preferredSize = Dimension(282, 64)
   add(playCover, BorderLayout.WEST)
   add(now, BorderLayout.CENTER)
  }, BorderLayout.WEST)
  val playback = clearPanel(FlowLayout(FlowLayout.CENTER, 10, 0))
  val mode = playerControl("mode", "顺序播放") {}
  mode.addActionListener { cycleQueueMode() }
  playback.add(mode)
  playback.add(playerControl("previous", "上一首") {
   val i = queue.indexOfFirst { queueKey(it) == current?.let(::queueKey) } - 1
   if (i in queue.indices) play(queue[i])
  })
  val toggle = playerControl("play", "播放") {
   val session = audioSession
   if (session != null) session.togglePause()
   else current?.let { play(it) } ?: run {
    queue.firstOrNull()?.let { play(it) }
   }
  }.apply {
   background = accent
   foreground = bg
   preferredSize = Dimension(58, 42)
  }
  playback.add(toggle)
  playback.add(playerControl("next", "下一首") { next() })
  val volume = playerControl("volume", "音量") {}
  installVolumeControl(volume)
  playback.add(volume)

  val timeline = clearPanel(BorderLayout(10, 0))
  for (label in listOf(elapsedLabel, durationLabel)) {
   label.font = Font(desktopFont, Font.PLAIN, 12)
   label.foreground = Color(192, 188, 175)
   label.preferredSize = Dimension(40, 22)
  }
  styledSlider(timeProgress)
  timeProgress.isEnabled = false
  timeProgress.addChangeListener {
   if (scrubbing || timeProgress.valueIsAdjusting) elapsedLabel.text = timeLabel(timeProgress.value.toLong())
  }
  timeProgress.addMouseListener(object : java.awt.event.MouseAdapter() {
   override fun mousePressed(e: java.awt.event.MouseEvent) {
    if (timeProgress.isEnabled && SwingUtilities.isLeftMouseButton(e)) {
     scrubbing = true
     elapsedLabel.text = timeLabel(timeProgress.value.toLong())
    }
   }
   override fun mouseReleased(e: java.awt.event.MouseEvent) {
    if (!scrubbing) return
    scrubbing = false
    val track = current ?: return
    val offset = timeProgress.value.toLong().coerceAtMost((activeDuration - 1).coerceAtLeast(0).toLong())
    if (offset != audioSession?.seconds || seekPending) {
     play(track, startAt = offset, initiallyPaused = audioSession?.isPaused == true, seeking = true)
    }
   }
  })
  timeline.add(elapsedLabel, BorderLayout.WEST)
  timeline.add(progressCards, BorderLayout.CENTER)
  timeline.add(durationLabel, BorderLayout.EAST)
  bottom.add(clearPanel(BorderLayout(0, 4)).apply {
   add(clearPanel(BorderLayout()).apply {
    add(playback, BorderLayout.NORTH)
    add(collectionStatus, BorderLayout.SOUTH)
   }, BorderLayout.NORTH)
   add(timeline, BorderLayout.SOUTH)
  }, BorderLayout.CENTER)

  val addToFolder = playerControl("heart", "添加到 B 站收藏夹") { current?.let { chooseTrackPlaylist(it) } }
  val partsButton = playerControl("parts", "分 P 队列") {}.apply { isEnabled = false }
  partsButton.addActionListener { showPartQueue(partsButton) }
  val queueButton = playerControl("queue", "播放队列") {}
  queueButton.addActionListener { showQueue(queueButton) }
  bottom.add(clearPanel(FlowLayout(FlowLayout.RIGHT, 8, 12)).apply {
   preferredSize = Dimension(172, 60)
   add(addToFolder)
   add(partsButton)
   add(queueButton)
  }, BorderLayout.EAST)

  var showingPause = false
  val titleTimer = Timer(30) { nowPlaying.advance() }.apply { start() }
  val progressTimer = Timer(180) {
   val session = audioSession
   val running = session != null && !session.isPaused
   val shownMode = partPlaybackMode ?: playbackMode
   mode.icon = PlaybackIcon(modeIcon(shownMode))
   mode.toolTipText = (if (partPlaybackMode != null) "分 P · " else "队列 · ") + modeName(shownMode)
   mode.accessibleContext.accessibleName = mode.toolTipText
   val audio = activeAudio
   val canShowParts = audio?.parts?.size?.let { it > 1 } == true
   partsButton.isEnabled = canShowParts
   partsButton.foreground = if (canShowParts) Color(208, 205, 195) else Color(108, 104, 92)
   if (showingPause != running) {
    showingPause = running
    toggle.icon = PlaybackIcon(if (running) "pause" else "play")
    toggle.toolTipText = if (running) "暂停" else "播放"
    toggle.accessibleContext.accessibleName = toggle.toolTipText
   }
   addToFolder.isEnabled = current != null
   if (session != null && !scrubbing && !seekPending && !timeProgress.valueIsAdjusting) {
    timeProgress.value = session.seconds.toInt().coerceIn(0, timeProgress.maximum)
    elapsedLabel.text = timeLabel(session.seconds)
   }
  }
  progressTimer.start()
  frame.addWindowListener(object : java.awt.event.WindowAdapter() {
   override fun windowClosed(e: java.awt.event.WindowEvent) { progressTimer.stop(); titleTimer.stop(); coverRequest?.cancel(true) }
  })
  root.add(bottom, BorderLayout.SOUTH)
  styleInputs(root);frame.contentPane=root;frame.minimumSize=Dimension(1150,820);frame.setSize(1400,940);frame.setLocationRelativeTo(null);refresh()
  object : SwingWorker<BiliAccount?, Unit>() {
   override fun doInBackground() = loginStore?.restore(biliClient)
   override fun done() {
    if (!frame.isDisplayable || account.signedInAccount != null) return
    runCatching { get() }.onSuccess { restored ->
     if (restored != null) account.restore(restored)
    }
   }
  }.execute()
 }
 fun show(){frame.isVisible=true}
 private fun button(s:String,action:()->Unit):JButton=HoverButton(tr(s)).apply{background=if(s=="播放选中") accent else panelColor;foreground=if(background==accent) bg else Color(208,205,195);font=Font(desktopFont,Font.BOLD,15);addActionListener{action()}}
 private fun styleInputs(c:Container){for(child in c.components){when(child){is SearchField->Unit;is JTextField->{child.background=Color(37,37,38);child.foreground=Color(235,232,222);child.caretColor=accent;child.border=BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,accent),BorderFactory.createEmptyBorder(5,10,5,10))};is JCheckBox->{child.isOpaque=false;child.foreground=Color(210,207,185)};is JComboBox<*>->{child.background=Color(37,37,38);child.foreground=Color(235,232,222)};is JLabel->{if(child.foreground.red<90 && child.foreground.green<90)child.foreground=Color(190,187,165)}};if(child is Container)styleInputs(child)}}
 private fun toggleMaximized() {
  val maximized = frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
  if (!maximized) frame.maximizedBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
  frame.extendedState = if (maximized) Frame.NORMAL else Frame.MAXIMIZED_BOTH
 }

 private fun installWindowDrag(area: JComponent) {
  val mouse = object : java.awt.event.MouseAdapter() {
   var offset: Point? = null
   var pressed: Point? = null
   override fun mousePressed(e: java.awt.event.MouseEvent) {
    if (SwingUtilities.isLeftMouseButton(e)) {
     pressed = e.locationOnScreen
     offset = Point(e.xOnScreen - frame.x, e.yOnScreen - frame.y)
    }
   }
   override fun mouseDragged(e: java.awt.event.MouseEvent) {
    var delta = offset ?: return
    if (frame.extendedState and Frame.MAXIMIZED_BOTH != 0) {
     if ((pressed ?: return).distance(e.locationOnScreen) < 5) return
     val fraction = (delta.x.toDouble() / frame.width).coerceIn(0.0, 1.0)
     frame.extendedState = Frame.NORMAL
     delta = Point((frame.width * fraction).toInt(), 22)
     offset = delta
    }
    frame.setLocation(e.xOnScreen - delta.x, e.yOnScreen - delta.y)
   }
   override fun mouseReleased(e: java.awt.event.MouseEvent) { offset = null }
   override fun mouseClicked(e: java.awt.event.MouseEvent) {
    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) toggleMaximized()
   }
  }
  area.addMouseListener(mouse)
  area.addMouseMotionListener(mouse)
 }

 private fun installSearchHints(field: JTextField, popup: JPopupMenu) {
  var suggestionWorker: SwingWorker<List<String>, Unit>? = null
  popup.isFocusable = false
  popup.background = panelColor
  fun showHints(term: String, suggestions: List<String> = emptyList()) {
   if (!field.isShowing || !field.hasFocus() || term != field.text.trim()) return
   popup.removeAll()
   fun column(title: String, values: List<String>, empty: String, history: Boolean): JPanel {
    val content = JPanel(BorderLayout(0, 10)).apply {
     isOpaque = false
     border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
    }
    val header = JPanel(BorderLayout()).apply {
     isOpaque = false
     add(JLabel(tr(title)).apply {
      font = Font(desktopFont, Font.BOLD, 15)
      foreground = Color(242, 239, 225)
     }, BorderLayout.WEST)
     if (history && searchHistory.isNotEmpty()) add(button("清空") {
      searchHistory = emptyList()
      saveSearchHistory()
      showHints(term, suggestions)
     }.apply {
      isFocusable = false
      foreground = accent
      border = BorderFactory.createEmptyBorder(3, 10, 3, 10)
     }, BorderLayout.EAST)
    }
    content.add(header, BorderLayout.NORTH)
    val list = JPanel(GridLayout(0, 1, 0, 2)).apply { isOpaque = false }
    if (values.isEmpty()) list.add(JLabel(empty).apply { foreground = Color(174, 171, 158) })
    for (query in values.take(8)) {
     list.add(button(query) { field.text = query; field.postActionEvent() }.apply {
      isFocusable = false
      horizontalAlignment = SwingConstants.LEFT
      toolTipText = query
      putClientProperty("html.disable", true)
      border = BorderFactory.createEmptyBorder(7, 8, 7, 8)
     })
    }
    content.add(JPanel(BorderLayout()).apply {
     isOpaque = false
     add(list, BorderLayout.NORTH)
    }, BorderLayout.CENTER)
    return content
   }
   val width = minOf(590, (frame.width - field.locationOnScreen.x + frame.x - 24).coerceAtLeast(380))
   val height = 66 + 36 * maxOf(1, minOf(8, maxOf(searchHistory.size, suggestions.size)))
   popup.add(RoundedPanel(24).apply {
    layout = GridLayout(1, 2, 12, 0)
    background = Color(31, 30, 26)
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    preferredSize = Dimension(width, height)
    add(column("搜索建议", suggestions, if (term.isBlank()) "继续输入以获取联想词" else "暂无建议", false))
    add(column("搜索历史", searchHistory, "暂无搜索历史", true))
   })
   popup.preferredSize = Dimension(width, height)
   if (!popup.isVisible) popup.show(field, -34, field.height + 8)
   else { popup.pack(); popup.repaint() }
  }
  val timer = Timer(300) {
   val term = field.text.trim()
   if (term.isBlank() || !field.hasFocus()) return@Timer
   val task = object : SwingWorker<List<String>, Unit>() {
    override fun doInBackground() = biliClient.suggestions(term)
    override fun done() {
     if (suggestionWorker !== this) return
     suggestionWorker = null
     showHints(term, runCatching { get() }.getOrDefault(emptyList()))
    }
   }
   suggestionWorker = task
   task.execute()
  }.apply { isRepeats = false }
  fun schedule() {
   timer.stop()
   suggestionWorker?.cancel(true)
   suggestionWorker = null
   val term = field.text.trim()
   showHints(term)
   if (term.isNotEmpty()) timer.restart()
  }
  field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
   override fun insertUpdate(e: javax.swing.event.DocumentEvent) = schedule()
   override fun removeUpdate(e: javax.swing.event.DocumentEvent) = schedule()
   override fun changedUpdate(e: javax.swing.event.DocumentEvent) = schedule()
  })
  field.addFocusListener(object : java.awt.event.FocusAdapter() {
   override fun focusGained(e: java.awt.event.FocusEvent) = schedule()
   override fun focusLost(e: java.awt.event.FocusEvent) {
    timer.stop()
    suggestionWorker?.cancel(true)
    suggestionWorker = null
    SwingUtilities.invokeLater { if (!field.hasFocus()) popup.isVisible = false }
   }
  })
  field.addActionListener {
   timer.stop()
   suggestionWorker?.cancel(true)
   suggestionWorker = null
   popup.isVisible = false
  }
 }

 private fun saveSearchHistory() {
  runCatching { save() }.onFailure { showNotice("无法保存搜索历史，请检查音乐库目录") }
 }


 private fun searchInApp(keyword: String) {
  searchHistory = (listOf(keyword) + searchHistory.filterNot { it == keyword }).take(20)
  saveSearchHistory()
  searchTab(keyword, users = false)
 }

 private fun searchTab(keyword: String, users: Boolean) {
  val account = accountPanel?.signedInAccount
  page = "搜索结果"
  refresh()
  val controls = listOf("视频" to { searchTab(keyword, false) }, "用户" to { searchTab(keyword, true) })
  if (users) {
   browser.open("搜索：$keyword", controls, "用户", { biliClient.searchCreators(keyword, it, account) },
    { it.id.toString() }, { creator -> creatorRow(creator) { showCreator(creator, false) { searchTab(keyword, true) } } })
  } else {
   browser.open("搜索：$keyword", controls, "视频", { biliClient.searchVideos(keyword, it, account) },
    BiliVideo::bvid, ::videoRow)
  }
 }

 private fun loadHomeRecommendations(forceRefresh: Boolean = false) {
  recommendationsOpened = true
  recommendationWorker?.cancel(true)
  val account = accountPanel?.signedInAccount
  val cached = if (forceRefresh) emptyList() else rankingCache
  val alreadySaved = members.values.flatten().toSet()
  val previous = previousDiscover
  recommendationMessage.text = ""
  val worker = object : SwingWorker<List<BiliVideo>, Unit>() {
   override fun doInBackground() =
    if (cached.isEmpty()) biliClient.musicRanking(account) else cached

   override fun done() {
    if (recommendationWorker !== this || isCancelled) return
    recommendationWorker = null
    runCatching { get() }.onSuccess { ranked ->
     rankingCache = ranked
     val unsaved = ranked.filter { it.bvid !in alreadySaved }
     homeRecommendations = (unsaved.filter { it.bvid !in previous }.shuffled() +
      unsaved.filter { it.bvid in previous }.shuffled()).take(10)
     previousDiscover = homeRecommendations.map(BiliVideo::bvid).toSet()
     recommendationGrid.removeAll()
     homeRecommendations.forEach { video ->
      knownVideos[video.bvid] = video
      recommendationGrid.add(trackRow(video.asTrack(), video, compact = true))
     }
     recommendationGrid.revalidate()
     recommendationGrid.repaint()
     recommendationMessage.text = if (homeRecommendations.isEmpty()) "暂无可推荐的新歌" else ""
    }.onFailure { recommendationMessage.text = requestError(it) }
   }
  }
  recommendationWorker = worker
  worker.execute()
 }

 private fun showFavoriteCreators() {
  page = "UP主"
  refresh()
  val account = accountPanel?.signedInAccount
  if (account == null) {
   openAccountPage()
   return
  }
  val group = selectedFollowGroup(account)
  val controls = mutableListOf<Pair<String, () -> Unit>>("选择关注分组" to { chooseFollowGroup(account) })
  if (group != null) controls.add("随机播放" to {
   startShuffle {
    val creators = linkedMapOf<Long, BiliCreator>()
    for (index in 1..500) {
     if (Thread.currentThread().isInterrupted) throw InterruptedException()
     val batch = biliClient.followGroupMembers(account, group, index)
     val count = creators.size
     batch.items.forEach { creators[it.id] = it }
     if (!batch.hasMore) break
     check(creators.size > count && index < 500) { "未能完整读取关注分组" }
    }
    creators.values.flatMap { creatorSubmissions(it, account) }.map(BiliVideo::asTrack)
   }
  })
  groupMembers.clear()
  browser.open("", controls, loader = { index ->
   if (group == null) BiliPage(emptyList(), false) else biliClient.followGroupMembers(account, group, index)
  }, key = { it.id.toString() }, renderer = { creator ->
   groupMembers.add(creator.id)
   creatorRow(creator, showHeart = false) { showCreator(creator, false, ::showFavoriteCreators) }
  })
 }

 private fun showCreator(creator: BiliCreator, collections: Boolean, back: () -> Unit) {
  val account = accountPanel?.signedInAccount
  page = "UP主详情"
  refresh()
  val controls = listOf(
   "投稿" to { showCreator(creator, false, back) },
   "合集" to { showCreator(creator, true, back) },
   "随机播放" to {
    startShuffle { signedIn -> creatorSubmissions(creator, signedIn).map(BiliVideo::asTrack) }
   }
  )
  if (collections) {
   browser.open(creator.name, controls, "合集", { biliClient.creatorCollections(creator.id, it, account) },
    { it.id.toString() }, { collection ->
     collectionRow(collection) { showCreator(creator, true, back) }
    })
  } else {
   browser.open(creator.name, controls + ("全部加入队列" to { addAllCreatorSubmissions(creator) }), "投稿", { biliClient.creatorVideos(creator.id, it, account) },
    BiliVideo::bvid, ::videoRow)
  }
  val favorite = playerControl("heart", "关注或加入分组") {}
  fun updateHeart() {
   val liked = creator.id in groupMembers
   favorite.icon = PlaybackIcon(if (liked) "heart-filled" else "heart")
   favorite.foreground = if (liked) accent else Color(208, 205, 195)
   favorite.toolTipText = if (liked) "取消关注 UP 主" else "关注或加入分组"
  }
  updateHeart()
  favorite.addActionListener { toggleCreator(creator, ::updateHeart) }
  browser.showProfile(creator.fans, back, favorite) {
   ProfileAvatar(loadBiliAvatar(if (creator.avatar.startsWith("//")) "https:${creator.avatar}" else creator.avatar))
  }
 }

 private fun addAllCreatorSubmissions(creator: BiliCreator) {
  if (creatorQueueWorker != null) return
  val account = accountPanel?.signedInAccount
  browser.setActionState("全部加入队列", "正在读取投稿…", false)
  val worker = object : SwingWorker<List<Track>, Unit>() {
   override fun doInBackground() = creatorSubmissions(creator, account).flatMap {
    if (isCancelled) throw InterruptedException()
    expandQueueTrack(it.asTrack(), account)
   }

   override fun done() {
    if (creatorQueueWorker !== this || isCancelled) return
    creatorQueueWorker = null
    browser.setActionState("全部加入队列", "全部加入队列", true)
    if (!frame.isDisplayable || accountPanel?.signedInAccount !== account) return
    runCatching { get() }.onSuccess { submissions ->
     val existing = queue.map(::queueKey).toMutableSet()
     val additions = submissions.filter { existing.add(queueKey(it)) }
     queue = queue + additions
     val status = if (additions.isEmpty()) "没有新的投稿可加入" else "已加入 ${additions.size} 项"
     browser.setActionState("全部加入队列", status, true)
    }.onFailure {
     showPlaylistNotice("读取投稿失败", requestError(it))
    }
   }
  }
  creatorQueueWorker = worker
  worker.execute()
 }

 private fun creatorSubmissions(creator: BiliCreator, account: BiliAccount?): List<BiliVideo> {
  val gathered = linkedMapOf<String, BiliVideo>()
  for (page in 1..1000) {
   if (Thread.currentThread().isInterrupted) throw InterruptedException()
   val batch = biliClient.creatorVideos(creator.id, page, account)
   val count = gathered.size
   batch.items.forEach { gathered.putIfAbsent(it.bvid, it) }
   if (!batch.hasMore) return gathered.values.toList()
   check(gathered.size > count) { "未能完整读取 UP 主投稿，请重试" }
  }
  error("未能完整读取 UP 主投稿，请重试")
 }

 private fun cancelShuffle() {
  shuffleWorker?.cancel(true)
  shuffleWorker = null
  if (collectionShufflePending) {
   collectionShufflePending = false
   reportCollectionStatus("")
  }
 }

 private fun shuffleTracks(tracks: List<Track>) = startShuffle { tracks }

 private fun startShuffle(collection: Boolean = false, source: (BiliAccount?) -> List<Track>) {
  cancelShuffle()
  collectionShufflePending = collection
  if (collection) reportCollectionStatus("正在读取合集，准备随机播放…", pending = true)
  val account = accountPanel?.signedInAccount
  val worker = object : SwingWorker<List<Track>, Unit>() {
   override fun doInBackground(): List<Track> {
    val candidates = source(account).distinctBy(Track::id)
    if (collection) return candidates.distinctBy(::queueKey).shuffled()
    val songs = candidates.flatMap { track ->
     if (isCancelled) throw InterruptedException()
     try {
      expandQueueTrack(track, account)
     } catch (restricted: BiliRequestRestricted) {
      throw restricted
     } catch (interrupted: InterruptedException) {
      throw interrupted
     } catch (_: Exception) {
      listOf(track)
     }
    }
    return songs.distinctBy(::queueKey).shuffled()
   }

   override fun done() {
    if (shuffleWorker !== this || isCancelled) return
    shuffleWorker = null
    collectionShufflePending = false
    if (collection) reportCollectionStatus("")
    if (!frame.isDisplayable || accountPanel?.signedInAccount !== account) return
    runCatching { get() }.onSuccess { songs ->
     val first = songs.firstOrNull()
     if (first == null) {
      if (collection) reportCollectionStatus("合集没有可播放的歌曲")
      else showPlaylistNotice("随机播放", "这里还没有可播放的音乐")
      return@onSuccess
     }
     queue = songs
     deferredCollectionParts.clear()
     if (collection) deferredCollectionParts.addAll(songs.map(Track::id))
     visitedQueue.clear()
     playbackMode = 2
     partPlaybackMode = null
     play(first)
    }.onFailure {
     if (collection) reportCollectionStatus("读取合集列表失败 · ${requestError(it)}")
     else showPlaylistNotice("无法开始随机播放", requestError(it))
    }
   }
  }
  shuffleWorker = worker
  worker.execute()
 }

 private fun showLibraryCollections() {
  page = "我的合集"
  refresh()
  val saved = libraryCollections()
  browser.open("合集", listOf("导入收藏的合集" to ::importCollections),
   loader = { BiliPage(saved, false) }, key = { it.id.toString() },
   renderer = { collectionRow(it, ::showLibraryCollections) })
 }

 private fun collectionRow(collection: BiliCollection, back: () -> Unit): BrowseRow =
  browseRow(collection.name, "${collection.count} 个视频", collection.cover, "查看合集") {
   showCollection(collection, back)
  }.apply {
   val popup = roundedPopup().apply {
    add(popupItem("随机播放") { shuffleCollection(collection) })
    add(popupItem("全部加入队列") { enqueueCollection(collection) })
    add(popupItem(collectionFavoriteLabel(collection)) { toggleCollectionFavorite(collection, back) })
   }
   fun attach(component: Component) {
    if (component is JComponent) component.componentPopupMenu = popup
    if (component is Container) component.components.forEach(::attach)
   }
   attach(this)
   installHover()
  }

 private fun collectionFavoriteLabel(collection: BiliCollection) =
  if (libraryCollections().any { it.id == collection.id }) "取消收藏合集" else "收藏合集"

 private fun showCollection(collection: BiliCollection, back: () -> Unit) {
  val account = accountPanel?.signedInAccount
  page = "合集详情"
  refresh()
  browser.open(collection.name, listOf(
   "返回" to back,
   "随机播放" to { shuffleCollection(collection) },
   "全部加入队列" to { enqueueCollection(collection) },
   collectionFavoriteLabel(collection) to { toggleCollectionFavorite(collection) { showCollection(collection, back) } }
  ), loader = {
   biliClient.collectionVideos(collection.owner, collection.id, it, account)
  }, key = BiliVideo::bvid, renderer = ::videoRow)
 }

 private fun allCollectionTracks(collection: BiliCollection, account: BiliAccount?): List<Track> {
  val tracks = linkedMapOf<String, Track>()
  for (index in 1..500) {
   if (Thread.currentThread().isInterrupted) throw InterruptedException()
   val batch = biliClient.collectionVideos(collection.owner, collection.id, index, account)
   val count = tracks.size
   batch.items.forEach { tracks.putIfAbsent(it.bvid, it.asTrack()) }
   if (!batch.hasMore) return tracks.values.toList()
   check(tracks.size > count) { "合集分页返回重复内容" }
  }
  error("未能完整读取合集")
 }

 private fun shuffleCollection(collection: BiliCollection) {
  if (collectionQueueWorker != null || collectionShufflePending) return
  startShuffle(collection = true) { account -> allCollectionTracks(collection, account) }
 }

 private var collectionQueueWorker: SwingWorker<List<Track>, Unit>? = null

 private fun enqueueCollection(collection: BiliCollection) {
  if (collectionQueueWorker != null || collectionShufflePending) return
  reportCollectionStatus("正在读取合集，准备加入队列…", pending = true)
  val account = accountPanel?.signedInAccount
  collectionQueueWorker = object : SwingWorker<List<Track>, Unit>() {
   override fun doInBackground() = allCollectionTracks(collection, account).flatMap {
    if (isCancelled) throw InterruptedException()
    expandQueueTrack(it, account)
   }
   override fun done() {
    if (collectionQueueWorker !== this) return
    collectionQueueWorker = null
    if (!frame.isDisplayable || isCancelled || accountPanel?.signedInAccount !== account) {
     reportCollectionStatus("")
     return
    }
    runCatching { get() }.onSuccess { additions ->
     val existing = queue.map(::queueKey).toMutableSet()
     val added = additions.filter { existing.add(queueKey(it)) }
     queue = queue + added
     reportCollectionStatus(when {
      additions.isEmpty() -> "合集没有可加入的歌曲"
      added.isEmpty() -> "这些歌曲已在播放队列中"
      else -> "已加入 ${added.size} 首到播放队列"
     })
    }.onFailure { reportCollectionStatus("合集入队失败 · ${requestError(it)}") }
   }
  }.also { it.execute() }
 }

 private fun saveCollections(account: BiliAccount, collections: List<BiliCollection>, after: () -> Unit) {
  val key = account.id.toString()
  val old = savedCollections.put(key, collections.distinctBy(BiliCollection::id))
  runCatching { save() }.onSuccess { updateSidebar(); after() }.onFailure {
   if (old == null) savedCollections.remove(key) else savedCollections[key] = old
   showPlaylistNotice("合集保存失败", "无法写入本机歌单设置")
  }
 }

 private fun toggleCollectionFavorite(collection: BiliCollection, after: () -> Unit) {
  val account = accountPanel?.signedInAccount ?: run { openAccountPage(); return }
  val existing = libraryCollections()
  val updated = if (existing.any { it.id == collection.id }) existing.filterNot { it.id == collection.id }
   else existing + collection
  saveCollections(account, updated, after)
 }

 private fun importCollections() {
  val account = accountPanel?.signedInAccount ?: run { openAccountPage(); return }
  fetchFolderData(account, { biliClient.collectedCollections(account) }) { collected ->
   val available = collected.filter { item -> libraryCollections().none { it.id == item.id } }
   val chosen = pickBiliFolders("导入收藏的合集", "导入",
    available.map { BiliFolder(it.id, it.name) }, multiple = true).map(BiliFolder::id).toSet()
   if (chosen.isNotEmpty()) saveCollections(account, libraryCollections() + available.filter { it.id in chosen }, ::showLibraryCollections)
  }
 }

 private fun videoRow(video: BiliVideo): JComponent {
  knownVideos[video.bvid] = video
  return trackRow(video.asTrack(), video)
 }

 private fun trackRow(track: Track, video: BiliVideo?, compact: Boolean = false): JComponent {
  val metadata = listOfNotNull(
   video?.author?.takeIf(String::isNotBlank) ?: track.creator.takeIf(String::isNotBlank),
   video?.views?.let { "播放 ${formatViews(it)}" },
   video?.duration?.let(::formatDuration)?.let { "时长 $it" },
   video?.published?.let { "发布 ${formatPublished(it)}" }
  )
  val row = browseRow(track.title, metadata.joinToString("  ·  "), video?.cover.orEmpty(), "播放", compact) {
   deferredCollectionParts.clear()
   val rows = if (page == "首页") homeRecommendations else browser.items
   queue = rows.mapNotNull { item ->
    when (item) {
     is BiliVideo -> item.asTrack()
     is Track -> item
     else -> null
    }
   }
   play(track)
  }
  val actions = JPanel(if (compact) GridLayout(1, 2, 3, 0) else GridLayout(2, 1, 0, 4)).apply { isOpaque = false }
  val folderAction = compactButton("", "添加到 B 站收藏夹") { chooseTrackPlaylist(track) }.apply {
   icon = PlaybackIcon("heart")
  }
  val more = compactButton("···", "更多操作") {}
  fun menu() = roundedPopup().apply {
   add(popupItem("下一首播放") { enqueueNext(track) })
   add(popupItem("加入队列") { enqueueTrack(track) })
   add(popupItem("添加到歌单") { chooseTrackPlaylist(track) })
   if (page == "音乐库") {
    val folder = remoteFolders[playlist.selectedItem]
    val account = accountPanel?.signedInAccount
    if (folder != null && account != null) {
     add(popupItem("从此收藏夹删除") { changeFolderTrack(account, folder, track, remove = true) })
    }
   }
  }
  more.addActionListener {
   val popup = menu()
   popup.show(more, more.width - popup.preferredSize.width, more.height)
  }
  val rightClick = object : java.awt.event.MouseAdapter() {
   private var opened = false
   override fun mousePressed(e: java.awt.event.MouseEvent) {
    opened = false
    if (e.isPopupTrigger) open(e)
   }
   override fun mouseReleased(e: java.awt.event.MouseEvent) {
    if (!opened && (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e))) open(e)
   }
   private fun open(e: java.awt.event.MouseEvent) {
    opened = true
    menu().show(e.component, e.x, e.y)
   }
  }
  actions.add(folderAction)
  actions.add(more)
  row.add(actions, BorderLayout.EAST)
  fun addContextMenu(component: Component) {
   component.addMouseListener(rightClick)
   if (component is Container) component.components.forEach(::addContextMenu)
  }
  addContextMenu(row)
  row.installHover()
  return row
 }

 private fun creatorRow(creator: BiliCreator, showHeart: Boolean = true, open: () -> Unit): JComponent {
  val row = browseRow(creator.name, if (creator.fans < 0) "" else "${creator.fans} 粉丝", creator.avatar, "查看主页", action = open)
  if (showHeart) {
   val heart = playerControl("heart", "关注或加入分组") {}
   fun updateHeart() {
    val liked = creator.id in groupMembers
    heart.icon = PlaybackIcon(if (liked) "heart-filled" else "heart")
    heart.foreground = if (liked) accent else Color(208, 205, 195)
    heart.toolTipText = if (liked) "取消关注 UP 主" else "关注或加入分组"
   }
   updateHeart()
   heart.addActionListener { toggleCreator(creator, ::updateHeart) }
   row.add(JPanel(GridBagLayout()).apply { isOpaque = false; add(heart) }, BorderLayout.EAST)
  }
  row.installHover()
  return row
 }

 private fun compactButton(symbol: String, description: String, action: () -> Unit) =
  button(symbol, action).apply {
   toolTipText = tr(description)
   accessibleContext.accessibleName = tr(description)
   preferredSize = Dimension(42, 36)
   border = BorderFactory.createEmptyBorder()
  }

 private fun browseRow(title: String, detail: String, coverUrl: String, actionName: String, compact: Boolean = false, action: () -> Unit): BrowseRow {
  val row = BrowseRow()
  val coverWidth = if (compact) 86 else 112
  val coverHeight = if (compact) 54 else 72
  if (compact) row.border = BorderFactory.createEmptyBorder(5, 7, 5, 7)
  val cover = button("", action).apply {
   icon = PlaybackIcon(if (actionName == "播放") "music" else "play")
   foreground = accent
   background = Color(42, 39, 32)
   preferredSize = Dimension(coverWidth, coverHeight)
   border = BorderFactory.createEmptyBorder()
   toolTipText = actionName
   accessibleContext.accessibleName = actionName
  }
  if (coverUrl.isNotBlank()) {
   val worker = object : SwingWorker<Icon?, Unit>() {
    override fun doInBackground(): Icon? = loadBiliAvatar(if (coverUrl.startsWith("//")) "https:$coverUrl" else coverUrl)
     ?.getScaledInstance(coverWidth - 7, coverHeight - 4, Image.SCALE_SMOOTH)?.let(::ImageIcon)
    override fun done() {
     if (!cover.isDisplayable) return
     val image = runCatching { get() }.getOrNull() ?: return
     cover.icon = image
     cover.text = ""
    }
   }
   thumbnailExecutor.execute(worker)
  }
  val titleLabel = ScrollingTitle(title).apply {
   toolTipText = title
   val preferred = Font(desktopFont, Font.BOLD, if (compact) 15 else 16)
   font = if (preferred.canDisplayUpTo(title) < 0) preferred else Font(Font.DIALOG, preferred.style, preferred.size)
   foreground = Color(241, 238, 226)
  }
  row.onHoverChanged = titleLabel::setScrollHovered
  val text = JPanel(GridLayout(2, 1, 0, 4)).apply {
   isOpaque = false
   add(titleLabel)
   add(JLabel(detail).apply {
    putClientProperty("html.disable", true)
    font = Font(desktopFont, Font.PLAIN, 12)
    foreground = Color(180, 176, 160)
   })
  }
  row.add(cover, BorderLayout.WEST)
  row.add(text, BorderLayout.CENTER)
  val click = object : java.awt.event.MouseAdapter() {
   override fun mouseClicked(e: java.awt.event.MouseEvent) {
    if (e.clickCount == 1 && SwingUtilities.isLeftMouseButton(e)) action()
   }
  }
  row.addMouseListener(click)
  text.addMouseListener(click)
  text.components.forEach { it.addMouseListener(click) }
  return row
 }

 private fun selectedFollowGroup(account: BiliAccount): Long? =
  followGroups.getProperty(account.id.toString())?.toLongOrNull()

 private fun chooseFollowGroup(account: BiliAccount, after: () -> Unit = ::showFavoriteCreators) {
  fetchFolderData(account, { biliClient.followGroups(account) }) { groups ->
   if (groups.isEmpty()) {
    showPlaylistNotice("选择关注分组", "请先在 B 站新建一个关注分组，再回来选择。")
    return@fetchFolderData
   }
   val group = pickBiliFolders("选择关注分组", "使用此分组", groups).firstOrNull() ?: return@fetchFolderData
   val key = account.id.toString()
   val old = followGroups.setProperty(key, group.id.toString())
   runCatching { save() }.onSuccess {
    groupMembers.clear()
    after()
   }.onFailure {
    if (old == null) followGroups.remove(key) else followGroups.setProperty(key, old.toString())
    showPlaylistNotice("关注分组未保存", "无法保存本机设置")
   }
  }
 }

 private fun toggleCreator(creator: BiliCreator, after: () -> Unit) {
  val account = accountPanel?.signedInAccount ?: run { openAccountPage(); return }
  val group = selectedFollowGroup(account)
  if (group == null) {
   chooseFollowGroup(account) { toggleCreator(creator, after) }
   return
  }
  fetchFolderData(account, { biliClient.followState(account, creator.id) }) { (following, groups) ->
   if (following && group in groups) {
    showPlayerDialog("取消关注", JLabel("确定在 B 站取消关注「${creator.name}」吗？"), closable = true) { dialog ->
     listOf(button("取消") { dialog.dispose() }, button("取消关注") {
      dialog.dispose()
      fetchFolderData(account, { biliClient.setFollow(account, creator.id, false) }) {
       groupMembers.remove(creator.id)
       after()
      }
     })
    }
   } else {
    fetchFolderData(account, {
     if (!following) biliClient.setFollow(account, creator.id, true)
     biliClient.copyFollowToGroup(account, creator.id, group)
    }) {
     groupMembers.add(creator.id)
     after()
    }
   }
  }
 }

 private fun expandQueueTrack(track: Track, account: BiliAccount?): List<Track> {
  if ("#p=" in track.id) return listOf(track)
  val id = BilibiliLinks.parseVideoId(track.id) ?: return listOf(track)
  val parts = biliClient.parts(id, account)
  return if (parts.size > 1) parts.map { partTrack(track, it) } else listOf(track)
 }

 private fun enqueueNext(track: Track) = enqueueTrack(track, next = true)

 private fun enqueueTrack(track: Track, next: Boolean = false) {
  val account = accountPanel?.signedInAccount
  object : SwingWorker<List<Track>, Unit>() {
   override fun doInBackground() = expandQueueTrack(track, account)
   override fun done() {
    if (!frame.isDisplayable || accountPanel?.signedInAccount !== account) return
    runCatching { get() }.onSuccess { additions ->
     val index = if (next) (queue.indexOfFirst { queueKey(it) == current?.let(::queueKey) } + 1).coerceIn(0, queue.size) else queue.size
     queue = queue.take(index) + additions + queue.drop(index)
    }.onFailure { showPlaylistNotice("加入队列失败", requestError(it)) }
   }
  }.execute()
 }

 private fun requestError(error: Throwable): String {
  val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
  return when (cause) {
   is IllegalStateException -> cause.message ?: "B 站请求失败"
   is javax.sound.sampled.LineUnavailableException -> "无法打开音频输出，请检查系统播放设备"
   else -> "无法读取 B 站数据，请检查网络后重试"
  }
 }

 private fun choosePlaylistAction() {
  val account = accountPanel?.signedInAccount ?: run { openAccountPage(); return }
  var action = ""
  showPlayerDialog("B 站收藏夹", JLabel("选择歌单来源"), closable = true) { dialog ->
   listOf(button("导入 B 站收藏夹") { action = "import"; dialog.dispose() },
    button("导入收藏的合集") { action = "collections"; dialog.dispose() },
    button("新建 B 站收藏夹") { action = "create"; dialog.dispose() }.apply { background = accent; foreground = bg })
  }
  when (action) {
   "collections" -> importCollections()
   "import" -> fetchFolderData(account, { biliClient.folders(account) }) { folders ->
    val available = folders.filter { it.id !in selectedFolderIds(account) }
    val chosen = pickBiliFolders("导入 B 站收藏夹", "导入", available, multiple = true)
    if (chosen.isNotEmpty()) saveFolderSelection(account, selectedFolderIds(account) + chosen.map(BiliFolder::id), folders)
   }
   "create" -> editFolderName("新建 B 站收藏夹", "") { title ->
    fetchFolderData(account, {
     val id = biliClient.createFolder(account, title)
     id to biliClient.folders(account)
    }) { (id, folders) -> saveFolderSelection(account, selectedFolderIds(account) + id, folders) }
   }
  }
 }

 private fun editFolderName(title: String, current: String, submit: (String) -> Unit) {
  val input = JTextField(current, 24).apply {
   font = Font(desktopFont, Font.PLAIN, 15)
   foreground = Color(242, 240, 232)
   background = Color(42, 39, 31)
   caretColor = accent
   border = BorderFactory.createEmptyBorder(9, 12, 9, 12)
   accessibleContext.accessibleName = "歌单名称"
  }
  showPlayerDialog(title, input) { dialog ->
   fun commit() {
    val value = input.text.trim()
    if (value.isBlank()) return
    dialog.dispose()
    if (value != current) submit(value)
   }
   input.addActionListener { commit() }
   listOf(button("取消") { dialog.dispose() },
    button("保存") { commit() }.apply { background = accent; foreground = bg })
  }
 }

 private fun showPlayerDialog(title: String, body: JComponent, closable: Boolean = false, actions: (JDialog) -> List<JButton>) {
  val dialog = JDialog(frame, title, Dialog.ModalityType.APPLICATION_MODAL)
  dialog.isUndecorated = true
  dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
  dialog.background = panelColor
  dialog.contentPane = RoundedPanel(24).apply {
   background = panelColor
   layout = BorderLayout(0, 15)
   border = BorderFactory.createEmptyBorder(20, 22, 16, 22)
   add(JPanel(BorderLayout(12, 0)).apply {
    isOpaque = false
    add(JLabel(tr(title)).apply {
     font = Font(desktopFont, Font.BOLD, 19)
     foreground = Color(242, 240, 232)
    }, BorderLayout.CENTER)
    if (closable) add(compactButton("×", "取消并关闭") { dialog.dispose() }.apply {
     font = font.deriveFont(22f)
    }, BorderLayout.EAST)
   }, BorderLayout.NORTH)
   add(body, BorderLayout.CENTER)
   add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
    isOpaque = false
    actions(dialog).forEach { add(it) }
   }, BorderLayout.SOUTH)
  }
  dialog.rootPane.registerKeyboardAction({ dialog.dispose() },
   KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
  dialog.pack()
  if (dialog.graphicsConfiguration.device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
   dialog.shape = java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, dialog.width.toDouble(), dialog.height.toDouble(), 24.0, 24.0)
  }
  dialog.setLocationRelativeTo(frame)
  dialog.isVisible = true
 }

 private fun showPlaylistNotice(title: String, text: String) {
  showPlayerDialog(title, JLabel(tr(text)).apply { foreground = Color(218, 214, 196) }) { dialog ->
   listOf(button("关闭") { dialog.dispose() })
  }
 }

 private fun selectedFolderIds(account: BiliAccount): Set<Long> =
  selectedFolders.getProperty(account.id.toString(), "").split(',').mapNotNull(String::toLongOrNull).toSet()

 private fun saveFolderSelection(account: BiliAccount, ids: Set<Long>, folders: List<BiliFolder>) {
  val key = account.id.toString()
  val old = selectedFolders.getProperty(key, "")
  selectedFolders.setProperty(key, ids.joinToString(","))
  runCatching { save() }.onSuccess { applyFolders(folders) }.onFailure {
   selectedFolders.setProperty(key, old)
   showPlaylistNotice("歌单设置未保存", "无法保存侧栏设置")
  }
 }

 private fun loadRemoteFolders(account: BiliAccount) {
  if (selectedFolderIds(account).isNotEmpty()) {
   fetchFolderData(account, { biliClient.folders(account) }, ::applyFolders)
  }
 }

 private fun applyFolders(folders: List<BiliFolder>) {
  val selected = remoteFolders[playlist.selectedItem]?.id
  val previous = remoteFolders.mapValues { (name, _) -> members[name].orEmpty() }
  val previousNames = remoteFolders.entries.associate { it.value.id to it.key }
  val ids = accountPanel?.signedInAccount?.let(::selectedFolderIds).orEmpty()
  remoteFolders.clear()
  folders.filter { it.id in ids }.forEach { folder ->
   val base = folder.title
   var name = base
   var suffix = 2
   while (name in remoteFolders) name = "$base (${suffix++})"
   remoteFolders[name] = folder
  }
  members.clear()
  remoteFolders.forEach { (name, folder) ->
   previous[previousNames[folder.id]]?.let { members[name] = it }
  }
  names = remoteFolders.keys.toMutableList()
  loading = true
  playlist.removeAllItems()
  names.forEach(playlist::addItem)
  playlist.selectedItem = remoteFolders.entries.firstOrNull { it.value.id == selected }?.key ?: names.firstOrNull()
  loading = false
  updateSidebar()
  if (page == "音乐库" && selected !in remoteFolders.values.map { it.id }) page = "首页"
  refresh()
 }

 private fun cacheFolder(name: String, songs: List<BiliVideo>) {
  songs.forEach { knownVideos[it.bvid] = it }
  val entries = songs.map(BiliVideo::asTrack)
  tracks = (entries + tracks).distinctBy(Track::id)
  members[name] = entries.map(Track::id).toSet()
 }

 private fun openFolder(name: String) {
  val account = accountPanel?.signedInAccount ?: return
  val folder = remoteFolders[name] ?: return
  loading = true
  playlist.selectedItem = name
  loading = false
  page = "音乐库"
  refresh()
  if (folderRequest != null) {
   pendingFolder = name
   return
  }
  fetchFolderData(account, { biliClient.videos(account, folder) }) { songs ->
   cacheFolder(name, songs)
   if (page == "音乐库" && playlist.selectedItem == name) refresh()
  }
 }

 private fun renameRemoteFolder(folder: BiliFolder) {
  val account = accountPanel?.signedInAccount ?: return
  editFolderName("重命名 B 站收藏夹", folder.title) { name ->
   fetchFolderData(account, { biliClient.renameFolder(account, folder, name); biliClient.folders(account) }, ::applyFolders)
  }
 }

 private fun hideRemoteFolder(folder: BiliFolder) {
  val account = accountPanel?.signedInAccount ?: return
  saveFolderSelection(account, selectedFolderIds(account) - folder.id, remoteFolders.values.toList())
 }

 private fun deleteRemoteFolder(folder: BiliFolder) {
  val account = accountPanel?.signedInAccount ?: return
  showPlayerDialog("删除 B 站收藏夹", JLabel("确定在 B 站删除「${folder.title}」吗？")) { dialog ->
   listOf(button("取消") { dialog.dispose() }, button("删除") {
    dialog.dispose()
    fetchFolderData(account, { biliClient.deleteFolder(account, folder); biliClient.folders(account) }, ::applyFolders)
   }.apply { background = accent; foreground = bg })
  }
 }

 private var pendingFolder: String? = null
 private var folderRequest: SwingWorker<*, Unit>? = null

 private fun <T> fetchFolderData(account: BiliAccount, request: () -> T, consume: (T) -> Unit) {
  if (folderRequest != null) { showPlaylistNotice("请稍候", "正在处理 B 站收藏夹"); return }
  val task = object : SwingWorker<T, Unit>() {
   override fun doInBackground() = request()
   override fun done() {
    if (folderRequest !== this) return
    folderRequest = null
    if (!frame.isDisplayable || accountPanel?.signedInAccount !== account) return
    runCatching { get() }.onSuccess(consume).onFailure { showPlaylistNotice("B 站请求失败", requestError(it)) }
    if (folderRequest == null) {
     val pending = pendingFolder
     pendingFolder = null
     if (pending != null && page == "音乐库" && playlist.selectedItem == pending) openFolder(pending)
    }
   }
  }
  folderRequest = task
  task.execute()
 }

 private fun chooseTrackPlaylist(track: Track) {
  val account = accountPanel?.signedInAccount ?: run { openAccountPage(); return }
  fun choose(folders: List<BiliFolder>) {
   val folder = pickBiliFolders("添加到 B 站收藏夹", "添加", folders).firstOrNull() ?: return
   changeFolderTrack(account, folder, track, remove = false)
  }
  choose(remoteFolders.values.toList())
 }

 private fun pickBiliFolders(
  title: String, action: String, folders: List<BiliFolder>, multiple: Boolean = false
 ): List<BiliFolder> {
  if (folders.isEmpty()) {
   showPlayerDialog(title, JLabel("没有可选择的 B 站收藏夹"), closable = true) { emptyList() }
   return emptyList()
  }
  val rows = RoundedPanel(18).apply {
   background = panelColor
   layout = BoxLayout(this, BoxLayout.Y_AXIS)
   border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
  }
  val choices = folders.mapIndexed { index, folder ->
   val row = RoundedPanel(15).apply {
    background = panelColor
    layout = BorderLayout()
    maximumSize = Dimension(Int.MAX_VALUE, 57)
    preferredSize = Dimension(400, 57)
   }
   val choice = JCheckBox(folder.title, PlaybackIcon("folder")).apply {
    isOpaque = false
    putClientProperty("html.disable", true)
    selectedIcon = PlaybackIcon("folder")
    foreground = Color(232, 229, 217)
    font = Font(desktopFont, Font.BOLD, 16)
    iconTextGap = 12
    border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
   }
   choice.isFocusPainted = false
   choice.addItemListener {
    row.background = if (choice.isSelected) tintedSurface(.16) else panelColor
    choice.foreground = if (choice.isSelected) accent else Color(232, 229, 217)
    row.repaint()
   }
   row.add(choice, BorderLayout.CENTER)
   rows.add(row)
   if (index < folders.lastIndex) rows.add(JPanel().apply {
    background = Color(62, 58, 48)
    maximumSize = Dimension(Int.MAX_VALUE, 1)
    preferredSize = Dimension(1, 1)
   })
   choice to folder
  }
  if (!multiple) choices.forEach { (choice, _) ->
   choice.addItemListener {
    if (choice.isSelected) choices.forEach { (other, _) -> if (other !== choice) other.isSelected = false }
   }
  }
  val list = themeScroll(JScrollPane(rows)).apply {
   horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
   preferredSize = Dimension(430, (folders.size * 58 + 10).coerceAtMost(348))
  }
  var selected = emptyList<BiliFolder>()
  showPlayerDialog(title, list, closable = true) { dialog ->
   val confirm = button(action) {
    selected = choices.filter { it.first.isSelected }.map { it.second }
    dialog.dispose()
   }.apply { background = accent; foreground = bg; isEnabled = false }
   choices.forEach { (choice, _) ->
    choice.addItemListener { confirm.isEnabled = choices.any { it.first.isSelected } }
   }
   listOf(button("取消") { dialog.dispose() }, confirm)
  }
  return selected
 }

 private fun confirmSignOut(): Boolean {
  val dialog = JDialog(frame, "退出登录", Dialog.ModalityType.APPLICATION_MODAL)
  dialog.isUndecorated = true
  dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
  var confirmed = false
  val content = RoundedPanel(22).apply {
   layout = BorderLayout(0, 20)
   background = panelColor
   border = BorderFactory.createEmptyBorder(22, 24, 18, 24)
   add(JLabel("确定退出 B 站账号吗？").apply {
    foreground = Color(232, 231, 226)
    font = Font(desktopFont, Font.BOLD, 17)
   }, BorderLayout.NORTH)
  }
  val cancel = button("取消") { dialog.dispose() }
  val confirm = button("退出") {
   confirmed = true
   dialog.dispose()
  }.apply {
   background = accent
   foreground = bg
  }
  content.add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
   isOpaque = false
   add(cancel)
   add(confirm)
  }, BorderLayout.SOUTH)
  dialog.contentPane = content
  dialog.rootPane.defaultButton = cancel
  dialog.rootPane.registerKeyboardAction(
   { dialog.dispose() }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
   JComponent.WHEN_IN_FOCUSED_WINDOW
  )
  dialog.pack()
  if (dialog.graphicsConfiguration.device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
   dialog.shape = java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, dialog.width.toDouble(), dialog.height.toDouble(), 22.0, 22.0)
  }
  dialog.setLocationRelativeTo(frame)
  dialog.isVisible = true
  return confirmed
 }

 private fun showNotice(message: String) { showPlaylistNotice("提示", message) }
 private fun openAccountPage() {
  if (accountPanel?.accountName != null) {
   return
  }
  page = "B站账户"
  refresh()
  accountPanel?.enter()
 }

 private fun refresh(){
  if (page != "B站账户") accountPanel?.leave()
  if (page !in browsePages) browser.stop()
  mainPages?.let{(it.layout as CardLayout).show(it,when (page) {
   "首页" -> "home"
   in browsePages -> "results"
   "B站账户" -> "account"
   "设置" -> "settings"
   else -> "home"
  })}
  if (page == "首页" && !recommendationsOpened) loadHomeRecommendations()
  navButtons.forEach{(name,btn)->btn.background=if(name==page) accent else panelColor;btn.foreground=if(name==page) bg else Color(208,205,195)}
  val visible=when(page){"音乐库"->tracks.filter{it.id in members[playlist.selectedItem.toString()].orEmpty()};"最近播放"->recent.mapNotNull{id->tracks.find{it.id==id}};else->emptyList()}
  if (page in listOf("音乐库", "最近播放")) {
   val controls = if (page == "音乐库") listOf("随机播放" to { shuffleTracks(visible) }) else emptyList()
   val selected = playlist.selectedItem?.toString().orEmpty()
   val title = if (page == "音乐库") selected else page
   browser.open(title, controls, loader = { BiliPage(visible, false) }, key = Track::id,
    renderer = { track -> trackRow(track, knownVideos[track.id.substringBefore("#p=")]) })
  }
 }
 private fun changeFolderTrack(account: BiliAccount, folder: BiliFolder, track: Track, remove: Boolean) {
  val id = track.id.substringBefore("#p=")
  fetchFolderData(account, {
   if (remove) biliClient.remove(account, folder, id) else biliClient.add(account, folder, id)
   biliClient.videos(account, folder)
  }) { songs ->
   remoteFolders.entries.firstOrNull { it.value.id == folder.id }?.let { cacheFolder(it.key, songs) }
   if (page == "音乐库") refresh()
   if (page == "首页") loadHomeRecommendations()
  }
 }

 private fun showAudioLoading(loading: Boolean) {
  audioLoading.foreground = accent
  audioLoading.background = panelColor
  audioLoading.animate(loading)
  (progressCards.layout as CardLayout).show(progressCards, if (loading) "loading" else "progress")
 }

 private fun stopAudio() {
  showAudioLoading(false)
  playbackWorker?.cancel(true)
  playbackWorker = null
  audioSession?.cancel()
  audioSession = null
 }

 private fun play(t: Track, startAt: Long = 0, initiallyPaused: Boolean = false, seeking: Boolean = false) {
  val id = BilibiliLinks.parseVideoId(t.id.substringBefore("#p=")) ?: BilibiliLinks.parseVideoId(t.uri)
  val part = t.id.substringAfter("#p=", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
  if (id == null) { showNotice("这条记录缺少有效的 B 站视频编号"); return }
  cancelShuffle()
  stopAudio()
  if (current?.id?.substringBefore("#p=") != id) {
   activeAudio = null
   partPlaybackMode = null
   visitedParts.clear()
  }
  visitedQueue.add(queueKey(t))
  visitedParts.add(part)
  val account = accountPanel?.signedInAccount
  val session = DesktopAudioSession(startAt, initiallyPaused, playerVolume)
  audioSession = session
  current = t
  nowPlaying.text = t.title
  nowPlaying.toolTipText = t.title
  nowDetail.text = if (t.creator.isBlank()) "B 站音轨" else t.creator
  seekPending = seeking
  if (!seeking) showAudioLoading(true)
  if (!seeking) {
   scrubbing = false
   timeProgress.isEnabled = false
   activeDuration = 0
   timeProgress.maximum = 0
   elapsedLabel.text = "00:00"
   durationLabel.text = "00:00"
   loadPlaybackCover(id)
  } else {
   timeProgress.value = startAt.toInt()
   elapsedLabel.text = timeLabel(startAt)
  }
  val worker = object : SwingWorker<Unit, Unit>() {
   override fun doInBackground() {
    val audio = biliClient.audio(id, account, part)
    if (isCancelled) return
    SwingUtilities.invokeLater {
     if (playbackWorker !== this) return@invokeLater
     if (deferredCollectionParts.remove(id) && queue.any { it.id == id } && audio.parts.size > 1) {
      val alreadyPlayed = visitedQueue.toSet()
      val known = queue.filterNot { it.id == id }.map(::queueKey).toMutableSet()
      val additional = audio.parts.map { partTrack(t, it) }.filter { known.add(queueKey(it)) }
      queue = queue.flatMap { if (it.id == id) additional else listOf(it) }
      visitedQueue.addAll(alreadyPlayed)
     }
     activeAudio = audio
     activeDuration = audio.duration
     if (!scrubbing) {
      timeProgress.maximum = activeDuration.coerceAtLeast(0)
      timeProgress.isEnabled = activeDuration > 0
     }
     durationLabel.text = timeLabel(activeDuration.toLong())
     seekPending = false
    }
    session.play(audio) {
     SwingUtilities.invokeLater {
      if (playbackWorker !== this) return@invokeLater
      showAudioLoading(false)
      if (tracks.none { it.id == t.id }) tracks = tracks + t
      recent = recordRecent(recent, t.id)
      runCatching { save() }.onFailure { showNotice("无法保存最近播放") }
     }
    }
   }
   override fun done() {
    if (playbackWorker !== this) return
    playbackWorker = null
    audioSession = null
    showAudioLoading(false)
    runCatching { get() }.onSuccess {
     if (!scrubbing) next(automatic = true)
    }.onFailure {
     activeAudio = null
     seekPending = false
     timeProgress.isEnabled = false
     nowDetail.text = "播放失败"
     showPlaylistNotice("加载歌曲失败", requestError(it))
    }
   }
  }
  playbackWorker = worker
  worker.execute()
 }

 private fun timeLabel(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

 private fun queueKey(track: Track): String = track.id.removeSuffix("#p=1")

 private fun modeName(mode: Int) = listOf("顺序播放", "单曲循环", "随机播放")[mode]
 private fun modeIcon(mode: Int) = listOf("mode", "repeat", "shuffle")[mode]

 private fun cycleQueueMode() {
  playbackMode = (playbackMode + 1) % 3
  partPlaybackMode = null
  visitedQueue.clear()
  current?.let { visitedQueue.add(queueKey(it)) }
 }

 private fun cyclePartMode() {
  partPlaybackMode = ((partPlaybackMode ?: 0) + 1) % 3
  visitedParts.clear()
  current?.id?.substringAfter("#p=", "1")?.toIntOrNull()?.let(visitedParts::add)
 }

 private fun next(automatic: Boolean = false) {
  val track = current
  val base = track?.id?.substringBefore("#p=")
  val part = track?.id?.substringAfter("#p=", "1")?.toIntOrNull() ?: 1
  val parts = activeAudio?.parts.orEmpty()
  val scoped = partPlaybackMode
  if (automatic && (scoped ?: playbackMode) == 1 && track != null) {
   play(track)
   return
  }
  if (scoped == 2 && track != null) {
   parts.filter { it.page != part && it.page !in visitedParts }.randomOrNull()?.let { play(partTrack(track, it)) }
   return
  }
  if (scoped == null && playbackMode == 2) {
   queue.distinctBy(::queueKey).filter {
    queueKey(it) !in visitedQueue && (track == null || queueKey(it) != queueKey(track))
   }.randomOrNull()?.let { play(it) }
   return
  }
  if (scoped == null && track != null) {
   val expandedIndex = queue.indexOfFirst { "#p=" in it.id && queueKey(it) == queueKey(track) }
   if (expandedIndex >= 0) {
    queue.getOrNull(expandedIndex + 1)?.let { play(it) }
    return
   }
  }
  val nextPart = parts.firstOrNull { it.page > part }
  if (track != null && nextPart != null) {
   play(partTrack(track, nextPart))
   return
  }
  if (scoped != null) return
  val currentIndex = queue.indexOfFirst { it.id == track?.id }.takeIf { it >= 0 }
   ?: queue.indexOfLast { it.id.substringBefore("#p=") == base }
  var index = currentIndex + 1
  if (parts.size > 1) {
   while (index in queue.indices && queue[index].id.substringBefore("#p=") == base) index++
  }
  if (index in queue.indices) play(queue[index])
 }

 private fun partTrack(track: Track, part: BiliPart): Track {
  val bvid = track.id.substringBefore("#p=")
  return Track("$bvid#p=${part.page}", part.title.ifBlank { track.title }, track.creator,
   uri = BilibiliLinks.player(bvid).toString() + "&p=${part.page}")
 }

 private fun showPartQueue(anchor: JButton) {
  val track = current ?: return
  val parts = activeAudio?.parts.orEmpty()
  if (parts.size < 2) return
  val active = track.id.substringAfter("#p=", "1").toIntOrNull() ?: 1
  val popup = roundedPopup()
  val mode = partPlaybackMode ?: 0
  addQueueHeader(popup, "分 P 队列", parts.size, mode,
   onMode = { cyclePartMode(); showPartQueue(anchor) })
  val entries = queueEntries()
  for (part in parts) {
   entries.add(queueRow("P${part.page}  ${part.title}", part.page == active) {
    popup.isVisible = false
    play(partTrack(track, part))
   })
  }
  popup.add(queueScroll(entries, parts.size))
  showAbove(popup, anchor)
 }

 private fun addQueueHeader(popup: JPopupMenu, title: String, count: Int, mode: Int,
  onMode: () -> Unit, clear: (() -> Unit)? = null
 ) {
  popup.add(JPanel(BorderLayout(8, 0)).apply {
   isOpaque = false
   border = BorderFactory.createEmptyBorder(5, 8, 8, 4)
   add(JPanel(GridLayout(2, 1, 0, 4)).apply {
    isOpaque = false
    add(JLabel(tr(title)).apply { font = Font(desktopFont, Font.BOLD, 17); foreground = Color(242, 239, 225) })
    add(JLabel("$count 项 · ${modeName(mode)}").apply { foreground = Color(180, 176, 161) })
   }, BorderLayout.CENTER)
   add(JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
    isOpaque = false
    if (clear != null) add(button("清空") { popup.isVisible = false; clear() }.apply {
     border = BorderFactory.createEmptyBorder(7, 8, 7, 8)
    })
    add(playerControl(modeIcon(mode), modeName(mode)) { popup.isVisible = false; onMode() })
   }, BorderLayout.EAST)
  })
 }

 private fun queueEntries() = JPanel().apply {
  isOpaque = false
  layout = BoxLayout(this, BoxLayout.Y_AXIS)
 }

 private fun queueRow(title: String, selected: Boolean, action: () -> Unit) = button(title, action).apply {
  alignmentX = 0f
  horizontalAlignment = SwingConstants.LEFT
  preferredSize = Dimension(1, 40)
  maximumSize = Dimension(Int.MAX_VALUE, 40)
  toolTipText = title
  putClientProperty("html.disable", true)
  if (selected) { foreground = accent; background = Color(55, 49, 30) }
 }

 private fun queueScroll(entries: JPanel, count: Int) = themeScroll(JScrollPane(entries)).apply {
  preferredSize = Dimension(350, (count * 40).coerceIn(72, 300))
  viewport.background = panelColor
 }

 private fun showAbove(popup: JPopupMenu, anchor: JButton) {
  popup.show(anchor, anchor.width - popup.preferredSize.width, -popup.preferredSize.height)
 }

 private fun installVolumeControl(anchor: JButton) {
  var lastAudibleVolume = playerVolume.coerceAtLeast(1)
  var visible = false
  val slider = styledSlider(JSlider(SwingConstants.VERTICAL, 0, 100, playerVolume))
  val value = JLabel("$playerVolume%", SwingConstants.CENTER).apply { foreground = accent }
  var syncing = false
  val surface = RoundedPanel(18).apply {
   layout = BorderLayout(0, 4)
   background = Color(37, 35, 29)
   border = BorderFactory.createEmptyBorder(8, 9, 9, 9)
   preferredSize = Dimension(58, 155)
   add(value, BorderLayout.NORTH)
   add(slider, BorderLayout.CENTER)
  }
  fun setVolume(newValue: Int) {
   playerVolume = newValue
   if (newValue > 0) lastAudibleVolume = newValue
   audioSession?.volume = newValue
   value.text = "$newValue%"
   anchor.icon = PlaybackIcon(if (newValue == 0) "muted" else "volume")
   syncing = true
   slider.value = newValue
   syncing = false
  }
  slider.addChangeListener { if (!syncing) setVolume(slider.value) }
  fun hideSlider() {
   if (!visible) return
   visible = false
   val area = surface.bounds
   frame.layeredPane.remove(surface)
   frame.layeredPane.repaint(area)
  }
  val hide = Timer(200) { hideSlider() }.apply { isRepeats = false }
  fun showSlider() {
   hide.stop()
   if (visible || !anchor.isShowing) return
   val size = surface.preferredSize
   val location = SwingUtilities.convertPoint(anchor, 0, 0, frame.layeredPane)
   surface.setBounds(location.x + (anchor.width - size.width) / 2, location.y - size.height - 4, size.width, size.height)
   frame.layeredPane.add(surface, JLayeredPane.POPUP_LAYER)
   surface.doLayout()
   surface.repaint()
   visible = true
  }
  anchor.toolTipText = null
  anchor.addActionListener { setVolume(if (playerVolume == 0) lastAudibleVolume else 0) }
  anchor.addMouseListener(object : java.awt.event.MouseAdapter() {
   override fun mouseEntered(e: java.awt.event.MouseEvent) { showSlider() }
   override fun mouseExited(e: java.awt.event.MouseEvent) { hide.restart() }
  })
  val hover = object : java.awt.event.MouseAdapter() {
   override fun mouseEntered(e: java.awt.event.MouseEvent) { hide.stop() }
   override fun mouseExited(e: java.awt.event.MouseEvent) { hide.restart() }
  }
  surface.addMouseListener(hover)
  value.addMouseListener(hover)
  slider.addMouseListener(hover)
  frame.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
   override fun windowLostFocus(e: java.awt.event.WindowEvent) { hide.stop(); hideSlider() }
  })
  frame.addComponentListener(object : java.awt.event.ComponentAdapter() {
   override fun componentMoved(e: java.awt.event.ComponentEvent) { hide.stop(); hideSlider() }
  })
 }

 private fun playerControl(iconName: String, description: String, action: () -> Unit) =
  compactButton("", description, action).apply {
   icon = PlaybackIcon(iconName)
   preferredSize = Dimension(42, 42)
  }

 private fun showQueue(anchor: JButton) {
  val popup = roundedPopup()
  addQueueHeader(popup, "播放队列", queue.size, playbackMode,
   onMode = { cycleQueueMode(); showQueue(anchor) },
   clear = if (queue.isEmpty()) null else { { queue = emptyList(); deferredCollectionParts.clear(); showQueue(anchor) } })
  val entries = queueEntries()
  if (queue.isEmpty()) {
   entries.add(JLabel("队列为空", SwingConstants.CENTER).apply {
    alignmentX = 0f
    foreground = Color(180, 176, 161)
    maximumSize = Dimension(Int.MAX_VALUE, 50)
   })
  } else queue.forEachIndexed { index, track ->
   entries.add(queueRow("${index + 1}  ${track.title}", track.id == current?.id) {
    popup.isVisible = false
    partPlaybackMode = null
    play(track)
   })
  }
  popup.add(queueScroll(entries, queue.size))
  showAbove(popup, anchor)
 }

 private fun loadPlaybackCover(id: String) {
  coverRequest?.cancel(true)
  playCover.icon = null
  playCover.text = "♫"
  val url = knownVideos[id]?.cover.orEmpty()
  if (url.isBlank()) return
  val worker = object : SwingWorker<Icon?, Unit>() {
   override fun doInBackground(): Icon? =
    loadBiliAvatar(if (url.startsWith("//")) "https:$url" else url)
     ?.let { ImageIcon(it.getScaledInstance(60, 60, Image.SCALE_SMOOTH)) }
   override fun done() {
    if (coverRequest !== this) return
    val icon = runCatching { get() }.getOrNull() ?: return
    playCover.text = ""
    playCover.icon = icon
   }
  }
  coverRequest = worker
  worker.execute()
 }
 private fun chooseLanguage() {
  showPlayerDialog("语言", JLabel(if (english) "Playback restarts when the language changes." else "切换语言会重新打开窗口并停止当前播放。"), closable = true) { dialog ->
   listOf("简体中文" to false, "English" to true).map { (name, value) ->
    button(name) {
     dialog.dispose()
     if (english == value) return@button
     english = value
     runCatching { save() }.onSuccess {
      frame.dispose()
      DesktopApp().show()
     }.onFailure {
      english = !value
      showPlaylistNotice("语言", if (english) "Could not save language preference." else "无法保存语言设置。")
     }
    }
   }
  }
 }

 private fun chooseTheme() {
  var selected: String? = null
  showPlayerDialog("主题切换", JLabel("当前主题 · $themeName"), closable = true) { dialog ->
   themes.keys.map { name ->
    button(name) { selected = name; dialog.dispose() }.apply {
     if (name == themeName) { background = accent; foreground = bg }
    }
   }
  }
  val name = selected ?: return
  if (name == themeName) return
  val oldName = themeName
  val previous = themes.getValue(oldName)
  themeName = name
  runCatching { save() }.onSuccess {
   applyPalette(themes.getValue(name))
   updateThemeColors(root, previous)
   root.repaint()
  }.onFailure {
   themeName = oldName
   showPlaylistNotice("保存设置失败", "无法保存主题设置")
  }
 }

 private fun updateThemeColors(component: Component, previous: PlayerTheme) {
  fun color(value: Color?) = when (value) {
   previous.accent -> accent
   previous.background -> bg
   previous.surface -> panelColor
   else -> value
  }
  component.foreground = color(component.foreground)
  component.background = color(component.background)
  if (component.name == "theme.hero") component.background = tintedSurface(.065)
  if (component is Container) component.components.forEach { updateThemeColors(it, previous) }
 }

 private fun loadBiliAvatar(url: String): java.awt.image.BufferedImage? {
  if (url.isBlank()) return null
  val hash = java.security.MessageDigest.getInstance("SHA-256")
   .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  val directory = File(cacheDirectory, "bilimusic-images")
  val target = File(directory, "$hash.png")
  runCatching { if (target.isFile) javax.imageio.ImageIO.read(target) else null }.getOrNull()?.let { return it }
  val image = fetchBiliImage(url) ?: return null
  runCatching {
   java.nio.file.Files.createDirectories(directory.toPath())
   val temp = java.nio.file.Files.createTempFile(directory.toPath(), "image-", ".tmp")
   try {
    check(javax.imageio.ImageIO.write(image, "png", temp.toFile()))
    java.nio.file.Files.move(temp, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
   } finally { java.nio.file.Files.deleteIfExists(temp) }
  }
  return image
 }

 private fun save() {
  val data = Properties()
  if (storage.isFile) {
   val previous = Properties()
   storage.inputStream().use(previous::load)
   previous.stringPropertyNames().filter { it == "names" || it.startsWith("playlist.") || it.startsWith("imported.") || it.startsWith("up.") }
    .forEach { data.setProperty(it, previous.getProperty(it)) }
  }
  selectedFolders.stringPropertyNames().forEach { data.setProperty("selectedFolders.$it", selectedFolders.getProperty(it)) }
  savedCollections.forEach { (account, items) ->
   val json = org.json.JSONArray()
   items.forEach { item ->
    json.put(org.json.JSONObject().put("id", item.id).put("owner", item.owner)
     .put("name", item.name).put("cover", item.cover).put("count", item.count))
   }
   data.setProperty("collections.$account", json.toString())
  }
  data.setProperty("count", tracks.size.toString())
  tracks.forEachIndexed { index, track ->
   data.setProperty("$index.id", track.id)
   data.setProperty("$index.title", track.title)
   data.setProperty("$index.uri", track.uri)
   data.setProperty("$index.creator", track.creator)
   knownVideos[track.id.substringBefore("#p=")]?.let { video ->
    data.setProperty("$index.cover", video.cover)
    data.setProperty("$index.duration", video.duration)
    video.views?.let { data.setProperty("$index.views", it.toString()) }
    video.published?.let { data.setProperty("$index.published", it.toString()) }
   }
  }
  data.setProperty("recent", recent.joinToString("\n"))
  data.setProperty("searchHistory", searchHistory.joinToString("\n"))
  data.setProperty("home.greeting", greeting)
  data.setProperty("theme", themeName)
  data.setProperty("language", if (english) "en" else "zh")
  data.setProperty("cache.directory", cacheDirectory.absolutePath)
  followGroups.stringPropertyNames().forEach { data.setProperty("followGroup.$it", followGroups.getProperty(it)) }
  storage.parentFile.mkdirs()
  val temporary = java.nio.file.Files.createTempFile(storage.parentFile.toPath(), "library-", ".tmp")
  try {
   java.nio.file.Files.newOutputStream(temporary).use { data.store(it, "B station music player") }
   java.nio.file.Files.move(temporary, storage.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
  } finally { java.nio.file.Files.deleteIfExists(temporary) }
 }

 private fun load() {
  if (!storage.exists()) return
  runCatching {
   val data = Properties()
   storage.inputStream().use { data.load(it) }
   english = data.getProperty("language") == "en"
   themeName = when (val stored = data.getProperty("theme")) {
    "薄荷绿" -> "墨绿色"
    "冰川蓝" -> "墨蓝色"
    in themes -> stored
    else -> themes.keys.first()
   }
   applyPalette(themes.getValue(themeName))
   data.getProperty("cache.directory")?.takeIf(String::isNotBlank)?.let { cacheDirectory = File(it) }
   data.stringPropertyNames().filter { it.startsWith("selectedFolders.") }.forEach {
    selectedFolders.setProperty(it.removePrefix("selectedFolders."), data.getProperty(it))
   }
   data.stringPropertyNames().filter { it.startsWith("collections.") }.forEach { key ->
    runCatching {
     val json = org.json.JSONArray(data.getProperty(key))
     savedCollections[key.removePrefix("collections.")] = (0 until json.length()).map { index ->
      val item = json.getJSONObject(index)
      BiliCollection(item.getLong("id"), item.getString("name"), item.optString("cover"), item.optInt("count"), item.getLong("owner"))
     }
    }
   }
   tracks = (0 until data.getProperty("count", "0").toInt()).mapNotNull { index ->
    val id = data.getProperty("$index.id") ?: return@mapNotNull null
    val uri = data.getProperty("$index.uri", "")
    if (uri.startsWith("file:") || uri.startsWith("content:")) return@mapNotNull null
    val title = data.getProperty("$index.title", id)
    val creator = data.getProperty("$index.creator", "")
    BilibiliLinks.parseVideoId(id.substringBefore("#p="))?.let { bvid ->
     val cover = data.getProperty("$index.cover", "")
     val duration = data.getProperty("$index.duration", "")
     val views = data.getProperty("$index.views")?.toLongOrNull()
     val published = data.getProperty("$index.published")?.toLongOrNull()
     if (cover.isNotBlank() || duration.isNotBlank() || views != null || published != null) {
      knownVideos[bvid] = BiliVideo(0, bvid, title, creator, cover, duration, views = views, published = published)
     }
    }
    Track(id, title, creator, uri = uri)
   }
   recent = data.getProperty("recent", "").lineSequence().filter(String::isNotBlank).toList()
   searchHistory = data.getProperty("searchHistory", "").lineSequence().filter(String::isNotBlank).take(20).toList()
   data.stringPropertyNames().filter { it.startsWith("followGroup.") }.forEach {
    followGroups.setProperty(it.removePrefix("followGroup."), data.getProperty(it))
   }
   greeting = data.getProperty("home.greeting", defaultGreeting).ifBlank { defaultGreeting }
  }.onFailure { showNotice("本地资料读取失败，请保留文件以便排查") }
 }

}
private class RoundedPanel(private val radius:Int):JPanel(){
 init{isOpaque=false}
 override fun paintComponent(g:Graphics){val g2=g.create() as Graphics2D;g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g2.color=background;g2.fillRoundRect(0,0,width,height,radius,radius);g2.dispose();super.paintComponent(g)}
}
fun main(){System.setProperty("awt.useSystemAAFontSettings","on");System.setProperty("swing.aatext","true");SwingUtilities.invokeLater{val uiFont=javax.swing.plaf.FontUIResource(desktopFont,Font.PLAIN,14);UIManager.getDefaults().keys().toList().filter{it.toString().endsWith(".font")}.forEach{UIManager.put(it,uiFont)};UIManager.put("Label.foreground",Color(220,218,232));UIManager.put("CheckBox.foreground",Color(220,218,232));UIManager.put("CheckBox.background",panelColor);DesktopApp().show()}}


private class HoverButton(text:String):JButton(text){
 private var progress=0f
 private var target=0f
 private var keyboardFocus = false
 private val animation=Timer(15){
  val delta=target-progress
  progress=if(kotlin.math.abs(delta)<.015f) target else progress+delta*.22f
  repaint()
  if(progress==target)(it.source as Timer).stop()
 }
 init{
  isFocusPainted=false;isBorderPainted=false;isContentAreaFilled=false;isOpaque=false
  cursor=Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  border=BorderFactory.createEmptyBorder(13,18,13,18)
  addFocusListener(object : java.awt.event.FocusAdapter() {
   override fun focusGained(e: java.awt.event.FocusEvent) {
    keyboardFocus = e.cause == java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD ||
     e.cause == java.awt.event.FocusEvent.Cause.TRAVERSAL_BACKWARD ||
     e.cause == java.awt.event.FocusEvent.Cause.TRAVERSAL_UP ||
     e.cause == java.awt.event.FocusEvent.Cause.TRAVERSAL_DOWN
    repaint()
   }
   override fun focusLost(e: java.awt.event.FocusEvent) { keyboardFocus = false; repaint() }
  })
  addMouseListener(object:java.awt.event.MouseAdapter(){
   override fun mousePressed(e:java.awt.event.MouseEvent){keyboardFocus=false;repaint()}
   override fun mouseEntered(e:java.awt.event.MouseEvent){if(isEnabled){target=1f;animation.start()}}
   override fun mouseExited(e:java.awt.event.MouseEvent){target=0f;animation.start()}
  })
 }
 override fun removeNotify(){animation.stop();super.removeNotify()}
 override fun paintComponent(g:Graphics){
  val g2=g.create() as Graphics2D
  g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
  val lift=if(model.isPressed) 0f else progress*1.5f
  g2.translate(width/2.0,height/2.0-lift);val scale=1.0+progress*.025;g2.scale(scale,scale);g2.translate(-width/2.0,-height/2.0)
  val amount=progress*(if(model.isPressed) .20f else .12f)
  g2.color=Color((background.red+(255-background.red)*amount).toInt(),(background.green+(255-background.green)*amount).toInt(),(background.blue+(255-background.blue)*amount).toInt())
  g2.fillRoundRect(3,3,width-6,height-6,22,22)
  super.paintComponent(g2)
  if(hasFocus() && keyboardFocus){g2.color=accent;g2.stroke=BasicStroke(1.5f);g2.drawRoundRect(3,3,width-7,height-7,22,22)}
  g2.dispose()
 }
}


private class SoundArtwork:JPanel(){
 init{isOpaque=false}
 override fun paintComponent(g:Graphics){val a=g.create() as Graphics2D;a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);val cx=width/2;val cy=height/2
  val scale=minOf(width,height).coerceAtMost(136)/136.0;a.translate(cx.toDouble(),cy.toDouble());a.scale(scale,scale);a.translate(-cx.toDouble(),-cy.toDouble())
  a.color=Color(accent.red,accent.green,accent.blue,18);a.fillOval(cx-68,cy-68,136,136);a.color=Color(accent.red,accent.green,accent.blue,45);a.stroke=BasicStroke(1f);a.drawOval(cx-57,cy-57,114,114)
  a.color=accent;val heights=intArrayOf(22,42,66,90,54,76,38,20);heights.forEachIndexed{i,h->a.fillRoundRect(cx-57+i*16,cy-h/2,7,h,7,7)};a.dispose()
 }
}



private class SearchField : JTextField() {
 init {
  font = Font(desktopFont, Font.BOLD, 17)
  foreground = Color(245, 242, 232)
  caretColor = accent
  isOpaque = false
  border = BorderFactory.createEmptyBorder()
 }

 override fun paintComponent(g: Graphics) {
  super.paintComponent(g)
  if (text.isNotEmpty()) return
  val graphics = g.create() as Graphics2D
  try {
   graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
   graphics.font = font
   graphics.color = Color(193, 190, 180)
   val metrics = graphics.fontMetrics
   graphics.drawString("搜索音乐", 0, (height - metrics.height) / 2 + metrics.ascent)
  } finally {
   graphics.dispose()
  }
 }
}

private class SearchIcon : Icon {
 override fun getIconWidth() = 24
 override fun getIconHeight() = 24

 override fun paintIcon(component: Component, g: Graphics, x: Int, y: Int) {
  val graphics = g.create() as Graphics2D
  try {
   graphics.translate(x, y)
   graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   graphics.color = if ((component as? JButton)?.model?.isRollover == true) accent else Color(226, 223, 213)
   graphics.stroke = BasicStroke(2.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
   graphics.drawOval(3, 3, 13, 13)
   graphics.drawLine(14, 14, 21, 21)
  } finally {
   graphics.dispose()
  }
 }
}

private class AddPlaylistIcon : Icon {
 override fun getIconWidth() = 18
 override fun getIconHeight() = 18

 override fun paintIcon(component: Component, g: Graphics, x: Int, y: Int) {
  val graphics = g.create() as Graphics2D
  try {
   graphics.translate(x, y)
   graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   graphics.color = component.foreground
   graphics.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
   graphics.drawLine(4, 9, 14, 9)
   graphics.drawLine(9, 4, 9, 14)
  } finally {
   graphics.dispose()
  }
 }
}


private fun fetchBiliImage(url: String): java.awt.image.BufferedImage? {
 return runCatching {
  val uri = java.net.URI(url)
  val host = uri.host?.lowercase() ?: return null
  if (uri.scheme !in listOf("http", "https") || uri.userInfo != null ||
      (host != "hdslb.com" && !host.endsWith(".hdslb.com") &&
       host != "bilibili.com" && !host.endsWith(".bilibili.com"))) return null
  val secureUrl = java.net.URI("https", null, host, -1, uri.path, uri.query, null).toURL()
  val connection = secureUrl.openConnection() as java.net.HttpURLConnection
  try {
   connection.instanceFollowRedirects = false
   connection.connectTimeout = 5000
   connection.readTimeout = 5000
   if (connection.responseCode != 200) return null
   val data = connection.inputStream.use { it.readNBytes(1_048_577) }
   if (data.size > 1_048_576) return null
   javax.imageio.ImageIO.read(data.inputStream())
  } finally {
   connection.disconnect()
  }
 }.getOrNull()
}

private class ProfileAvatar(private val image: java.awt.image.BufferedImage?) : Icon {
 override fun getIconWidth() = 36
 override fun getIconHeight() = 36

 override fun paintIcon(component: Component, g: Graphics, x: Int, y: Int) {
  val graphics = g.create() as Graphics2D
  try {
   graphics.translate(x, y)
   graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   val circle = java.awt.geom.Ellipse2D.Double(0.0, 0.0, 36.0, 36.0)
   graphics.color = Color(48, 45, 36)
   graphics.fill(circle)
   if (image == null) {
    graphics.color = accent
    graphics.fillOval(14, 8, 8, 8)
    graphics.fillRoundRect(10, 19, 16, 9, 8, 8)
   } else {
    graphics.clip(circle)
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    val side = minOf(image.width, image.height)
    val left = (image.width - side) / 2
    val top = (image.height - side) / 2
    graphics.drawImage(image, 0, 0, 36, 36, left, top, left + side, top + side, null)
   }
  } finally {
   graphics.dispose()
  }
 }
}

private class SignOutIcon : Icon {
 override fun getIconWidth() = 20
 override fun getIconHeight() = 20

 override fun paintIcon(component: Component, g: Graphics, x: Int, y: Int) {
  val graphics = g.create() as Graphics2D
  try {
   graphics.translate(x, y)
   graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   graphics.color = component.foreground
   graphics.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
   graphics.drawPolyline(intArrayOf(9, 3, 3, 9), intArrayOf(3, 3, 17, 17), 4)
   graphics.drawLine(8, 10, 17, 10)
   graphics.drawPolyline(intArrayOf(13, 17, 13), intArrayOf(6, 10, 14), 3)
  } finally {
   graphics.dispose()
  }
 }
}



private fun BiliVideo.asTrack() = Track(bvid, title, author, uri = BilibiliLinks.player(bvid).toString())


private fun formatViews(views: Long): String = when {
 views >= 100_000_000 -> String.format(java.util.Locale.ROOT, "%.1f亿", views / 100_000_000.0)
 views >= 10_000 -> String.format(java.util.Locale.ROOT, "%.1f万", views / 10_000.0)
 else -> views.toString()
}

private fun formatPublished(epochSeconds: Long): String =
 java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString()

private fun formatDuration(raw: String): String? {
 val text = raw.trim()
 val seconds = text.toLongOrNull()
 if (seconds != null) return if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else null
 val parts = text.split(':').map { it.toLongOrNull() ?: return null }
 if (parts.size !in 2..3 || parts.any { it < 0 }) return null
 return parts.first().toString() + parts.drop(1).joinToString("") { ":%02d".format(it) }
}



private class GreetingLabel : JLabel() {
 override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

 override fun paintComponent(g: Graphics) {
  val value = text.orEmpty()
  if (value.isBlank()) return
  val graphics = g.create() as Graphics2D
  try {
   graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
   graphics.color = foreground
   val styled = java.text.AttributedString(value)
   styled.addAttribute(java.awt.font.TextAttribute.FONT, font)
   val fallback = Font("Dialog", font.style, font.size)
   var offset = 0
   while (offset < value.length) {
    val cp = value.codePointAt(offset)
    val end = offset + Character.charCount(cp)
    if (!font.canDisplay(cp)) styled.addAttribute(java.awt.font.TextAttribute.FONT, fallback, offset, end)
    offset = end
   }
   val layout = java.awt.font.TextLayout(styled.iterator, graphics.fontRenderContext)
   layout.draw(graphics, 0f, (height - layout.ascent - layout.descent) / 2 + layout.ascent)
  } finally { graphics.dispose() }
 }
}



