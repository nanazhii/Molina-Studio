// Stub kotlinx.coroutines minimal: withContext dijalankan inline (cukup untuk menguji logika W7Thumb).
package kotlinx.coroutines

open class CoroutineDispatcher

object Dispatchers {
    val IO = CoroutineDispatcher()
}

suspend fun <T> withContext(context: CoroutineDispatcher, block: suspend () -> T): T = block()
