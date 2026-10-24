package id.manga.terjemah

import android.graphics.Bitmap
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

/**
 * OCR Jepang + terjemahan Jepang->Indonesia, semuanya di HP (Google ML Kit).
 * Gratis dan tanpa internet setelah model terjemahan diunduh sekali.
 * Terjemahan disimpan di cache, jadi saat scroll balik hasilnya langsung muncul.
 */
class OfflineEngine {

    private val recognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val translator = newTranslator()

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    /** @param exclude area yang diabaikan (tombol mengambang kita sendiri). */
    suspend fun translate(screen: Bitmap, exclude: Rect?): List<TextBox> {
        val result = recognizer.process(InputImage.fromBitmap(screen, 0)).await()
        val out = mutableListOf<TextBox>()
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            if (exclude != null && Rect.intersects(box, exclude)) continue
            val jp = readBlock(block)
            if (!containsJapanese(jp)) continue
            val id = cache[jp] ?: translator.translate(jp).await().trim().also { cache[jp] = it }
            if (id.isNotEmpty()) out += TextBox(Rect(box), id)
        }
        return out
    }

    /**
     * Teks manga biasanya vertikal: kolom dibaca dari kanan ke kiri.
     * Kalau sebagian besar baris dalam blok berbentuk tinggi-sempit, urutkan kolom dari kanan.
     */
    private fun readBlock(block: Text.TextBlock): String {
        val lines = block.lines
        if (lines.isEmpty()) return block.text.replace("\n", "").trim()
        val verticalCount = lines.count { l ->
            val b = l.boundingBox
            b != null && b.height() > b.width() * 1.3f
        }
        val ordered = if (verticalCount * 2 > lines.size) {
            lines.sortedByDescending { it.boundingBox?.centerX() ?: 0 }
        } else {
            lines.sortedBy { it.boundingBox?.top ?: 0 }
        }
        return ordered.joinToString("") { it.text.trim() }
    }

    fun close() {
        recognizer.close()
        translator.close()
    }

    companion object {
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
