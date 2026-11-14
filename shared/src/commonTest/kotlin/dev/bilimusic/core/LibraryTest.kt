package dev.bilimusic.core
import kotlin.test.*
class LibraryTest {
 private val a = Track("a", "A")
 private val b = Track("b", "B")
 @Test fun queueDeduplicates() { assertEquals(2, randomQueue(listOf(a,b,a)).size); assertTrue(randomQueue(emptyList()).isEmpty()) }
 @Test fun recommendationExcludesSaved() { assertEquals(listOf(b), recommend(listOf(a,b,b),setOf("a"))) }
 @Test fun recentMovesToTopAndLimits() { assertEquals(listOf("a","b"), recordRecent(listOf("b","a"),"a",2)); assertEquals(listOf("c","b"),recordRecent(listOf("b","a"),"c",2)) }
}
