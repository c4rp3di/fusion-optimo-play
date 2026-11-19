package com.optimo.play

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Prepara un archivo de subtítulos para mandarlo a una tele: SRT para DLNA/Samsung, WebVTT para Chromecast. */
object Subs {

    class Listo(val bytes: ByteArray, val ext: String, val mime: String)

    private val TIEMPO = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

    class Cue(val ini: Long, val fin: Long, val texto: String)

    private fun tiempoMs(s: String): Long? {
        val m = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})").find(s) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        return ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].padEnd(3, '0').toLong()
    }

    /** Todas las líneas (inicio, fin, texto) de un SRT, WebVTT o ASS/SSA, ordenadas. Vacío si no se entiende el formato. */
    fun leerCues(f: File): List<Cue> {
        return try {
            val txt = leerTexto(f)
            val res = ArrayList<Cue>()
            if (txt.contains("[Script Info]")) {
                for (l in txt.lines()) {
                    if (!l.startsWith("Dialogue:")) continue
                    val p = l.substring(9).trim().split(",", limit = 10)
                    if (p.size < 10) continue
                    val ini = tiempoMs(p[1]) ?: continue
                    val fin = tiempoMs(p[2]) ?: continue
                    val t = p[9].replace(Regex("\\{[^}]*\\}"), "").replace("\\N", " ").replace("\\n", " ").trim()
                    if (t.isNotEmpty()) res.add(Cue(ini, fin, t))
                }
            } else {
                for (bloque in txt.split(Regex("\n\\s*\n"))) {
                    val ls = bloque.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val i = ls.indexOfFirst { it.contains("-->") }
                    if (i < 0) continue
                    val m = Regex("(.+?)\\s*-->\\s*(\\S+)").find(ls[i]) ?: continue
                    val ini = tiempoMs(m.groupValues[1]) ?: continue
                    val fin = tiempoMs(m.groupValues[2]) ?: continue
                    val t = ls.drop(i + 1).joinToString(" ").replace(Regex("<[^>]*>"), "").replace(Regex("\\{[^}]*\\}"), "").trim()
                    if (t.isNotEmpty()) res.add(Cue(ini, fin, t))
                }
            }
            res.sortedBy { it.ini }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun leerTexto(f: File): String {
        val b = f.readBytes()
        var t: String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            String(b, Charsets.ISO_8859_1) // subtítulos antiguos (Windows-1252)
        }
        if (t.startsWith("\uFEFF")) t = t.substring(1)
        return t.replace("\r\n", "\n").replace("\r", "\n")
    }

    /** null si el formato no se puede convertir (p. ej. .ass con estilos). [desfaseMs] positivo = más tarde. */
    fun preparar(f: File, desfaseMs: Long, comoVtt: Boolean): Listo? {
        return try {
            var txt = leerTexto(f)
            if (txt.contains("[Script Info]") || !txt.contains("-->")) return null
            if (txt.startsWith("WEBVTT") && !comoVtt) {
                val i = txt.indexOf("\n\n")
                txt = if (i >= 0) txt.substring(i + 2) else txt
            }
            val sep = if (comoVtt) "." else ","
            txt = TIEMPO.replace(txt) { m ->
                val g = m.groupValues
                var ms = ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[4].toLong() + desfaseMs
                if (ms < 0) ms = 0
                val s = ms / 1000
                "%02d:%02d:%02d%s%03d".format(s / 3600, (s % 3600) / 60, s % 60, sep, ms % 1000)
            }
            if (comoVtt && !txt.startsWith("WEBVTT")) txt = "WEBVTT\n\n" + txt
            if (comoVtt) Listo(txt.toByteArray(Charsets.UTF_8), "vtt", "text/vtt; charset=utf-8")
            else Listo(txt.toByteArray(Charsets.UTF_8), "srt", "text/srt; charset=utf-8")
        } catch (e: Exception) {
            null
        }
    }
}
