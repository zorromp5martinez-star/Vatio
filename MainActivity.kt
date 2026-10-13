package com.vatio.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val bgColor = Color.parseColor("#0F1519")
    private val surface = Color.parseColor("#172027")
    private val textColor = Color.parseColor("#E8F0F3")
    private val muted = Color.parseColor("#8FA3AE")
    private val accent = Color.parseColor("#3DDBC8")
    private val accentInk = Color.parseColor("#06211E")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private val tabButtons = mutableListOf<Button>()
    private val live = mutableMapOf<String, TextView>()
    private var tab = 0
    private var homeBusy = false
    private var logView: TextView? = null
    private val logLines = ArrayDeque<String>()

    private val loop = object : Runnable {
        override fun run() {
            if (tab == 0) refreshHome()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }
        root.addView(tv("Vatio", 26f, textColor, true).apply {
            setPadding(dp(16), dp(16), dp(16), dp(4))
        })
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        listOf("Inicio", "FPS", "Módulos", "Fondo").forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { showTab(i) }
            }
            tabButtons.add(b)
            tabs.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Comprueba si hay root sin bloquear la pantalla (puede salir el aviso del gestor root).
        Thread {
            Root.check()
            ui.post { if (tab == 0 || tab == 2) showTab(tab) }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        showTab(tab)
        ui.removeCallbacks(loop)
        ui.post(loop)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(loop)
    }

    // ---------- Utilidades de interfaz ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun tv(text: String, size: Float = 16f, color: Int = textColor, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(accentInk)
            setBackgroundColor(accent)
            setOnClickListener { onClick() }
        }

    private fun gap(): View =
        View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) }

    private fun showTab(i: Int) {
        tab = i
        logView = null
        tabButtons.forEachIndexed { idx, b ->
            b.setBackgroundColor(if (idx == i) accent else surface)
            b.setTextColor(if (idx == i) accentInk else textColor)
        }
        when (i) {
            0 -> showHome()
            1 -> showFps()
            2 -> showModules()
            else -> showApps()
        }
    }

    // ---------- Inicio: lecturas reales en vivo (cada segundo) ----------

    private fun showHome() {
        content.removeAllViews()
        live.clear()
        content.addView(tv("Tu teléfono, en vivo", 20f, textColor, true))
        content.addView(tv("Todo lo que ves aquí lo lee el sistema en este momento.", 14f, muted))
        for (k in listOf("root", "ram", "bat", "temp", "thermal", "cpu", "freq", "gov", "gpu", "hz")) {
            val v = tv("", 17f)
            live[k] = v
            content.addView(v)
        }
        refreshHome()
    }

    private fun refreshHome() {
        val rootText = when (Root.available) {
            true -> "disponible"
            false -> "no disponible"
            null -> "comprobando…"
        }
        live["root"]?.text = "Root: $rootText"

        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val used = mi.totalMem - mi.availMem
        live["ram"]?.text = "RAM en uso: ${gb(used)} de ${gb(mi.totalMem)} (${used * 100 / mi.totalMem}%)"

        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000) ?: -1000
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val chargeText = if (charging) "cargando" else "sin cargar"
        live["bat"]?.text = "Batería: $pct%, $chargeText"
        live["temp"]?.text = if (temp > -1000) {
            "Temperatura de la batería: " + "%.1f".format(temp / 10.0) + " °C"
        } else {
            "Temperatura de la batería: no disponible"
        }
        live["thermal"]?.text = "Estado térmico del sistema: " + thermalName()
        live["cpu"]?.text = "Núcleos de CPU: " + Runtime.getRuntime().availableProcessors()
        live["hz"]?.text = "Tasa de refresco ahora: " + currentHz().roundToInt() + " Hz"

        // Lecturas de sysfs: en un hilo aparte para no trabar la pantalla.
        if (!homeBusy) {
            homeBusy = true
            Thread {
                val freqs = Sys.cpuFreqs()
                val gov = Sys.governor()
                val gpu = Sys.gpuMhz()
                ui.post {
                    homeBusy = false
                    live["freq"]?.text = if (freqs != null) {
                        "Frecuencia por núcleo (MHz): " + freqs.joinToString(" ") { (it / 1000).toString() }
                    } else {
                        "Frecuencia de CPU: necesita root en tu equipo"
                    }
                    live["gov"]?.text = "Gobernador de CPU: " + (gov ?: "necesita root en tu equipo")
                    live["gpu"]?.text = "Frecuencia de GPU: " + (if (gpu != null) "$gpu MHz" else "no disponible en tu equipo")
                }
            }.start()
        }
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1073741824.0)

    private fun thermalName(): String {
        if (Build.VERSION.SDK_INT < 29) return "no disponible en tu versión de Android"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            0 -> "normal"
            1 -> "leve"
            2 -> "moderado"
            3 -> "alto"
            4 -> "crítico"
            5 -> "emergencia"
            else -> "apagado inminente"
        }
    }

    @Suppress("DEPRECATION")
    private fun currentHz(): Float = windowManager.defaultDisplay.refreshRate

    @Suppress("DEPRECATION")
    private fun supportedRates(): List<Float> =
        windowManager.defaultDisplay.supportedModes
            .map { it.refreshRate.roundToInt().toFloat() }
            .distinct()
            .sorted()

    // ---------- Módulo de FPS (tasa de refresco) ----------

    private fun canWriteSettings(): Boolean {
        if (Settings.System.canWrite(this)) return true
        toast("Activa el permiso para modificar ajustes y vuelve a Vatio")
        startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
        )
        return false
    }

    private fun showFps() {
        content.removeAllViews()
        content.addView(tv("Módulo de FPS", 20f, textColor, true))
        content.addView(
            tv(
                "Fija la tasa de refresco de la pantalla y comprueba si el sistema la respetó. " +
                    "Ningún módulo puede hacer que un juego supere los FPS que el propio juego soporta.",
                14f, muted
            )
        )
        val cur = tv("", 17f)
        val result = tv("", 15f, accent)
        val rates = supportedRates()
        val refresh = { cur.text = "Tasa actual: ${currentHz().roundToInt()} Hz" }
        refresh()
        content.addView(cur)
        content.addView(
            tv("Tasas que admite tu pantalla: " + rates.joinToString(", ") { "${it.roundToInt()} Hz" }, 14f, muted)
        )
        for (r in rates) {
            content.addView(btn("Fijar ${r.roundToInt()} Hz") { applyRate(r, result, refresh) })
            content.addView(gap())
        }
        content.addView(btn("Volver a automático") {
            if (!canWriteSettings()) return@btn
            try {
                Settings.System.putFloat(contentResolver, "min_refresh_rate", 0f)
                Settings.System.putFloat(contentResolver, "peak_refresh_rate", rates.maxOrNull() ?: 60f)
                result.text = "Restaurado a automático."
            } catch (e: Exception) {
                result.text = "No pude restaurar: ${e.message}"
            }
        })
        content.addView(gap())
        content.addView(btn("Medir de nuevo") { refresh() })
        content.addView(result)
    }

    private fun applyRate(hz: Float, result: TextView, onDone: () -> Unit) {
        if (!canWriteSettings()) return
        try {
            Settings.System.putFloat(contentResolver, "min_refresh_rate", hz)
            Settings.System.putFloat(contentResolver, "peak_refresh_rate", hz)
        } catch (e: Exception) {
            result.text = "No pude escribir el ajuste: ${e.message}"
            return
        }
        result.text = "Ajuste guardado. Comprobando…"
        ui.postDelayed({
            val now = currentHz()
            onDone()
            result.text = if (abs(now - hz) < 1.5f) {
                "Verificado: la pantalla va a ${now.roundToInt()} Hz."
            } else {
                "No verificado: la pantalla va a ${now.roundToInt()} Hz. " +
                    "Tu fabricante puede controlar la tasa con otro ajuste. " +
                    "Si la pantalla está quieta puede bajar sola: mueve algo y mide de nuevo."
            }
        }, 1500)
    }

    // ---------- Módulos (con root) ----------

    private fun appendLog(line: String) {
        ui.post {
            logLines.addLast(line)
            while (logLines.size > 80) logLines.removeFirst()
            logView?.text = logLines.joinToString("\n")
        }
    }

    private fun showModules() {
        content.removeAllViews()
        content.addView(tv("Módulos", 20f, textColor, true))
        val rootText = when (Root.available) {
            true -> "Root: disponible"
            false -> "Root: no disponible. Los módulos quedan inactivos."
            null -> "Root: comprobando…"
        }
        content.addView(tv(rootText, 15f, if (Root.available == true) accent else muted))
        content.addView(
            tv(
                "Un módulo es un .zip con module.prop y scripts (service.sh, post-fs-data.sh), como los de Magisk. " +
                    "Vatio ejecuta sus scripts como root. No monta archivos de la carpeta system/.",
                14f, muted
            )
        )
        content.addView(btn("Importar módulo (.zip)") { pickModule() })
        content.addView(gap())
        content.addView(btn("Buscar root de nuevo") {
            Thread {
                Root.check()
                ui.post { if (tab == 2) showModules() }
            }.start()
        })
        content.addView(gap())
        content.addView(btn("Reaplicar los activados") { reapplyAll() })

        content.addView(tv("Registro en vivo", 17f, textColor, true))
        val lv = tv(logLines.joinToString("\n"), 12f, muted)
        lv.typeface = Typeface.MONOSPACE
        logView = lv
        content.addView(lv)

        content.addView(tv("Módulos instalados", 17f, textColor, true))
        val mods = Modules.list(this)
        if (mods.isEmpty()) content.addView(tv("Todavía no importaste ninguno.", 14f, muted))
        val prefs = getSharedPreferences("vatio", MODE_PRIVATE)
        for (m in mods) {
            val on = prefs.getBoolean("on:" + m.id, false)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(surface)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val title = if (m.version.isNotEmpty()) m.name + "  " + m.version else m.name
            row.addView(tv(title, 16f, textColor, true).apply { setPadding(0, 0, 0, 0) })
            if (m.description.isNotEmpty()) {
                row.addView(tv(m.description, 14f, muted).apply { setPadding(0, dp(2), 0, 0) })
            }
            row.addView(tv(if (on) "Activo" else "Inactivo", 14f, if (on) accent else muted))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = dp(8)
            buttons.addView(btn(if (on) "Desactivar" else "Activar") {
                if (on) deactivate(m) else askActivate(m)
            }, lp)
            buttons.addView(
                btn("Quitar") { confirmRemove(m) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(buttons)
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowLp.bottomMargin = dp(8)
            content.addView(row, rowLp)
        }
    }

    private fun pickModule() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_IMPORT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            try {
                val m = Modules.importZip(this, uri)
                ui.post {
                    toast("Importado: " + m.name)
                    if (tab == 2) showModules()
                }
            } catch (e: Exception) {
                ui.post { toast("No pude importar: " + e.message) }
            }
        }.start()
    }

    private fun askActivate(m: Module) {
        if (Root.available != true) {
            toast("Este módulo necesita root y no lo tengo disponible")
            return
        }
        val scripts = Modules.scripts(m)
        if (scripts.isEmpty()) {
            toast("El módulo no trae service.sh ni post-fs-data.sh")
            return
        }
        val text = scripts.joinToString("\n\n") { "# " + it.name + "\n" + it.readText() }
        val bad = Modules.dangerous(text)
        if (bad != null) {
            AlertDialog.Builder(this)
                .setTitle("Módulo bloqueado")
                .setMessage("Este módulo contiene una orden que puede dañar el sistema:\n\n" + bad + "\n\nNo lo voy a ejecutar.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }
        val overlay = java.io.File(m.dir, "system").exists()
        val warn = if (overlay) "Trae archivos en system/ que Vatio no puede montar; solo se ejecutarán sus scripts.\n\n" else ""
        val shown = text.take(1200) + (if (text.length > 1200) "\n…" else "")
        AlertDialog.Builder(this)
            .setTitle("Activar " + m.name)
            .setMessage(warn + "Se ejecutará como root:\n\n" + shown)
            .setPositiveButton("Ejecutar") { _, _ -> activate(m) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun activate(m: Module) {
        getSharedPreferences("vatio", MODE_PRIVATE).edit().putBoolean("on:" + m.id, true).apply()
        runModule(m)
        showModules()
    }

    private fun runModule(m: Module) {
        Thread {
            for (s in Modules.scripts(m)) {
                appendLog("[" + m.name + "] ejecutando " + s.name)
                val code = Root.exec(s, m.dir) { appendLog("[" + m.name + "] " + it) }
                appendLog("[" + m.name + "] " + s.name + " terminó con código " + code)
            }
        }.start()
    }

    private fun deactivate(m: Module) {
        getSharedPreferences("vatio", MODE_PRIVATE).edit().putBoolean("on:" + m.id, false).apply()
        val un = java.io.File(m.dir, "uninstall.sh")
        if (un.exists() && Root.available == true) {
            Thread {
                Root.exec(un, m.dir) { appendLog("[" + m.name + "] " + it) }
                appendLog("[" + m.name + "] desactivado")
            }.start()
        } else {
            appendLog("[" + m.name + "] desactivado. Lo que cambió su script puede volver a su valor al reiniciar.")
        }
        showModules()
    }

    private fun reapplyAll() {
        if (Root.available != true) {
            toast("Necesito root para ejecutar módulos")
            return
        }
        val prefs = getSharedPreferences("vatio", MODE_PRIVATE)
        val active = Modules.list(this).filter { prefs.getBoolean("on:" + it.id, false) }
        if (active.isEmpty()) {
            toast("No hay módulos activados")
            return
        }
        active.forEach { runModule(it) }
    }

    private fun confirmRemove(m: Module) {
        AlertDialog.Builder(this)
            .setTitle("Quitar " + m.name)
            .setMessage("Se borra el módulo de Vatio. No deshace lo que su script ya cambió.")
            .setPositiveButton("Quitar") { _, _ ->
                getSharedPreferences("vatio", MODE_PRIVATE).edit().remove("on:" + m.id).apply()
                m.dir.deleteRecursively()
                showModules()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Fondo: apps reales instaladas ----------

    private fun showApps() {
        content.removeAllViews()
        content.addView(tv("Apps en segundo plano", 20f, textColor, true))
        content.addView(
            tv(
                "Estas son tus apps instaladas. Toca una para abrir su ajuste y elegir si puede " +
                    "seguir en segundo plano (Batería).",
                14f, muted
            )
        )
        val pm = packageManager
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }
        for ((pkg, name) in apps) {
            val free = power.isIgnoringBatteryOptimizations(pkg)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(surface)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            row.addView(tv(name, 16f, textColor, true).apply { setPadding(0, 0, 0, 0) })
            row.addView(
                tv(
                    if (free) "Sin restricciones en segundo plano" else "Optimizada por el sistema",
                    14f,
                    if (free) accent else muted
                ).apply { setPadding(0, dp(2), 0, 0) }
            )
            row.setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                )
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            content.addView(row, lp)
        }
    }

    companion object {
        private const val REQ_IMPORT = 42
    }
}
