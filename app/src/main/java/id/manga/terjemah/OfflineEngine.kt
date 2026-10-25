package id.manga.terjemah

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OCR + terjemahan ke Indonesia, semuanya di HP (Google ML Kit).
 * Bahasa sumber: Jepang (manga, termasuk teks vertikal) atau Korea (Weverse, webtoon, dll).
 *
 * Masalah utama ML Kit di manga: satu balon berisi teks vertikal dipecah jadi banyak potongan
 * (per kolom, kadang per huruf), lalu tiap potongan diterjemahkan sendiri-sendiri -> hasilnya aneh.
 * Di sini potongan-potongan itu digabung dulu per balon, diurutkan sesuai cara baca
 * (kolom kanan -> kiri, atas -> bawah), lalu balon vertikal dibaca ulang dengan resolusi lebih besar.
 * Baru setelah itu satu kalimat utuh diterjemahkan.
 */
class OfflineEngine(private val lang: String = Prefs.LANG_JA) {

    private val korean = lang == Prefs.LANG_KO

    private enum class Orient { V, H, N } // vertikal, horizontal, netral (1 huruf / kotak)

    private class Frag(val rect: Rect, val text: String) {
        val orient: Orient = when {
            rect.height() > rect.width() * 1.6f -> Orient.V
            rect.width() > rect.height() * 1.6f -> Orient.H
            else -> Orient.N
        }
        val charSize: Int get() = max(1, min(rect.width(), rect.height()))
    }

    private val recognizer = if (korean) {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    } else {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val translator = newTranslator(lang)

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    /** @param exclude area yang diabaikan (tombol mengambang kita sendiri). */
    suspend fun translate(screen: Bitmap, exclude: Rect?): List<TextBox> {
        val result = recognizer.process(InputImage.fromBitmap(screen, 0)).await()
        val frags = toFrags(result, exclude, 0, 0, 1f)

        val out = mutableListOf<TextBox>()
        for (group in groupFrags(frags)) {
            val rect = union(group)
            var jp = readGroup(group)

            // Balon vertikal (manga Jepang): baca ulang potongan gambarnya dalam ukuran lebih besar.
            if (!korean && isVertical(group)) {
                val again = runCatching { reocr(screen, rect, group) }.getOrNull()
                if (again != null && sourceCount(again) >= sourceCount(jp)) jp = again
            }

            jp = cleanup(jp)
            if (!isMostlySource(jp)) continue // lewati tombol/UI situs, angka, huruf tunggal
            if (!korean && isSoundEffect(jp, screen, rect, group)) continue // efek suara dibiarkan asli
            val id = cache[jp] ?: translateText(jp).also { cache[jp] = it }
            if (id.isNotEmpty()) out += TextBox(rect, id)
        }
        return dedupe(out)
    }

    // ---------------- Potongan OCR ----------------

    private fun toFrags(result: Text, exclude: Rect?, offX: Int, offY: Int, scale: Float): List<Frag> {
        val frags = mutableListOf<Frag>()
        for (block in result.textBlocks) for (line in block.lines) {
            val b = line.boundingBox ?: continue
            val t = line.text.trim()
            if (t.isEmpty()) continue
            val r = Rect(
                offX + (b.left / scale).toInt(), offY + (b.top / scale).toInt(),
                offX + (b.right / scale).toInt(), offY + (b.bottom / scale).toInt()
            )
            if (exclude != null && Rect.intersects(r, exclude)) continue
            frags += Frag(r, t)
        }
        return frags
    }

    /** Gabungkan potongan yang berdekatan menjadi satu balon (union-find). */
    private fun groupFrags(frags: List<Frag>): List<List<Frag>> {
        val parent = IntArray(frags.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in frags.indices) for (j in i + 1 until frags.size) {
            if (shouldMerge(frags[i], frags[j])) parent[find(i)] = find(j)
        }
        return frags.indices.groupBy { find(it) }.values.map { idx -> idx.map { frags[it] } }
    }

    private fun shouldMerge(a: Frag, b: Frag): Boolean {
        val hGap = max(0, max(a.rect.left, b.rect.left) - min(a.rect.right, b.rect.right))
        val vGap = max(0, max(a.rect.top, b.rect.top) - min(a.rect.bottom, b.rect.bottom))
        val hOverlap = min(a.rect.right, b.rect.right) - max(a.rect.left, b.rect.left)
        val vOverlap = min(a.rect.bottom, b.rect.bottom) - max(a.rect.top, b.rect.top)
        val size = (a.charSize + b.charSize) / 2f

        return when {
            // Dua kolom vertikal bersebelahan dalam satu balon
            a.orient == Orient.V && b.orient == Orient.V ->
                hGap <= size * 0.9f && vOverlap > min(a.rect.height(), b.rect.height()) * 0.2f
            // Dua baris horizontal bertumpuk dalam satu kotak
            a.orient == Orient.H && b.orient == Orient.H ->
                vGap <= size * (if (korean) 0.5f else 0.8f) &&
                    hOverlap > min(a.rect.width(), b.rect.width()) * 0.2f &&
                    (!korean || abs(a.rect.left - b.rect.left) <= size * 2f) // baris 1 paragraf rata kiri
            // Huruf tunggal / potongan kecil: gabung kalau menempel
            else -> hGap <= size * 0.7f && vGap <= size * 0.7f
        }
    }

    private fun isVertical(group: List<Frag>): Boolean {
        if (korean) return false // Korea di layar HP hampir selalu horizontal
        val v = group.count { it.orient == Orient.V }
        val h = group.count { it.orient == Orient.H }
        return v > h || (v == 0 && h == 0 && union(group).let { it.height() > it.width() * 1.2f })
    }

    /** Urutan baca: vertikal = kolom kanan->kiri, tiap kolom atas->bawah; horizontal = atas->bawah. */
    private fun readGroup(group: List<Frag>): String {
        if (!isVertical(group)) {
            // Korea memakai spasi antar kata, jadi antar baris diberi spasi.
            return group.sortedWith(compareBy<Frag>({ it.rect.top / max(1, it.charSize) }, { it.rect.left }))
                .joinToString(if (korean) " " else "") { it.text }
        }
        val sorted = group.sortedByDescending { it.rect.centerX() }
        val columns = mutableListOf<MutableList<Frag>>()
        for (f in sorted) {
            val col = columns.lastOrNull()
            if (col != null && abs(col.first().rect.centerX() - f.rect.centerX()) < f.charSize * 0.6f) {
                col += f
            } else {
                columns += mutableListOf(f)
            }
        }
        return columns.joinToString("") { col -> col.sortedBy { it.rect.top }.joinToString("") { it.text } }
    }

    /** Potong area balon, perbesar (huruf kecil jadi lebih terbaca), beri margin putih, OCR ulang. */
    private suspend fun reocr(screen: Bitmap, rect: Rect, group: List<Frag>): String? {
        val avgCol = group.map { it.charSize }.average().toFloat()
        val pad = (avgCol * 0.4f).toInt()
        val crop = Rect(
            max(0, rect.left - pad), max(0, rect.top - pad),
            min(screen.width, rect.right + pad), min(screen.height, rect.bottom + pad)
        )
        if (crop.width() < 8 || crop.height() < 8) return null

        val scale = (64f / avgCol).coerceIn(1f, 3f)
        val margin = 24
        val w = (crop.width() * scale).toInt() + margin * 2
        val h = (crop.height() * scale).toInt() + margin * 2
        val src = Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height())
        val scaled = Bitmap.createScaledBitmap(src, w - margin * 2, h - margin * 2, true)
        val canvasBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(canvasBmp).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, margin.toFloat(), margin.toFloat(), null)
        }
        try {
            val res = recognizer.process(InputImage.fromBitmap(canvasBmp, 0)).await()
            val frags = toFrags(res, null, 0, 0, 1f)
            if (frags.isEmpty()) return null
            return readGroup(frags)
        } finally {
            if (src !== screen) src.recycle()
            if (scaled !== src) scaled.recycle()
            canvasBmp.recycle()
        }
    }

    // ---------------- Penyaring & perapian ----------------

    private fun sourceCount(s: String) = s.count { isSourceChar(it, lang) }

    /**
     * Jepang: minimal separuh huruf Jepang. Korea: lebih longgar (minimal sepertiga), karena
     * postingan/komentar sering bercampur kata Inggris, angka, dan emoji.
     */
    private fun isMostlySource(s: String): Boolean {
        val core = s.filter { !it.isWhitespace() && it !in PUNCT && !Character.isSurrogate(it) }
        val n = sourceCount(core)
        return n >= 2 && n * (if (korean) 3 else 2) >= core.length
    }

    /**
     * Terjemahan ML Kit sering kacau untuk slang chat Korea (ㅋㅋㅋ, ㅎㅎ, ㅠㅠ), jadi slang itu
     * dicopot dulu, kalimatnya diterjemahkan, lalu padanan Indonesianya ditempel lagi di akhir.
     */
    private suspend fun translateText(src: String): String {
        if (!korean) return translator.translate(src).await().trim()
        val tails = mutableListOf<String>()
        if (Regex("[ㅋ]{2,}").containsMatchIn(src)) tails += "wkwk"
        if (Regex("[ㅎ]{2,}").containsMatchIn(src)) tails += "hehe"
        if (Regex("[ㅠㅜ]{2,}").containsMatchIn(src)) tails += "ㅠㅠ"
        val body = src.replace(Regex("[ㅋㅎㅠㅜ]{2,}"), " ").replace(Regex("\\s+"), " ").trim()
        val translated = if (body.count { containsKorean(it.toString()) } >= 1) {
            translator.translate(body).await().trim()
        } else {
            body // sisa tanpa huruf Korea (emoji, angka) dibiarkan
        }
        return (listOf(translated) + tails).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Efek suara (ドドド, ガシャン, ザワ…) biasanya: pendek, hanya katakana, dan digambar langsung
     * di atas gambar — bukan di dalam balon putih. Dialog katakana pendek di balon putih (ウソ！)
     * tetap diterjemahkan karena latar belakangnya putih.
     */
    private fun isSoundEffect(jp: String, screen: Bitmap, rect: Rect, group: List<Frag>): Boolean {
        val core = jp.filter { !it.isWhitespace() && it !in PUNCT || it == 'ー' }
        if (core.isEmpty() || core.length > 8) return false
        val katakanaOnly = core.all { it in '゠'..'ヿ' || it in 'ｦ'..'ﾟ' }
        if (!katakanaOnly) return false
        return whiteRatio(screen, rect, group) < 0.5f
    }

    /** Fraksi piksel terang di sekitar teks (balon dialog = mayoritas putih). */
    private fun whiteRatio(screen: Bitmap, rect: Rect, group: List<Frag>): Float {
        val pad = (group.map { it.charSize }.average() * 0.5).toInt()
        val l = max(0, rect.left - pad)
        val t = max(0, rect.top - pad)
        val r = min(screen.width - 1, rect.right + pad)
        val b = min(screen.height - 1, rect.bottom + pad)
        if (r <= l || b <= t) return 1f
        var white = 0
        var total = 0
        val n = 16
        for (iy in 0 until n) for (ix in 0 until n) {
            val x = l + (r - l) * ix / (n - 1)
            val y = t + (b - t) * iy / (n - 1)
            val c = screen.getPixel(x, y)
            val lum = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
            if (lum > 200) white++
            total++
        }
        return white.toFloat() / total
    }

    private fun cleanup(s: String): String = if (korean) {
        s.replace(Regex("\\s+"), " ").trim()
    } else s
        .replace("\n", "")
        .replace(Regex("[.。・]{2,}|…+"), "…")
        .replace('!', '！')
        .replace('?', '？')
        .trim()

    /** Buang kotak yang sebagian besar tertimpa kotak lain yang lebih besar. */
    private fun dedupe(boxes: List<TextBox>): List<TextBox> {
        val sorted = boxes.sortedByDescending { it.rect.width() * it.rect.height() }
        val keep = mutableListOf<TextBox>()
        for (b in sorted) {
            val area = max(1, b.rect.width() * b.rect.height())
            val covered = keep.any { k ->
                val i = Rect()
                i.setIntersect(k.rect, b.rect) && i.width() * i.height() > area * 0.5f
            }
            if (!covered) keep += b
        }
        return keep
    }

    private fun union(group: List<Frag>): Rect {
        val r = Rect(group.first().rect)
        for (f in group) r.union(f.rect)
        return r
    }

    fun close() {
        recognizer.close()
        translator.close()
    }

    companion object {
        private const val PUNCT = "!?！？…・。、，,.~〜ー－-ｰ\"'「」『』()（）【】♡♪★☆"

        private fun mlkitLang(lang: String) =
            if (lang == Prefs.LANG_KO) TranslateLanguage.KOREAN else TranslateLanguage.JAPANESE

        private fun newTranslator(lang: String): Translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(mlkitLang(lang))
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
        )

        /** Unduh model terjemahan bahasa sumber + Indonesia (sekali saja, butuh internet). */
        suspend fun downloadModels(lang: String) {
            val t = newTranslator(lang)
            try {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } finally {
                t.close()
            }
        }

        /** Apakah model bahasa sumber & Indonesia sudah ada di HP. */
        suspend fun modelsReady(lang: String): Boolean {
            val mgr = RemoteModelManager.getInstance()
            val src = TranslateRemoteModel.Builder(mlkitLang(lang)).build()
            val id = TranslateRemoteModel.Builder(TranslateLanguage.INDONESIAN).build()
            return mgr.isModelDownloaded(src).await() && mgr.isModelDownloaded(id).await()
        }
    }
}
