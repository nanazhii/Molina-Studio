package com.molina.vault.crypto

import java.util.BitSet

object PasswordPolicy {
    /** >= 14 karakter, atau >= 5 kata (min 12 karakter); minimal 8 karakter unik. Tanpa membuat String. */
    fun isAcceptable(p: CharArray): Boolean {
        var words = 0
        var inWord = false
        val seen = BitSet(65536)
        for (ch in p) {
            seen.set(ch.code)
            if (ch.isWhitespace()) inWord = false
            else if (!inWord) { inWord = true; words++ }
        }
        val longEnough = p.size >= 14 || (words >= 5 && p.size >= 12)
        return longEnough && seen.cardinality() >= 8
    }
}
