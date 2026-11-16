package com.optimo.play

import android.content.Context
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Sha1Hash
import java.io.File
import java.io.IOException

/** Un archivo dentro de un torrent. `offset` es su posición dentro del conjunto del torrent (para calcular piezas). */
class ArchivoTorrent(val indice: Int, val ruta: String, val tam: Long, val offset: Long) {
    val nombre: String get() = ruta.substringAfterLast('/')
    val esVideo: Boolean get() = Utiles.esVideo(nombre)
}

/**
 * Cliente torrent para REPRODUCIR mientras se descarga (libtorrent4j).
 *
 * Idea: se baja solo el archivo elegido, con prioridad a las piezas del principio y del final (el índice del MKV
 * suele estar al final) y, al reproducir, a las piezas que hacen falta ahora y las siguientes (plazos).
 * El servidor local (ServidorFlujo) le da el archivo a libVLC esperando a cada pieza.
 *
 * Todo lo que toca la API de libtorrent4j está en ESTE archivo.
 * Al cerrar, lo descargado se borra de la caché.
 */
class MotorTorrent(private val ctx: Context) {

    @Volatile var cancelado = false
    @Volatile var carpeta: File? = null
        private set
    @Volatile var archivo: ArchivoTorrent? = null
        private set

    private var sm: SessionManager? = null
    @Volatile private var th: TorrentHandle? = null
    @Volatile private var info: TorrentInfo? = null
    private var piezasCabeza = 1
    private var piezasCola = 1

    private val cachePrio = HashMap<Int, Priority>()

    /**
     * Prioridad de libtorrent por su valor numérico (0 ignorar, 4 normal, 7 máxima).
     * Se busca por reflexión porque los nombres de las constantes cambian entre versiones de libtorrent4j.
     */
    @Synchronized
    private fun prio(valor: Int): Priority {
        cachePrio[valor]?.let { return it }
        var mejor: Priority? = null
        var dif = Int.MAX_VALUE
        var cualquiera: Priority? = null
        for (f in Priority::class.java.fields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers) || f.type != Priority::class.java) continue
            val p = f.get(null) as? Priority ?: continue
            if (f.name != "IGNORE") cualquiera = p
            val n: Int = try {
                (Priority::class.java.getMethod("swig").invoke(p) as Number).toInt()
            } catch (e: Exception) {
                -1
            }
            if (n < 0) continue
            val d = Math.abs(n - valor)
            if (d < dif) {
                dif = d
                mejor = p
            }
        }
        val r = mejor ?: cualquiera ?: Priority.IGNORE
        cachePrio[valor] = r
        return r
    }

    @Synchronized
    private fun sesion(): SessionManager {
        var s = sm
        if (s == null) {
            s = SessionManager()
            s.start()
            sm = s
        }
        return s
    }

    /** Extrae el info-hash (20 bytes) de un enlace magnet: btih en hexadecimal (40) o base32 (32). */
    private fun hashDeMagnet(magnet: String): ByteArray {
        val m = Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(magnet)
            ?: throw IOException("Magnet sin info-hash (xt=urn:btih)")
        val t = m.groupValues[1]
        if (t.length == 40 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(20) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        if (t.length == 32) {
            val alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val salida = ByteArray(20)
            var acumulado = 0
            var bits = 0
            var pos = 0
            for (c in t.uppercase()) {
                val v = alfabeto.indexOf(c)
                if (v < 0) throw IOException("Info-hash base32 inválido")
                acumulado = (acumulado shl 5) or v
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    if (pos < 20) salida[pos++] = ((acumulado shr bits) and 0xFF).toByte()
                }
            }
            return salida
        }
        throw IOException("Info-hash de magnet no reconocido")
    }

    /**
     * Añade el torrent (magnet o archivo .torrent) y BLOQUEA hasta tener los metadatos.
     * Devuelve la lista de archivos. Lanza IOException si falla o se agota el tiempo.
     */
    fun abrir(origen: String, archivoTorrent: File?, estado: (String) -> Unit): List<ArchivoTorrent> {
        cerrar()
        cancelado = false
        val s = sesion()
        val d = File(ctx.cacheDir, "torrent/" + System.currentTimeMillis())
        d.mkdirs()
        carpeta = d
        th = null

        // OJO: el handle que llega dentro de una alerta apunta a memoria de la propia alerta, que se libera al
        // terminar el aviso; guardarlo provoca un fallo nativo (SIGSEGV en torrent_handle::is_valid).
        // Por eso se calcula el info-hash y se pide un handle propio con find().
        val hash: Sha1Hash
        if (archivoTorrent != null) {
            val ti0 = TorrentInfo(archivoTorrent)
            hash = ti0.infoHash()
            s.download(ti0, d)
        } else {
            hash = Sha1Hash(hashDeMagnet(origen))
            s.download(origen, d, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        }
        var h: TorrentHandle? = null
        val limite = System.currentTimeMillis() + 30_000
        while (h == null) {
            if (cancelado) throw InterruptedException()
            h = try { s.find(hash) } catch (e: Exception) { null }
            if (h != null) break
            if (System.currentTimeMillis() > limite) throw IOException("No se pudo añadir el torrent")
            Thread.sleep(200)
        }
        val hOk: TorrentHandle = h ?: throw IOException("No se pudo añadir el torrent")
        th = hOk
        if (archivoTorrent != null) hOk.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        hOk.resume()

        val inicio = System.currentTimeMillis()
        var ti: TorrentInfo? = null
        while (ti == null) {
            if (cancelado) throw InterruptedException()
            ti = try { hOk.torrentFile() } catch (e: Exception) { null }
            if (ti != null) break
            if (System.currentTimeMillis() - inicio > 180_000) {
                throw IOException("Sin metadatos tras 3 minutos (¿hay peers o trackers vivos?)")
            }
            val peers = try { hOk.status().numPeers() } catch (e: Exception) { 0 }
            estado("Buscando metadatos… $peers peers")
            Thread.sleep(700)
        }
        val tiOk: TorrentInfo = ti ?: throw IOException("Sin metadatos")
        info = tiOk

        val fs = tiOk.files()
        val lista = ArrayList<ArchivoTorrent>()
        for (i in 0 until fs.numFiles()) {
            val ruta = fs.filePath(i)
            if (ruta.contains("/.pad/")) continue // archivos de relleno de libtorrent
            lista.add(ArchivoTorrent(i, ruta, fs.fileSize(i), fs.fileOffset(i)))
        }
        return lista
    }

    /** Descarga solo este archivo y prioriza su principio y su final. */
    fun seleccionar(a: ArchivoTorrent) {
        val h = th ?: throw IOException("Torrent no iniciado")
        val ti = info ?: throw IOException("Torrent sin metadatos")
        archivo = a
        val n = ti.files().numFiles()
        for (i in 0 until n) {
            h.filePriority(i, if (i == a.indice) prio(4) else Priority.IGNORE)
        }
        val pl = ti.pieceLength().toLong()
        val primera = (a.offset / pl).toInt()
        val ultima = ((a.offset + a.tam - 1) / pl).toInt()
        val total = ultima - primera + 1
        piezasCabeza = ((4L * 1024 * 1024 + pl - 1) / pl).toInt().coerceIn(1, total)
        piezasCola = ((1536L * 1024 + pl - 1) / pl).toInt().coerceIn(1, total)
        for (k in 0 until piezasCabeza) {
            h.piecePriority(primera + k, prio(7))
            h.setPieceDeadline(primera + k, 500 + k * 250)
        }
        for (k in 0 until piezasCola) {
            val p = ultima - k
            h.piecePriority(p, prio(7))
            h.setPieceDeadline(p, 3000 + k * 250)
        }
    }

    /** ¿Ya están las piezas de arranque (principio y final del archivo)? */
    fun listo(): Boolean {
        val h = th ?: return false
        val ti = info ?: return false
        val a = archivo ?: return false
        if (!h.isValid) return false
        val pl = ti.pieceLength().toLong()
        val primera = (a.offset / pl).toInt()
        val ultima = ((a.offset + a.tam - 1) / pl).toInt()
        for (k in 0 until piezasCabeza) if (!h.havePiece(primera + k)) return false
        for (k in 0 until piezasCola) if (!h.havePiece(ultima - k)) return false
        return true
    }

    fun resumen(): String {
        val h = th ?: return "Conectando…"
        return try {
            val st = h.status()
            "Torrent · ${st.numPeers()} peers · ${st.downloadRate() / 1024} KB/s · preparando buffer…"
        } catch (e: Exception) {
            "Torrent · preparando buffer…"
        }
    }

    /** Estadísticas cortas para mostrar mientras se reproduce. */
    fun estadisticas(): String {
        val h = th ?: return ""
        return try {
            val st = h.status()
            "${st.numPeers()} peers · ${st.downloadRate() / 1024} KB/s · ${(st.progress() * 100).toInt()}%"
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Bloquea hasta que las piezas que cubren [pos, pos+n) del archivo estén descargadas, pidiéndolas con urgencia
     * junto con las siguientes. False si se cancela o se agota el tiempo (unos 3 minutos).
     */
    fun esperarRango(a: ArchivoTorrent, pos: Long, n: Long): Boolean {
        val h = th ?: return false
        val ti = info ?: return false
        val pl = ti.pieceLength().toLong()
        val p0 = ((a.offset + pos) / pl).toInt()
        val p1 = ((a.offset + pos + n - 1) / pl).toInt()
        val ultima = ((a.offset + a.tam - 1) / pl).toInt()
        var vueltas = 0
        while (!cancelado) {
            if (!h.isValid) return false
            var faltan = false
            for (p in p0..p1) {
                if (!h.havePiece(p)) {
                    faltan = true
                    h.setPieceDeadline(p, 0)
                }
            }
            var k = 1
            while (k <= 10 && p1 + k <= ultima) {
                val q = p1 + k
                if (!h.havePiece(q)) h.setPieceDeadline(q, 400 * k)
                k++
            }
            if (!faltan) return true
            vueltas++
            if (vueltas > 1200) return false
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** Detiene el torrent actual y borra lo descargado. La sesión de libtorrent se mantiene lista. */
    fun cerrar() {
        cancelado = true
        val h = th
        val s = sm
        if (h != null && s != null) {
            try { s.remove(h) } catch (e: Exception) { /* ya no existe */ }
        }
        th = null
        info = null
        archivo = null
        val d = carpeta
        carpeta = null
        if (d != null) Thread { d.deleteRecursively() }.start()
    }

    /** Apaga también la sesión (al destruir el servicio). */
    @Synchronized
    fun detener() {
        cerrar()
        try { sm?.stop() } catch (e: Exception) { /* nada */ }
        sm = null
    }
}
