package com.vatio.app

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.zip.ZipInputStream

/** Shell root persistente: sirve para lecturas rápidas en tiempo real sin abrir su a cada rato. */
object RootShell {
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Synchronized
    private fun ensure(): Boolean {
        if (proc != null) return true
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            proc = p
            writer = p.outputStream.bufferedWriter()
            reader = p.inputStream.bufferedReader()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Ejecuta un comando como root y devuelve su salida, o null si no hay root. Bloquea: usar fuera del hilo principal. */
    @Synchronized
    fun run(cmd: String): String? {
        if (!ensure()) return null
        return try {
            val w = writer!!
            val r = reader!!
            w.write(cmd + "\necho __VATIO_END__\n")
            w.flush()
            val sb = StringBuilder()
            while (true) {
                val line = r.readLine()
                if (line == null) {
                    proc = null
                    return null
                }
                if (line == "__VATIO_END__") break
                sb.append(line).append('\n')
            }
            sb.toString().trim()
        } catch (e: Exception) {
            proc = null
            null
        }
    }
}

object Root {
    /** null = todavía no comprobado. */
    @Volatile
    var available: Boolean? = null

    /** Comprueba si hay root (puede mostrar el aviso de permiso del gestor root). Bloquea. */
    fun check(): Boolean {
        val out = RootShell.run("id")
        val ok = out != null && out.contains("uid=0")
        available = ok
        return ok
    }

    /** Ejecuta un script como root y entrega cada línea de salida en cuanto aparece. Devuelve el código de salida. */
    fun exec(script: File, moduleDir: File, onLine: (String) -> Unit): Int {
        return try {
            val cmd = "MODDIR=" + shq(moduleDir.absolutePath) + " sh " + shq(script.absolutePath)
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine { onLine(it) }
            p.waitFor()
        } catch (e: Exception) {
            onLine("Error: " + e.message)
            -1
        }
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/** Lecturas reales del sistema. Usa root solo si hace falta y está disponible. */
object Sys {
    private fun direct(path: String): String? = try {
        File(path).readText().trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun viaRoot(cmd: String): String? =
        if (Root.available == true) RootShell.run(cmd) else null

    /** Frecuencia actual de cada núcleo en kHz, o null si no se puede leer. */
    fun cpuFreqs(): List<Int>? {
        val cores = Runtime.getRuntime().availableProcessors()
        val readings = (0 until cores).map {
            direct("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.toIntOrNull()
        }
        if (readings.all { it != null }) return readings.map { it ?: 0 }
        val cmd = (0 until cores).joinToString("; ") {
            "cat /sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq 2>/dev/null || echo 0"
        }
        val out = viaRoot(cmd) ?: return null
        val values = out.lines().mapNotNull { it.trim().toIntOrNull() }
        return if (values.size == cores) values else null
    }

    fun governor(): String? {
        val path = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        return direct(path) ?: viaRoot("cat $path 2>/dev/null")?.ifEmpty { null }
    }

    /** Frecuencia de GPU Adreno en MHz, o null si tu GPU no expone ese dato. */
    fun gpuMhz(): Long? {
        val path = "/sys/class/kgsl/kgsl-3d0/gpuclk"
        val raw = direct(path) ?: viaRoot("cat $path 2>/dev/null")
        val hz = raw?.trim()?.toLongOrNull() ?: return null
        return hz / 1000000L
    }
}

class Module(
    val id: String,
    val dir: File,
    val name: String,
    val version: String,
    val author: String,
    val description: String
)

object Modules {
    private val blocked = listOf(
        Regex("""rm\s+(-\S+\s+)*(/\*?|/system|/vendor|/data|/sdcard|/storage)(\s|$)"""),
        Regex("""\bdd\b[^\n]*of=/dev/block"""),
        Regex("""\bmkfs"""),
        Regex("""wipe\s+data""")
    )

    private fun root(ctx: Context): File = File(ctx.filesDir, "modules").apply { mkdirs() }

    fun list(ctx: Context): List<Module> =
        (root(ctx).listFiles() ?: emptyArray())
            .filter { File(it, "module.prop").exists() }
            .map { load(it) }
            .sortedBy { it.name.lowercase() }

    private fun load(dir: File): Module {
        val p = parseProp(File(dir, "module.prop").readText())
        return Module(
            dir.name, dir,
            p["name"] ?: dir.name,
            p["version"] ?: "",
            p["author"] ?: "",
            p["description"] ?: ""
        )
    }

    private fun parseProp(text: String): Map<String, String> =
        text.lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
        }.toMap()

    fun scripts(m: Module): List<File> =
        listOf("post-fs-data.sh", "service.sh").map { File(m.dir, it) }.filter { it.exists() }

    /** Devuelve la línea peligrosa si el script trae una orden que puede dañar el sistema. */
    fun dangerous(text: String): String? {
        for (line in text.lines()) {
            val l = line.trim()
            if (l.startsWith("#")) continue
            if (blocked.any { it.containsMatchIn(l) }) return l
        }
        return null
    }

    fun importZip(ctx: Context, uri: Uri): Module {
        val tmp = File(ctx.cacheDir, "imp_" + System.currentTimeMillis())
        tmp.mkdirs()
        try {
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("No pude abrir el archivo")
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                        throw SecurityException("Ruta inválida dentro del zip")
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> zip.copyTo(o) }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
            val propFile = tmp.walkTopDown().firstOrNull { it.isFile && it.name == "module.prop" }
                ?: throw IllegalStateException("El zip no trae module.prop")
            val props = parseProp(propFile.readText())
            val rawId = props["id"] ?: ("mod_" + System.currentTimeMillis())
            val id = rawId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val dest = File(root(ctx), id)
            dest.deleteRecursively()
            propFile.parentFile!!.copyRecursively(dest, true)
            return load(dest)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
