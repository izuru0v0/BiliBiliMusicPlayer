package dev.bilimusic.desktop

import java.awt.*
import javax.swing.*
import javax.swing.plaf.basic.BasicSliderUI

internal fun styledSlider(slider: JSlider) = slider.apply {
 isOpaque = false
 setUI(object : BasicSliderUI(this) {
  override fun getThumbSize() = Dimension(12, 12)
  override fun paintFocus(g: Graphics) {}
  override fun paintTrack(g: Graphics) {
   val a = g.create() as Graphics2D
   a.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   a.color = Color(77, 73, 61)
   if (slider.orientation == SwingConstants.VERTICAL) {
    val x = trackRect.x + trackRect.width / 2 - 2
    a.fillRoundRect(x, trackRect.y, 4, trackRect.height, 4, 4)
    if (slider.isEnabled) {
     a.color = accent
     val y = yPositionForValue(slider.value)
     a.fillRoundRect(x, y, 4, (trackRect.y + trackRect.height - y).coerceAtLeast(0), 4, 4)
    }
   } else {
    val y = trackRect.y + trackRect.height / 2 - 2
    a.fillRoundRect(trackRect.x, y, trackRect.width, 4, 4, 4)
    if (slider.isEnabled) {
     a.color = accent
     a.fillRoundRect(trackRect.x, y, (xPositionForValue(slider.value) - trackRect.x).coerceAtLeast(0), 4, 4, 4)
    }
   }
   a.dispose()
  }
  override fun createTrackListener(slider: JSlider): TrackListener = object : TrackListener() {
   override fun mousePressed(e: java.awt.event.MouseEvent) {
    if (slider.isEnabled && SwingUtilities.isLeftMouseButton(e) && !thumbRect.contains(e.point)) {
     slider.value = if (slider.orientation == SwingConstants.VERTICAL) valueForYPosition(e.y) else valueForXPosition(e.x)
    }
    super.mousePressed(e)
   }
  }

  override fun paintThumb(g: Graphics) {
   if (!slider.isEnabled) return
   val a = g.create() as Graphics2D
   a.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   a.color = accent
   a.fillOval(thumbRect.x + 2, thumbRect.y + 2, 8, 8)
   a.dispose()
  }
 })
}

internal class PlaybackIcon(private val kind: String) : Icon {
 override fun getIconWidth() = 22
 override fun getIconHeight() = 22
 override fun paintIcon(component: Component, g: Graphics, x: Int, y: Int) {
  val a = g.create() as Graphics2D
  try {
   a.translate(x, y)
   a.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   a.color = component.foreground
   a.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
   when (kind) {
    "play" -> a.fillPolygon(intArrayOf(7, 17, 7), intArrayOf(4, 11, 18), 3)
    "pause" -> { a.fillRoundRect(6, 4, 4, 14, 2, 2); a.fillRoundRect(13, 4, 4, 14, 2, 2) }
    "previous" -> { a.drawLine(5, 5, 5, 17); a.fillPolygon(intArrayOf(17, 7, 17), intArrayOf(5, 11, 17), 3) }
    "next" -> { a.drawLine(17, 5, 17, 17); a.fillPolygon(intArrayOf(5, 15, 5), intArrayOf(5, 11, 17), 3) }
    "volume", "muted" -> {
     a.drawPolygon(intArrayOf(3, 6, 11, 11, 6, 3), intArrayOf(8, 8, 4, 18, 14, 14), 6)
     if (kind == "muted") {
      a.drawLine(14, 8, 20, 14)
      a.drawLine(20, 8, 14, 14)
     } else {
      a.drawArc(9, 6, 9, 10, -65, 130)
      a.drawArc(8, 3, 14, 16, -65, 130)
     }
    }
    "queue" -> {
     for (i in 5..17 step 6) a.drawLine(3, i, 12, i)
     a.fillPolygon(intArrayOf(15, 20, 15), intArrayOf(8, 11, 14), 3)
    }
    "music" -> {
     a.drawLine(10, 5, 10, 16)
     a.drawLine(10, 5, 17, 3)
     a.drawLine(17, 3, 17, 13)
     a.fillOval(5, 14, 6, 5)
     a.fillOval(12, 11, 6, 5)
    }
    "folder" -> {
     a.drawRoundRect(2, 7, 18, 12, 4, 4)
     a.drawLine(3, 7, 3, 4)
     a.drawLine(3, 4, 9, 4)
     a.drawLine(9, 4, 12, 7)
    }
    "heart", "heart-filled" -> {
     val p = java.awt.geom.Path2D.Double()
     p.moveTo(11.0, 18.0)
     p.curveTo(9.0, 16.0, 3.0, 12.0, 3.0, 8.0)
     p.curveTo(3.0, 4.0, 8.0, 3.0, 11.0, 7.0)
     p.curveTo(14.0, 3.0, 19.0, 4.0, 19.0, 8.0)
     p.curveTo(19.0, 12.0, 13.0, 16.0, 11.0, 18.0)
     if (kind == "heart-filled") a.fill(p) else a.draw(p)
    }
    "discover" -> {
     a.drawOval(3, 3, 16, 16)
     a.drawPolygon(intArrayOf(8, 14, 12), intArrayOf(14, 8, 12), 3)
     a.drawLine(8, 14, 9, 10)
     a.drawLine(9, 10, 14, 8)
    }
    "home" -> {
     a.drawPolyline(intArrayOf(3, 11, 19), intArrayOf(10, 3, 10), 3)
     a.drawPolyline(intArrayOf(5, 5, 17, 17), intArrayOf(9, 18, 18, 9), 4)
     a.drawRect(9, 13, 4, 5)
    }
    "recent" -> {
     a.drawOval(4, 4, 14, 14)
     a.drawLine(11, 7, 11, 11)
     a.drawLine(11, 11, 14, 13)
    }
    "tag" -> {
     a.drawPolygon(intArrayOf(3, 11, 19, 12, 3), intArrayOf(4, 4, 12, 19, 10), 5)
     a.fillOval(6, 7, 3, 3)
    }
    "creator" -> {
     a.drawRoundRect(3, 7, 16, 11, 5, 5)
     a.drawLine(8, 7, 5, 3)
     a.drawLine(14, 7, 17, 3)
     a.drawLine(7, 13, 9, 12)
     a.drawLine(13, 12, 15, 13)
     a.drawLine(6, 18, 6, 20)
     a.drawLine(16, 18, 16, 20)
    }
    "settings" -> {
     val path = java.awt.geom.Path2D.Double()
     for (tooth in 0..7) {
      for (edge in 0..3) {
       val angle = (tooth + edge / 4.0) * Math.PI / 4
       val radius = if (edge == 1 || edge == 2) 9.0 else 7.2
       val px = 11 + radius * kotlin.math.cos(angle)
       val py = 11 + radius * kotlin.math.sin(angle)
       if (tooth == 0 && edge == 0) path.moveTo(px, py) else path.lineTo(px, py)
      }
     }
     path.closePath()
     a.draw(path)
     a.drawOval(8, 8, 6, 6)
    }
    "parts" -> {
     for (y in 6..18 step 6) a.drawLine(3, y, 10, y)
     a.font = Font("Dialog", Font.BOLD, 10)
     a.drawString("P", 13, 15)
    }
    "shuffle" -> {
     a.drawLine(3, 6, 7, 6); a.drawLine(7, 6, 15, 16); a.drawLine(15, 16, 19, 16)
     a.drawLine(3, 16, 7, 16); a.drawLine(7, 16, 15, 6); a.drawLine(15, 6, 19, 6)
     a.drawLine(17, 4, 19, 6); a.drawLine(17, 18, 19, 16)
    }
    else -> {
     a.drawArc(3, 5, 16, 12, 0, 160)
     a.drawArc(3, 5, 16, 12, 180, 160)
     a.drawLine(16, 4, 19, 8); a.drawLine(19, 8, 15, 8)
     a.drawLine(6, 18, 3, 14); a.drawLine(3, 14, 7, 14)
     if (kind == "repeat") {
      a.font = Font("Dialog", Font.BOLD, 9)
      a.drawString("1", 9, 14)
     }
    }
   }
  } finally { a.dispose() }
 }
}

internal class ScrollingTitle(text: String) : JLabel(text) {
 private var previous = ""
 private var position = 0
 private var delay = 0
 private var fontKey: Pair<String, Font>? = null
 private var shaped: java.awt.font.TextLayout? = null

 private fun titleLayout(): java.awt.font.TextLayout? {
  val value = text.orEmpty()
  val key = value to font
  if (key == fontKey) return shaped
  fontKey = key
  val display = StringBuilder()
  val spans = mutableListOf<Triple<Int, Int, Font>>()
  val alternatives = listOf(font, Font(Font.DIALOG, font.style, font.size),
   Font("Segoe UI Symbol", Font.PLAIN, font.size), Font("Segoe UI Emoji", Font.PLAIN, font.size),
   Font("Cambria Math", Font.PLAIN, font.size))
  value.codePoints().forEachOrdered { code ->
   val raw = String(Character.toChars(code))
   val normalized = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
   val selected = alternatives.firstOrNull { it.canDisplay(code) }
   val start = display.length
   display.append(if (selected == null && normalized != raw && font.canDisplayUpTo(normalized) < 0) normalized else raw)
   spans.add(Triple(start, display.length, selected ?: font))
  }
  shaped = if (display.isEmpty()) null else {
   val attributed = java.text.AttributedString(display.toString())
   for ((start, end, face) in spans) attributed.addAttribute(java.awt.font.TextAttribute.FONT, face, start, end)
   java.awt.font.TextLayout(attributed.iterator, getFontMetrics(font).fontRenderContext)
  }
  return shaped
 }

 private val hoverTimer = Timer(20) { advance() }

 fun setScrollHovered(hovered: Boolean) {
  if (hovered) {
   if ((titleLayout()?.advance ?: 0f) > width && !hoverTimer.isRunning) {
    delay = 16
    hoverTimer.start()
   }
  } else {
   hoverTimer.stop()
   position = 0
   repaint()
  }
 }

 override fun removeNotify() {
  hoverTimer.stop()
  super.removeNotify()
 }

 fun advance() {
  if (text != previous) {
   previous = text.orEmpty()
   position = 0
   delay = 32
   repaint()
  }
  val textWidth = kotlin.math.ceil(titleLayout()?.advance ?: 0f).toInt()
  if (width <= 0 || textWidth <= width) {
   if (position != 0) { position = 0; repaint() }
   return
  }
  if (delay > 0) { delay--; return }
  position = (position + 1) % (textWidth + 34)
  repaint()
 }

 override fun paintComponent(g: Graphics) {
  val layout = titleLayout() ?: return
  val graphics = g.create() as Graphics2D
  try {
   graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
   graphics.color = foreground
   val baseline = (height - layout.ascent - layout.descent) / 2 + layout.ascent
   val offset = if (text == previous) position else 0
   layout.draw(graphics, -offset.toFloat(), baseline)
   if (layout.advance > width && offset > 0) {
    layout.draw(graphics, kotlin.math.ceil(layout.advance) + 34 - offset, baseline)
   }
  } finally { graphics.dispose() }
 }
}

object PlayerToolTipFactory {
 @JvmStatic fun createUI(component: JComponent): javax.swing.plaf.ComponentUI = PlayerToolTipUI()
}

class PlayerToolTipUI : javax.swing.plaf.ToolTipUI() {
 override fun installUI(c: JComponent) {
  super.installUI(c)
  c.isOpaque = false
  c.foreground = Color(232, 229, 217)
  c.font = Font(desktopFont, Font.PLAIN, 13)
  c.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
 }

 override fun getPreferredSize(c: JComponent): Dimension {
  val tip = c as JToolTip
  val metrics = c.getFontMetrics(c.font)
  return Dimension(metrics.stringWidth(tip.tipText.orEmpty()) + 20, metrics.height + 12)
 }

 override fun update(g: Graphics, c: JComponent) {
  val a = g.create() as Graphics2D
  try {
   a.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
   a.color = Color(40, 38, 31)
   a.fillRoundRect(0, 0, c.width, c.height, 13, 13)
   a.color = Color(81, 72, 43)
   a.drawRoundRect(0, 0, c.width - 1, c.height - 1, 13, 13)
   a.color = c.foreground
   a.font = c.font
   a.drawString((c as JToolTip).tipText.orEmpty(), 10, 6 + a.fontMetrics.ascent)
  } finally { a.dispose() }
 }
}




internal class AudioLoadingIndicator : JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)) {
 private var startedAt = 0L
 private val ring = object : JComponent() {
  init { preferredSize = Dimension(18, 18) }
  override fun paintComponent(g: Graphics) {
   val a = g.create() as Graphics2D
   try {
    a.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    a.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    a.color = Color(accent.red, accent.green, accent.blue, 45)
    a.drawOval(2, 2, 14, 14)
    val phase = (System.nanoTime() - startedAt) / 1_000_000.0
    val sweep = 65 + (35 * (1 + kotlin.math.sin(phase / 160))).toInt()
    a.color = accent
    a.drawArc(2, 2, 14, 14, -(phase / 3.3).toInt() % 360, sweep)
   } finally { a.dispose() }
  }
 }
 private val timer = Timer(16) { ring.repaint() }
 init {
  isOpaque = false
  add(ring)
  add(JLabel("加载中…").apply {
   foreground = Color(208, 205, 195)
   font = Font(desktopFont, Font.PLAIN, 12)
  })
 }

 fun animate(active: Boolean) {
  if (active) {
   startedAt = System.nanoTime()
   timer.start()
  } else timer.stop()
 }

 override fun removeNotify() {
  timer.stop()
  super.removeNotify()
 }
}
