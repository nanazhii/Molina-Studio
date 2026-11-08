package com.molina.code.run

/**
 * Isi editor yang dikirim ke Script Execution. [sourceKey] mengenali sumber yang sama (path berkas,
 * judul tab untuk tab tanpa berkas, atau `script:<id>` untuk tab hasil EDIT dari Settings) sehingga
 * mengirim ulang memperbarui entri lama, bukan menggandakan.
 */
class ScriptShare(val title: String, val sourceKey: String, val text: String)

/** Hasil kiriman; [message] siap tampil untuk Toast, [ok] true bila skrip tersimpan/sinkron. */
class ScriptSendResult(val ok: Boolean, val message: String)

/** Dipasang :app (penyimpanan skrip di Settings). */
fun interface ScriptSink {
    fun send(share: ScriptShare): ScriptSendResult
}
