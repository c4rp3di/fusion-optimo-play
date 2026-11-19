package com.optimo.play

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class Programa(val inicio: Long, val fin: Long, val titulo: String, val desc: String)

/**
 * Guía de programación XMLTV (.xml o .xml.gz). Solo se guarda lo cercano al momento actual (de hace 3 h a 36 h
 * vista) para no llenar la memoria. El canal se enlaza por tvg-id y, si no lo hay o no coincide, por nombre.
 */
/**
 * Programación de una guía XMLTV. [otras] son guías adicionales por dirección: un canal que lleva la suya propia
 * (p. ej. un favorito de otra lista) se busca en ella y no en la principal.
 */
class Guia(
    private val porId: Map<String, List<Programa>>,
    private val idDeNombre: Map<String, String>,
    private val otras: Map<String, Guia> = emptyMap()
) {

    fun conOtras(o: Map<String, Guia>): Guia = Guia(porId, idDeNombre, o)

    val canalesConProgramas: Int get() = porId.size + otras.values.sumOf { it.canalesConProgramas }

    private val cache = HashMap<String, List<Programa>>()

    @Synchronized
    fun de(c: Canal): List<Programa> {
        cache[c.clave]?.let { return it }
        val propia = c.guia?.let { otras[it] }
        if (propia != null) {
            val r = propia.de(c)
            cache[c.clave] = r
            return r
        }
        var id = c.tvgId?.lowercase()?.takeIf { porId.containsKey(it) }
        if (id == null) id = idDeNombre[norm(c.nombre)]
        if (id == null) id = idDeNombre[norm(c.etiqueta)]
        val r = if (id != null) (porId[id] ?: emptyList()) else emptyList()
        cache[c.clave] = r
        return r
    }

    fun ahora(c: Canal, t: Long = System.currentTimeMillis()): Programa? = de(c).firstOrNull { it.inicio <= t && t < it.fin }

    fun luego(c: Canal, t: Long = System.currentTimeMillis()): Programa? {
        val a = ahora(c, t)
        val desde = a?.fin ?: t
        return de(c).firstOrNull { it.inicio >= desde }
    }

    /** Lo que queda por emitir (incluido lo que está sonando). */
    fun pendientes(c: Canal, t: Long = System.currentTimeMillis()): List<Programa> = de(c).filter { it.fin > t }

    companion object {

        fun norm(s: String): String {
            val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            return d.replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "")
        }

        /** «20261116130000 +0100» → milisegundos UTC. Sin zona se toma UTC (lo que dice el estándar). */
        fun fecha(s: String?): Long {
            val t = s?.trim() ?: return -1L
            if (t.length < 12) return -1L
            return try {
                val y = t.substring(0, 4).toInt()
                val mo = t.substring(4, 6).toInt()
                val d = t.substring(6, 8).toInt()
                val h = t.substring(8, 10).toInt()
                val mi = t.substring(10, 12).toInt()
                val se = if (t.length >= 14) t.substring(12, 14).toInt() else 0
                var off = 0L
                val resto = if (t.length > 14) t.substring(14).trim() else ""
                if (resto.length >= 5 && (resto[0] == '+' || resto[0] == '-')) {
                    val sg = if (resto[0] == '-') -1 else 1
                    off = sg * (resto.substring(1, 3).toInt() * 60L + resto.substring(3, 5).toInt()) * 60_000L
                }
                val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
                cal.clear()
                cal.set(y, mo - 1, d, h, mi, se)
                cal.timeInMillis - off
            } catch (e: Exception) {
                -1L
            }
        }

        /** Descarga (o reutiliza la de hace menos de 6 h) y lee la guía. Bloquea: llamar desde un hilo. */
        fun cargar(ctx: Context, url: String): Guia {
            val dir = File(ctx.cacheDir, "guias")
            dir.mkdirs()
            val f = File(dir, Integer.toHexString(url.hashCode()) + ".xml")
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * 3600_000L) {
                val tmp = File(dir, f.name + ".tmp")
                val c = Red.conexion(url, null, null)
                c.readTimeout = 60_000
                if (c.responseCode >= 400) throw IOException("El servidor responde " + c.responseCode)
                var total = 0L
                c.inputStream.use { i ->
                    FileOutputStream(tmp).use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 80L * 1024 * 1024) throw IOException("La guía es demasiado grande")
                            o.write(buf, 0, n)
                        }
                    }
                }
                f.delete()
                if (!tmp.renameTo(f)) throw IOException("No se pudo guardar la guía")
            }
            val bis = BufferedInputStream(FileInputStream(f))
            bis.mark(4)
            val b0 = bis.read()
            val b1 = bis.read()
            bis.reset()
            val ins: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(bis) else bis
            ins.use { return leer(it) }
        }

        private fun leer(ins: InputStream): Guia {
            val ahora = System.currentTimeMillis()
            val desde = ahora - 3 * 3600_000L
            val hasta = ahora + 36 * 3600_000L
            val progs = HashMap<String, ArrayList<Programa>>()
            val nombres = HashMap<String, String>()
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            var canalId: String? = null
            var dentro = false
            var pid = ""
            var ini = 0L
            var fin = 0L
            var titulo = ""
            var desc = ""
            var total = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> canalId = p.getAttributeValue(null, "id")?.lowercase()
                        "display-name" -> {
                            val id = canalId
                            val t = p.nextText().trim()
                            if (id != null && t.isNotEmpty()) nombres[norm(t)] = id
                        }
                        "programme" -> {
                            dentro = true
                            pid = (p.getAttributeValue(null, "channel") ?: "").lowercase()
                            ini = fecha(p.getAttributeValue(null, "start"))
                            fin = fecha(p.getAttributeValue(null, "stop"))
                            titulo = ""
                            desc = ""
                        }
                        "title" -> if (dentro && titulo.isEmpty()) titulo = p.nextText().trim()
                        "desc" -> if (dentro && desc.isEmpty()) desc = p.nextText().trim()
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> canalId = null
                        "programme" -> {
                            if (dentro && ini > 0 && fin > ini && fin > desde && ini < hasta && pid.isNotEmpty() && total < 400_000) {
                                progs.getOrPut(pid) { ArrayList() }.add(Programa(ini, fin, titulo, desc))
                                total++
                            }
                            dentro = false
                        }
                    }
                }
                ev = p.next()
            }
            val salida = HashMap<String, List<Programa>>()
            for ((k, v) in progs) salida[k] = v.sortedBy { it.inicio }
            if (salida.isEmpty()) throw IOException("La guía no trae programación para estas horas")
            return Guia(salida, nombres)
        }
    }
}
