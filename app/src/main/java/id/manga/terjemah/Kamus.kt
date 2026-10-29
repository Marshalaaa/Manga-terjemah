package id.manga.terjemah

import android.content.Context
import java.io.File
import java.text.Normalizer

/**
 * Kamus offline yang dipakai SEBELUM ML Kit. Urutan prioritas:
 *  1. Koreksi buatan kamu sendiri (disimpan di HP, bisa diubah lewat layar Koreksi)
 *  2. Frasa manga (assets/frasa_manga.tsv, ditulis manual)
 *  3. Kalimat Tatoeba Jepang–Indonesia (assets/tatoeba_ja_id.tsv, dibuat otomatis saat build)
 * Kalau tidak ada yang cocok, teks diterjemahkan ML Kit setelah nama di Daftar nama dikunci.
 *
 * Pencocokan dilakukan per balon utuh, setelah teks dinormalisasi (tanda baca, spasi, huruf
 * lebar/sempit, ー dobel, っ di akhir), jadi 「なんだと！？」 dan 「なんだと？」 dianggap sama.
 * Entri yang diakhiri ? di kamus hanya dipakai untuk kalimat tanya.
 */
object Kamus {

    /** Naik setiap koreksi atau daftar nama berubah -> cache terjemahan lama dibuang. */
    @Volatile
    var version = 0
        private set

    @Volatile private var frasa: Map<String, String> = emptyMap()
    @Volatile private var tatoeba: Map<String, String> = emptyMap()
    @Volatile private var koreksi: Map<String, Pair<String, String>> = emptyMap() // key -> (jp asli, id)
    @Volatile private var glosarium: List<Pair<String, String>> = emptyList()
    @Volatile private var loaded = false

    val jumlahFrasa get() = frasa.size
    val jumlahTatoeba get() = tatoeba.size
    val jumlahKoreksi get() = koreksi.size

    /** Muat semua kamus sekali saja. Panggil dari thread latar (membaca file). */
    @Synchronized
    fun ensureLoaded(c: Context) {
        if (loaded) return
        frasa = readAsset(c, "frasa_manga.tsv")
        tatoeba = readAsset(c, "tatoeba_ja_id.tsv")
        koreksi = readKoreksi(c)
        glosarium = parseGlosarium(Prefs.glosarium(c))
        loaded = true
    }

    /** Terjemahan dari kamus, atau null kalau tidak ada yang cocok. */
    fun lookup(jp: String): String? {
        val core = key(jp, keepQuestion = false)
        if (core.isEmpty()) return null
        val question = isQuestion(jp)
        val keys = if (question) listOf("$core?", core) else listOf(core)

        for (k in keys) koreksi[k]?.let { return it.second }
        for (k in keys) frasa[k]?.let { return matchPunct(jp, it) }
        for (k in keys) tatoeba[k]?.let { return matchPunct(jp, it) }
        return null
    }

    /** Ganti nama/istilah dari Daftar nama supaya tidak diterjemahkan ML Kit jadi kata biasa. */
    fun applyGlosarium(jp: String): String {
        var s = jp
        for ((from, to) in glosarium) s = s.replace(from, to)
        return s
    }

    // ---------------- Koreksi ----------------

    /** Simpan koreksi. Teks kosong = hapus koreksi (kembali ke terjemahan otomatis). */
    fun setKoreksi(c: Context, jp: String, id: String) {
        val k = key(jp, keepQuestion = true)
        if (k.isEmpty()) return
        val m = koreksi.toMutableMap()
        if (id.isBlank()) m.remove(k) else m[k] = jp to id.trim()
        koreksi = m
        writeKoreksi(c)
        version++
    }

    fun hapusKoreksi(c: Context, key: String) {
        koreksi = koreksi - key
        writeKoreksi(c)
        version++
    }

    fun hapusSemuaKoreksi(c: Context) {
        koreksi = emptyMap()
        writeKoreksi(c)
        version++
    }

    /** Daftar koreksi tersimpan: (key, jp asli, terjemahan). */
    fun daftarKoreksi(): List<Triple<String, String, String>> =
        koreksi.map { (k, v) -> Triple(k, v.first, v.second) }.sortedBy { it.second }

    /** Koreksi yang sedang berlaku untuk teks ini, kalau ada. */
    fun koreksiUntuk(jp: String): String? = koreksi[key(jp, keepQuestion = true)]?.second

    // ---------------- Daftar nama ----------------

    fun setGlosarium(c: Context, raw: String) {
        Prefs.saveGlosarium(c, raw)
        val parsed = parseGlosarium(raw)
        if (parsed != glosarium) {
            glosarium = parsed
            version++
        }
    }

    /** Baris "ヒカリ = Hikari" (pemisah =, tab, atau →). Yang panjang diganti duluan. */
    fun parseGlosarium(raw: String): List<Pair<String, String>> =
        raw.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val parts = t.split(Regex("\\s*(=|\\t|→)\\s*"), limit = 2)
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
            else parts[0].trim() to parts[1].trim()
        }.sortedByDescending { it.first.length }

    // ---------------- Normalisasi ----------------

    private val STRIP = Regex("[\\s!?.,。、・…‥「」『』()（）【】\\[\\]<>《》〈〉\"'“”‘’♡♥♪★☆※*]")

    private fun nfkc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)

    private fun isQuestion(jp: String): Boolean = nfkc(jp).trimEnd().let {
        it.endsWith("?") || it.endsWith("?!") || it.endsWith("!?")
    }

    /**
     * Kunci pencocokan. NFKC menyamakan huruf lebar/sempit (！ -> !, ｶ -> カ).
     * 〜/~ dianggap ー, ー berderet jadi satu, lalu ー dan っ/ッ di akhir dibuang (くそっ = くそ).
     */
    fun key(jp: String, keepQuestion: Boolean): String {
        val n = nfkc(jp)
        var s = n.replace(Regex("[~〜～]"), "ー").replace(STRIP, "")
        s = s.replace(Regex("ー{2,}"), "ー")
        s = s.trimEnd('ー', 'っ', 'ッ')
        if (keepQuestion && isQuestion(n) && s.isNotEmpty()) s += "?"
        return s
    }

    /** Samakan tanda seru/tanya di akhir terjemahan dengan teks aslinya. */
    private fun matchPunct(jp: String, out: String): String {
        val ends = Regex("[!?]+$").find(nfkc(jp).trimEnd())?.value ?: return out
        val marks = (if ('!' in ends) "!" else "").let { if ('?' in ends) "?$it" else it }
        val base = out.trimEnd().trimEnd('.', '!', '?', '。')
        if (base.endsWith("…")) return out
        return base + marks
    }

    // ---------------- File ----------------

    private fun readAsset(c: Context, name: String): Map<String, String> {
        val m = HashMap<String, String>()
        runCatching {
            c.assets.open(name).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val jp = line.substring(0, tab)
                    val id = line.substring(tab + 1).trim()
                    if (id.isEmpty()) continue
                    val k = key(jp, keepQuestion = true)
                    if (k.isNotEmpty() && k !in m) m[k] = id // yang pertama menang
                }
            }
        }
        return m
    }

    private fun koreksiFile(c: Context) = File(c.filesDir, "koreksi.tsv")

    private fun readKoreksi(c: Context): Map<String, Pair<String, String>> {
        val f = koreksiFile(c)
        if (!f.exists()) return emptyMap()
        val m = HashMap<String, Pair<String, String>>()
        runCatching {
            f.forEachLine(Charsets.UTF_8) { line ->
                val p = line.split('\t')
                if (p.size >= 2 && p[1].isNotBlank()) {
                    val k = key(p[0], keepQuestion = true)
                    if (k.isNotEmpty()) m[k] = p[0] to p[1]
                }
            }
        }
        return m
    }

    private fun writeKoreksi(c: Context) {
        val text = koreksi.values.joinToString("\n") { (jp, id) ->
            jp.replace('\t', ' ').replace('\n', ' ') + "\t" + id.replace('\t', ' ').replace('\n', ' ')
        }
        runCatching { koreksiFile(c).writeText(text, Charsets.UTF_8) }
    }
}
