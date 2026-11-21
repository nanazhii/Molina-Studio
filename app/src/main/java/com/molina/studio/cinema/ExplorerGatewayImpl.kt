package com.molina.studio.cinema

import android.content.Context
import com.molina.core.api.ExplorerGateway
import com.molina.core.api.ExplorerPickKind
import com.molina.core.api.ExplorerRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** [EXPLORER-GW] Implementasi kontrak [ExplorerGateway]: menyimpan satu permintaan aktif untuk dirender [ExplorerHost]. */
class ExplorerGatewayImpl(context: Context) : ExplorerGateway {
    private val app = context.applicationContext
    private val state = MutableStateFlow<ExplorerRequest?>(null)
    private val seq = AtomicLong(0)

    override val request: StateFlow<ExplorerRequest?> get() = state.asStateFlow()

    override fun pick(kind: ExplorerPickKind, startDir: File?, onResult: (File?) -> Unit): Long {
        val id = seq.incrementAndGet()
        val replaced = state.getAndUpdate { ExplorerRequest(id, kind, startDir, onResult) }
        replaced?.onResult?.invoke(null)
        return id
    }

    override fun lastFolder(): File? = ExplorerRecent.last(app)

    override fun complete(id: Long, result: File?) {
        var done: ExplorerRequest? = null
        state.update { cur ->
            if (cur != null && cur.id == id) { done = cur; null } else { done = null; cur }
        }
        done?.onResult?.invoke(result)
    }
}
