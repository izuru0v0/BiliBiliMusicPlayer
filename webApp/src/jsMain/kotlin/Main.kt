import dev.bilimusic.core.Track
import dev.bilimusic.core.VideoLibrary
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import org.w3c.dom.events.Event

fun main() {
    val list = document.getElementById("library")!!
    val recentList = document.getElementById("recent")!!
    val status = document.getElementById("status")!!
    val frame = document.getElementById("player") as HTMLIFrameElement
    val input = document.getElementById("video") as HTMLInputElement
    val title = document.getElementById("title") as HTMLInputElement
    val tracks = linkedMapOf<String, Track>()
    var recent = emptyList<String>()
    runCatching {
        val stored = window.localStorage.getItem("bilimusic.tracks").orEmpty()
        stored.lineSequence().filter { it.contains('|') }.forEach {
            val track = VideoLibrary.track(it.substringBefore('|'), it.substringAfter('|'))
            if (track != null) tracks[track.id] = track
        }
        recent = window.localStorage.getItem("bilimusic.recent").orEmpty().split(',').filter { it in tracks }
    }

    fun save() {
        runCatching {
            window.localStorage.setItem("bilimusic.tracks", tracks.values.joinToString("\n") {
                "${it.id}|${it.title.replace('\n', ' ').replace('\r', ' ')}"
            })
            window.localStorage.setItem("bilimusic.recent", recent.joinToString(","))
        }.onFailure { status.textContent = "浏览器存储不可用" }
    }

    fun play(track: Track) {
        frame.src = track.uri
        frame.hidden = false
        recent = VideoLibrary.recent(recent, track.id)
        status.textContent = track.title
        save()
    }

    fun render() {
        fun rows(target: Element, items: List<Track>, removable: Boolean) {
            target.textContent = ""
            items.forEach { track ->
                val row = document.createElement("li")
                val playButton = document.createElement("button") as HTMLButtonElement
                playButton.textContent = track.title
                playButton.onclick = { play(track); render() }
                row.appendChild(playButton)
                if (removable) {
                    val remove = document.createElement("button") as HTMLButtonElement
                    remove.textContent = "移除"
                    remove.onclick = { tracks.remove(track.id); recent = recent.filterNot { it == track.id }; save(); render() }
                    row.appendChild(remove)
                }
                target.appendChild(row)
            }
        }
        rows(list, tracks.values.toList(), true)
        rows(recentList, recent.mapNotNull(tracks::get), false)
    }

    document.getElementById("add")!!.addEventListener("submit", { event: Event ->
        event.preventDefault()
        val track = VideoLibrary.track(input.value, title.value)
        if (track == null) {
            status.textContent = "请输入 BV 号或 B 站视频链接"
        } else {
            tracks[track.id] = track
            title.value = ""
            input.value = ""
            play(track)
            render()
        }
    })
    (document.getElementById("shuffle") as HTMLButtonElement).onclick = {
        val track = VideoLibrary.shuffle(tracks.values.toList()).firstOrNull()
        if (track == null) status.textContent = "请先添加视频" else { play(track); render() }
    }
    render()
}
