// Stub minimal kotlinx.coroutines.flow untuk uji JVM murni (ShieldPolicy memakai StateFlow). Hanya dipakai di scripts/.
package kotlinx.coroutines.flow

interface StateFlow<out T> { val value: T }
interface MutableStateFlow<T> : StateFlow<T> { override var value: T }

private class StateImpl<T>(override var value: T) : MutableStateFlow<T>

@Suppress("FunctionName")
fun <T> MutableStateFlow(value: T): MutableStateFlow<T> = StateImpl(value)

inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) { value = function(value) }
fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = this

interface MutableSharedFlow<T> { fun tryEmit(value: T): Boolean }
@Suppress("FunctionName")
fun <T> MutableSharedFlow(replay: Int = 0, extraBufferCapacity: Int = 0): MutableSharedFlow<T> =
    object : MutableSharedFlow<T> { override fun tryEmit(value: T) = true }
