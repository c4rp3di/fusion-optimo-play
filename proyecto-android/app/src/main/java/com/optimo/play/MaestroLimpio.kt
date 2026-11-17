package com.optimo.play

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * Servidor local (127.0.0.1) por el que libVLC abre un canal HLS para evitar que se congele por sus subtítulos.
 *
 * Algunos directos (p. ej. Pluto TV) declaran una pista WebVTT cuyos trozos «de relleno» (`/placeholder/`) llevan una marca de tiempo
 * fija que no casa con el vídeo, y libVLC 3 se queda esperándola. Aquí se descarga el manifiesto original (con su Referer y
 * User-Agent) y:
 *  - modo normal: se conservan las pistas de subtítulos, pero sus trozos de relleno se sustituyen por un WebVTT vacío local;
 *    los trozos con texto real se dejan pasar tal cual desde el servidor original;
 *  - modo [quitar]: se eliminan las pistas de subtítulos.
 * El vídeo y el audio los sigue bajando libVLC directamente del servidor original. Si lo que llega no es un manifiesto maestro
 * (o no tiene subtítulos y no hay que quitarlos), se redirige a la dirección original.
 */
class MaestroLimpio(
    private val url: String,
    private val referer: String?,
    private val ua: String?,
    private val quitar: Boolean
) {

    private var ss: ServerSocket? = null
    private val token = UUID.randomUUID().toString().replace("-", "").take(12)
    @Volatile private var activo = false
    @Volatile private var puerto = 0

    /** Arranca y devuelve la dirección local que debe abrir libVLC. */
    @Synchronized
    fun iniciar(): String {
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        ss = s
        puerto = s.localPort
        activo = true
        val t = Thread {
            try {
                while (activo) {
                    val c = s.accept()
                    Thread { atender(c) }.start()
                }
            } catch (e: Exception) {
                // Cerrado.
            }
        }
        t.isDaemon = true
        t.start()
        return base() + "master.m3u8"
    }

    @Synchronized
    fun detener() {
        activo = false
        try { ss?.close() } catch (e: Exception) { /* nada */ }
        ss = null
    }

    private fun base(): String = "http://127.0.0.1:" + puerto + "/" + token + "/"

    private fun responder(c: Socket, cabeceras: String, cuerpo: ByteArray) {
        val o = c.getOutputStream()
        o.write((cabeceras + "Content-Length: " + cuerpo.size + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        o.write(cuerpo)
        o.flush()
    }

    private fun descargar(dir: String): Pair<String, String>? {
        return try {
            val con = Red.conexion(dir, ua, referer)
            val cod = con.responseCode
            var res: Pair<String, String>? = null
            if (cod < 400) {
                val fin = con.url.toString()
                res = Pair(String(con.inputStream.use { Red.leerBytes(it, 2_000_000) }, Charsets.UTF_8), fin)
            }
            try { con.disconnect() } catch (e: Throwable) { /* nada */ }
            res
        } catch (e: Exception) {
            null
        }
    }

    private fun atender(c: Socket) {
        try {
            c.soTimeout = 15000
            val ent = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val linea = ent.readLine() ?: return
            while (true) {
                val l = ent.readLine() ?: break
                if (l.isEmpty()) break
            }
            val ruta = linea.split(" ").getOrNull(1) ?: ""
            if (!ruta.startsWith("/" + token + "/")) {
                responder(c, "HTTP/1.1 403 Forbidden\r\n", ByteArray(0))
                return
            }
            val sub = ruta.substring(token.length + 2)
            val tipoLista = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n"
            when {
                sub.startsWith("vacio.vtt") ->
                    responder(c, "HTTP/1.1 200 OK\r\nContent-Type: text/vtt; charset=utf-8\r\n", "WEBVTT\n\n".toByteArray())
                sub.startsWith("sub.m3u8") -> {
                    val u = URLDecoder.decode(sub.substringAfter("?u=", ""), "UTF-8")
                    val d = if (u.isEmpty()) null else descargar(u)
                    if (d == null) {
                        responder(c, "HTTP/1.1 502 Bad Gateway\r\n", ByteArray(0))
                    } else {
                        responder(c, tipoLista, reescribirSub(d.first, d.second).toByteArray(Charsets.UTF_8))
                    }
                }
                else -> {
                    val d = descargar(url)
                    val t = d?.first
                    val hayMaestro = t != null && t.trimStart().startsWith("#EXTM3U") && t.contains("#EXT-X-STREAM-INF")
                    if (d == null || t == null || !hayMaestro || (!quitar && !t.contains("TYPE=SUBTITLES"))) {
                        // Nada que arreglar: que libVLC lo abra tal cual.
                        responder(c, "HTTP/1.1 302 Found\r\nLocation: " + url + "\r\n", ByteArray(0))
                    } else {
                        responder(c, tipoLista, reescribirMaestro(t, d.second).toByteArray(Charsets.UTF_8))
                    }
                }
            }
        } catch (e: Exception) {
            // Cliente cerrado.
        } finally {
            try { c.close() } catch (e: Exception) { /* nada */ }
        }
    }

    private fun absoluta(base: String, ref: String): String =
        try { URL(URL(base), ref).toString() } catch (e: Exception) { ref }

    private val reUri = Regex("URI=\"([^\"]*)\"")

    private fun reescribirMaestro(txt: String, base: String): String {
        val sal = StringBuilder()
        for (crudo in txt.lines()) {
            val l = crudo.trim()
            if (l.isEmpty()) continue
            val esSub = l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=SUBTITLES")
            if (esSub && quitar) continue
            if (l.startsWith("#")) {
                var r = l
                if (quitar && r.startsWith("#EXT-X-STREAM-INF")) {
                    r = r.replace(Regex(",?SUBTITLES=\"[^\"]*\""), "").replace(":,", ":")
                }
                r = reUri.replace(r) { m ->
                    val abs = absoluta(base, m.groupValues[1])
                    if (esSub) "URI=\"" + base() + "sub.m3u8?u=" + URLEncoder.encode(abs, "UTF-8") + "\""
                    else "URI=\"" + abs + "\""
                }
                sal.append(r).append('\n')
            } else {
                sal.append(absoluta(base, l)).append('\n')
            }
        }
        return sal.toString()
    }

    /** Lista de una pista de subtítulos: los trozos de relleno pasan a ser un WebVTT vacío local; el resto, direcciones absolutas. */
    private fun reescribirSub(txt: String, base: String): String {
        val sal = StringBuilder()
        for (crudo in txt.lines()) {
            val l = crudo.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#")) {
                sal.append(reUri.replace(l) { m -> "URI=\"" + absoluta(base, m.groupValues[1]) + "\"" }).append('\n')
            } else if (l.contains("placeholder", true) || l.contains("empty_sub", true)) {
                sal.append(base() + "vacio.vtt").append('\n')
            } else {
                sal.append(absoluta(base, l)).append('\n')
            }
        }
        return sal.toString()
    }
}
