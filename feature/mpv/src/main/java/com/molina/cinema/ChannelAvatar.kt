package com.molina.cinema

/**
 * [MOLINA-AVATAR] Monogram bulat per channel: inisial + warna deterministik dari nama.
 * Sengaja offline (tanpa tvg-logo / request ke host pihak ketiga).
 */
object ChannelAvatar {
    private val palette = intArrayOf(
        0xFF2F3E57.toInt(), 0xFF3B3350.toInt(), 0xFF264B4A.toInt(), 0xFF4A3A2C.toInt(),
        0xFF2E4A3A.toInt(), 0xFF4A2F3C.toInt(), 0xFF3A3F55.toInt(), 0xFF44402A.toInt()
    )

    fun initials(name: String): String {
        val words = name.split(' ', '-', '_', '.').map { w -> w.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "\u2022"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "${words[0][0]}${words[1][0]}".uppercase()
        }
    }

    fun colorFor(name: String): Int =
        palette[(name.trim().lowercase().hashCode() and Int.MAX_VALUE) % palette.size]
}
