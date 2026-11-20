package com.optimo.play

import android.net.Uri
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo en 127.0.0.1 que le sirve a libVLC los archivos de Mega ya descifrados.
 *
 * Mega guarda cada archivo cifrado con AES-128-CTR. Cuando libVLC pide un rango de bytes (para saltar en el vídeo),
 * se le pide a Mega ese rango (alineado a bloques de 16 bytes), se descifra con el contador que toca y se descarta
 * lo sobrante del primer bloque. Las direcciones de descarga de Mega se piden al abrir cada archivo y se renuevan
 * si caducan. La ruta lleva un token aleatorio: otra app del móvil no puede leer el flujo sin conocerlo.
 * [alError] avisa de fallos de Mega (cuota agotada, enlace caído…) para mostrarlos en pantalla.
 */
class ServidorMega(
    private val cli: MegaCliente,
    private val alError: (String) -> Unit,
    /**
     * false (por defecto): solo escucha en 127.0.0.1, para libVLC.
     * true: escucha en la red local para una tele o un Chromecast (solo mientras se emite), con CORS y cabeceras DLNA.
     * La ruta sigue llevando un token aleatorio.
     */
    private val lan: Boolean = false
) {

    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val token = UUID.randomUUID().toString().replace("-", "").take(12)
    private val nodos = ConcurrentHashMap<String, MegaFuente>()
    @Volatile private var activo = false
    /** Tipo MIME que se anuncia para los MKV: las teles DLNA (Samsung) esperan «video/x-mkv»; Chromecast, «video/x-matroska». */
    @Volatile var mimeMkv: String = "video/x-matroska"

    private fun mimeDe(nombre: String): String {
        val t = Utiles.tipoMime(nombre)
        return if (t == "video/x-matroska") mimeMkv else t
    }

    @Synchronized
    fun iniciar(): Int {
        val actual = ss
        if (actual != null) return actual.localPort
        val s = if (lan) ServerSocket(0, 16) else ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        ss = s
        activo = true
        val t = Thread {
            try {
                while (activo) {
                    val c = s.accept()
                    pool.execute { atender(c) }
                }
            } catch (e: Exception) {
                // Servidor cerrado.
            }
        }
        t.isDaemon = true
        t.start()
        return s.localPort
    }

    /** Registra el archivo y devuelve la dirección local con la que se reproduce. */
    fun url(n: MegaFuente): String {
        nodos[n.handle] = n
        return "http://127.0.0.1:${iniciar()}/$token/${n.handle}/${Uri.encode(n.nombre)}"
    }

    /** Archivo que se registró con [url] (el identificador es el segundo tramo de la ruta local). */
    fun nodo(handle: String): MegaFuente? = nodos[handle]

    /** Registra el archivo y devuelve la dirección en la red local ([ip] del móvil) para la tele. Solo con lan = true. */
    fun urlLan(n: MegaFuente, ip: String): String {
        nodos[n.handle] = n
        return "http://$ip:${iniciar()}/$token/${n.handle}/${Uri.encode(n.nombre)}"
    }

    @Synchronized
    fun detener() {
        activo = false
        try { ss?.close() } catch (e: Exception) { /* nada */ }
        ss = null
        pool.shutdownNow()
    }

    private fun responder(c: Socket, codigo: Int, texto: String) {
        try {
            val cors = if (lan) "Access-Control-Allow-Origin: *\r\n" else ""
            val cuerpo = "HTTP/1.1 $codigo $texto\r\nContent-Length: 0\r\n${cors}Connection: close\r\n\r\n"
            c.getOutputStream().write(cuerpo.toByteArray(Charsets.ISO_8859_1))
            c.getOutputStream().flush()
        } catch (e: Exception) {
            // Cliente cerrado.
        }
    }

    private fun atender(c: Socket) {
        var lec: MegaLectura? = null
        var info = ""
        var corte = ""
        var cnt: Contador? = null
        try {
            c.soTimeout = 15000
            val ent = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val linea = ent.readLine() ?: return
            val partes = linea.split(" ")
            if (partes.size < 2) return
            val metodo = partes[0].uppercase()
            val ruta = partes[1]
            var rango: String? = null
            var ua = ""
            while (true) {
                val l = ent.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("range:", true)) rango = l.substring(6).trim()
                if (l.startsWith("user-agent:", true)) ua = l.substring(11).trim()
            }
            if (lan) info = "MEGA " + metodo + " " + ruta.replace(token, "…") + " · Range: " + (rango ?: "-") + " · UA: " + ua
            if (lan && metodo == "OPTIONS") {
                responder(c, 204, "No Content")
                return
            }
            val seg = ruta.substringBefore('?').split("/").filter { it.isNotEmpty() }
            if (seg.size < 2 || seg[0] != token) {
                responder(c, 403, "Forbidden")
                return
            }
            val n = nodos[seg[1]]
            if (n == null) {
                responder(c, 404, "Not Found")
                return
            }
            val total = n.tam
            if (total <= 0L) {
                responder(c, 416, "Range Not Satisfiable")
                return
            }
            var ini = 0L
            var fin = total - 1
            var parcial = false
            if (rango != null) {
                val m = Regex("bytes=(\\d*)-(\\d*)").find(rango)
                if (m != null) {
                    val g1 = m.groupValues[1]
                    val g2 = m.groupValues[2]
                    if (g1.isNotEmpty()) {
                        ini = g1.toLong()
                        if (g2.isNotEmpty()) fin = minOf(g2.toLong(), total - 1)
                    } else if (g2.isNotEmpty()) {
                        ini = maxOf(0L, total - g2.toLong())
                    }
                    parcial = true
                }
            }
            if (ini >= total || ini > fin) {
                responder(c, 416, "Range Not Satisfiable")
                return
            }
            c.soTimeout = 0
            val contador = Contador(c.getOutputStream())
            cnt = contador
            val sal = BufferedOutputStream(contador, 65536)
            val cab = StringBuilder()
            cab.append(if (parcial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            cab.append("Content-Type: ").append(mimeDe(n.nombre)).append("\r\n")
            cab.append("Accept-Ranges: bytes\r\n")
            cab.append("Content-Length: ").append(fin - ini + 1).append("\r\n")
            if (parcial) cab.append("Content-Range: bytes $ini-$fin/$total\r\n")
            if (lan) {
                // Chromecast exige CORS; las Samsung/DLNA piden estas cabeceras para permitir saltar de posición.
                cab.append("Access-Control-Allow-Origin: *\r\n")
                cab.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
                cab.append("transferMode.dlna.org: Streaming\r\n")
                cab.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            }
            cab.append("Connection: close\r\n\r\n")
            if (metodo == "HEAD") {
                sal.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
                sal.flush()
                return
            }
            // Primero se conecta con Mega: si falla, se responde con error en vez de dejar a libVLC con una cabecera vacía.
            val lectura: MegaLectura = try {
                n.abrir(cli, ini, fin)
            } catch (e: MegaException) {
                alError(e.message ?: "Error de Mega")
                responder(c, if (e.codigo == MegaException.CUOTA) 503 else 502, "Bad Gateway")
                return
            } catch (e: Exception) {
                alError("No se pudo conectar con Mega: " + (e.message ?: e.javaClass.simpleName))
                responder(c, 502, "Bad Gateway")
                return
            }
            lec = lectura
            sal.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
            lectura.volcar(sal)
            sal.flush()
        } catch (e: Exception) {
            // Lo normal: libVLC cierra la conexión al saltar de posición.
            corte = e.javaClass.simpleName + ": " + (e.message ?: "")
        } finally {
            if (lan && info.isNotEmpty()) {
                RegistroEmision.anotar(info + " → enviados " + (cnt?.total ?: 0L) + " B" + (if (corte.isNotEmpty()) " · cortado (" + corte + ")" else ""))
            }
            try { lec?.close() } catch (e: Exception) { /* nada */ }
            try { c.close() } catch (e: Exception) { /* nada */ }
        }
    }
}
