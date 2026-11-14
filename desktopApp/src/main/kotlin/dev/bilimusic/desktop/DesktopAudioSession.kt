package dev.bilimusic.desktop

import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

internal class DesktopAudioSession(
 private val startAt: Long = 0,
 initiallyPaused: Boolean = false,
 initialVolume: Int = 100
) {
 private val lock = Object()
 @Volatile private var cancelled = false
 @Volatile private var paused = initiallyPaused
 @Volatile var volume: Int = initialVolume
 @Volatile private var line: SourceDataLine? = null
 @Volatile private var decoder: Process? = null
 val seconds: Long get() = startAt + (line?.longFramePosition ?: 0) / 44100
 val isPaused: Boolean get() = paused

 fun togglePause() = synchronized(lock) {
  if (cancelled) return@synchronized
  paused = !paused
  if (paused) line?.stop() else line?.start()
  lock.notifyAll()
 }

 fun cancel() = synchronized(lock) {
  cancelled = true
  decoder?.destroyForcibly()
  line?.close()
  lock.notifyAll()
 }

 fun play(audio: BiliAudio, started: () -> Unit) {
  val command = mutableListOf(findFfmpeg(), "-nostdin", "-hide_banner", "-loglevel", "error",
   "-rw_timeout", "15000000", "-referer", "https://www.bilibili.com/",
   "-user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
  val proxy = ProxySelector.getDefault()?.select(audio.url)?.firstOrNull()
  if (proxy?.type() == Proxy.Type.HTTP) {
   val address = proxy.address() as? InetSocketAddress
   if (address != null) command += listOf("-http_proxy", java.net.URI("http", null, address.hostString, address.port, null, null, null).toString())
  }
  if (startAt > 0) command += listOf("-ss", "$startAt")
  command += listOf("-i", audio.url.toString(), "-vn", "-ac", "2", "-ar", "44100", "-f", "s16le", "pipe:1")
  val format = AudioFormat(44100f, 16, 2, true, false)
  val output = AudioSystem.getSourceDataLine(format)
  try {
   synchronized(lock) {
    if (cancelled) return
    output.open(format, 4096)
    line = output
    if (!paused) output.start()
    decoder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
   }
   val process = decoder ?: return
   var played = false
   process.inputStream.use { input ->
    val buffer = ByteArray(4096)
    while (!cancelled) {
     synchronized(lock) {
      while (paused && !cancelled) lock.wait()
     }
     if (cancelled) break
     val count = input.readNBytes(buffer, 0, buffer.size).let { it - it % 4 }
     if (count < 4) break
     val gain = volume.coerceIn(0, 100)
     if (gain != 100) applyVolume(buffer, count, gain)
     var offset = 0
     while (offset < count && !cancelled) {
      val written = synchronized(lock) {
       while (paused && !cancelled) lock.wait()
       if (cancelled) 0 else {
        val available = minOf(output.available(), count - offset).let { it - it % 4 }
        if (available == 0) { lock.wait(8); 0 } else output.write(buffer, offset, available)
       }
      }
      offset += written
      if (written > 0 && !played && !cancelled) {
       played = true
       started()
      }
     }
    }
   }
   if (!cancelled) {
    check(process.waitFor() == 0 && played) { "无法解码 B 站音轨，请重试或选择其他曲目" }
    output.drain()
   }
  } finally {
   cancel()
   output.close()
  }
 }

 private fun applyVolume(buffer: ByteArray, size: Int, gain: Int) {
  for (i in 0 until size step 2) {
   val sample = (buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)
   val scaled = sample * gain / 100
   buffer[i] = scaled.toByte()
   buffer[i + 1] = (scaled shr 8).toByte()
  }
 }

 private fun findFfmpeg(): String {
  val configured = System.getenv("BILIMUSIC_FFMPEG")?.takeIf { File(it).isFile }
  if (configured != null) return configured
  val bundled = System.getProperty("bilimusic.ffmpeg")?.let(::File)?.takeIf(File::isFile)
  if (bundled != null) return bundled.absolutePath
  val executable = if (System.getProperty("os.name").startsWith("Windows")) "ffmpeg.exe" else "ffmpeg"
  return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
   .map { File(it.trim('"'), executable) }.firstOrNull(File::isFile)?.absolutePath
   ?: error("找不到 FFmpeg，请安装后将它加入 PATH")
 }
}
