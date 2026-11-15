package dev.bilimusic.desktop

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.plaf.basic.BasicMenuItemUI
import javax.swing.plaf.basic.BasicScrollBarUI

internal fun themeScroll(scroll: JScrollPane) = scroll.apply {
 horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
 border = BorderFactory.createEmptyBorder()
 viewport.background = bg
 background = bg
 verticalScrollBar.unitIncrement = 24
 verticalScrollBar.preferredSize = Dimension(10, 0)
 verticalScrollBar.setUI(object : BasicScrollBarUI() {
  override fun createIncreaseButton(orientation: Int) = hiddenArrow()
  override fun createDecreaseButton(orientation: Int) = hiddenArrow()
  private fun hiddenArrow() = JButton().apply {
   preferredSize = Dimension(0, 0)
   minimumSize = preferredSize
   maximumSize = preferredSize
   isFocusable = false
  }
  override fun paintTrack(g: Graphics, c: JComponent, bounds: Rectangle) {
   g.color = bg
   g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
  }
  override fun paintThumb(g: Graphics, c: JComponent, bounds: Rectangle) {
   if (bounds.isEmpty || !c.isEnabled) return
   val graphics = g.create() as Graphics2D
   graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   graphics.color = if (isThumbRollover || isDragging) Color(129, 116, 73) else Color(82, 78, 66)
   graphics.fillRoundRect(bounds.x + 2, bounds.y + 1, bounds.width - 4, bounds.height - 2, 8, 8)
   graphics.dispose()
  }
 })
}

internal fun themePopup(popup: JPopupMenu) = popup.apply {
 background = panelColor
 border = BorderFactory.createCompoundBorder(
  BorderFactory.createLineBorder(Color(63, 59, 48)),
  BorderFactory.createEmptyBorder(6, 5, 6, 5)
 )
}

internal fun roundedPopup(): JPopupMenu = object : JPopupMenu() {
 override fun paintComponent(g: Graphics) {
  val graphics = g.create() as Graphics2D
  try {
   graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   graphics.color = panelColor
   graphics.fillRoundRect(0, 0, width, height, 22, 22)
   graphics.color = Color(63, 59, 48)
   graphics.drawRoundRect(0, 0, width - 1, height - 1, 22, 22)
  } finally { graphics.dispose() }
 }
}.apply {
 isOpaque = false
 border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
}

internal fun popupItem(text: String, action: () -> Unit) = JMenuItem(text).apply {
 font = Font(desktopFont, Font.PLAIN, 14)
 background = panelColor
 foreground = Color(232, 229, 217)
 border = BorderFactory.createEmptyBorder(9, 13, 9, 13)
 setUI(object : BasicMenuItemUI() {
  override fun installDefaults() {
   super.installDefaults()
   selectionBackground = Color(61, 53, 30)
   selectionForeground = accent
  }
 })
 addActionListener { action() }
}

internal class BrowserRows : JPanel(), Scrollable {
 init {
  isOpaque = false
  layout = BoxLayout(this, BoxLayout.Y_AXIS)
 }
 override fun getPreferredScrollableViewportSize() = preferredSize
 override fun getScrollableTracksViewportWidth() = true
 override fun getScrollableTracksViewportHeight() = false
 override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 24
 override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
  (visibleRect.height - 24).coerceAtLeast(24)
}

internal class BrowseRow : JPanel(BorderLayout(16, 0)) {
 var onHoverChanged: (Boolean) -> Unit = {}
 private var shade = 0f
 private var hovered = false
 private val animation = Timer(16) { event ->
  val target = if (hovered) 1f else 0f
  shade += (target - shade) * .24f
  if (kotlin.math.abs(target - shade) < .02f) {
   shade = target
   (event.source as Timer).stop()
  }
  repaint()
 }

 init {
  isOpaque = false
  alignmentX = 0f
  border = BorderFactory.createEmptyBorder(9, 12, 9, 12)
  maximumSize = Dimension(Int.MAX_VALUE, 100)
  preferredSize = Dimension(1, 100)
 }

 fun installHover() {
  val listener = object : MouseAdapter() {
   override fun mouseEntered(e: MouseEvent) {
    hovered = true
    onHoverChanged(true)
    animation.start()
   }
   override fun mouseExited(e: MouseEvent) {
    val point = SwingUtilities.convertPoint(e.component, e.point, this@BrowseRow)
    if (!contains(point)) {
     hovered = false
     onHoverChanged(false)
     animation.start()
    }
   }
  }
  fun attach(component: Component) {
   component.addMouseListener(listener)
   if (component is Container) component.components.forEach(::attach)
  }
  attach(this)
 }

 override fun removeNotify() {
  animation.stop()
  super.removeNotify()
 }

 override fun paintComponent(g: Graphics) {
  val graphics = g.create() as Graphics2D
  graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
  graphics.color = Color(25 + (shade * 16).toInt(), 25 + (shade * 13).toInt(), 27 + (shade * 5).toInt())
  graphics.fillRoundRect(0, 0, width, height, 20, 20)
  graphics.dispose()
  super.paintComponent(g)
 }
}

internal class PagedBrowser(
 private val makeButton: (String, () -> Unit) -> JButton,
 private val errorText: (Throwable) -> String,
 private val tr: (String) -> String
) : JPanel(BorderLayout(0, 14)) {
 private val heading = JLabel()
 private val detail = JLabel()
 private val backRow = JPanel(BorderLayout())
 private val titleRow = JPanel(BorderLayout(12, 0))
 private val centerCards = JPanel(CardLayout())
 private val centerMessage = JLabel("", SwingConstants.CENTER)
 private val tabs = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
 private val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
 private val actionRow = JPanel(BorderLayout())
 private val rows = BrowserRows()
 private val scroll = themeScroll(JScrollPane(rows))
 private val footer = makeButton("") { loadNext() }
 private var worker: SwingWorker<BiliPage<Any>, Unit>? = null
 private var load: ((Int) -> BiliPage<Any>)? = null
 private var draw: ((Any) -> JComponent)? = null
 private var identify: ((Any) -> String)? = null
 private var page = 1
 private var hasMore = false
 private var lastScroll = 0
 private val identities = mutableSetOf<String>()
 private var profileWorker: SwingWorker<Icon?, Unit>? = null
 private var profileSummary = ""
 val items = mutableListOf<Any>()

 init {
  isOpaque = false
  heading.font = Font(desktopFont, Font.BOLD, 22)
  heading.foreground = Color(242, 240, 232)
  heading.putClientProperty("html.disable", true)
  detail.foreground = Color(173, 170, 159)
  detail.putClientProperty("html.disable", true)
  backRow.isOpaque = false
  backRow.isVisible = false
  titleRow.isOpaque = false
  titleRow.add(heading, BorderLayout.CENTER)
  tabs.isOpaque = false
  rightActions.isOpaque = false
  actionRow.isOpaque = false
  actionRow.add(tabs, BorderLayout.CENTER)
  actionRow.add(rightActions, BorderLayout.EAST)
  add(JPanel(BorderLayout(0, 7)).apply {
   isOpaque = false
   add(JPanel(BorderLayout(0, 8)).apply {
    isOpaque = false
    add(backRow, BorderLayout.NORTH)
    add(titleRow, BorderLayout.CENTER)
   }, BorderLayout.NORTH)
   add(detail, BorderLayout.CENTER)
   add(actionRow, BorderLayout.SOUTH)
  }, BorderLayout.NORTH)
  centerMessage.font = Font(desktopFont, Font.BOLD, 21)
  centerMessage.foreground = Color(218, 214, 196)
  centerMessage.putClientProperty("html.disable", true)
  centerCards.isOpaque = false
  centerCards.add(scroll, "list")
  centerCards.add(centerMessage, "message")
  add(centerCards, BorderLayout.CENTER)
  footer.isVisible = false
  add(footer, BorderLayout.SOUTH)
  scroll.verticalScrollBar.addAdjustmentListener { event ->
   val value = event.value
   val movedDown = value > lastScroll
   lastScroll = value
   if (movedDown && nearEnd()) loadNext()
  }
  scroll.addMouseWheelListener { event ->
   if (event.wheelRotation > 0 && nearEnd()) loadNext()
  }
 }

 fun stop() {
  worker?.cancel(true)
  worker = null
  profileWorker?.cancel(true)
  profileWorker = null
  hasMore = false
 }

 private fun nearEnd(): Boolean {
  val bar = scroll.verticalScrollBar
  return bar.value + bar.visibleAmount >= bar.maximum - 24
 }

 fun <T : Any> open(
  title: String,
  controls: List<Pair<String, () -> Unit>> = emptyList(),
  selectedTab: String = "",
  loader: (Int) -> BiliPage<T>,
  key: (T) -> String,
  renderer: (T) -> JComponent
 ) {
  stop()
  heading.text = tr(title)
  heading.isVisible = title.isNotBlank()
  heading.icon = null
  backRow.removeAll()
  backRow.isVisible = false
  (titleRow.layout as BorderLayout).getLayoutComponent(BorderLayout.EAST)?.let(titleRow::remove)
  showContent(null)
  profileSummary = ""
  detail.text = ""
  detail.isVisible = false
  tabs.removeAll()
  rightActions.removeAll()
  controls.forEach { (label, callback) ->
   val target = if (label in listOf("刷新", "全部加入队列") || title.isNotBlank() && label in listOf("随机播放", "收藏合集", "取消收藏合集")) rightActions else tabs
   target.add(makeButton(label, callback).apply {
    putClientProperty("browse.action", label)
    if (label == selectedTab) { background = accent; foreground = bg }
   })
  }
  tabs.isVisible = tabs.componentCount > 0
  rightActions.isVisible = rightActions.componentCount > 0
  actionRow.isVisible = tabs.isVisible || rightActions.isVisible
  actionRow.revalidate()
  actionRow.repaint()
  revalidate()
  repaint()
  rows.removeAll()
  rows.revalidate()
  rows.repaint()
  scroll.verticalScrollBar.value = 0
  lastScroll = 0
  items.clear()
  identities.clear()
  @Suppress("UNCHECKED_CAST")
  val renderItem: (Any) -> JComponent = { renderer(it as T) }
  @Suppress("UNCHECKED_CAST")
  val itemKey: (Any) -> String = { key(it as T) }
  draw = renderItem
  identify = itemKey
  load = { n -> loader(n).let { BiliPage(it.items, it.hasMore) } }
  page = 1
  hasMore = true
  loadNext()
 }

 fun setActionState(id: String, text: String, enabled: Boolean) {
  for (button in (tabs.components.asList() + rightActions.components.asList()).filterIsInstance<JButton>()) {
   if (button.getClientProperty("browse.action") != id) continue
   button.text = tr(text)
   button.isEnabled = enabled
  }
 }

 private fun showContent(message: String?) {
  centerMessage.text = message.orEmpty()
  (centerCards.layout as CardLayout).show(centerCards, if (message == null) "list" else "message")
 }

 fun showProfile(fans: Long, back: () -> Unit, favorite: JButton, loadAvatar: () -> Icon?) {
  backRow.add(makeButton("← 返回", back), BorderLayout.WEST)
  backRow.isVisible = true
  titleRow.add(favorite, BorderLayout.EAST)
  profileSummary = if (fans < 0) "" else "$fans 粉丝"
  detail.text = profileSummary
  detail.isVisible = profileSummary.isNotBlank()
  revalidate()
  repaint()
  heading.iconTextGap = 12
  val task = object : SwingWorker<Icon?, Unit>() {
   override fun doInBackground() = loadAvatar()
   override fun done() {
    if (profileWorker !== this) return
    heading.icon = runCatching { get() }.getOrNull()
   }
  }
  profileWorker = task
  task.execute()
 }

 private fun loadNext() {
  val request = load ?: return
  if (!hasMore || worker != null) return
  val pageNumber = page
  footer.isVisible = false
  footer.isEnabled = false
  val task = object : SwingWorker<BiliPage<Any>, Unit>() {
   override fun doInBackground() = request(pageNumber)
   override fun done() {
    if (worker !== this) return
    worker = null
    runCatching { get() }.onSuccess { batch ->
     for (item in batch.items) {
      val key = identify?.invoke(item) ?: continue
      if (!identities.add(key)) continue
      val component = draw?.invoke(item) ?: continue
      items.add(item)
      rows.add(component)
      rows.add(Box.createVerticalStrut(7))
     }
     page++
     hasMore = batch.hasMore && batch.items.isNotEmpty()
     showContent(if (items.isEmpty()) tr("暂无结果") else null)
     detail.text = profileSummary
     detail.isVisible = detail.text.isNotBlank()
     footer.isVisible = false
     footer.isEnabled = false
     rows.revalidate()
     rows.repaint()
    }.onFailure {
     showContent(errorText(it))
     footer.text = tr("重试加载")
     footer.isVisible = true
     footer.isEnabled = true
    }
   }
  }
  worker = task
  task.execute()
 }
}

