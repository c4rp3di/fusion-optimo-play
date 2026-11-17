package com.optimo.play

import android.content.Context
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.swig.sha1_hash
import java.io.File
import java.io.IOException

/** Un archivo dentro de un torrent. `offset` es su posición dentro del conjunto del torrent (para calcular piezas). */
class ArchivoTorrent(val indice: Int, val ruta: String, val tam: Long, val offset: Long) {
    val nombre: String get() = ruta.substringAfterLast('/')
    val esVideo: Boolean get() = Utiles.esVideo(nombre)
    val esReproducible: Boolean get() = Utiles.esReproducible(nombre)
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

    /** Sube cada vez que se cierra el torrent: quien guardaba archivos del anterior sabe que debe parar. */
    @Volatile var generacion = 0
        private set
    @Volatile var carpeta: File? = null
        private set
    @Volatile var archivo: ArchivoTorrent? = null
        private set

    private var sm: SessionManager? = null
    /** Marca de «hay un torrent abierto». Los métodos nativos NO usan este objeto: piden un handle nuevo con [handle]. */
    @Volatile private var th: TorrentHandle? = null
    @Volatile private var hashActual: Sha1Hash? = null

    /**
     * Handle recién pedido a la sesión por info-hash (null si el torrent ya no existe). Guardar y compartir entre hilos un
     * mismo objeto TorrentHandle acabó en un fallo nativo (SIGSEGV en torrent_handle::is_valid), así que cada uso pide el suyo.
     */
    private fun handle(): TorrentHandle? {
        if (th == null) return null
        val s = sm ?: return null
        val hs = hashActual ?: return null
        return try { s.find(hs) } catch (e: Throwable) { null }
    }
    @Volatile private var info: TorrentInfo? = null
    private var piezasCabeza = 1
    private var piezasCola = 1

    private val cachePrio = HashMap<Int, Priority>()

    // ---- guardar en el dispositivo
    /** Archivos marcados para conservar. Valor: 0 descargando, 1 copiando a Descargas, 2 guardado, -1 error. */
    val guardado = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** Mensaje del último error al guardar cada archivo. */
    val errorGuardado = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private val cacheArchivo = HashMap<Int, Pair<Long, Int>>()

    fun pendientesGuardar(): Int = guardado.values.count { it == 0 || it == 1 }

    /** Marca (o desmarca) un archivo para guardarlo entero. Un archivo ya copiado o copiándose no se toca. */
    fun marcarGuardar(a: ArchivoTorrent, si: Boolean) {
        val h = handle() ?: return
        try {
            if (si) {
                val st = guardado[a.indice]
                if (st == 1 || st == 2) return
                guardado[a.indice] = 0
                errorGuardado.remove(a.indice)
                h.filePriority(a.indice, prio(4))
            } else {
                val st = guardado[a.indice]
                if (st == 1 || st == 2) return
                guardado.remove(a.indice)
                errorGuardado.remove(a.indice)
                if (archivo?.indice != a.indice) h.filePriority(a.indice, Priority.IGNORE)
            }
        } catch (e: Exception) {
            // Torrent ya cerrado.
        }
    }

    /** % de piezas de este archivo que ya están descargadas (se recalcula como mucho cada 2 s). */
    @Synchronized
    fun progresoArchivo(a: ArchivoTorrent): Int {
        val h = handle() ?: return 0
        val ti = info ?: return 0
        val ahora = System.currentTimeMillis()
        val c = cacheArchivo[a.indice]
        if (c != null && ahora - c.first < 2000) return c.second
        return try {
            if (!h.isValid) return 0
            val pl = ti.pieceLength().toLong()
            val p0 = (a.offset / pl).toInt()
            val p1 = ((a.offset + a.tam - 1) / pl).toInt()
            var tengo = 0
            for (p in p0..p1) if (h.havePiece(p)) tengo++
            val total = p1 - p0 + 1
            val pct = if (total > 0) (tengo.toLong() * 100 / total).toInt() else 0
            cacheArchivo[a.indice] = Pair(ahora, pct)
            pct
        } catch (e: Exception) {
            0
        }
    }

    /** Dónde escribe libtorrent este archivo. */
    fun rutaLocal(a: ArchivoTorrent): File? = carpeta?.let { File(it, a.ruta) }

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

    /**
     * Construye un Sha1Hash a partir de 20 bytes. La API pública cambia entre versiones de libtorrent4j
     * (en la 2.1 no hay constructor desde byte[]), así que se prueban varias vías por reflexión.
     */
    private fun sha1DeBytes(bytes: ByteArray): Sha1Hash {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val fallos = StringBuilder()
        // 1) Sha1Hash.parseHex(String)
        try {
            val m = Sha1Hash::class.java.getMethod("parseHex", String::class.java)
            return m.invoke(null, hex) as Sha1Hash
        } catch (e: Exception) {
            fallos.append("parseHex ")
        }
        // 2) El tipo interno sha1_hash: constructor desde String, desde byte_vector, o from_hex()
        var sw: Any? = null
        try {
            val cls = Class.forName("org.libtorrent4j.swig.sha1_hash")
            try {
                sw = cls.getConstructor(String::class.java).newInstance(hex)
            } catch (e: Exception) {
                fallos.append("sha1_hash(String) ")
            }
            if (sw == null) {
                try {
                    val vecCls = Class.forName("org.libtorrent4j.swig.byte_vector")
                    val vec = Class.forName("org.libtorrent4j.Vectors")
                        .getMethod("bytes2byte_vector", ByteArray::class.java).invoke(null, bytes)
                    sw = cls.getConstructor(vecCls).newInstance(vec)
                } catch (e: Exception) {
                    fallos.append("sha1_hash(byte_vector) ")
                }
            }
            if (sw == null) {
                try {
                    val o = cls.getConstructor().newInstance()
                    cls.getMethod("from_hex", String::class.java).invoke(o, hex)
                    sw = o
                } catch (e: Exception) {
                    fallos.append("from_hex ")
                }
            }
        } catch (e: Exception) {
            fallos.append("sin clase sha1_hash ")
        }
        if (sw is sha1_hash) return Sha1Hash(sw)
        val disponibles = Sha1Hash::class.java.methods.map { it.name }.distinct().sorted().joinToString(",")
        throw IOException("No se pudo calcular el info-hash ($fallos). Métodos de Sha1Hash: $disponibles")
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
        val gen = generacion
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
            hash = sha1DeBytes(hashDeMagnet(origen))
            s.download(origen, d, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        }
        var h: TorrentHandle? = null
        val limite = System.currentTimeMillis() + 30_000
        while (h == null) {
            if (cancelado || generacion != gen) throw InterruptedException()
            h = try { s.find(hash) } catch (e: Exception) { null }
            if (h != null) break
            if (System.currentTimeMillis() > limite) throw IOException("No se pudo añadir el torrent")
            Thread.sleep(200)
        }
        val hOk: TorrentHandle = h ?: throw IOException("No se pudo añadir el torrent")
        hashActual = hash
        th = hOk
        val hIni = handle() ?: throw InterruptedException()
        if (archivoTorrent != null) hIni.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        hIni.resume()

        val inicio = System.currentTimeMillis()
        var ti: TorrentInfo? = null
        while (ti == null) {
            if (cancelado || generacion != gen) throw InterruptedException()
            // Un handle nuevo en cada vuelta: si otro hilo cerró el torrent, find() devuelve null y se sale sin tocar memoria nativa.
            val hh = handle() ?: throw InterruptedException()
            ti = try { hh.torrentFile() } catch (e: Exception) { null }
            if (ti != null) break
            if (System.currentTimeMillis() - inicio > 180_000) {
                throw IOException("Sin metadatos tras 3 minutos (¿hay peers o trackers vivos?)")
            }
            val peers = try { hh.status().numPeers() } catch (e: Exception) { 0 }
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
        val h = handle() ?: throw IOException("Torrent no iniciado")
        val ti = info ?: throw IOException("Torrent sin metadatos")
        archivo = a
        val n = ti.files().numFiles()
        for (i in 0 until n) {
            val st = guardado[i]
            h.filePriority(i, if (i == a.indice || st == 0 || st == -1) prio(4) else Priority.IGNORE)
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
        val h = handle() ?: return false
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
        val h = handle() ?: return "Conectando…"
        return try {
            val st = h.status()
            "Torrent · ${st.numPeers()} peers · ${st.downloadRate() / 1024} KB/s · preparando buffer…"
        } catch (e: Exception) {
            "Torrent · preparando buffer…"
        }
    }

    private var cacheT = 0L
    private var cacheProg = 0
    private var cacheDescargado = 0L
    private var cacheAdelante = 0L

    private fun entero(o: Any, metodo: String): Int = try {
        (o.javaClass.getMethod(metodo).invoke(o) as Number).toInt()
    } catch (e: Exception) {
        -1
    }

    private fun velTxt(bytesPorSeg: Int): String =
        if (bytesPorSeg >= 1024 * 1024) "%.1f MB/s".format(bytesPorSeg / 1048576.0) else "${bytesPorSeg / 1024} KB/s"

    /** Cuenta piezas del archivo que ya están (cada 2 s: son llamadas nativas) y cuántas hay seguidas por delante. */
    private fun recalcular(h: TorrentHandle, ti: TorrentInfo, a: ArchivoTorrent, frac: Double) {
        val pl = ti.pieceLength().toLong()
        val p0 = (a.offset / pl).toInt()
        val p1 = ((a.offset + a.tam - 1) / pl).toInt()
        var tengo = 0
        for (p in p0..p1) if (h.havePiece(p)) tengo++
        val total = p1 - p0 + 1
        cacheProg = if (total > 0) tengo * 100 / total else 0
        cacheDescargado = minOf(tengo.toLong() * pl, a.tam)
        val posByte = (frac.coerceIn(0.0, 1.0) * a.tam).toLong()
        var q = ((a.offset + posByte) / pl).toInt().coerceIn(p0, p1)
        var n = 0
        while (q <= p1 && h.havePiece(q)) {
            n++
            q++
        }
        cacheAdelante = minOf(n * pl, a.tam - posByte).coerceAtLeast(0L)
    }

    /**
     * Panel de estado: velocidad, progreso del ARCHIVO que suena, peers y cuánto buffer hay por delante.
     * `frac` es la posición de reproducción (0..1) y `durMs` la duración, para estimar los segundos de buffer.
     */
    fun estadisticasDetalle(frac: Double, durMs: Long): String {
        val h = handle() ?: return ""
        val ti = info ?: return ""
        val a = archivo ?: return ""
        return try {
            if (!h.isValid) return ""
            val st = h.status()
            val ahora = System.currentTimeMillis()
            if (ahora - cacheT > 2000) {
                recalcular(h, ti, a, frac)
                cacheT = ahora
            }
            val sb = StringBuilder()
            sb.append("↓ ").append(velTxt(st.downloadRate()))
            val subida = entero(st, "uploadRate")
            if (subida >= 0) sb.append("   ↑ ").append(velTxt(subida))
            sb.append("\n").append(cacheProg).append(" % · ").append(Utiles.tam(cacheDescargado))
                .append(" / ").append(Utiles.tam(a.tam))
            sb.append("\n").append(st.numPeers()).append(" peers")
            val semillas = entero(st, "numSeeds")
            if (semillas >= 0) sb.append(" (").append(semillas).append(" semillas)")
            if (durMs > 0 && a.tam > 0) {
                val bytesPorSeg = a.tam.toDouble() / (durMs / 1000.0)
                val seg = (cacheAdelante / bytesPorSeg).toLong()
                sb.append(" · buffer ").append(Utiles.fmt(seg * 1000))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    /** Estadísticas cortas para mostrar mientras se reproduce. */
    fun estadisticas(): String {
        val h = handle() ?: return ""
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
        val h = handle() ?: return false
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
        generacion++
        val h = handle()
        val s = sm
        if (h != null && s != null) {
            try { s.remove(h) } catch (e: Exception) { /* ya no existe */ }
        }
        th = null
        hashActual = null
        info = null
        archivo = null
        guardado.clear()
        errorGuardado.clear()
        synchronized(this) { cacheArchivo.clear() }
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
