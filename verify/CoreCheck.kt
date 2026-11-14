import dev.bilimusic.core.*
fun main() {
 val a = Track("a", "A"); val b = Track("b", "B")
 check(randomQueue(listOf(a,b,a)).size==2); check(randomQueue(emptyList()).isEmpty())
 check(recommend(listOf(a,b,b),setOf("a"))==listOf(b))
 check(recordRecent(listOf("b","a"),"a",2)==listOf("a","b"))
 check(recordRecent(listOf("b","a"),"c",2)==listOf("c","b"))
 println("PASS: queue deduplication, recommendations, recent history")
}
