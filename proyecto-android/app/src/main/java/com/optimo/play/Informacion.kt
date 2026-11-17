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
        sb.append("URI recibida:\n  ").append(recortar(uri, 400)).append('\n')
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
