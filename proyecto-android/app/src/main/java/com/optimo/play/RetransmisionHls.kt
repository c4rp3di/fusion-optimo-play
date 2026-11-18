package com.optimo.play

import android.content.Context
import java.io.IOException
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Retransmisión de un canal HLS a una tele DLNA SIN libVLC: los segmentos de la mayoría de los canales ya son MPEG-TS
 * (H.264 + AAC), así que basta con bajarlos y servirlos uno detrás de otro en «/live.ts» (red local, sin recodificar).
 * Elige una sola variante (según la calidad del usuario) para que el flujo no cambie de resolución a mitad.
 *
 * Si la lista no sirve para esto (segmentos fMP4/CMAF, cifrados o que no son .ts), se usa el método anterior con libVLC
 * ([Retransmision]) en el mismo puerto, de modo que la dirección que ya se le dio a la tele sigue valiendo.
 */
class RetransmisionHls(
    private val ctx: Context,
    private val url: String,
    private val referer: String?,
    private val ua: String?,
    /** [Ajustes.calidadHls]: 0/1 máxima, 2 mínima, 3 hasta 720p, 4 hasta 480p. */
    private val calidad: Int,
    private val opcionesVlc: List<String>,
    /** true: reproducción en el propio móvil (solo 127.0.0.1 y sin respaldo con libVLC; si no vale, la conexión se cierra). */
    private val local: Boolean = false
) {
    private var ss: ServerSocket? = null
    @Volatile private var activo = false
    @Volatile private var puerto = 0
    private val listo = CountDownLatch(1)
    @Volatile private var lista: String? = null
    @Volatile private var vlc: Retransmision? = null
    @Volatile private var vlcPuerto = 0

    private class Lista(val seq: Long, val segs: List<String>, val objetivo: Int, val fin: Boolean, val problema: String?)

    /** Arranca el servidor y devuelve el puerto en el que se sirve «/live.ts». */
    fun iniciar(): Int {
        val s = if (local) ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) else ServerSocket(0)
        ss = s
        puerto = s.localPort
        activo = true
        val acepta = Thread {
            try {
                while (activo) {
                    val c = s.accept()
                    Thread { servir(c) }.also { it.isDaemon = true }.start()
                }
            } catch (e: Exception) {
                // Cerrado.
            }
        }
        acepta.isDaemon = true
        acepta.start()
        val prep = Thread { preparar() }
        prep.isDaemon = true
        prep.start()
        return puerto
    }

    fun liberar() {
        activo = false
        try { ss?.close() } catch (e: Exception) { /* nada */ }
        ss = null
        vlc?.liberar()
        vlc = null
        listo.countDown()
    }

    // ------------------------------------------------------------------ preparación

    private fun abrir(u: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", if (!ua.isNullOrBlank()) ua else "Mozilla/5.0")
        if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
        return c
    }

    private fun descargar(u: String): String {
        val c = abrir(u)
        try {
            val cod = c.responseCode
            if (cod !in 200..299) throw IOException("HTTP $cod")
            return c.inputStream.use { String(Red.leerBytes(it, 2_000_000), Charsets.UTF_8) }
        } finally {
            try { c.disconnect() } catch (e: Throwable) { /* nada */ }
        }
    }

    private fun preparar() {
        try {
            val txt = descargar(url)
            val media = if (txt.contains("#EXT-X-STREAM-INF")) {
                elegirVariante(url, txt) ?: throw IOException("el manifiesto no tiene variantes")
            } else {
                url
            }
            val mt = if (media == url && !txt.contains("#EXT-X-STREAM-INF")) txt else descargar(media)
            val l = parsear(media, mt)
            if (l.problema != null) throw IOException(l.problema)
            if (l.segs.isEmpty()) throw IOException("la lista no tiene segmentos")
            lista = media
            RegistroEmision.anotar(
                "HLS→TS propio: " + l.segs.size + " segmentos, objetivo " + l.objetivo + " s, calidad " + calidad +
                    " · lista " + media.substringBefore('?').takeLast(70)
            )
            listo.countDown()
        } catch (e: Exception) {
            if (local) {
                RegistroEmision.anotar("Modo compatible: este canal no se puede encadenar (" + (e.message ?: e.javaClass.simpleName) + ")")
                listo.countDown()
            } else {
                RegistroEmision.anotar("HLS→TS propio no vale (" + (e.message ?: e.javaClass.simpleName) + "): se usa libVLC")
                respaldoVlc()
            }
        }
    }

    /**
     * Pasa al método con libVLC: él emite el TS en un puerto aparte y este servidor (el que conoce la tele) lo canaliza
     * con las cabeceras DLNA correctas y lo anota en el registro.
     */
    private fun respaldoVlc() {
        try {
            val p2 = ServerSocket(0).use { it.localPort }
            val r = Retransmision(ctx, url, referer, ua, opcionesVlc, p2)
            r.iniciar()
            vlc = r
            vlcPuerto = p2
        } catch (t: Throwable) {
            RegistroEmision.anotar("libVLC tampoco pudo retransmitir: " + (t.message ?: t.javaClass.simpleName))
        }
        listo.countDown()
    }

    private fun cabecera(): String =
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: video/vnd.dlna.mpeg-tts\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "transferMode.dlna.org: Streaming\r\n" +
            "contentFeatures.dlna.org: DLNA.ORG_PN=AVC_TS_MP_HD_AAC_MULT5;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
            "Connection: close\r\n\r\n"

    /** Copia a la tele lo que emite libVLC en 127.0.0.1 (reintenta la conexión unos segundos: libVLC tarda en abrir el canal). */
    private fun tunelVlc(c: Socket, metodo: String): Long {
        var con: HttpURLConnection? = null
        var intentos = 0
        while (activo && con == null && intentos < 20) {
            try {
                val x = URL("http://127.0.0.1:" + vlcPuerto + "/live.ts").openConnection() as HttpURLConnection
                x.connectTimeout = 3000
                x.readTimeout = 20000
                x.responseCode
                con = x
            } catch (e: IOException) {
                intentos++
                try { Thread.sleep(500) } catch (i: InterruptedException) { return 0L }
            }
        }
        val x = con ?: run {
            RegistroEmision.anotar("HLS→TS vía libVLC: no se pudo conectar con libVLC en el móvil")
            return 0L
        }
        var total = 0L
        try {
            c.soTimeout = 0
            val o = c.getOutputStream()
            o.write(cabecera().toByteArray(Charsets.ISO_8859_1))
            o.flush()
            if (metodo == "HEAD") return 0L
            val buf = ByteArray(65536)
            val ent = x.inputStream
            while (activo) {
                val r = try { ent.read(buf) } catch (e: IOException) { -1 }
                if (r < 0) break
                o.write(buf, 0, r)
                total += r
            }
            o.flush()
        } finally {
            try { x.disconnect() } catch (e: Throwable) { /* nada */ }
        }
        return total
    }

    private fun elegirVariante(base: String, txt: String): String? {
        class V(val url: String, val bw: Long, val alto: Int)
        val vs = ArrayList<V>()
        val lineas = txt.lines().map { it.trim() }
        var i = 0
        while (i < lineas.size) {
            val l = lineas[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val alto = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                var j = i + 1
                while (j < lineas.size && (lineas[j].isEmpty() || lineas[j].startsWith("#"))) j++
                if (j < lineas.size) vs.add(V(URL(URL(base), lineas[j]).toString(), bw, alto))
                i = j
            }
            i++
        }
        if (vs.isEmpty()) return null
        val mayor = vs.sortedWith(compareBy<V>({ it.alto }, { it.bw })).last()
        val menor = vs.sortedWith(compareBy<V>({ it.bw }, { it.alto })).first()
        val elegida = when (calidad) {
            2 -> menor
            3 -> vs.filter { it.alto in 1..720 }.maxByOrNull { it.alto * 10_000_000L + it.bw } ?: menor
            4 -> vs.filter { it.alto in 1..480 }.maxByOrNull { it.alto * 10_000_000L + it.bw } ?: menor
            else -> mayor
        }
        RegistroEmision.anotar(
            "Variantes: " + vs.joinToString("; ") { (if (it.alto > 0) it.alto.toString() + "p " else "") + (it.bw / 1000) + " kbps" } +
                " → elegida " + (if (elegida.alto > 0) elegida.alto.toString() + "p " else "") + (elegida.bw / 1000) + " kbps"
        )
        return elegida.url
    }

    private fun parsear(base: String, txt: String): Lista {
        var seq = 0L
        var objetivo = 6
        var fin = false
        var problema: String? = null
        val segs = ArrayList<String>()
        for (raw in txt.lines()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            when {
                t.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = t.substringAfter(':').trim().toLongOrNull() ?: 0L
                t.startsWith("#EXT-X-TARGETDURATION:") -> objetivo = t.substringAfter(':').trim().toIntOrNull() ?: 6
                t.startsWith("#EXT-X-ENDLIST") -> fin = true
                t.startsWith("#EXT-X-MAP") -> problema = "segmentos fMP4 (EXT-X-MAP)"
                t.startsWith("#EXT-X-KEY") -> if (!t.contains("METHOD=NONE")) problema = "segmentos cifrados"
                t.startsWith("#") -> { /* otra etiqueta */ }
                else -> segs.add(URL(URL(base), t).toString())
            }
        }
        if (problema == null && segs.isNotEmpty()) {
            val ext = segs[0].substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            if (ext in setOf("m4s", "mp4", "cmfv", "cmfa", "aac", "m4a", "vtt")) problema = "los segmentos no son .ts (." + ext + ")"
        }
        return Lista(seq, segs, objetivo, fin, problema)
    }

    // ------------------------------------------------------------------ servir

    private fun servir(c: Socket) {
        var total = 0L
        var nota = ""
        try {
            c.soTimeout = 15000
            val ent = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val linea = ent.readLine() ?: return
            val metodo = linea.substringBefore(' ').uppercase()
            var uaTele = ""
            while (true) {
                val l = ent.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("user-agent:", true)) uaTele = l.substring(11).trim()
            }
            RegistroEmision.anotar("HLS→TS " + metodo + " " + linea.substringAfter(' ').substringBeforeLast(' ') + " · UA: " + uaTele)
            val l0 = if (listo.await(12, TimeUnit.SECONDS)) lista else null
            if (l0 == null) {
                if (vlcPuerto > 0) {
                    RegistroEmision.anotar("HLS→TS: canal servido con libVLC (puerto interno " + vlcPuerto + ")")
                    total = tunelVlc(c, metodo)
                    nota = "vía libVLC"
                } else {
                    RegistroEmision.anotar("HLS→TS: falló la preparación y libVLC no está activo; se cierra esta conexión")
                }
                return
            }
            c.soTimeout = 0
            val o = c.getOutputStream()
            val cab = cabecera()
            o.write(cab.toByteArray(Charsets.ISO_8859_1))
            o.flush()
            if (metodo == "HEAD") return
            var siguiente = -1L
            var enviados = 0
            val buf = ByteArray(65536)
            while (activo) {
                val l = parsear(l0, descargar(l0))
                if (l.problema != null) {
                    nota = "la lista cambió: " + l.problema
                    break
                }
                if (siguiente < 0) siguiente = maxOf(l.seq, l.seq + l.segs.size - 2)
                if (siguiente < l.seq) siguiente = l.seq
                var nuevo = false
                while (activo && siguiente < l.seq + l.segs.size) {
                    val seg = l.segs[(siguiente - l.seq).toInt()]
                    siguiente++
                    nuevo = true
                    if (enviados < 3) RegistroEmision.anotar("HLS→TS segmento " + (siguiente - 1) + ": " + seg.substringBefore('?').takeLast(60))
                    enviados++
                    val sc = try { abrir(seg) } catch (e: Exception) { null }
                    if (sc == null) continue
                    try {
                        val cod = try { sc.responseCode } catch (e: IOException) { -1 }
                        if (cod !in 200..299) {
                            RegistroEmision.anotar("HLS→TS: segmento con HTTP " + cod + ", se salta")
                            continue
                        }
                        val e2 = sc.inputStream
                        while (true) {
                            val r = try { e2.read(buf) } catch (e: IOException) { RegistroEmision.anotar("HLS→TS: error leyendo un segmento: " + e.message); -1 }
                            if (r < 0) break
                            o.write(buf, 0, r)
                            total += r
                        }
                    } finally {
                        try { sc.disconnect() } catch (e: Throwable) { /* nada */ }
                    }
                }
                if (l.fin) {
                    nota = "fin de la lista"
                    break
                }
                if (!nuevo) {
                    try { Thread.sleep(maxOf(1000L, l.objetivo * 500L)) } catch (e: InterruptedException) { break }
                }
            }
            o.flush()
        } catch (e: Exception) {
            nota = e.javaClass.simpleName + ": " + (e.message ?: "")
        } finally {
            RegistroEmision.anotar("HLS→TS conexión cerrada · enviados " + total + " B" + (if (nota.isNotEmpty()) " · " + nota else ""))
            try { c.close() } catch (e: Exception) { /* nada */ }
        }
    }
}
