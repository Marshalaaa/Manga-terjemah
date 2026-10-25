package id.manga.terjemah

import android.content.Context

/** Pengaturan yang disimpan di HP. */
object Prefs {
    const val MODE_OFFLINE = "offline"
    const val MODE_GEMINI = "gemini"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"

    /** Bahasa sumber: Jepang (manga) atau Korea (Weverse, webtoon, dll). */
    const val LANG_JA = "ja"
    const val LANG_KO = "ko"

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun mode(c: Context): String = sp(c).getString("mode", MODE_OFFLINE) ?: MODE_OFFLINE
    fun lang(c: Context): String =
        if (sp(c).getString("lang", LANG_JA) == LANG_KO) LANG_KO else LANG_JA
    fun geminiKey(c: Context): String = sp(c).getString("gemini_key", "") ?: ""
    fun geminiModel(c: Context): String =
        (sp(c).getString("gemini_model", DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL)
            .trim().ifBlank { DEFAULT_GEMINI_MODEL }

    fun save(c: Context, mode: String, lang: String, geminiKey: String, geminiModel: String) {
        sp(c).edit()
            .putString("mode", mode)
            .putString("lang", lang)
            .putString("gemini_key", geminiKey.trim())
            .putString("gemini_model", geminiModel.trim())
            .apply()
    }
}
