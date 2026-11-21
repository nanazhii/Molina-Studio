// Stub androidx.compose.runtime: state sederhana (tanpa snapshot), cukup untuk menguji logika ExplorerActions.
package androidx.compose.runtime

import kotlin.reflect.KProperty

class MutableState<T>(var value: T)

fun <T> mutableStateOf(value: T): MutableState<T> = MutableState(value)

operator fun <T> MutableState<T>.getValue(thisRef: Any?, property: KProperty<*>): T = value
operator fun <T> MutableState<T>.setValue(thisRef: Any?, property: KProperty<*>, v: T) { value = v }
