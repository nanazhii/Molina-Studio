package com.molina.core.exec

import com.molina.code.preview.RunPlan
import com.molina.code.preview.RunPlans
import com.molina.code.preview.ScriptLaunch

/**
 * [DRAWER-RUN] Aturan murni (tanpa Android) untuk menu "Jalankan" di daftar berkas: berkas apa yang didukung dan
 * satu baris shell yang diketik ke sesi terminal. Interpreter dipetakan oleh [RunPlans] (satu sumber dengan
 * Script Execution), jadi .sh/.bash memakai bash, .py memakai python3, .kts memakai kotlin; shebang menang atas ekstensi.
 * Berbeda dari "Edit di nano": baris ini menjalankan berkas langsung, tidak membuka editor.
 */
object DrawerRun {
    /** Didukung bila [RunPlans] mengenali ekstensinya. Berkas build Gradle (.gradle.kts) ditolak di sana. */
    fun supports(fileName: String): Boolean = RunPlans.of(fileName, null) != null

    /**
     * Baris perintah (diakhiri CR sebagai Enter) untuk menjalankan [guestFile] dari [guestDir]. [firstLine] = baris pertama
     * berkas (untuk shebang), boleh null. Null bila jenis tidak didukung atau ada karakter kontrol pada nama/path.
     * Interpreter yang belum terpasang mencetak petunjuk, bukan gagal diam-diam. Kode keluar dan durasi dicetak di akhir.
     */
    fun commandLine(fileName: String, firstLine: String?, guestDir: String, guestFile: String): String? {
        val plan = RunPlans.of(fileName, firstLine) ?: return null
        if (hasControl(fileName) || hasControl(guestDir) || hasControl(guestFile)) return null
        val q = ScriptLaunch::shellQuote
        val need = plan.requires.ifEmpty { listOf(plan.interpreter) }
        val check = need.joinToString(" && ") { r -> "{ [ -x ${q(r)} ] || command -v ${q(r)} >/dev/null 2>&1; }" }
        val start = "printf '\\033[1;32m\u25B6 %s (%s)\\033[0m\\n' ${q(fileName)} ${q(plan.label)}; _mr_t=\$(date +%s); "
        val run = "${q(plan.interpreter)} ${q(guestFile)}"
        val tail = "; _mr_rc=\$?; _mr_d=\$((\$(date +%s)-_mr_t)); " +
            "if [ \$_mr_rc -eq 0 ]; then printf '\\033[1;32m\u2714 selesai (%s dtk)\\033[0m\\n' \$_mr_d; " +
            "elif [ \$_mr_rc -eq 130 ]; then printf '\\033[1;31m\u2718 dihentikan (Ctrl+C)\\033[0m\\n'; " +
            "else printf '\\033[1;31m\u2718 keluar dengan kode %s (%s dtk)\\033[0m\\n' \$_mr_rc \$_mr_d; fi; " +
            "unset _mr_t _mr_rc _mr_d"
        return "cd ${q(guestDir)} && if $check; then $start$run$tail; else echo ${q("[Molina] " + hint(plan))}; fi\r"
    }

    internal fun hint(plan: RunPlan): String = plan.installHint
        ?: if (plan.needsPython) "python3 belum terpasang. Pasang: apt install python3"
        else "${plan.label} tidak ditemukan di Debian."

    private fun hasControl(s: String): Boolean = s.any { it.code < 32 || it.code == 127 }
}
