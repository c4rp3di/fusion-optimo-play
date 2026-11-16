package com.optimo.play

import android.content.Context
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    @Volatile private var latch: CountDownLatch? = null
    private var piezasCabeza = 1
    private var piezasCola = 1

    private val oyente = object : AlertListener {
        override fun types(): IntArray? = null
        override fun alert(alert: Alert<*>) {
            if (alert is AddTorrentAlert) {
                th = alert.handle()
                latch?.countDown()
            }
        }
    }

    @Synchronized
    private fun sesion(): SessionManager {
        var s = sm
        if (s == null) {
            s = SessionManager()
            s.addListener(oyente)
            s.start()
            sm = s
        }
        return s
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
        val l = CountDownLatch(1)
        latch = l
        th = null

        if (archivoTorrent != null) {
            s.download(TorrentInfo(archivoTorrent), d)
        } else {
            s.download(origen, d, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        }
        if (!l.await(30, TimeUnit.SECONDS)) throw IOException("No se pudo añadir el torrent")
        val h = th ?: throw IOException("No se pudo añadir el torrent")
        if (archivoTorrent != null) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        h.resume()

        val inicio = System.currentTimeMillis()
        var ti: TorrentInfo? = null
        while (ti == null) {
            if (cancelado) throw InterruptedException()
            ti = try { h.torrentFile() } catch (e: Exception) { null }
            if (ti != null) break
            if (System.currentTimeMillis() - inicio > 180_000) {
                throw IOException("Sin metadatos tras 3 minutos (¿hay peers o trackers vivos?)")
            }
            val peers = try { h.status().numPeers() } catch (e: Exception) { 0 }
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
            h.filePriority(i, if (i == a.indice) Priority.NORMAL else Priority.IGNORE)
        }
        val pl = ti.pieceLength().toLong()
        val primera = (a.offset / pl).toInt()
        val ultima = ((a.offset + a.tam - 1) / pl).toInt()
        val total = ultima - primera + 1
        piezasCabeza = ((4L * 1024 * 1024 + pl - 1) / pl).toInt().coerceIn(1, total)
        piezasCola = ((1536L * 1024 + pl - 1) / pl).toInt().coerceIn(1, total)
        for (k in 0 until piezasCabeza) {
            h.piecePriority(primera + k, Priority.SEVEN)
            h.setPieceDeadline(primera + k, 500 + k * 250)
        }
        for (k in 0 until piezasCola) {
            val p = ultima - k
            h.piecePriority(p, Priority.SEVEN)
            h.setPieceDeadline(p, 3000 + k * 250)
        }
    }

    /** ¿Ya están las piezas de arranque (principio y final del archivo)? */
    fun listo(): Boolean {
        val h = th ?: return false
        val ti = info ?: return false
        val a = archivo ?: return false
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
        latch = null
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
