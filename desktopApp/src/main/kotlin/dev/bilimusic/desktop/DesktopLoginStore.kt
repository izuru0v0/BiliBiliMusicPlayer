package dev.bilimusic.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import org.json.JSONObject

internal class DesktopLoginStore(private val file: File) {
 fun save(account: BiliAccount) {
  val json = JSONObject().put("cookie", account.cookie).put("csrf", account.csrf)
  val encrypted = crypt(Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8)), "Protect")
  file.parentFile.mkdirs()
  val temporary = Files.createTempFile(file.parentFile.toPath(), "login-", ".tmp")
  try {
   Files.writeString(temporary, encrypted)
   Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
  } finally {
   Files.deleteIfExists(temporary)
  }
 }

 fun restore(client: BiliAccountClient): BiliAccount? {
  if (!file.isFile) return null
  val decoded = Base64.getDecoder().decode(crypt(file.readText(), "Unprotect"))
  val json = JSONObject(decoded.toString(Charsets.UTF_8))
  return client.restoreAccount(json.getString("cookie"), json.getString("csrf"))
 }

 fun clear() { Files.deleteIfExists(file.toPath()) }

 private fun crypt(input: String, operation: String): String {
  val command = "Add-Type -AssemblyName System.Security; " +
   "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$operation(" +
   "[Convert]::FromBase64String([Console]::In.ReadToEnd()), " +
   "\$null, [Security.Cryptography.DataProtectionScope]::CurrentUser))"
  val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
   .redirectError(ProcessBuilder.Redirect.DISCARD).start()
  process.outputStream.bufferedWriter(Charsets.US_ASCII).use { it.write(input) }
  val result = process.inputStream.bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
  check(process.waitFor() == 0 && result.isNotEmpty()) { "无法访问 Windows 登录凭据存储" }
  return result
 }
}
