package id.manga.terjemah

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var overlayStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var downloadBtn: Button
    private lateinit var glosariumInput: EditText
    private lateinit var rbGemini: RadioButton
    private lateinit var rbOffline: RadioButton
    private lateinit var geminiSection: LinearLayout
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var testStatus: TextView
    private var loading = false
    private lateinit var kamusStatus: TextView
    private lateinit var startBtn: Button
    private lateinit var liveStatus: TextView
    private val statusTicker = object : Runnable {
        override fun run() {
            liveStatus.text = "Status: ${TranslatorService.lastStatus}"
            liveStatus.postDelayed(this, 1000)
        }
    }
    private lateinit var stopBtn: Button
    private var modelsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loading = true
        glosariumInput.setText(Prefs.glosarium(this))
        if (Prefs.mode(this) == Prefs.MODE_GEMINI) rbGemini.isChecked = true else rbOffline.isChecked = true
        keyInput.setText(Prefs.geminiKey(this))
        modelInput.setText(Prefs.geminiModel(this))
        updateModeUi()
        loading = false
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        TranslatorService.uiOpen = true // layar ini berisi teks Jepang, jangan ikut diterjemahkan
        refreshStatus()
        checkModels()
        refreshKamus()
        liveStatus.removeCallbacks(statusTicker)
        statusTicker.run()
    }

    override fun onPause() {
        liveStatus.removeCallbacks(statusTicker)
        saveGlosarium() // langsung dipakai penerjemah yang sedang berjalan
        saveMode()
        TranslatorService.uiOpen = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Manga Terjemah"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E8453C"))
        })
        root.addView(note(
            "Terjemahan live Jepang → Indonesia. Scroll seperti biasa; begitu kamu " +
                "berhenti sebentar, teks Jepang di layar otomatis ditimpa terjemahan."
        ))

        // Langkah 1
        root.addView(header("1. Izin tampil di atas aplikasi lain"))
        overlayStatus = note("")
        root.addView(overlayStatus)
        root.addView(button("Buka pengaturan izin") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        // Mode
        root.addView(header("Mode terjemahan"))
        rbGemini = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Online – Gemini (jauh lebih akurat, gratis dengan kuota harian)"
        }
        rbOffline = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Offline – ML Kit + kamus (tanpa internet, kualitas standar)"
        }
        root.addView(RadioGroup(this).apply {
            addView(rbGemini)
            addView(rbOffline)
            setOnCheckedChangeListener { _, _ ->
                updateModeUi()
                saveMode()
            }
        })
        geminiSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        geminiSection.addView(note(
            "Buat API key gratis di Google AI Studio (login akun Google → Create API key), " +
                "lalu tempel di bawah. Jangan bagikan key ini ke siapa pun."
        ))
        geminiSection.addView(button("Buka Google AI Studio") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        })
        keyInput = EditText(this).apply {
            hint = "Tempel API key Gemini di sini"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        geminiSection.addView(keyInput)
        geminiSection.addView(note("Model (biarkan default kalau ragu):"))
        modelInput = EditText(this).apply {
            hint = Prefs.DEFAULT_GEMINI_MODEL
            setSingleLine()
        }
        geminiSection.addView(modelInput)
        testStatus = note("")
        geminiSection.addView(button("Tes API key") { testKey() })
        geminiSection.addView(testStatus)
        root.addView(geminiSection)

        // Langkah 2
        root.addView(header("2. Model offline (cadangan)"))
        root.addView(note("Dipakai di mode offline, dan otomatis dipakai kalau kuota Gemini habis atau internet putus."))
        modelStatus = note("Memeriksa…")
        root.addView(modelStatus)
        downloadBtn = button("Unduh model (sekali saja, ±30 MB, pakai Wi-Fi)") { downloadModels() }
        root.addView(downloadBtn)

        // Langkah 3
        root.addView(header("3. Daftar nama (opsional)"))
        root.addView(note(
            "Nama karakter & istilah supaya tidak diterjemahkan jadi kata biasa (Gemini & offline). " +
                "Satu per baris, format: Jepang = Indonesia. Bisa diganti tiap ganti judul manga."
        ))
        glosariumInput = EditText(this).apply {
            hint = "ヒカリ = Hikari\n呪術 = jujutsu\n先輩 = senpai"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setSingleLine(false)
        }
        root.addView(glosariumInput)

        // Langkah 4
        root.addView(header("4. Kamus & koreksi"))
        kamusStatus = note("Memuat kamus…")
        root.addView(kamusStatus)
        root.addView(button("Lihat / kelola koreksi") {
            saveGlosarium()
            startActivity(Intent(this, KoreksiActivity::class.java))
        })

        // Langkah 5
        root.addView(header("5. Mulai"))
        root.addView(note(
            "Saat muncul dialog rekam layar, pilih \"Bagikan seluruh layar\" lalu Mulai. " +
                "Setelah itu buka Chrome dan baca manga seperti biasa."
        ))
        startBtn = button("Mulai terjemahan live") { startTranslator() }
        stopBtn = button("Hentikan") {
            stopService(Intent(this, TranslatorService::class.java))
            window.decorView.postDelayed({ refreshStatus() }, 300)
        }
        root.addView(startBtn)
        root.addView(stopBtn)
        liveStatus = note("").apply { setTextColor(Color.DKGRAY) }
        root.addView(liveStatus)

        root.addView(header("Tips"))
        root.addView(note(
            "• Ketuk 訳 untuk jeda/lanjut. Geser untuk memindahkan.\n" +
                "• Tekan lama 訳 untuk membetulkan terjemahan yang sedang tampil. Koreksimu dipakai " +
                "lagi setiap kalimat yang sama muncul.\n" +
                "• Kotak terjemahan sedikit transparan: aturan Android agar layar di bawahnya tetap bisa di-scroll.\n" +
                "• Kalau layar diputar, hentikan lalu mulai lagi."
        ))

        root.addView(note(
            "Kalimat contoh dari Tatoeba (tatoeba.org), lisensi CC BY 2.0 FR."
        ).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
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

    private fun setStatus(tv: TextView, ok: Boolean, okText: String, notText: String) {
        tv.text = if (ok) "✅ $okText" else "❌ $notText"
        tv.setTextColor(Color.parseColor(if (ok) "#2E7D32" else "#C62828"))
    }

    // ---------------- Status ----------------

    private fun refreshStatus() {
        setStatus(overlayStatus, Settings.canDrawOverlays(this), "Sudah diizinkan", "Belum diizinkan")
        val running = TranslatorService.isRunning
        startBtn.isEnabled = !running
        stopBtn.isEnabled = running
        startBtn.text = if (running) "Terjemahan live sedang berjalan" else "Mulai terjemahan live"
    }

    private fun refreshKamus() {
        scope.launch {
            withContext(Dispatchers.IO) { Kamus.ensureLoaded(applicationContext) }
            kamusStatus.text =
                "• ${Kamus.jumlahFrasa} frasa manga\n" +
                    "• ${Kamus.jumlahTatoeba} kalimat Tatoeba\n" +
                    "• ${Kamus.jumlahKoreksi} koreksi buatanmu"
        }
    }

    private fun updateModeUi() {
        geminiSection.visibility = if (rbGemini.isChecked) View.VISIBLE else View.GONE
    }

    private fun saveMode() {
        if (loading || !::keyInput.isInitialized) return
        val mode = if (rbGemini.isChecked) Prefs.MODE_GEMINI else Prefs.MODE_OFFLINE
        Prefs.saveGemini(this, mode, keyInput.text.toString(), modelInput.text.toString())
    }

    private fun testKey() {
        saveMode()
        val key = Prefs.geminiKey(this)
        if (key.isBlank()) {
            setStatus(testStatus, false, "", "API key masih kosong")
            return
        }
        testStatus.text = "Mengetes…"
        testStatus.setTextColor(Color.GRAY)
        scope.launch {
            try {
                val reply = GeminiEngine(key, Prefs.geminiModel(this@MainActivity)).test()
                setStatus(testStatus, true, "API key berfungsi. Jawaban tes: ${reply.take(60)}", "")
            } catch (e: GeminiEngine.QuotaException) {
                setStatus(testStatus, false, "", "Key benar, tapi kuota sedang habis/terbatas: ${e.message?.take(120)}")
            } catch (e: Exception) {
                setStatus(testStatus, false, "", "Gagal: ${e.message?.take(160)}")
            }
        }
    }

    private fun saveGlosarium() {
        if (::glosariumInput.isInitialized) Kamus.setGlosarium(this, glosariumInput.text.toString())
    }

    // ---------------- Model offline ----------------

    private fun checkModels() {
        scope.launch {
            modelsReady = runCatching { OfflineEngine.modelsReady() }.getOrDefault(false)
            setStatus(modelStatus, modelsReady, "Model siap (bisa dipakai tanpa internet)", "Model belum diunduh")
            downloadBtn.visibility = if (modelsReady) View.GONE else View.VISIBLE
        }
    }

    private fun downloadModels() {
        downloadBtn.isEnabled = false
        downloadBtn.text = "Mengunduh…"
        scope.launch {
            try {
                OfflineEngine.downloadModels()
                checkModels()
            } catch (e: Exception) {
                toast("Gagal mengunduh: ${e.message}")
            } finally {
                downloadBtn.isEnabled = true
                downloadBtn.text = "Unduh model (sekali saja, ±30 MB, pakai Wi-Fi)"
            }
        }
    }

    // ---------------- Mulai ----------------

    private fun startTranslator() {
        saveGlosarium()
        saveMode()
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan dulu tampil di atas aplikasi lain (langkah 1)")
            return
        }
        if (rbGemini.isChecked && keyInput.text.isBlank()) {
            toast("Isi API key Gemini dulu, atau pilih mode offline")
            return
        }
        if (!rbGemini.isChecked && !modelsReady) {
            toast("Unduh model offline dulu (langkah 2)")
            return
        }
        if (rbGemini.isChecked && !modelsReady) {
            toast("Tips: unduh juga model offline sebagai cadangan kalau kuota Gemini habis")
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Izin rekam layar ditolak")
            return
        }
        val svc = Intent(this, TranslatorService::class.java)
            .putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslatorService.EXTRA_RESULT_DATA, data)
        startForegroundService(svc)
        toast("Siap! Buka Chrome dan mulai baca")
        moveTaskToBack(true)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIF = 1002
    }
}
