// Stub android.util.LruCache (urutan akses, batas jumlah entri).
package android.util

class LruCache<K : Any, V : Any>(private val max: Int) {
    private val m = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > max
    }

    @Synchronized fun get(k: K): V? = m[k]
    @Synchronized fun put(k: K, v: V): V? = m.put(k, v)
}
