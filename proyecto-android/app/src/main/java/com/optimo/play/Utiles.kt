package com.optimo.play

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Funciones puras y pequeñas: nombres, tiempos y el hash de OpenSubtitles. */
object Utiles {

    private val EXT_VIDEO = setOf(
        "mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "flv", "3gp", "ogv", "vob"
    )

    fun esVideo(nombre: String): Boolean = EXT_VIDEO.contains(nombre.substringAfterLast('.', "").lowercase())

    /** Tipo MIME aproximado para el servidor local del torrent. */
    fun tipoMime(nombre: String): String = when (nombre.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** Primer magnet o enlace http(s) que aparezca en un texto compartido. */
    fun primerEnlace(texto: String?): String? {
        if (texto == null) return null
        return Regex("(magnet:\\?\\S+|https?://\\S+)", RegexOption.IGNORE_CASE).find(texto)?.value
    }

    fun esArchivoTorrent(uri: String): Boolean {
        val sinConsulta = uri.substringBefore('?').substringBefore('#')
        return sinConsulta.lowercase().endsWith(".torrent")
    }

    /** Nombre legible de lo que se va a reproducir. */
    fun nombreDe(ctx: Context, uri: String): String {
        try {
            if (uri.startsWith("magnet:", true)) {
                val m = Regex("[?&]dn=([^&]+)", RegexOption.IGNORE_CASE).find(uri)
                if (m != null) return URLDecoder.decode(m.groupValues[1], "UTF-8")
                return "Torrent"
            }
            val u = Uri.parse(uri)
            if (u.scheme == "content") {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrEmpty()) return n
                    }
                }
            }
            val seg = u.lastPathSegment
            if (!seg.isNullOrEmpty()) return seg
        } catch (e: Exception) {
            // Se usa el valor por defecto.
        }
        return uri.take(60)
    }

    /** Convierte un nombre de archivo en una búsqueda razonable: «Mi.Pelicula.2019.1080p.x264.mkv» → «Mi Pelicula 2019». */
    fun consultaSubs(nombre: String): String {
        var s = nombre
        val punto = s.lastIndexOf('.')
        if (punto > 0 && s.length - punto <= 5) s = s.substring(0, punto)
        s = s.replace(Regex("[._]+"), " ")
        s = s.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
        val corte = Regex(
            "\\b(480p|576p|720p|1080p|2160p|4k|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|hdtv|dvdrip|x264|x265|h264|h265|hevc|remux)\\b",
            RegexOption.IGNORE_CASE
        ).find(s)
        if (corte != null && corte.range.first > 2) s = s.substring(0, corte.range.first)
        return s.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Hash de OpenSubtitles: tamaño + suma de palabras de 64 bits (little endian) de los primeros y últimos 64 KiB.
     * Se lee por posición, sin mover el cursor del archivo.
     */
    fun hashOpenSubs(ch: FileChannel): String? {
        return try {
            val tam = ch.size()
            if (tam < 131072L) return null
            var hash = tam
            val buf = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
            for (pos in longArrayOf(0L, tam - 65536L)) {
                buf.clear()
                while (buf.hasRemaining()) {
                    val r = ch.read(buf, pos + buf.position())
                    if (r < 0) break
                }
                buf.flip()
                val lb = buf.asLongBuffer()
                while (lb.hasRemaining()) hash += lb.get()
            }
            "%016x".format(hash)
        } catch (e: Exception) {
            null
        }
    }

    fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }

    fun tam(b: Long): String {
        val kb = 1024.0
        return when {
            b >= kb * kb * kb -> "%.2f GB".format(b / (kb * kb * kb))
            b >= kb * kb -> "%.0f MB".format(b / (kb * kb))
            else -> "%.0f KB".format(b / kb)
        }
    }
}
