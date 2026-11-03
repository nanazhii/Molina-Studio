package com.molina.vault.crypto

object PasswordPolicy {
    /** Master password: >= 14 karakter, atau >= 5 kata; minimal 8 karakter unik. */
    fun isAcceptable(p: CharArray): Boolean {
        val words = String(p).trim().split(Regex("\\s+")).size
        val longEnough = p.size >= 14 || (words >= 5 && p.size >= 12)
        return longEnough && p.toSet().size >= 8
    }
}
