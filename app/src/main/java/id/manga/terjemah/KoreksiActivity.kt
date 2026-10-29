package id.manga.terjemah

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar Koreksi: dibuka dengan menekan lama tombol 訳 (atau dari layar utama).
 * Atas: terjemahan yang sedang tampil, bisa langsung dibetulkan.
 * Bawah: semua koreksi yang sudah tersimpan, bisa dihapus.
 */
class KoreksiActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: LinearLayout
    private val edits = mutableListOf<Triple<String, String, EditText>>() // (jp, teks awal, input)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        scope.launch {
            withContext(Dispatchers.IO) { Kamus.ensureLoaded(applicationContext) }
            build()
        }
    }

    override fun onResume() {
        super.onResume()
        TranslatorService.uiOpen = true
    }

    override fun onPause() {
        TranslatorService.uiOpen = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build() {
        root.removeAllViews()
        edits.clear()

        root.addView(TextView(this).apply {
            text = "Koreksi terjemahan"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E8453C"))
        })
        root.addView(note(
            "Betulkan terjemahan yang kurang pas. Kalau kalimat Jepang yang sama muncul lagi, " +
                "terjemahanmu yang dipakai. Kosongkan kotaknya untuk kembali ke terjemahan otomatis."
        ))

        // ---- Terjemahan di layar sekarang ----
        root.addView(header("Di layar tadi"))
        val items = TranslatorService.lastShown
            .filter { it.source.isNotBlank() }
            .distinctBy { it.source }
        if (items.isEmpty()) {
            root.addView(note(
                "Belum ada terjemahan. Buka manga, tunggu sampai terjemahan muncul, " +
                    "lalu tekan lama tombol 訳."
            ))
        } else {
            for (b in items) {
                root.addView(TextView(this).apply {
                    text = b.source
                    textSize = 17f
                    setPadding(0, dp(12), 0, 0)
                    setTextIsSelectable(true)
                })
                val current = Kamus.koreksiUntuk(b.source) ?: b.text
                val input = EditText(this).apply {
                    setText(current)
                    textSize = 15f
                }
                root.addView(input)
                edits += Triple(b.source, current, input)
            }
            root.addView(button("Simpan koreksi") { save() })
        }

        // ---- Semua koreksi tersimpan ----
        val all = Kamus.daftarKoreksi()
        root.addView(header("Koreksi tersimpan (${all.size})"))
        if (all.isEmpty()) {
            root.addView(note("Belum ada."))
        } else {
            for ((key, jp, id) in all.take(300)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                row.addView(TextView(this).apply {
                    text = "$jp\n→ $id"
                    textSize = 14f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this).apply {
                    text = "Hapus"
                    isAllCaps = false
                    setOnClickListener {
                        Kamus.hapusKoreksi(this@KoreksiActivity, key)
                        build()
                    }
                })
                root.addView(row)
            }
            if (all.size > 300) root.addView(note("…dan ${all.size - 300} lainnya."))
            root.addView(button("Hapus semua koreksi") {
                AlertDialog.Builder(this)
                    .setMessage("Hapus semua ${all.size} koreksi?")
                    .setPositiveButton("Hapus") { _, _ ->
                        Kamus.hapusSemuaKoreksi(this)
                        build()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            })
        }

        root.addView(button("Tutup") { finish() })
    }

    private fun save() {
        var n = 0
        for ((jp, before, input) in edits) {
            val after = input.text.toString().trim()
            if (after == before.trim()) continue
            Kamus.setKoreksi(this, jp, after)
            n++
        }
        Toast.makeText(
            this, if (n == 0) "Tidak ada yang diubah" else "$n koreksi disimpan", Toast.LENGTH_SHORT
        ).show()
        if (n > 0) finish()
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
