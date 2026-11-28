// Stub androidx.core.content.ContextCompat: hasil izin dikendalikan uji lewat [result].
package androidx.core.content

import android.content.Context

object ContextCompat {
    @JvmStatic var result: Int = 0
    @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = result
}
