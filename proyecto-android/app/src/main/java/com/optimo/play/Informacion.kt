package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Panel «Información» para cualquier fuente (URL, archivo local, manifiesto o torrent): qué se está reproduciendo,
 * en qué estado está libVLC, qué pistas tiene y, para URLs de red, qué responde el servidor. Sirve para depurar
 * sin logcat: el botón «Copiar» deja todo el texto en el portapapeles.
 *
 * Las pistas se leen por reflexión (campos públicos de IMedia.Track): si la versión de libVLC cambia sus nombres,
 * el panel lo indica en vez de impedir compilar.
 */
object Informacion {

    private fun recortar(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max) + "…"

    private fun esRed(uri: String): Boolean {
        val u = uri.lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    private fun esLocalHost(uri: String): Boolean {
        val h = try { Uri.parse(uri).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return h == "127.0.0.1" || h == "localhost"
    }

    fun mostrar(act: Activity, s: PlaybackService) {
        val dp = act.resources.displayMetrics.density
        val tv = TextView(act)
        tv.setTypeface(Typeface.MONOSPACE)
        tv.setTextSize(12f)
        tv.setTextIsSelectable(true)
        tv.setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        val base = construir(s)
        tv.text = base
        val scroll = ScrollView(act)
        scroll.addView(tv)
        val dialogo = AlertDialog.Builder(act)
            .setTitle("Información")
            .setView(scroll)
            .setPositiveButton("Copiar") { _, _ ->
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Optimo Play", tv.text.toString()))
                Toast.makeText(act, "Copiado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .create()
        dialogo.show()

        val uri = s.urlReproduccion ?: s.fuente?.uri
        if (uri != null && esRed(uri) && !esLocalHost(uri)) {
            val f = s.fuente
            Thread {
                val r = sondear(uri, f)
                act.runOnUiThread {
                    if (dialogo.isShowing) tv.text = base + "\n" + r
                }
            }.start()
        }
    }

    /** Texto con el estado actual del reproductor. */
    fun construir(s: PlaybackService): String {
        val sb = StringBuilder()
        val f = s.fuente
        val uri = f?.uri ?: ""
        val tipo = when {
            s.hayCanales -> "Lista de canales M3U (" + s.canales.size + " canales)"
            s.esTorrent -> "Torrent (se reproduce desde un servidor local)"
            s.esMega -> "Mega (se descifra en el móvil y se sirve desde un servidor local)"
            uri.startsWith("content:", true) -> "Archivo del dispositivo"
            uri.startsWith("file:", true) -> "Archivo del dispositivo"
            esRed(uri) && PlayerUtilTipo.esManifiesto(uri) -> "Red · manifiesto HLS/DASH"
            esRed(uri) -> "Red · URL directa"
            uri.isEmpty() -> "Sin fuente"
            else -> "Otro"
        }
        sb.append("Fuente: ").append(tipo).append('\n')
        sb.append("Título: ").append(s.titulo).append('\n')
        val canal = s.canalActivo
        if (canal != null) {
            sb.append("Canal: ").append(canal.etiqueta).append(" · grupo ").append(canal.grupo).append('\n')
            sb.append("Guía: ").append(if (s.estadoGuia.isEmpty()) "(sin guía)" else s.estadoGuia).append('\n')
        }
        // En los enlaces de Mega la clave va tras el #: no se muestra (el botón «Copiar» la sacaría del móvil).
        val uriVista = if (MegaEnlace.esMega(uri)) uri.substringBefore('#') + "#…(clave oculta)" else uri
        sb.append("URI recibida:\n  ").append(recortar(uriVista, 400)).append('\n')
        val real = s.urlReproduccion
        if (real != null && real != uri) sb.append("URL usada por libVLC:\n  ").append(recortar(real, 400)).append('\n')
        if (!f?.referer.isNullOrBlank()) sb.append("Referer: ").append(recortar(f?.referer ?: "", 200)).append('\n')
        if (!f?.ua.isNullOrBlank()) sb.append("User-Agent: ").append(recortar(f?.ua ?: "", 200)).append('\n')

        sb.append('\n')
        val m = s.mp
        sb.append("Estado libVLC: ").append(if (s.ultimoEvento.isEmpty()) "(sin eventos todavía)" else s.ultimoEvento).append('\n')
        if (m != null) {
            val reproduciendo = try { m.isPlaying } catch (e: Throwable) { false }
            val t = try { m.time } catch (e: Throwable) { 0L }
            val len = try { m.length } catch (e: Throwable) { 0L }
            val vel = try { m.rate } catch (e: Throwable) { 1f }
            sb.append("Reproduciendo: ").append(if (reproduciendo) "sí" else "no").append('\n')
            sb.append("Posición: ").append(Utiles.fmt(t)).append(" / ").append(if (len > 0) Utiles.fmt(len) else "--").append('\n')
            sb.append("Velocidad: ").append(vel).append("×\n")
        } else {
            sb.append("Reproductor: no creado\n")
        }
        sb.append("Búfer: ").append(s.buffering.toInt()).append(" %\n")
        sb.append("Decodificación: ").append(
            when (s.ajustes.decodificacion) {
                1 -> "software"
                2 -> "hardware (forzada)"
                else -> "automática"
            }
        ).append('\n')
        val e = s.error
        if (e != null) sb.append("Error: ").append(e).append('\n')
        if (s.esTorrent) {
            val est = s.motor.estadisticas()
            if (est.isNotEmpty()) sb.append("Torrent: ").append(est).append('\n')
        }

        sb.append('\n').append("Pistas:\n")
        sb.append(pistas(m))
        try {
            val au = m?.audioTracks
            if (au != null) {
                sb.append("\nAudio en el menú (preferido: ").append(s.ajustes.audioPreferido.ifEmpty { "automático" }).append("):\n")
                val actual = m?.audioTrack ?: -1
                for (p in au) sb.append("  ").append(if (p.id == actual) "● " else "  ").append(p.id).append(" · ").append(p.name).append('\n')
            }
        } catch (e: Throwable) {
            // Sin lista de pistas.
        }
        return sb.toString()
    }

    private fun campo(obj: Any, nombre: String): Any? {
        return try { obj.javaClass.getField(nombre).get(obj) } catch (e: Throwable) { null }
    }

    private fun pistas(m: org.videolan.libvlc.MediaPlayer?): String {
        if (m == null) return "  (sin reproductor)\n"
        val sb = StringBuilder()
        try {
            val media: Any = m.media ?: return "  (sin medio cargado)\n"
            val n = media.javaClass.getMethod("getTrackCount").invoke(media) as Int
            if (n <= 0) return "  (todavía sin pistas: libVLC no ha leído el contenido)\n"
            val getTrack = media.javaClass.getMethod("getTrack", java.lang.Integer.TYPE)
            for (i in 0 until n) {
                val t = getTrack.invoke(media, i) ?: continue
                val clase = t.javaClass.simpleName
                val etiqueta = when {
                    clase.contains("Video") -> "Vídeo"
                    clase.contains("Audio") -> "Audio"
                    clase.contains("Subtitle") || clase.contains("Text") -> "Subtítulos"
                    else -> clase
                }
                val partes = ArrayList<String>()
                val codec = campo(t, "codec")?.toString()
                if (!codec.isNullOrBlank()) partes.add(codec)
                val ancho = campo(t, "width") as? Int
                val alto = campo(t, "height") as? Int
                if (ancho != null && alto != null && ancho > 0) partes.add("${ancho}×${alto}")
                val fpsN = campo(t, "frameRateNum") as? Int
                val fpsD = campo(t, "frameRateDen") as? Int
                if (fpsN != null && fpsD != null && fpsD > 0 && fpsN > 0) partes.add("%.2f fps".format(fpsN.toDouble() / fpsD))
                val canales = campo(t, "channels") as? Int
                if (canales != null && canales > 0) partes.add("$canales canales")
                val tasa = campo(t, "rate") as? Int
                if (tasa != null && tasa > 0) partes.add("$tasa Hz")
                val bitrate = campo(t, "bitrate") as? Int
                if (bitrate != null && bitrate > 0) partes.add("${bitrate / 1000} kb/s")
                val idioma = campo(t, "language")?.toString()
                if (!idioma.isNullOrBlank() && idioma != "und") partes.add(idioma)
                sb.append("  ").append(etiqueta).append(": ")
                sb.append(if (partes.isEmpty()) "(sin detalles)" else partes.joinToString(" · ")).append('\n')
            }
        } catch (e: Throwable) {
            sb.append("  (no se pudieron leer las pistas: ").append(e.javaClass.simpleName).append(")\n")
        }
        return sb.toString()
    }

    /** Pide el primer byte de la URL (sin cookies) para saber qué responde el servidor. */
    private class Resp(val codigo: Int, val tipo: String, val urlFinal: String, val cuerpo: ByteArray)

    private fun pedir(url: String, f: Fuente?, max: Int): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", if (f?.ua.isNullOrBlank()) Utiles.UA_MOVIL else f?.ua ?: "")
        if (!f?.referer.isNullOrBlank()) c.setRequestProperty("Referer", f?.referer ?: "")
        val codigo = c.responseCode
        val flujo = if (codigo >= 400) c.errorStream else c.inputStream
        val datos = if (flujo != null) flujo.use { Red.leerBytes(it, max) } else ByteArray(0)
        val r = Resp(codigo, c.contentType ?: "", c.url.toString(), datos)
        try { c.disconnect() } catch (e: Throwable) { /* nada */ }
        return r
    }

    /**
     * Si la URL es HLS: baja el manifiesto, la lista de la primera variante y el primer segmento, y dice qué responde
     * el servidor en cada paso. Sirve para saber si un canal que se queda en «Cargando 0 %» es culpa del servidor
     * (rechaza segmentos, lista vacía…) o del reproductor.
     */
    /** Lista las pistas de subtítulos que declara el manifiesto y mira si la primera se puede descargar y es WebVTT. */
    private fun sondearSubs(lineas: List<String>, base: String, f: Fuente?, sb: StringBuilder) {
        val entradas = lineas.filter {
            it.startsWith("#EXT-X-MEDIA") && (it.contains("TYPE=SUBTITLES") || it.contains("TYPE=CLOSED-CAPTIONS"))
        }
        if (entradas.isEmpty()) {
            sb.append("  Subtítulos: el manifiesto no declara ninguno.\n")
            return
        }
        sb.append("  Subtítulos declarados (").append(entradas.size).append("):\n")
        var primera: String? = null
        for (l in entradas) {
            val tipo = if (l.contains("TYPE=CLOSED-CAPTIONS")) "CC incrustados en el vídeo" else "pista aparte"
            val idi = Regex("LANGUAGE=\"([^\"]*)\"").find(l)?.groupValues?.get(1) ?: "?"
            val nom = Regex("NAME=\"([^\"]*)\"").find(l)?.groupValues?.get(1) ?: "?"
            val marcas = listOf("DEFAULT=YES", "AUTOSELECT=YES", "FORCED=YES").filter { l.contains(it) }.joinToString(" ")
            val uri = Regex("URI=\"([^\"]*)\"").find(l)?.groupValues?.get(1)
            sb.append("    · ").append(nom).append(" [").append(idi).append("] ").append(tipo)
            if (marcas.isNotEmpty()) sb.append(" · ").append(marcas)
            sb.append('\n')
            if (primera == null && uri != null) primera = uri
        }
        val p = primera ?: return
        try {
            val urlLista = URL(URL(base), p).toString()
            val r = pedir(urlLista, f, 200_000)
            sb.append("  Lista de la 1ª pista de subtítulos: ").append(r.codigo).append(' ').append(r.tipo).append('\n')
            if (r.codigo >= 400) {
                sb.append("  → El servidor rechaza esa lista: elegir esa pista puede dejar el canal esperando.\n")
                return
            }
            val ls = String(r.cuerpo, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val segs = ls.filter { !it.startsWith("#") }
            val relleno = segs.count { it.contains("placeholder", true) }
            sb.append("  Trozos de relleno (placeholder): ").append(relleno).append(" de ").append(segs.size).append('\n')
            // Se mira cada trozo distinto: ¿trae algún subtítulo (línea con «-->») o todos están vacíos?
            val distintos = segs.map { URL(URL(urlLista), it).toString() }.distinct().take(8)
            var conTexto = 0
            for ((n, d) in distintos.withIndex()) {
                val rr = try { pedir(d, f, 65536) } catch (e: Exception) { null }
                if (rr == null) {
                    sb.append("    · trozo ").append(n + 1).append(": error al bajarlo\n")
                    continue
                }
                val cues = Regex("-->").findAll(String(rr.cuerpo, Charsets.UTF_8)).count()
                if (cues > 0) conTexto++
                sb.append("    · trozo ").append(n + 1).append(": ").append(recortar(d.substringAfterLast('/').substringBefore('?'), 40))
                    .append(" · ").append(cues).append(" subtítulos\n")
            }
            sb.append("  Trozos distintos con subtítulos: ").append(conTexto).append(" de ").append(distintos.size).append('\n')
            sb.append("  Segmentos de subtítulos: ").append(segs.size)
            sb.append(if (ls.any { it.startsWith("#EXT-X-ENDLIST") }) " (completa)" else " (directo)").append('\n')
            if (ls.any { it.startsWith("#EXT-X-MAP") }) sb.append("  Usa EXT-X-MAP (subtítulos en MP4/TTML): libVLC 3 no los lee bien.\n")
            val s0 = segs.firstOrNull()
            if (s0 == null) {
                sb.append("  → La lista no trae segmentos.\n")
                return
            }
            val su = URL(URL(urlLista), s0).toString()
            sb.append("  1er segmento: ").append(recortar(su, 160)).append('\n')
            val sg = pedir(su, f, 4096)
            val texto = String(sg.cuerpo, Charsets.UTF_8)
            val cab = texto.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            sb.append("  Respuesta: ").append(sg.codigo).append(' ').append(sg.tipo)
                .append(", empieza por «").append(recortar(cab, 40)).append("»\n")
            if (sg.codigo >= 400) {
                sb.append("  → El servidor rechaza los segmentos de subtítulos.\n")
            } else if (!cab.startsWith("WEBVTT")) {
                sb.append("  → No es WebVTT: libVLC puede no entenderlo.\n")
            } else if (texto.contains("X-TIMESTAMP-MAP")) {
                sb.append("  Trae X-TIMESTAMP-MAP: si sus tiempos no casan con el vídeo, libVLC se queda esperando.\n")
            }
        } catch (e: Exception) {
            sb.append("  Error sondeando subtítulos: ").append(e.javaClass.simpleName).append(": ").append(e.message ?: "").append('\n')
        }
    }

    private fun sondearHls(url: String, f: Fuente?, sb: StringBuilder) {
        sb.append("\nSondeo del flujo HLS:\n")
        try {
            val m = pedir(url, f, 300_000)
            val txt = String(m.cuerpo, Charsets.UTF_8)
            val lineas = txt.lines().map { it.trim() }.filter { it.isNotEmpty() }
            sb.append("  Manifiesto: ").append(m.codigo).append(", ").append(lineas.size).append(" líneas\n")
            sondearSubs(lineas, m.urlFinal, f, sb)
            var objetivo = m.urlFinal
            var media = lineas
            if (txt.contains("#EXT-X-STREAM-INF")) {
                val variantes = ArrayList<String>()
                var primera: String? = null
                for ((i, l) in lineas.withIndex()) {
                    if (l.startsWith("#EXT-X-STREAM-INF")) {
                        val res = Regex("RESOLUTION=([0-9x]+)").find(l)?.groupValues?.get(1) ?: "?"
                        val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull()
                        variantes.add(res + (if (bw != null) " · " + (bw / 1000) + " kbps" else ""))
                        if (primera == null && i + 1 < lineas.size) primera = lineas[i + 1]
                    }
                }
                sb.append("  Variantes (").append(variantes.size).append("): ").append(variantes.joinToString("; ")).append('\n')
                val p = primera
                if (p == null) {
                    sb.append("  Sin dirección de variante.\n")
                    return
                }
                val urlVariante = URL(URL(m.urlFinal), p).toString()
                val mv = pedir(urlVariante, f, 300_000)
                sb.append("  Lista de la 1ª variante: ").append(mv.codigo).append(' ').append(mv.tipo).append('\n')
                if (mv.codigo >= 400) {
                    sb.append("  → El servidor rechaza la lista de la variante.\n")
                    return
                }
                media = String(mv.cuerpo, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
                objetivo = mv.urlFinal
            }
            val segs = media.filter { !it.startsWith("#") }
            sb.append("  Segmentos en la lista: ").append(segs.size)
            sb.append(if (media.any { it.startsWith("#EXT-X-ENDLIST") }) " (con final: vídeo completo)" else " (directo)").append('\n')
            val marcas = ArrayList<String>()
            if (media.any { it.startsWith("#EXT-X-DISCONTINUITY") }) marcas.add("discontinuidades")
            if (media.any { it.startsWith("#EXT-X-CUE") || it.startsWith("#EXT-OATCLS") || it.startsWith("#EXT-X-DATERANGE") }) {
                marcas.add("marcas de publicidad")
            }
            if (media.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) marcas.add("cifrado (EXT-X-KEY)")
            if (marcas.isNotEmpty()) sb.append("  Contiene: ").append(marcas.joinToString(", ")).append('\n')
            val s0 = segs.firstOrNull()
            if (s0 == null) {
                sb.append("  → La lista no trae ningún segmento: no hay nada que reproducir todavía.\n")
                return
            }
            val su = URL(URL(objetivo), s0).toString()
            sb.append("  1er segmento: ").append(recortar(su, 160)).append('\n')
            val sg = pedir(su, f, 65536)
            sb.append("  Respuesta del segmento: ").append(sg.codigo).append(' ').append(sg.tipo).append(", ")
                .append(sg.cuerpo.size).append(" bytes leídos\n")
            if (sg.codigo >= 400) {
                sb.append("  → El servidor rechaza los segmentos: por eso libVLC no llena el búfer.\n")
            } else {
                sb.append("  → Los segmentos se descargan bien desde aquí.\n")
            }
        } catch (e: Exception) {
            sb.append("  Error: ").append(e.javaClass.simpleName).append(": ").append(e.message ?: "").append('\n')
        }
    }

    private fun sondear(url: String, f: Fuente?): String {
        val sb = StringBuilder("Sondeo HTTP (sin cookies):\n")
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("Range", "bytes=0-0")
            c.setRequestProperty("User-Agent", if (f?.ua.isNullOrBlank()) "Mozilla/5.0 (Linux; Android 14) OptimoPlay" else f?.ua ?: "")
            if (!f?.referer.isNullOrBlank()) c.setRequestProperty("Referer", f?.referer ?: "")
            val codigo = c.responseCode
            sb.append("  Código: ").append(codigo).append(' ').append(c.responseMessage ?: "").append('\n')
            val tipo = c.contentType
            sb.append("  Tipo: ").append(tipo ?: "(no indicado)").append('\n')
            val rango = c.getHeaderField("Content-Range")
            if (rango != null) sb.append("  Content-Range: ").append(rango).append('\n')
            else sb.append("  Longitud: ").append(c.contentLengthLong).append('\n')
            sb.append("  Accept-Ranges: ").append(c.getHeaderField("Accept-Ranges") ?: "(no indicado)").append('\n')
            val fin = c.url.toString()
            if (fin != url) sb.append("  URL final tras redirecciones:\n    ").append(recortar(fin, 300)).append('\n')
            try { c.disconnect() } catch (e: Throwable) { /* nada */ }
            val t = tipo?.lowercase() ?: ""
            if (codigo >= 400) {
                sb.append("  → El servidor rechaza la petición (puede exigir cookies o una sesión).\n")
            } else if (t.startsWith("text/html")) {
                sb.append("  → Devuelve una página web, no un vídeo: libVLC no puede reproducir esta URL.\n")
            }
            if (codigo < 400 && (t.contains("mpegurl") || url.substringBefore('?').lowercase().endsWith(".m3u8"))) {
                sondearHls(url, f, sb)
            }
        } catch (e: Exception) {
            sb.append("  Error: ").append(e.javaClass.simpleName).append(": ").append(e.message ?: "").append('\n')
            if ((e.message ?: "").contains("Cleartext", true)) {
                sb.append("  (Android bloquea aquí http sin cifrar; libVLC sí puede abrirlo.)\n")
            }
        }
        return sb.toString()
    }
}

/** Utilidad mínima para no depender de otras clases: ¿la URL es un manifiesto HLS o DASH? */
object PlayerUtilTipo {
    fun esManifiesto(uri: String): Boolean {
        val r = uri.substringBefore('?').substringBefore('#').lowercase()
        return r.endsWith(".m3u8") || r.endsWith(".mpd")
    }
}
