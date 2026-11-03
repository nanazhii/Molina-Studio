package android.content

// MOLINA-H11-GLUE
// Stub uji JVM: hanya permukaan Context/SharedPreferences yang dipakai VaultStore dan VaultPrefs.
import java.io.File

interface SharedPreferences {
    fun getLong(key: String, def: Long): Long
    fun getInt(key: String, def: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putLong(key: String, v: Long): Editor
        fun putInt(key: String, v: Int): Editor
        fun apply()
    }
}

open class Context(private val noBackup: File) {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext: Context get() = this
    val noBackupFilesDir: File get() = noBackup
    private val store = HashMap<String, Any>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = object : SharedPreferences {
        override fun getLong(key: String, def: Long) = (store["$name/$key"] as? Long) ?: def
        override fun getInt(key: String, def: Int) = (store["$name/$key"] as? Int) ?: def
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putLong(key: String, v: Long) = also { store["$name/$key"] = v }
            override fun putInt(key: String, v: Int) = also { store["$name/$key"] = v }
            override fun apply() {}
        }
    }
}
