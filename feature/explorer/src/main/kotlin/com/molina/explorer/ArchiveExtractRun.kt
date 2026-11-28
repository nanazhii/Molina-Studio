package com.molina.explorer

import com.molina.core.fileops.ExtractResult
import com.molina.core.fileops.FileOps

// [ARCHIVE-DEST-2] Satu jalur untuk semua ekstrak: jalankan [extract], simpan [ExtractResult], lalu tampilkan dialog hasil
// (jumlah berkas + lokasi lengkap). Galat sandi diarahkan ke [onPassword]. Dipakai W7SheetHost dan menu Alat.

internal fun runExtractWithResult(
    run: ((FileOps.Ctl) -> String, () -> Unit, (Throwable) -> Unit) -> Unit,
    onSheet: (W7Sheet) -> Unit,
    say: (String, Boolean) -> Unit,
    onPassword: (wrong: Boolean) -> Unit,
    extract: (FileOps.Ctl) -> ExtractResult,
) {
    val res = arrayOfNulls<ExtractResult>(1)
    run(
        { ctl -> res[0] = extract(ctl); "" }, // teks kosong: toast disembunyikan, hasil tampil di dialog
        { res[0]?.let { onSheet(W7Sheet.ExtractDone(it)) } },
        {
            when (it) {
                is FileOps.NeedPassword -> { say("", false); onPassword(false) }
                is FileOps.WrongPassword -> { say("", false); onPassword(true) }
                else -> Unit
            }
        }
    )
}
