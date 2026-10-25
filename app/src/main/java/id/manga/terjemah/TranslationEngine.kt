package id.manga.terjemah

import android.graphics.Rect

/** Satu area teks di layar beserta terjemahannya (koordinat piksel layar). */
data class TextBox(val rect: Rect, val text: String)

private val JAPANESE_CHARS = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF66-\\uFF9F]")

/** Hangul: suku kata lengkap + jamo (termasuk ㅋ, ㅎ, ㅠ, ㅜ). */
private val KOREAN_CHARS = Regex("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]")

fun containsJapanese(s: String): Boolean = JAPANESE_CHARS.containsMatchIn(s)

fun containsKorean(s: String): Boolean = KOREAN_CHARS.containsMatchIn(s)

/** Apakah karakter ini termasuk huruf bahasa sumber yang dipilih. */
fun isSourceChar(c: Char, lang: String): Boolean =
    if (lang == Prefs.LANG_KO) containsKorean(c.toString()) else containsJapanese(c.toString())
