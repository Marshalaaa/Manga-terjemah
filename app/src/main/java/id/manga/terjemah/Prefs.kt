package id.manga.terjemah

import android.content.Context

/** Pengaturan yang disimpan di HP. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Daftar nama/istilah, satu per baris: "ヒカリ = Hikari". */
    fun glosarium(c: Context): String = sp(c).getString("glosarium", "") ?: ""

    fun saveGlosarium(c: Context, raw: String) {
        sp(c).edit().putString("glosarium", raw).apply()
    }
}
