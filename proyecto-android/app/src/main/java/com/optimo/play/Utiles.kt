package com.optimo.play

import android.content.Context
import android.net.Uri
import android.os.Build
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

    private val EXT_AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac", "mka")

    /** User-Agent de móvil que se manda a las listas, guías y canales si no traen el suyo. */
    const val UA_MOVIL = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Muchas listas de canales llevan marcas que debe rellenar el reproductor: [UA], [DEVICE_ID], [LMT]…
     * Se sustituyen: privacidad al máximo (limitar publicidad personalizada, no vender datos), un identificador
     * aleatorio por sesión en vez del real, y vacío lo que no se conoce.
     */
    fun resolverMarcas(url: String, ua: String, idSesion: String): String {
        if (!url.contains('[')) return url
        return Regex("\\[([A-Z0-9_]+)\\]").replace(url) { m ->
            when (m.groupValues[1]) {
                "LMT", "DNS" -> "1"
                "US_PRIVACY" -> "1YYN"
                "DEVICE_ID" -> idSesion
                "UA" -> Uri.encode(ua)
                "APP_BUNDLE" -> "com.optimo.play"
                "APP_NAME" -> "OptimoPlay"
                "DEVICE_MAKE" -> Uri.encode(Build.MANUFACTURER)
                "DEVICE_MODEL" -> Uri.encode(Build.MODEL)
                "GDPR" -> "0"
                "VIEWSIZE" -> "1280x720"
                "NONCE" -> java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                else -> ""
            }
        }
    }

    /** Hora local «HH:mm». */
    fun hora(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    fun esVideo(nombre: String): Boolean = EXT_VIDEO.contains(nombre.substringAfterLast('.', "").lowercase())

    fun esAudio(nombre: String): Boolean = EXT_AUDIO.contains(nombre.substringAfterLast('.', "").lowercase())

    /** Vídeo o audio: lo que se puede poner en la lista de reproducción de un torrent. */
    fun esReproducible(nombre: String): Boolean = esVideo(nombre) || esAudio(nombre)

    /** Orden «natural»: «CD2» antes que «CD10», «pista 9» antes que «pista 10». Sin distinguir mayúsculas. */
    fun comparaNatural(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                var ie = i
                while (ie < x.length && x[ie].isDigit()) ie++
                var je = j
                while (je < y.length && y[je].isDigit()) je++
                val nx = x.substring(i, ie).trimStart('0')
                val ny = y.substring(j, je).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                if (x[i] != y[j]) return x[i].compareTo(y[j])
                i++
                j++
            }
        }
        return (x.length - i) - (y.length - j)
    }

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
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
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

    /**
     * Instantes de inicio (ms) de cada línea de un archivo de subtítulos SRT/VTT/ASS, sin tener en cuenta
     * el desfase. Sirve para saltar de diálogo en diálogo al sincronizar.
     */
    fun tiemposSubs(f: java.io.File): List<Long> {
        val salida = ArrayList<Long>()
        try {
            val txt = String(f.readBytes(), Charsets.ISO_8859_1) // los tiempos son ASCII: vale cualquier codificación
            val srt = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->")
            for (m in srt.findAll(txt)) {
                val h = m.groupValues[1].toLongOrNull() ?: 0L
                val mi = m.groupValues[2].toLong()
                val s = m.groupValues[3].toLong()
                val ms = m.groupValues[4].padEnd(3, '0').toLong()
                salida.add(((h * 60 + mi) * 60 + s) * 1000 + ms)
            }
            if (salida.isEmpty()) {
                val ass = Regex("(?m)^Dialogue:\\s*[^,]*,(\\d+):(\\d{2}):(\\d{2})[.](\\d{2})")
                for (m in ass.findAll(txt)) {
                    val h = m.groupValues[1].toLong()
                    val mi = m.groupValues[2].toLong()
                    val s = m.groupValues[3].toLong()
                    val cs = m.groupValues[4].toLong()
                    salida.add(((h * 60 + mi) * 60 + s) * 1000 + cs * 10)
                }
            }
        } catch (e: Exception) {
            // Sin lista: la sincronización por líneas no estará disponible.
        }
        return salida.sorted().distinct()
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
