package com.optimo.play

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Prepara un archivo de subtítulos para mandarlo a una tele: SRT para DLNA/Samsung, WebVTT para Chromecast. */
object Subs {

    class Listo(val bytes: ByteArray, val ext: String, val mime: String)

    private val TIEMPO = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

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
