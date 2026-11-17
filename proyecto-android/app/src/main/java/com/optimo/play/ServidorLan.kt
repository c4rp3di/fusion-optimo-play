package com.optimo.play

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo en la red local para que una tele o un Chromecast puedan leer lo que el móvil tiene:
 * el archivo del torrent que se está bajando o un archivo del dispositivo. Solo existe mientras se emite.
 * Cada ruta lleva un token aleatorio; admite Range, CORS (Chromecast lo exige) y las cabeceras DLNA que piden las Samsung.
 */
class ServidorLan(private val ctx: Context, private val motor: () -> MotorTorrent) {

    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val token = UUID.randomUUID().toString().replace("-", "").take(16)
    private val archivos = ConcurrentHashMap<Int, Pair<Uri, String>>()
    private var siguienteId = 1
    @Volatile private var activo = false
    private val textos = ConcurrentHashMap<Int, Pair<ByteArray, String>>()
    /** Si no es null, se anuncia en las respuestas del vídeo (cabecera «CaptionInfo.sec» de Samsung). */
    @Volatile var subCabecera: String? = null

    @Synchronized
    private fun iniciar(): Int {
        val actual = ss
        if (actual != null) return actual.localPort
        val s = ServerSocket(0, 16)
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

    /** Dirección en la red local del archivo del torrent; null si no hay red local. */
    fun urlTorrent(a: ArchivoTorrent): String? {
        val ip = Utiles.ipLan(ctx) ?: return null
        return "http://" + ip + ":" + iniciar() + "/" + token + "/t/" + a.indice + "/" + Uri.encode(a.nombre)
    }

    /** Dirección en la red local de un archivo del dispositivo (content:// o file://). */
    @Synchronized
    fun urlArchivo(u: Uri, nombre: String): String? {
        val ip = Utiles.ipLan(ctx) ?: return null
        val id = siguienteId++
        archivos[id] = Pair(u, nombre)
        return "http://" + ip + ":" + iniciar() + "/" + token + "/f/" + id + "/" + Uri.encode(nombre)
    }

    /** Sirve un subtítulo ya preparado y devuelve su dirección. */
    @Synchronized
    fun urlTexto(bytes: ByteArray, ext: String, mime: String): String? {
        val ip = Utiles.ipLan(ctx) ?: return null
        val id = siguienteId++
        textos[id] = Pair(bytes, mime)
        return "http://" + ip + ":" + iniciar() + "/" + token + "/s/" + id + "/sub." + ext
    }

    @Synchronized
    fun detener() {
        activo = false
        try { ss?.close() } catch (e: Exception) { /* nada */ }
        ss = null
        archivos.clear()
        textos.clear()
    }

    private interface Origen {
        val tam: Long
        val nombre: String
        fun enviar(sal: OutputStream, ini: Long, fin: Long)
    }

    private fun origenTorrent(indice: Int): Origen? {
        val m = motor()
        val a = m.archivo ?: return null
        if (a.indice != indice) return null
        return object : Origen {
            override val tam: Long get() = a.tam
            override val nombre: String get() = a.nombre
            override fun enviar(sal: OutputStream, ini: Long, fin: Long) {
                val dir = m.carpeta ?: return
                var raf: RandomAccessFile? = null
                val buf = ByteArray(65536)
                var pos = ini
                try {
                    while (pos <= fin) {
                        val n = minOf(256L * 1024L, fin - pos + 1L)
                        if (!m.esperarRango(a, pos, n)) return
                        if (raf == null) raf = RandomAccessFile(File(dir, a.ruta), "r")
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
                } finally {
                    try { raf?.close() } catch (e: Exception) { /* nada */ }
                }
            }
        }
    }

    private fun origenArchivo(id: Int): Origen? {
        val par = archivos[id] ?: return null
        val uri = par.first
        val tamano: Long = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        if (tamano <= 0) return null
        return object : Origen {
            override val tam: Long get() = tamano
            override val nombre: String get() = par.second
            override fun enviar(sal: OutputStream, ini: Long, fin: Long) {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return
                pfd.use {
                    val ch = FileInputStream(it.fileDescriptor).channel
                    ch.position(ini)
                    val buf = java.nio.ByteBuffer.allocate(65536)
                    var restante = fin - ini + 1
                    while (restante > 0) {
                        buf.clear()
                        if (restante < buf.capacity()) buf.limit(restante.toInt())
                        val r = ch.read(buf)
                        if (r <= 0) break
                        sal.write(buf.array(), 0, r)
                        restante -= r
                    }
                }
            }
        }
    }

    private fun responder(c: Socket, codigo: Int, texto: String) {
        try {
            val cuerpo = "HTTP/1.1 $codigo $texto\r\nContent-Length: 0\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
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
            val metodo = partes[0].uppercase()
            val ruta = partes[1]
            var rango: String? = null
            while (true) {
                val l = ent.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("range:", true)) rango = l.substring(6).trim()
            }
            if (metodo == "OPTIONS") {
                responder(c, 204, "No Content")
                return
            }
            val seg = ruta.substringBefore('?').split("/").filter { it.isNotEmpty() }
            if (seg.size < 3 || seg[0] != token) {
                responder(c, 403, "Forbidden")
                return
            }
            val id = seg[2].toIntOrNull() ?: -1
            if (seg[1] == "s") {
                val t = textos[id]
                if (t == null) {
                    responder(c, 404, "Not Found")
                    return
                }
                val so = BufferedOutputStream(c.getOutputStream(), 65536)
                so.write(("HTTP/1.1 200 OK\r\nContent-Type: " + t.second + "\r\nContent-Length: " + t.first.size +
                    "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                if (metodo != "HEAD") so.write(t.first)
                so.flush()
                return
            }
            val origen = if (seg[1] == "t") origenTorrent(id) else origenArchivo(id)
            if (origen == null) {
                responder(c, 404, "Not Found")
                return
            }
            val total = origen.tam
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
            cab.append("Content-Type: ").append(Utiles.tipoMime(origen.nombre)).append("\r\n")
            cab.append("Accept-Ranges: bytes\r\n")
            cab.append("Content-Length: ").append(fin - ini + 1).append("\r\n")
            if (parcial) cab.append("Content-Range: bytes $ini-$fin/$total\r\n")
            cab.append("Access-Control-Allow-Origin: *\r\n")
            cab.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
            cab.append("transferMode.dlna.org: Streaming\r\n")
            cab.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            val sc = subCabecera
            if (sc != null) cab.append("CaptionInfo.sec: ").append(sc).append("\r\n")
            cab.append("Connection: close\r\n\r\n")
            sal.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
            if (metodo == "HEAD") {
                sal.flush()
                return
            }
            origen.enviar(sal, ini, fin)
            sal.flush()
        } catch (e: Exception) {
            // Lo normal: el receptor corta la conexión al saltar de posición.
        } finally {
            try { c.close() } catch (e: Exception) { /* nada */ }
        }
    }
}
