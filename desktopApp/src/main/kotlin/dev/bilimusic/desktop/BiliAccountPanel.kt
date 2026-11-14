package dev.bilimusic.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*

internal class BiliAccountPanel(
 private val surface: Color,
 private val text: Color,
 private val onPrimary: Color,
 private val makeButton: (String, () -> Unit) -> JButton,
 private val onAuthenticated: (BiliAccount) -> Unit
) : JPanel(GridBagLayout()) {
 private val client = BiliAccountClient()
 private val message = JLabel(" ", SwingConstants.CENTER)
 private val qr = JLabel("", SwingConstants.CENTER).apply {
  preferredSize = Dimension(240, 240)
 }
 private var account: BiliAccount? = null
 private var worker: SwingWorker<*, *>? = null
 private val pollTimer = Timer(2000) { poll() }.apply { isRepeats = false }
 private var code: LoginCode? = null
 private val action = makeButton("正在获取二维码…") { startLogin() }.apply {
  isEnabled = false
  preferredSize = Dimension(280, 48)
 }

 init {
  background = surface
  message.foreground = text
  message.font = Font("Microsoft YaHei UI", Font.PLAIN, 14)
  val content = JPanel(GridBagLayout()).apply { isOpaque = false }
  content.add(qr, GridBagConstraints().apply { gridy = 0 })
  content.add(message, GridBagConstraints().apply {
   gridy = 1
   insets = Insets(12, 8, 8, 8)
  })
  content.add(action, GridBagConstraints().apply { gridy = 2 })
  add(content)
 }

 val accountName: String? get() = account?.name
 val signedInAccount: BiliAccount? get() = account

 private fun setPrompt(label: String, canRetry: Boolean = false, detail: String = " ") {
  action.text = label
  action.background = if (canRetry) accent else surface
  action.foreground = if (canRetry) onPrimary else text
  action.isEnabled = canRetry
  message.text = detail
 }

 fun enter() {
  if (account == null && code == null && worker == null) startLogin()
 }

 fun restore(value: BiliAccount) {
  if (account != null) return
  leave()
  account = value
  onAuthenticated(value)
 }

 fun signOut() {
  account = null
  leave()
 }

 fun leave() {
  if (account == null) {
   pollTimer.stop()
   worker?.cancel(true)
   worker = null
   code = null
   qr.icon = null
   setPrompt("正在获取二维码…")
  }
 }

 override fun removeNotify() {
  pollTimer.stop()
  worker?.cancel(true)
  worker = null
  super.removeNotify()
 }

 private fun startLogin() {
  if (worker != null) return
  pollTimer.stop()
  code = null
  qr.icon = null
  setPrompt("正在获取二维码…")
  runRequest({ client.generate() }) { result ->
   code = result
   qr.icon = ImageIcon(renderLoginQr(result.url))
   setPrompt("请用 B 站 App 扫码登录")
   pollTimer.restart()
  }
 }

 private fun poll() {
  val currentCode = code ?: return
  runRequest({ client.poll(currentCode.key) }) { result ->
   when (result) {
    LoginPoll.Waiting, LoginPoll.Confirm -> {
     setPrompt(if (result == LoginPoll.Waiting) "请用 B 站 App 扫码登录" else "已扫码，请在手机上确认")
     pollTimer.restart()
    }
    LoginPoll.Expired -> {
     code = null
     setPrompt("重新获取二维码", canRetry = true, detail = "二维码已过期")
    }
    is LoginPoll.Success -> {
     account = result.account
     code = null
     qr.icon = null
     onAuthenticated(result.account)
    }
   }
  }
 }

 private fun <T> runRequest(request: () -> T, onSuccess: (T) -> Unit) {
  if (worker != null) return
  val task = object : SwingWorker<T, Unit>() {
   override fun doInBackground() = request()
   override fun done() {
    if (worker !== this) return
    worker = null
    try {
     onSuccess(get())
    } catch (error: Exception) {
     val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
     val detail = when (cause) {
      is java.net.UnknownHostException -> "无法解析 B 站登录域名，请检查 DNS 或系统代理"
      is java.net.SocketTimeoutException -> "连接 B 站超时，请确认网络或系统代理可用"
      is java.net.ConnectException, is java.net.SocketException -> "无法建立连接，请确认系统代理正在运行"
      is javax.net.ssl.SSLException -> "无法建立安全连接，请检查系统时间和代理设置"
      is IllegalStateException, is IllegalArgumentException -> cause.message ?: "B 站请求失败"
      else -> "B 站请求失败，请稍后重试"
     }
     if (account == null) {
      code = null
      qr.icon = null
      setPrompt("重新获取二维码", canRetry = true, detail = detail)
     }
    }
   }
  }
  worker = task
  task.execute()
 }
}



internal fun renderLoginQr(payload: String): BufferedImage {
 val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 1, 1,
  mapOf(EncodeHintType.MARGIN to 2))
 val scale = (240 / matrix.width).coerceAtLeast(1)
 val size = matrix.width * scale
 val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
 for (y in 0 until size) {
  for (x in 0 until size) {
   image.setRGB(x, y, if (matrix[x / scale, y / scale]) 0x000000 else 0xffffff)
  }
 }
 return image
}

