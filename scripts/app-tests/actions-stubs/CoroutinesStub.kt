// Stub kotlinx.coroutines: launch/withContext berjalan sinkron di thread pemanggil (cukup untuk menguji urutan logika).
package kotlinx.coroutines

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

interface CoroutineScope
class Job
object Dispatchers { val IO: Any = Any() }

fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job {
    block.startCoroutine(this, Continuation(EmptyCoroutineContext) { it.getOrThrow() })
    return Job()
}

suspend fun <T> withContext(context: Any, block: suspend CoroutineScope.() -> T): T = object : CoroutineScope {}.block()
