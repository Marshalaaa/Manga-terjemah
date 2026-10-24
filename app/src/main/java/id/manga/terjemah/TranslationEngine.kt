package id.manga.terjemah

import android.graphics.Rect

/** Satu area teks di layar beserta terjemahannya (koordinat piksel layar). */
data class TextBox(val rect: Rect, val text: String)

private val JAPANESE_CHARS = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF66-\\uFF9F]")

fun containsJapanese(s: String): Boolean = JAPANESE_CHARS.containsMatchIn(s)
