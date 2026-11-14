package dev.bilimusic.core
import kotlin.random.Random
data class Track(val id:String, val title:String, val creator:String="", val uri:String="")
fun randomQueue(tracks:List<Track>, random:Random=Random.Default):List<Track> = tracks.distinctBy { it.id }.shuffled(random)
fun recommend(candidates:List<Track>, savedIds:Set<String>, count:Int=10):List<Track> = randomQueue(candidates.filter { it.id !in savedIds }).take(count.coerceAtLeast(0))
fun recordRecent(ids:List<String>, id:String, limit:Int=500):List<String> = (listOf(id)+ids.filterNot { it==id }).take(limit.coerceAtLeast(0))
