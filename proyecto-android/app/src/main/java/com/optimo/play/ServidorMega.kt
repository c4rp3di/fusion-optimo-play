package com.optimo.play

import android.net.Uri
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    private val alError: (String) -> Unit
) {

    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val token = UUID.randomUUID().toString().replace("-", "").take(12)
    private val nodos = ConcurrentHashMap<String, MegaNodo>()
    @Volatile private var activo = false

    @Synchronized
    fun iniciar(): Int {
        val actual = ss
        if (actual != null) return actual.localPort
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
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
    fun url(n: MegaNodo): String {
        nodos[n.handle] = n
        return "http://127.0.0.1:${iniciar()}/$token/${n.handle}/${Uri.encode(n.nombre)}"
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
            val cuerpo = "HTTP/1.1 $codigo $texto\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            c.getOutputStream().write(cuerpo.toByteArray(Charsets.ISO_8859_1))
            c.getOutputStream().flush()
        } catch (e: Exception) {
            // Cliente cerrado.
        }
    }

    private fun atender(c: Socket) {
        var con: HttpURLConnection? = null
        try {
            c.soTimeout = 15000
            val ent = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val linea = ent.readLine() ?: return
            val partes = linea.split(" ")
            if (partes.size < 2) return
            val metodo = partes[0]
            val ruta = partes[1]
            var rango: String? = null
            while (true) {
                val l = ent.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("range:", true)) rango = l.substring(6).trim()
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
            val sal = BufferedOutputStream(c.getOutputStream(), 65536)
            val cab = StringBuilder()
            cab.append(if (parcial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            cab.append("Content-Type: ").append(Utiles.tipoMime(n.nombre)).append("\r\n")
            cab.append("Accept-Ranges: bytes\r\n")
            cab.append("Content-Length: ").append(fin - ini + 1).append("\r\n")
            if (parcial) cab.append("Content-Range: bytes $ini-$fin/$total\r\n")
            cab.append("Connection: close\r\n\r\n")
            if (metodo == "HEAD") {
                sal.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
                sal.flush()
                return
            }
            // Primero se conecta con Mega: si falla, se responde con error en vez de dejar a libVLC con una cabecera vacía.
            val bloque = ini and 15L.inv()
            val conexion: HttpURLConnection = try {
                conectar(n, bloque, fin)
            } catch (e: MegaException) {
                alError(e.message ?: "Error de Mega")
                responder(c, if (e.codigo == MegaException.CUOTA) 503 else 502, "Bad Gateway")
                return
            } catch (e: Exception) {
                alError("No se pudo conectar con Mega: " + (e.message ?: e.javaClass.simpleName))
                responder(c, 502, "Bad Gateway")
                return
            }
            con = conexion
            sal.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
            enviar(sal, conexion, n, ini, bloque, fin)
        } catch (e: Exception) {
            // Lo normal: libVLC cierra la conexión al saltar de posición.
        } finally {
            try { con?.disconnect() } catch (e: Exception) { /* nada */ }
            try { c.close() } catch (e: Exception) { /* nada */ }
        }
    }

    /** Abre la descarga de Mega del rango [desde, hasta] (ambos incluidos). Renueva la dirección si ha caducado. */
    private fun conectar(n: MegaNodo, desde: Long, hasta: Long): HttpURLConnection {
        var url = cli.urlDescarga(n, false)
        var renovada = false
        while (true) {
            val con = URL("$url/$desde-$hasta").openConnection() as HttpURLConnection
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.setRequestProperty("User-Agent", "Mozilla/5.0")
            val codigo = try { con.responseCode } catch (e: Exception) { con.disconnect(); throw e }
            if (codigo in 200..299) return con
            val espera = con.getHeaderField("X-MEGA-Time-Left")?.toLongOrNull()
            con.disconnect()
            when {
                codigo == 509 || codigo == 429 -> {
                    val extra = if (espera != null && espera > 0) " Vuelve a intentarlo en " + maxOf(1L, espera / 60) + " min." else ""
                    throw MegaException(
                        MegaException.CUOTA,
                        "Se ha superado el límite de transferencia de Mega.$extra Prueba más tarde o con otra conexión."
                    )
                }
                (codigo == 403 || codigo == 404 || codigo == 410) && !renovada -> {
                    renovada = true
                    url = cli.urlDescarga(n, true)
                }
                else -> throw MegaException(MegaException.GENERAL, "Mega respondió HTTP $codigo al descargar")
            }
        }
    }

    private fun enviar(sal: BufferedOutputStream, con: HttpURLConnection, n: MegaNodo, ini: Long, bloque: Long, fin: Long) {
        // El contador de AES-CTR de Mega: 8 bytes de nonce y 8 bytes con el nº de bloque de 16 bytes (big-endian).
        val iv = ByteArray(16)
        System.arraycopy(n.nonce, 0, iv, 0, 8)
        var b = bloque / 16
        for (i in 15 downTo 8) {
            iv[i] = (b and 0xFF).toByte()
            b = b ushr 8
        }
        val cifra = Cipher.getInstance("AES/CTR/NoPadding")
        cifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(n.aes, "AES"), IvParameterSpec(iv))
        var saltar = (ini - bloque).toInt()
        var restante = fin - ini + 1
        val entrada = con.inputStream
        val buf = ByteArray(65536)
        val claro = ByteArray(65536 + 64)
        while (restante > 0) {
            val r = entrada.read(buf)
            if (r < 0) break
            val k = cifra.update(buf, 0, r, claro, 0)
            var off = 0
            var cnt = k
            if (saltar > 0) {
                val s = minOf(saltar, cnt)
                off = s
                cnt -= s
                saltar -= s
            }
            if (cnt > 0) {
                val w = minOf(cnt.toLong(), restante).toInt()
                sal.write(claro, off, w)
                restante -= w
            }
        }
        sal.flush()
    }
}
