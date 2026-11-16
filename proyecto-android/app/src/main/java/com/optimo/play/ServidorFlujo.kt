package com.optimo.play

import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo en 127.0.0.1 que le sirve a libVLC el archivo del torrent que se está descargando.
 * Admite peticiones Range (para saltar en el vídeo) y espera a que lleguen las piezas necesarias.
 * La ruta lleva un token aleatorio, así que otra app del móvil no puede leer el flujo sin conocerlo.
 * Sin dependencias: solo sockets del sistema.
 */
class ServidorFlujo(private val motor: MotorTorrent) {

    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val token = UUID.randomUUID().toString().replace("-", "").take(12)
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

    fun url(a: ArchivoTorrent): String = "http://127.0.0.1:${iniciar()}/$token/${a.indice}/${Uri.encode(a.nombre)}"

    @Synchronized
    fun detener() {
        activo = false
        try { ss?.close() } catch (e: Exception) { /* nada */ }
        ss = null
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
            val a = motor.archivo
            if (a == null || seg[1].toIntOrNull() != a.indice) {
                responder(c, 404, "Not Found")
                return
            }
            val total = a.tam
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
            cab.append("Content-Type: ").append(Utiles.tipoMime(a.nombre)).append("\r\n")
            cab.append("Accept-Ranges: bytes\r\n")
            cab.append("Content-Length: ").append(fin - ini + 1).append("\r\n")
            if (parcial) cab.append("Content-Range: bytes $ini-$fin/$total\r\n")
            cab.append("Connection: close\r\n\r\n")
            sal.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
            if (metodo == "HEAD") {
                sal.flush()
                return
            }
            enviar(sal, a, ini, fin)
        } catch (e: Exception) {
            // Lo normal: libVLC cierra la conexión al saltar de posición.
        } finally {
            try { c.close() } catch (e: Exception) { /* nada */ }
        }
    }

    private fun enviar(sal: BufferedOutputStream, a: ArchivoTorrent, ini: Long, fin: Long) {
        val dir = motor.carpeta ?: return
        val f = File(dir, a.ruta)
        var raf: RandomAccessFile? = null
        val buf = ByteArray(65536)
        var pos = ini
        try {
            while (pos <= fin) {
                val n = minOf(256L * 1024L, fin - pos + 1L)
                if (!motor.esperarRango(a, pos, n)) return
                if (raf == null) raf = RandomAccessFile(f, "r")
                raf.seek(pos)
                var restante = n
                while (restante > 0) {
                    val r = raf.read(buf, 0, minOf(buf.size.toLong(), restante).toInt())
                    if (r <= 0) return
                    sal.write(buf, 0, r)
                    restante -= r
                    pos += r
                }
            }
            sal.flush()
        } finally {
            try { raf?.close() } catch (e: Exception) { /* nada */ }
        }
    }
}
