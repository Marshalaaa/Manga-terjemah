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
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OCR Jepang + terjemahan Jepang->Indonesia, semuanya di HP (Google ML Kit).
 *
 * Masalah utama ML Kit di manga: satu balon berisi teks vertikal dipecah jadi banyak potongan
 * (per kolom, kadang per huruf), lalu tiap potongan diterjemahkan sendiri-sendiri -> hasilnya aneh.
 * Di sini potongan-potongan itu digabung dulu per balon, diurutkan sesuai cara baca
 * (kolom kanan -> kiri, atas -> bawah), lalu balon vertikal dibaca ulang dengan resolusi lebih besar.
 * Baru setelah itu satu kalimat utuh diterjemahkan.
 */
class OfflineEngine {

    private enum class Orient { V, H, N } // vertikal, horizontal, netral (1 huruf / kotak)

    private class Frag(val rect: Rect, val text: String) {
        val orient: Orient = when {
            rect.height() > rect.width() * 1.6f -> Orient.V
            rect.width() > rect.height() * 1.6f -> Orient.H
            else -> Orient.N
        }
        val charSize: Int get() = max(1, min(rect.width(), rect.height()))
    }

    private val recognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val translator = newTranslator()

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

            // Balon vertikal: baca ulang potongan gambarnya dalam ukuran lebih besar.
            if (isVertical(group)) {
                val again = runCatching { reocr(screen, rect, group) }.getOrNull()
                if (again != null && japaneseCount(again) >= japaneseCount(jp)) jp = again
            }

            jp = cleanup(jp)
            if (!isMostlyJapanese(jp)) continue // buang tombol/UI situs, angka, efek suara 1 huruf
            val id = cache[jp] ?: translator.translate(jp).await().trim().also { cache[jp] = it }
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
                vGap <= size * 0.8f && hOverlap > min(a.rect.width(), b.rect.width()) * 0.2f
            // Huruf tunggal / potongan kecil: gabung kalau menempel
            else -> hGap <= size * 0.7f && vGap <= size * 0.7f
        }
    }

    private fun isVertical(group: List<Frag>): Boolean {
        val v = group.count { it.orient == Orient.V }
        val h = group.count { it.orient == Orient.H }
        return v > h || (v == 0 && h == 0 && union(group).let { it.height() > it.width() * 1.2f })
    }

    /** Urutan baca: vertikal = kolom kanan->kiri, tiap kolom atas->bawah; horizontal = atas->bawah. */
    private fun readGroup(group: List<Frag>): String {
        if (!isVertical(group)) {
            return group.sortedWith(compareBy<Frag>({ it.rect.top / max(1, it.charSize) }, { it.rect.left }))
                .joinToString("") { it.text }
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

    private fun japaneseCount(s: String) = s.count { containsJapanese(it.toString()) }

    private fun isMostlyJapanese(s: String): Boolean {
        val core = s.filter { !it.isWhitespace() && it !in PUNCT }
        val jp = japaneseCount(core)
        return jp >= 2 && jp * 2 >= core.length
    }

    private fun cleanup(s: String): String = s
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

        private fun newTranslator(): Translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
        )

        /** Unduh model terjemahan (sekali saja, butuh internet). */
        suspend fun downloadModels() {
            val t = newTranslator()
            try {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } finally {
                t.close()
            }
        }

        /** Apakah model Jepang & Indonesia sudah ada di HP. */
        suspend fun modelsReady(): Boolean {
            val mgr = RemoteModelManager.getInstance()
            val ja = TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()
            val id = TranslateRemoteModel.Builder(TranslateLanguage.INDONESIAN).build()
            return mgr.isModelDownloaded(ja).await() && mgr.isModelDownloaded(id).await()
        }
    }
}
