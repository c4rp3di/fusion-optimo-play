package com.optimo.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.DocumentsContract
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.io.File
import java.io.RandomAccessFile

/**
 * Dueño del reproductor. Vive como servicio en primer plano (tipo mediaPlayback) para que el audio siga sonando
 * con la pantalla bloqueada o con la app en segundo plano, con notificación de control y sesión multimedia
 * (botones de auriculares / pantalla de bloqueo).
 *
 * La actividad se conecta, le "presta" las vistas a libVLC mientras está visible y se las quita al salir.
 */
class PlaybackService : Service() {

    companion object {
        const val CANAL = "reproduccion"
        const val ID_NOTIF = 71
        const val A_PLAYPAUSE = "com.optimo.play.PLAYPAUSE"
        const val A_ADELANTE = "com.optimo.play.ADELANTE"
        const val A_ATRAS = "com.optimo.play.ATRAS"
        const val A_CERRAR = "com.optimo.play.CERRAR"

        /** El servicio en marcha (mismo proceso), para añadir a la cola desde el explorador sin abrir el reproductor. */
        @Volatile var activo: PlaybackService? = null
    }

    inner class Enlace : Binder() {
        val servicio: PlaybackService get() = this@PlaybackService
    }

    private val enlace = Enlace()
    override fun onBind(intent: Intent?): IBinder = enlace

    private val main = Handler(Looper.getMainLooper())
    lateinit var ajustes: Ajustes
        private set
    val motor: MotorTorrent by lazy { MotorTorrent(this) }
    private val servidor: ServidorFlujo by lazy { ServidorFlujo(motor) }

    var mp: MediaPlayer? = null
        private set
    private var libvlc: LibVLC? = null
    private var pfd: ParcelFileDescriptor? = null

    // ---- estado visible para la interfaz (la actividad lo consulta cada ~400 ms)
    @Volatile var fuente: Fuente? = null
        private set
    @Volatile var titulo: String = ""
    @Volatile var estado: String = ""
    @Volatile var error: String? = null
    @Volatile var buffering: Float = 100f
    @Volatile var fin: Boolean = false
    @Volatile var necesitaElegir: Boolean = false
    @Volatile var archivosTorrent: List<ArchivoTorrent> = emptyList()
    @Volatile var avisoUi: String? = null
    @Volatile var esTorrent: Boolean = false
    @Volatile var retardoSubs: Long = 0L // microsegundos
        private set

    /** Último evento de libVLC (para el panel «Información» y el aviso de «sin respuesta»). */
    @Volatile var ultimoEvento: String = ""

    /** URL que se le dio a libVLC (en un torrent es la del servidor local). */
    val urlReproduccion: String? get() = ultimaUrl

    private var claveRep: String = ""
    private var posReanudar = 0L
    private var inicioHecho = false
    private var hiloTorrent: Thread? = null
    // ---- enlaces de Mega (archivo o carpeta, con o sin contraseña)
    /** El enlace de Mega pide contraseña (o la que se dio era incorrecta): la actividad enseña el diálogo. */
    @Volatile var necesitaClaveMega = false
    /** Motivo que se muestra en el diálogo de contraseña (p. ej. «Contraseña incorrecta»). */
    @Volatile var avisoClaveMega: String? = null
    /** Último fallo notificado por Mega al servir el contenido (cuota agotada, enlace caído…). */
    @Volatile var errorMega: String? = null
    /** Enlace de Mega que se está reproduciendo (también cuando es una carpeta que pasa a cola). */
    @Volatile private var megaOrigen: String? = null
    private var hiloMega: Thread? = null
    private var servidorMega: ServidorMega? = null
    /** Cliente de Mega del enlace abierto y servidor en la red local para la tele (solo mientras se emite). */
    private var clienteMega: MegaCliente? = null
    private var megaLan: ServidorMega? = null
    @Volatile private var genMega = 0
    val esMega: Boolean get() = megaOrigen != null
    private val subsExternas = ArrayList<File>()
    @Volatile var archivoTorrentActual: ArchivoTorrent? = null
        private set
    @Volatile private var objetivoTorrent: ArchivoTorrent? = null
    private var memoOrigen: List<ArchivoTorrent>? = null
    private var memoLista: List<ArchivoTorrent> = emptyList()
    // ---- lista de canales (M3U)
    @Volatile var canales: List<Canal> = emptyList()
        private set
    @Volatile var canalActual: Int = -1
        private set
    @Volatile var necesitaElegirCanal: Boolean = false
    @Volatile var guia: Guia? = null
        private set
    @Volatile var estadoGuia: String = ""
        private set
    @Volatile private var urlGuiaCabecera: String? = null
    private var fuenteActiva: Fuente? = null
    private val idSesion = java.util.UUID.randomUUID().toString()
    val hayCanales: Boolean get() = canales.isNotEmpty()
    val canalActivo: Canal? get() = canales.getOrNull(canalActual)
    private val candadoGuardado = Any()
    private var hiloGuardado: Thread? = null
    private var genGuardado = -1

    // ---- cola de reproducción local (carpeta, .m3u de un disco, varios archivos). Solo se toca desde el hilo principal.
    @Volatile var cola: List<Pista> = emptyList()
        private set
    @Volatile var colaActual: Int = -1
        private set
    @Volatile var tituloCola: String = ""
        private set
    /** 0 sin repetir, 1 repetir toda la cola, 2 repetir la pista. */
    @Volatile var repetir: Int = 0
        private set
    @Volatile var aleatorio: Boolean = false
        private set
    /** Documento (.m3u o audio) para el que hace falta que el usuario dé acceso a su carpeta; la actividad lo atiende. */
    @Volatile var necesitaCarpeta: Uri? = null
        private set
    private var motivoCarpeta = 0 // 1 = lista .m3u, 2 = resto de la carpeta
    private var entradasPendientes: List<EntradaM3u> = emptyList()
    private var fallosSeguidos = 0
    private val vistos = HashSet<Int>()
    private val historial = ArrayList<Int>()
    val hayCola: Boolean get() = cola.isNotEmpty()

    /** ¿Lo que suena es solo audio? Entonces los controles no se ocultan (no hay imagen que ver). */
    val esAudioActual: Boolean
        get() = Utiles.esAudio(if (cola.isNotEmpty()) (cola.getOrNull(colaActual)?.nombre ?: "") else titulo)

    // ---- bloqueos del sistema
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var sesion: MediaSession? = null
    private var foco: AudioFocusRequest? = null
    private var pausadoPorFoco = false
    private var enPrimerPlano = false

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate() {
        super.onCreate()
        activo = this
        ajustes = Ajustes(this)
        repetir = ajustes.colaRepetir
        aleatorio = ajustes.colaAleatorio
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Reproducción", NotificationManager.IMPORTANCE_LOW))

        val s = MediaSession(this, "OptimoPlay")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (emisor != null) emisor?.reproducir() else mp?.play() }
            override fun onPause() { if (emisor != null) emisor?.pausar() else mp?.pause() }
            override fun onStop() { detener() }
            override fun onSeekTo(pos: Long) { mp?.setTime(pos) }
            override fun onSkipToNext() { siguiente() }
            override fun onSkipToPrevious() { anterior() }
            override fun onFastForward() { saltar(10_000) }
            override fun onRewind() { saltar(-10_000) }
        }, main)
        sesion = s

        registerReceiver(ruidoso, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        primerPlano()
        when (intent?.action) {
            A_PLAYPAUSE -> alternar()
            A_ADELANTE -> saltar(10_000)
            A_ATRAS -> saltar(-10_000)
            A_CERRAR -> detener()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activo === this) activo = null
        guardarPos()
        try { unregisterReceiver(ruidoso) } catch (e: Exception) { /* nada */ }
        hiloTorrent?.interrupt()
        cerrarMega()
        volverALocal(false)
        destruirReproductor()
        liberarBloqueos()
        abandonarFoco()
        sesion?.release()
        sesion = null
        if (servidorIniciado) servidor.detener()
        motor.detener()
        super.onDestroy()
    }

    private var servidorIniciado = false

    private val ruidoso = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (mp?.isPlaying == true) mp?.pause() // se desconectaron los auriculares
        }
    }

    // ---------------------------------------------------------------- abrir contenido

    /**
     * ¿Hay que abrir [uri] aunque sea la misma que ya está cargada? Sí si es distinta, o si la anterior falló,
     * terminó o nunca llegó a sonar: así reenviar la misma URL desde Optimo vuelve a intentarlo.
     */
    fun debeAbrir(uri: String): Boolean {
        // Una carpeta de Mega pasa a cola de reproducción (la fuente pasa a ser la dirección local de cada pista).
        if (megaOrigen == uri && mp != null && error == null && !fin) return false
        if (fuente?.uri != uri) return true
        if (mp == null) return true
        return error != null || fin
    }

    /** Empieza a reproducir una fuente nueva (URL, archivo, magnet o .torrent). */
    fun abrir(f: Fuente) {
        sinSubsTemporal = false
        guardarPos()
        hiloTorrent?.interrupt()
        motor.cerrar()
        cerrarMega()
        limpiarCola()
        error = null
        errorMega = null
        fin = false
        estado = ""
        ultimoEvento = ""
        objetivoTorrent = null
        canales = emptyList()
        canalActual = -1
        necesitaElegirCanal = false
        guia = null
        estadoGuia = ""
        urlGuiaCabecera = null
        necesitaElegir = false
        archivosTorrent = emptyList()
        subsExternas.clear()
        retardoSubs = 0L
        archivoTorrentActual = null
        fuente = f
        titulo = f.titulo
        claveRep = f.uri
        val mega = MegaEnlace.esMega(f.uri)
        esTorrent = !mega && (f.uri.startsWith("magnet:", true) || Utiles.esArchivoTorrent(f.uri) ||
            esContenidoTorrent(f.uri))
        if (!esTorrent) {
            // Los torrents se anotan cuando se sabe qué archivo suena (prepararArchivo).
            ajustes.registrar(f.uri, f.titulo)
            posReanudar = ajustes.posicion(claveRep)
        }
        primerPlano()
        crearReproductor()
        if (mega) {
            abrirMega(f)
        } else if (esTorrent) {
            abrirTorrent(f)
        } else if (puedeSerLista(f.uri)) {
            abrirPosibleLista(f)
        } else {
            reproducir(f.uri, f)
            if (ajustes.colaAuto && f.uri.startsWith("content:") && Utiles.esAudio(f.titulo)) {
                val arbol = Carpetas.arbolDe(this, Uri.parse(f.uri))
                if (arbol != null) construirHermanos(f, arbol, false)
            }
        }
    }

    // ---------------------------------------------------------------- cola de reproducción local

    private fun limpiarCola() {
        cola = emptyList()
        colaActual = -1
        tituloCola = ""
        necesitaCarpeta = null
        entradasPendientes = emptyList()
        fallosSeguidos = 0
        vistos.clear()
        historial.clear()
    }

    /** Empieza a reproducir una cola de archivos locales desde la pista [indice]. */
    fun abrirCola(pistas: List<Pista>, indice: Int, titulo: String, conservarMega: Boolean = false) {
        if (pistas.isEmpty()) return
        guardarPos()
        hiloTorrent?.interrupt()
        motor.cerrar()
        if (!conservarMega) cerrarMega()
        error = null
        fin = false
        estado = ""
        ultimoEvento = ""
        objetivoTorrent = null
        canales = emptyList()
        canalActual = -1
        necesitaElegirCanal = false
        guia = null
        estadoGuia = ""
        urlGuiaCabecera = null
        necesitaElegir = false
        archivosTorrent = emptyList()
        subsExternas.clear()
        retardoSubs = 0L
        archivoTorrentActual = null
        esTorrent = false
        limpiarCola()
        cola = ArrayList(pistas)
        tituloCola = titulo
        primerPlano()
        ponerPista(indice.coerceIn(0, pistas.size - 1))
    }

    /** Pone a sonar la pista [i] de la cola (con un reproductor nuevo, como al cambiar de canal). */
    private fun ponerPista(i: Int) {
        val p = cola.getOrNull(i) ?: return
        colaActual = i
        val f = Fuente(p.uri, p.titulo)
        fuente = f
        titulo = p.titulo
        claveRep = "" // sin posición que recordar por pista
        posReanudar = 0L
        error = null
        errorMega = null
        fin = false
        estado = ""
        ultimoEvento = ""
        subsExternas.clear()
        retardoSubs = 0L
        crearReproductor()
        reproducir(p.uri, f)
    }

    /** Siguiente índice según el modo (aleatorio / repetir). -1 si la cola se acaba. [auto] = lo decide el fin de la pista. */
    private fun indiceSiguiente(auto: Boolean): Int {
        val n = cola.size
        if (n == 0) return -1
        if (auto && repetir == 2) return colaActual
        if (aleatorio && n > 1) {
            vistos.add(colaActual)
            var libres = (0 until n).filter { it !in vistos }
            if (libres.isEmpty()) {
                if (auto && repetir != 1) return -1
                vistos.clear()
                vistos.add(colaActual)
                libres = (0 until n).filter { it != colaActual }
            }
            return libres[(Math.random() * libres.size).toInt().coerceAtMost(libres.size - 1)]
        }
        val s = colaActual + 1
        return if (s < n) s else if (repetir == 1 || !auto) 0 else -1
    }

    private fun avanzarCola(auto: Boolean): Boolean {
        val d = indiceSiguiente(auto)
        if (d < 0) return false
        if (d != colaActual) {
            historial.add(colaActual)
            if (historial.size > 300) historial.removeAt(0)
        }
        ponerPista(d)
        return true
    }

    private fun anteriorCola() {
        val m = mp
        val t = try { m?.time ?: 0L } catch (e: Throwable) { 0L }
        if (t > 5000L) {
            m?.setTime(0L)
            return
        }
        if (aleatorio) {
            if (historial.isNotEmpty()) {
                val d = historial.removeAt(historial.size - 1)
                ponerPista(d)
            } else {
                m?.setTime(0L)
            }
            return
        }
        if (colaActual > 0) {
            historial.add(colaActual)
            ponerPista(colaActual - 1)
        } else if (repetir == 1 && cola.size > 1) {
            historial.add(colaActual)
            ponerPista(cola.size - 1)
        } else {
            m?.setTime(0L)
        }
    }

    /** Salta a una pista de la cola elegida en el panel. */
    fun saltarACola(i: Int) {
        if (i !in cola.indices) return
        if (i != colaActual) historial.add(colaActual)
        ponerPista(i)
    }

    /** Añade pistas al final de la cola (o justo después de la que suena). */
    fun anadirACola(pistas: List<Pista>, aContinuacion: Boolean = false) {
        if (pistas.isEmpty()) return
        val l = ArrayList(cola)
        if (aContinuacion && colaActual in l.indices) l.addAll(colaActual + 1, pistas) else l.addAll(pistas)
        cola = l
        vistos.clear()
        avisoUi = if (aContinuacion) "Sonará a continuación" else "Añadido a la cola (" + pistas.size + ")"
    }

    fun quitarDeCola(i: Int) {
        if (i !in cola.indices) return
        val l = ArrayList(cola)
        l.removeAt(i)
        vistos.clear()
        historial.clear()
        if (l.isEmpty()) {
            cola = emptyList()
            colaActual = -1
            return
        }
        cola = l
        if (i < colaActual) {
            colaActual--
        } else if (i == colaActual) {
            ponerPista(minOf(i, l.size - 1)) // la que sonaba se quita: pasa a la que ocupa su sitio
        }
    }

    /** Sube (delta -1) o baja (delta +1) una pista de la cola. */
    fun moverEnCola(i: Int, delta: Int) {
        val j = i + delta
        if (i !in cola.indices || j !in cola.indices) return
        val l = ArrayList(cola)
        val p = l.removeAt(i)
        l.add(j, p)
        cola = l
        colaActual = when (colaActual) {
            i -> j
            j -> i
            else -> colaActual
        }
        vistos.clear()
        historial.clear()
    }

    /** Pone la pista [i] justo detrás de la que suena. */
    fun ponerSiguiente(i: Int) {
        if (i !in cola.indices || i == colaActual || colaActual !in cola.indices) return
        val l = ArrayList(cola)
        val actual = l[colaActual]
        val p = l.removeAt(i)
        val pos = l.indexOf(actual) + 1
        l.add(pos, p)
        cola = l
        colaActual = l.indexOf(actual)
        vistos.clear()
        historial.clear()
        avisoUi = "Sonará a continuación"
    }

    /** Vacía la cola; lo que suena sigue sonando. */
    fun vaciarCola() {
        cola = emptyList()
        colaActual = -1
        vistos.clear()
        historial.clear()
    }

    fun fijarRepetir(v: Int) {
        repetir = v.coerceIn(0, 2)
        ajustes.colaRepetir = repetir
    }

    fun fijarAleatorio(v: Boolean) {
        aleatorio = v
        ajustes.colaAleatorio = v
        vistos.clear()
    }

    /**
     * Pone en cola el resto de archivos de la carpeta del que suena (los del mismo tipo: audios con audio, vídeos con
     * vídeo). [explicito] = lo pidió el usuario, así que se avisa si no se pudo.
     */
    private fun construirHermanos(f: Fuente, arbol: Uri, explicito: Boolean) {
        val u = Uri.parse(f.uri)
        val audio = Utiles.esAudio(f.titulo)
        Thread {
            try {
                val id = try { DocumentsContract.getDocumentId(u) } catch (e: Exception) { null }
                val padre = Carpetas.ubicarPadre(this, arbol, u, f.titulo)
                if (padre == null) {
                    if (explicito) main.post { avisoUi = "No se encontró la carpeta del archivo en la que has elegido" }
                    return@Thread
                }
                val hermanos = Carpetas.hijos(this, arbol, padre)
                    .filter { !it.esCarpeta && (if (audio) Utiles.esAudio(it.nombre) else Utiles.esVideo(it.nombre)) }
                    .sortedWith { a, b -> Utiles.comparaNatural(a.nombre, b.nombre) }
                var idx = hermanos.indexOfFirst { it.docId == id }
                if (idx < 0) idx = hermanos.indexOfFirst { it.nombre == f.titulo }
                if (hermanos.size < 2 || idx < 0) {
                    if (explicito) main.post { avisoUi = "No hay más archivos de este tipo en la carpeta" }
                    return@Thread
                }
                val pistas = hermanos.map { Pista(it.uri.toString(), it.nombre) }
                main.post {
                    if (fuente !== f || hayCola) return@post
                    cola = pistas
                    colaActual = idx
                    tituloCola = ""
                    vistos.clear()
                    historial.clear()
                    avisoUi = "Cola: " + pistas.size + " archivos de la carpeta"
                }
            } catch (e: Exception) {
                if (explicito) main.post { avisoUi = "No se pudo leer la carpeta: " + (e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    /** Menú ⋮ › «Poner en cola el resto de la carpeta»: usa el acceso ya concedido o pide la carpeta. */
    fun pedirColaCarpeta() {
        val f = fuente ?: return
        val u = Uri.parse(f.uri)
        if (u.scheme != "content") {
            avisoUi = "Solo para archivos del dispositivo"
            return
        }
        val arbol = Carpetas.arbolDe(this, u)
        if (arbol != null) {
            construirHermanos(f, arbol, true)
        } else {
            motivoCarpeta = 2
            entradasPendientes = emptyList()
            necesitaCarpeta = u
        }
    }

    /** La actividad consiguió que el usuario eligiera una carpeta (ya con el permiso conservado). */
    fun carpetaConcedida(arbol: Uri) {
        val doc = necesitaCarpeta ?: return
        necesitaCarpeta = null
        val f = fuente ?: return
        val motivo = motivoCarpeta
        val entradas = entradasPendientes
        if (motivo == 2) {
            construirHermanos(f, arbol, true)
            return
        }
        estado = "Buscando los archivos de la lista…"
        Thread {
            val nombre = Utiles.nombreDe(this, doc.toString())
            val padre = try { Carpetas.ubicarPadre(this, arbol, doc, nombre) } catch (e: Exception) { null }
            if (padre == null) {
                main.post {
                    if (fuente === f) {
                        estado = ""
                        error = "La carpeta elegida no contiene la lista «$nombre». Elige la carpeta donde está."
                    }
                }
            } else {
                resolverLista(f, arbol, padre, entradas)
            }
        }.start()
    }

    fun carpetaRechazada() {
        if (necesitaCarpeta == null) return
        val motivo = motivoCarpeta
        necesitaCarpeta = null
        if (motivo == 1) {
            estado = ""
            error = "Sin acceso a la carpeta no se pueden reproducir los archivos de la lista"
        }
    }

    // ---------------------------------------------------------------- listas de canales (M3U)

    private fun puedeSerLista(uri: String): Boolean {
        val u = uri.lowercase()
        if (u.startsWith(Biblioteca.PREFIJO)) return true
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file:")) {
            val r = u.substringBefore('?').substringBefore('#')
            return r.endsWith(".m3u") || r.endsWith(".m3u8")
        }
        if (u.startsWith("content:")) {
            val tipo = try { contentResolver.getType(Uri.parse(uri)) ?: "" } catch (e: Exception) { "" }
            if (tipo.contains("mpegurl", true)) return true
            val n = Utiles.nombreDe(this, uri).lowercase()
            return n.endsWith(".m3u") || n.endsWith(".m3u8")
        }
        return false
    }

    /**
     * Una URL .m3u/.m3u8 puede ser un solo vídeo HLS o una lista de canales. Se lee y se decide por el contenido:
     * lista → se muestra el panel de canales; si no (o si no se puede leer) → se le pasa tal cual a libVLC.
     */
    private fun abrirPosibleLista(f: Fuente) {
        estado = "Leyendo…"
        Thread {
            var lista: ListaM3u? = null
            var local: String? = null
            try {
                val txt = ListaM3u.leer(this, f)
                if (txt != null) {
                    if (ListaM3u.esLocal(txt)) local = txt else if (ListaM3u.esLista(txt)) lista = ListaM3u.parsear(txt)
                }
            } catch (e: Exception) {
                // Si no se puede leer aquí, se intenta con libVLC igualmente.
            }
            main.post {
                if (fuente !== f) return@post // se abrió otra cosa mientras tanto
                estado = ""
                val loc = local
                if (loc != null) {
                    abrirListaLocal(f, loc)
                    return@post
                }
                val l = lista
                if (l != null && l.canales.isNotEmpty()) {
                    canales = l.canales
                    urlGuiaCabecera = l.urlGuia
                    titulo = f.titulo
                    necesitaElegirCanal = true
                    estado = "Elige un canal (" + l.canales.size + ")"
                    iniciarGuia(f)
                } else {
                    reproducir(f.uri, f)
                }
            }
        }.start()
    }

    /**
     * Lista .m3u de archivos (típico de un disco): sus entradas son rutas, normalmente relativas a la carpeta donde está
     * la lista. Se buscan junto a ella; si la app aún no tiene acceso a esa carpeta, se le pide al usuario una vez.
     */
    private fun abrirListaLocal(f: Fuente, txt: String) {
        val entradas = ListaM3u.entradas(txt)
        estado = "Buscando los archivos de la lista…"
        Thread {
            try {
                val u = Uri.parse(f.uri)
                when (u.scheme) {
                    "http", "https" -> {
                        val base = java.net.URL(f.uri)
                        val pistas = entradas.mapNotNull { e ->
                            try {
                                val url = java.net.URL(base, e.ruta).toString()
                                val nombre = Uri.decode(url.substringBefore('?').substringAfterLast('/'))
                                Pista(url, nombre, e.titulo ?: Utiles.sinExtension(nombre))
                            } catch (x: Exception) {
                                null
                            }
                        }
                        main.post { colaDeLista(f, pistas, entradas.size) }
                    }
                    "file" -> {
                        val dir = File(u.path ?: "").parentFile
                        val pistas = entradas.mapNotNull { e ->
                            val r = File(dir, e.ruta.replace('\\', '/'))
                            if (r.isFile) Pista(Uri.fromFile(r).toString(), r.name, e.titulo ?: Utiles.sinExtension(r.name)) else null
                        }
                        main.post { colaDeLista(f, pistas, entradas.size) }
                    }
                    "content" -> {
                        val arbol = Carpetas.arbolDe(this, u)
                        val padre = if (arbol != null) Carpetas.ubicarPadre(this, arbol, u, Utiles.nombreDe(this, f.uri)) else null
                        if (arbol != null && padre != null) {
                            resolverLista(f, arbol, padre, entradas)
                        } else {
                            main.post {
                                if (fuente === f) {
                                    entradasPendientes = entradas
                                    motivoCarpeta = 1
                                    necesitaCarpeta = u
                                    estado = "Hace falta acceso a la carpeta de la lista"
                                }
                            }
                        }
                    }
                    else -> main.post {
                        if (fuente === f) {
                            estado = ""
                            error = "Esta lista usa rutas de archivos locales: ábrela desde su carpeta"
                        }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (fuente === f) {
                        estado = ""
                        error = "No se pudo leer la lista: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }.start()
    }

    /** Busca cada entrada junto a la lista (hilo de fondo) y arranca la cola con las que encuentra. */
    private fun resolverLista(f: Fuente, arbol: Uri, padreId: String, entradas: List<EntradaM3u>) {
        val cache = HashMap<String, List<EntradaDoc>>()
        val pistas = ArrayList<Pista>()
        for (e in entradas) {
            val d = Carpetas.resolver(this, arbol, padreId, e.ruta, cache) ?: continue
            pistas.add(Pista(d.uri.toString(), d.nombre, e.titulo?.takeIf { it.isNotBlank() } ?: Utiles.sinExtension(d.nombre)))
        }
        main.post { colaDeLista(f, pistas, entradas.size) }
    }

    private fun colaDeLista(f: Fuente, pistas: List<Pista>, total: Int) {
        if (fuente !== f) return
        estado = ""
        if (pistas.isEmpty()) {
            error = "No se encontraron en la carpeta los archivos de la lista (" + total + " entradas)"
            return
        }
        val nuevo = ArrayList(pistas)
        limpiarCola()
        cola = nuevo
        tituloCola = f.titulo
        ponerPista(0)
        if (pistas.size < total) avisoUi = (total - pistas.size).toString() + " de " + total + " archivos de la lista no se encontraron"
    }

    /** Pone el canal número [i] de la lista (índice dentro de [canales]). */
    fun elegirCanal(i: Int) {
        val lista = canales
        if (i !in lista.indices) return
        sinSubsTemporal = false
        val lf = fuente ?: return
        val c = lista[i]
        necesitaElegirCanal = false
        canalActual = i
        error = null
        fin = false
        estado = ""
        ultimoEvento = ""
        titulo = c.nombre
        claveRep = "" // un directo no tiene posición que recordar
        posReanudar = 0L
        subsExternas.clear()
        retardoSubs = 0L
        if (c.drm) avisoUi = "Este canal usa DRM: puede que no se reproduzca"
        val ua = c.ua ?: lf.ua ?: Utiles.UA_MOVIL
        val url = Utiles.resolverMarcas(c.url, ua, idSesion)
        crearReproductor()
        reproducir(url, Fuente(url, c.nombre, c.referer ?: lf.referer, ua))
    }

    // ---- guía de programación

    private fun iniciarGuia(f: Fuente) {
        val manual = ajustes.guiaDe(f.uri)
        val url = if (manual.isNotEmpty()) manual else (urlGuiaCabecera ?: "")
        if (url.isEmpty()) {
            guia = null
            estadoGuia = "Esta lista no indica ninguna guía de programación. Si tienes la dirección de una (XMLTV, .xml o .xml.gz), puedes ponerla a mano."
            return
        }
        cargarGuia(url)
    }

    private fun cargarGuia(url: String) {
        val lista = fuente?.uri
        estadoGuia = "Descargando la guía…"
        guia = null
        Thread {
            try {
                val g = Guia.cargar(this, url)
                main.post {
                    if (fuente?.uri != lista) return@post
                    guia = g
                    val con = canales.count { g.de(it).isNotEmpty() }
                    estadoGuia = "Guía cargada: " + g.canalesConProgramas + " canales en la guía, " + con + " de " + canales.size +
                        " canales de la lista con programación."
                }
            } catch (e: Exception) {
                main.post {
                    if (fuente?.uri == lista) estadoGuia = "No se pudo cargar la guía: " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    /** Guía puesta a mano para esta lista (vacía = usar la que indique la lista). */
    fun fijarGuiaManual(url: String) {
        val f = fuente ?: return
        ajustes.fijarGuia(f.uri, url)
        iniciarGuia(f)
    }

    /** Dirección de guía que anuncia la propia lista, si la hay. */
    fun guiaDeLaLista(): String? = urlGuiaCabecera

    private fun esContenidoTorrent(uri: String): Boolean {
        return try {
            val u = Uri.parse(uri)
            u.scheme == "content" && contentResolver.getType(u) == "application/x-bittorrent"
        } catch (e: Exception) {
            false
        }
    }

    private fun crearReproductor() {
        destruirReproductor()
        val v = LibVLC(this, ajustes.opcionesVlc())
        val m = MediaPlayer(v)
        m.setEventListener(escucha)
        libvlc = v
        mp = m
    }

    private var maestro: MaestroLimpio? = null

    private fun destruirReproductor() {
        main.removeCallbacks(vigilanteInicio)
        maestro?.detener()
        maestro = null
        val m = mp
        val v = libvlc
        mp = null
        libvlc = null
        if (m != null) {
            try { m.setEventListener(null) } catch (e: Throwable) { /* nada */ }
            try { m.stop() } catch (e: Throwable) { /* nada */ }
            try { m.detachViews() } catch (e: Throwable) { /* nada */ }
            try { m.release() } catch (e: Throwable) { /* nada */ }
        }
        if (v != null) {
            try { v.release() } catch (e: Throwable) { /* nada */ }
        }
        cerrarPfd()
    }

    private fun cerrarPfd() {
        try { pfd?.close() } catch (e: Exception) { /* nada */ }
        pfd = null
    }

    private fun reproducir(url: String, f: Fuente) {
        val m = mp ?: return
        val v = libvlc ?: return
        inicioHecho = false
        fuenteActiva = f
        ultimaUrl = url
        cerrarPfd()
        val uri = Uri.parse(url)
        val media: Media
        if (uri.scheme == "content") {
            val p = try { contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
            if (p == null) {
                error = "No se puede abrir el archivo"
                return
            }
            pfd = p
            media = Media(v, p.fileDescriptor)
        } else if ((uri.scheme == "http" || uri.scheme == "https") && canales.isNotEmpty() && url.contains("m3u8", true)) {
            // Canal HLS: libVLC abre un manifiesto local que es el original con los trozos de subtítulos de relleno
            // neutralizados (o sin las pistas de subtítulos, si así se ha pedido). Si no hay nada que arreglar, redirige al original.
            maestro?.detener()
            val ml = MaestroLimpio(url, f.referer, f.ua, ajustes.sinSubsCanales || sinSubsTemporal)
            maestro = ml
            media = Media(v, Uri.parse(ml.iniciar()))
        } else {
            media = Media(v, uri)
        }
        when (ajustes.decodificacion) {
            1 -> media.setHWDecoderEnabled(false, false)
            2 -> media.setHWDecoderEnabled(true, true)
            else -> media.setHWDecoderEnabled(true, false)
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            media.addOption(":network-caching=2500")
            f.referer?.let { media.addOption(":http-referrer=$it") }
            f.ua?.let { media.addOption(":http-user-agent=$it") }
        }
        m.setMedia(media)
        media.release()
        val dEmi = destinoEmision
        if (emisor != null && dEmi != null) {
            // Se está emitiendo: el contenido nuevo se manda a la misma pantalla en vez de sonar en el móvil.
            main.removeCallbacks(vigilanteInicio)
            val p = posReanudar
            main.post { if (emisor != null) emitirA(dEmi, p) }
            return
        }
        m.play()
        main.removeCallbacks(vigilanteInicio)
        main.postDelayed(vigilanteInicio, 12_000L)
    }

    /**
     * Si libVLC no llega a reproducir en 12 s no hay evento de error (p. ej. una URL que no es un vídeo): se avisa en
     * pantalla en vez de dejar solo el título. Si luego empieza a sonar, alReproducir() quita el aviso.
     */
    private val vigilanteInicio = Runnable {
        if (mp != null && !inicioHecho && error == null) {
            val u = ultimaUrl ?: ""
            val corta = if (u.length > 90) u.substring(0, 90) + "…" else u
            error = "No ha empezado a reproducir tras 12 s (libVLC: " +
                (if (ultimoEvento.isEmpty()) "sin respuesta" else ultimoEvento) +
                ").\n" + corta + "\nMás detalles en ⋮ › Información"
        }
    }

    // ---------------------------------------------------------------- Mega

    private fun cerrarMega() {
        genMega++
        hiloMega?.interrupt()
        hiloMega = null
        servidorMega?.detener()
        servidorMega = null
        megaLan?.detener()
        megaLan = null
        clienteMega = null
        megaOrigen = null
        necesitaClaveMega = false
        avisoClaveMega = null
    }

    /**
     * Abre un enlace de Mega: archivo suelto (se reproduce directamente) o carpeta (pasa a cola con sus audios y vídeos).
     * Si el enlace está protegido con contraseña (#P!…) se usa la dada, la recordada en esta sesión o se pide en pantalla.
     * Todo el trabajo de red va en un hilo; el contenido llega a libVLC por un servidor local que lo va descifrando.
     */
    private fun abrirMega(f: Fuente) {
        val gen = ++genMega
        hiloMega?.interrupt()
        megaOrigen = f.uri
        errorMega = null
        necesitaClaveMega = false
        estado = "Conectando con Mega…"
        val t = Thread {
            try {
                var enl = MegaEnlace.analizar(f.uri)
                    ?: throw MegaException(MegaException.GENERAL, "No reconozco este enlace de Mega")
                if (enl.protegido != null) {
                    val pass = f.clave ?: MegaClaves.obtener(f.uri)
                    if (pass == null) {
                        if (gen == genMega) {
                            avisoClaveMega = null
                            estado = "Este enlace de Mega tiene contraseña"
                            necesitaClaveMega = true
                        }
                        return@Thread
                    }
                    estado = "Comprobando la contraseña…"
                    enl = try {
                        MegaEnlace.desproteger(enl, pass)
                    } catch (e: MegaException) {
                        if (e.codigo == MegaException.CLAVE_INCORRECTA) {
                            MegaClaves.olvidar(f.uri)
                            if (gen == genMega) {
                                avisoClaveMega = e.message
                                estado = "Este enlace de Mega tiene contraseña"
                                necesitaClaveMega = true
                            }
                            return@Thread
                        }
                        throw e
                    }
                    MegaClaves.guardar(f.uri, pass)
                }
                val clave = enl.clave
                    ?: throw MegaException(
                        MegaException.GENERAL,
                        "Al enlace le falta la clave (lo que va detrás del #). Copia el enlace completo"
                    )
                val datos = enl
                if (clave.size != (if (datos.carpeta) 16 else 32)) {
                    throw MegaException(MegaException.GENERAL, "La clave del enlace no tiene el tamaño correcto: ¿está completo el enlace?")
                }
                val sub = datos.sub
                estado = "Consultando Mega…"
                val cli = MegaCliente(if (datos.carpeta) datos.handle else null)
                val srv = ServidorMega(cli, { m: String ->
                    errorMega = m
                    error = m
                })
                if (!datos.carpeta) {
                    val nodo = cli.infoArchivo(datos.handle, clave)
                    val url = srv.url(nodo)
                    main.post {
                        if (gen != genMega) {
                            srv.detener()
                            return@post
                        }
                        servidorMega = srv
                        clienteMega = cli
                        estado = ""
                        titulo = nodo.nombre
                        ajustes.registrar(f.uri, nodo.nombre)
                        crearReproductor()
                        reproducir(url, f)
                    }
                } else {
                    val dir = cli.listarCarpeta(clave)
                    var lista = dir.archivos.filter { Utiles.esReproducible(it.nombre) }
                    if (sub != null && datos.subCarpeta) lista = lista.filter { it.padres.contains(sub) }
                    if (lista.isEmpty()) {
                        throw MegaException(
                            MegaException.GENERAL,
                            if (dir.archivos.isEmpty()) "La carpeta de Mega está vacía"
                            else "La carpeta de Mega no tiene audio ni vídeo que reproducir"
                        )
                    }
                    lista = lista.sortedWith { a, b ->
                        val c = Utiles.comparaNatural(a.ruta, b.ruta)
                        if (c != 0) c else Utiles.comparaNatural(a.nombre, b.nombre)
                    }
                    val variasRutas = lista.map { it.ruta }.distinct().size > 1
                    val pistas = lista.map {
                        val base = Utiles.sinExtension(it.nombre)
                        Pista(srv.url(it), it.nombre, if (variasRutas && it.ruta.isNotEmpty()) it.ruta + " / " + base else base)
                    }
                    var inicio = 0
                    if (sub != null && !datos.subCarpeta) inicio = lista.indexOfFirst { it.handle == sub }.coerceAtLeast(0)
                    main.post {
                        if (gen != genMega) {
                            srv.detener()
                            return@post
                        }
                        servidorMega = srv
                        clienteMega = cli
                        abrirCola(pistas, inicio, dir.nombre, true)
                    }
                }
            } catch (e: InterruptedException) {
                // Cancelado por otra apertura.
            } catch (e: MegaException) {
                if (gen == genMega) {
                    estado = ""
                    error = e.message
                }
            } catch (e: Exception) {
                if (gen == genMega) {
                    estado = ""
                    error = "No se pudo abrir el enlace de Mega: " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }
        hiloMega = t
        t.start()
    }

    /** El usuario escribió la contraseña de un enlace protegido: se vuelve a intentar abrirlo. */
    fun darClaveMega(clave: String) {
        val f = fuente ?: return
        if (!MegaEnlace.esMega(f.uri)) return
        necesitaClaveMega = false
        avisoClaveMega = null
        abrirMega(f.copy(clave = clave))
    }

    /** El usuario cerró el diálogo sin dar la contraseña. */
    fun claveMegaRechazada() {
        if (!necesitaClaveMega) return
        necesitaClaveMega = false
        avisoClaveMega = null
        estado = ""
        error = "Sin la contraseña no se puede abrir este enlace de Mega"
    }

    // ---------------------------------------------------------------- torrent

    private fun abrirTorrent(f: Fuente) {
        estado = "Conectando al torrent…"
        val t = Thread {
            try {
                var archivoT: File? = null
                if (!f.uri.startsWith("magnet:", true)) archivoT = copiarTorrent(f.uri)
                val lista = motor.abrir(f.uri, archivoT) { s -> estado = s }
                archivosTorrent = lista
                marcarYaGuardados(lista)
                val rep = listaReproducible()
                when {
                    rep.size == 1 -> prepararArchivo(rep[0])
                    rep.isEmpty() && lista.isNotEmpty() -> prepararArchivo(lista.maxByOrNull { it.tam }!!)
                    rep.size > 1 -> {
                        necesitaElegir = true
                        estado = "Elige un archivo del torrent"
                    }
                    else -> error = "El torrent está vacío"
                }
            } catch (e: InterruptedException) {
                // Cancelado por otra apertura.
            } catch (e: Exception) {
                if (!motor.cancelado) error = e.message ?: "Error de torrent"
            }
        }
        hiloTorrent = t
        t.start()
    }

    /** El usuario eligió un archivo concreto del torrent (también sirve para cambiar de capítulo). */
    fun elegirArchivoTorrent(indice: Int) {
        val a = archivosTorrent.firstOrNull { it.indice == indice } ?: return
        necesitaElegir = false
        objetivoTorrent = a
        fin = false
        error = null
        hiloTorrent?.interrupt()
        guardarPos()
        val t = Thread {
            try {
                prepararArchivo(a)
            } catch (e: InterruptedException) {
                // Cancelado.
            } catch (e: Exception) {
                if (!motor.cancelado) error = e.message ?: "Error de torrent"
            }
        }
        hiloTorrent = t
        t.start()
    }

    /** Hilo de fondo: prioriza, espera al buffer de arranque y entonces arranca libVLC contra el servidor local. */
    private fun prepararArchivo(a: ArchivoTorrent) {
        val f = fuente ?: return
        objetivoTorrent = a
        motor.seleccionar(a)
        while (!motor.listo()) {
            if (Thread.currentThread().isInterrupted || motor.cancelado) return
            estado = motor.resumen()
            Thread.sleep(500)
        }
        servidorIniciado = true
        val url = servidor.url(a)
        estado = ""
        main.post {
            if (motor.cancelado) return@post
            archivoTorrentActual = a
            titulo = a.nombre
            claveRep = f.uri + "#" + a.indice
            ajustes.registrar(claveRep, a.nombre)
            posReanudar = ajustes.posicion(claveRep)
            subsExternas.clear()
            crearReproductor()
            reproducir(url, f)
        }
    }

    private fun copiarTorrent(uri: String): File {
        val u = Uri.parse(uri)
        val destino = File(cacheDir, "entrada.torrent")
        val abierto = if (u.scheme == "content" || u.scheme == "file") {
            contentResolver.openInputStream(u)
        } else {
            java.net.URL(uri).openStream()
        }
        val ent = abierto ?: throw java.io.IOException("No se puede leer el .torrent")
        ent.use { i -> destino.outputStream().use { o -> i.copyTo(o) } }
        return destino
    }

    // ---------------------------------------------------------------- eventos de libVLC

    private val escucha = object : MediaPlayer.EventListener {
        override fun onEvent(event: MediaPlayer.Event) {
            val tipo = event.type
            val buf = event.buffering
            main.post {
                ultimoEvento = nombreEvento(tipo)
                when (tipo) {
                    MediaPlayer.Event.Playing -> alReproducir()
                    MediaPlayer.Event.Paused -> {
                        liberarBloqueos()
                        guardarPos()
                        actualizarSesion()
                        notificar()
                    }
                    MediaPlayer.Event.Stopped -> {
                        liberarBloqueos()
                        actualizarSesion()
                    }
                    MediaPlayer.Event.EndReached -> {
                        if (claveRep.isNotEmpty()) ajustes.guardarPosicion(claveRep, 0L)
                        val sig = if (esTorrent) siguienteEnLista() else null
                        if (hayCola) {
                            if (!avanzarCola(true)) {
                                fin = true
                                liberarBloqueos()
                                actualizarSesion()
                                notificar()
                            }
                        } else if (sig != null) {
                            // Pasa solo al siguiente archivo del torrent (capítulo, pista…).
                            avisoUi = "Siguiente: " + sig.nombre
                            elegirArchivoTorrent(sig.indice)
                        } else {
                            fin = true
                            liberarBloqueos()
                            actualizarSesion()
                            notificar()
                        }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        val em = errorMega
                        if (em != null) {
                            // Mega ya dijo por qué (cuota agotada, enlace caído…): no tiene sentido saltar de pista en pista.
                            error = em
                            liberarBloqueos()
                        } else if (hayCola && cola.size > 1 && fallosSeguidos < cola.size - 1) {
                            // Una pista rota no para la cola: se salta a la siguiente.
                            fallosSeguidos++
                            val rota = cola.getOrNull(colaActual)?.titulo ?: ""
                            if (avanzarCola(true)) avisoUi = "No se pudo reproducir «" + rota + "»; sigo con la siguiente"
                        } else {
                            error = "No se puede reproducir este contenido"
                            liberarBloqueos()
                        }
                    }
                    MediaPlayer.Event.Buffering -> buffering = buf
                }
            }
        }
    }

    private fun nombreEvento(tipo: Int): String = when (tipo) {
        MediaPlayer.Event.Opening -> "Opening (abriendo)"
        MediaPlayer.Event.Buffering -> "Buffering"
        MediaPlayer.Event.Playing -> "Playing"
        MediaPlayer.Event.Paused -> "Paused"
        MediaPlayer.Event.Stopped -> "Stopped"
        MediaPlayer.Event.EndReached -> "EndReached"
        MediaPlayer.Event.EncounteredError -> "EncounteredError"
        else -> "evento 0x" + Integer.toHexString(tipo)
    }

    private fun alReproducir() {
        val m = mp ?: return
        estado = ""
        error = null
        fin = false
        fallosSeguidos = 0
        pausadoPorFoco = false
        pedirFoco()
        tomarBloqueos()
        if (!inicioHecho) {
            inicioHecho = true
            if (posReanudar > 15_000) m.setTime(posReanudar)
            posReanudar = 0L
            for (f in subsExternas) {
                m.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
            }
            if (retardoSubs != 0L) m.setSpuDelay(retardoSubs)
            programarSubsAuto()
            programarAudioPreferido()
        }
        actualizarSesion()
        notificar()
    }

    // ---------------------------------------------------------------- pista de audio preferida

    @Volatile private var audioManual = false

    /** El usuario eligió una pista a mano: la preferencia automática ya no la cambia. */
    fun elegirAudio(id: Int) {
        audioManual = true
        mp?.setAudioTrack(id)
    }

    private fun programarAudioPreferido() {
        audioManual = false
        if (ajustes.audioPreferido.isEmpty()) return
        // Las pistas de un directo (HLS) aparecen con retraso: se intenta dos veces.
        main.postDelayed({ aplicarAudioPreferido() }, 1500L)
        main.postDelayed({ aplicarAudioPreferido() }, 5000L)
    }

    /** Pone la pista que pide el ajuste «Audio preferido», si existe. */
    fun aplicarAudioPreferido(forzar: Boolean = false) {
        val m = mp ?: return
        val pref = ajustes.audioPreferido
        if (pref.isEmpty() || (audioManual && !forzar)) return
        val pistas = m.audioTracks ?: return
        val reales = pistas.filter { it.id >= 0 }
        if (reales.isEmpty()) return
        val elegida = if (pref == "primera") reales[0] else reales.firstOrNull { Utiles.coincideIdioma(it.name, pref) }
        if (elegida != null && elegida.id != m.audioTrack) m.setAudioTrack(elegida.id)
    }

    // ---------------------------------------------------------------- enviar a la tele (DLNA / Chromecast)

    /** Pantalla remota activa; null si se reproduce en el móvil. */
    @Volatile var emisor: Emisor? = null
        private set
    @Volatile var destinoEmision: Destino? = null
        private set
    private var lan: ServidorLan? = null
    private var enviarPausaLocal = false
    private var retrans: Retransmision? = null

    /** Manda lo que suena a una tele. El móvil se queda sirviendo el archivo si es del torrent o del dispositivo. */
    fun emitirA(d: Destino, posForzada: Long = -1L) {
        val f = fuenteActiva ?: fuente
        val url0 = ultimaUrl
        if (f == null || url0 == null) {
            avisoUi = "No hay nada reproduciéndose que enviar"
            return
        }
        val m = mp
        val pos = if (posForzada >= 0L) posForzada else try { m?.time ?: 0L } catch (e: Throwable) { 0L }
        val len = try { m?.length ?: 0L } catch (e: Throwable) { 0L }
        val directo = if (len > 0L) false else (hayCanales || m?.isPlaying == true)
        // Si ya se emitía a esta misma pantalla (p. ej. llegaron subtítulos), se reutiliza la conexión.
        val previo = emisor
        val reutilizar = previo != null && lan != null && destinoEmision?.id == d.id && destinoEmision?.tipo == d.tipo
        if (!reutilizar) volverALocal(false)
        val srv = if (reutilizar) lan!! else ServidorLan(this) { motor }
        var url: String? = null
        var mime = "video/mp4"
        var retransmitido = false
        val ta = archivoTorrentActual
        if (megaOrigen != null) {
            // Mega: el móvil descarga, descifra y sirve el archivo por la red local (la tele no sabe descifrar AES).
            // La dirección local de libVLC lleva el identificador del archivo: se busca su nodo y se registra en el servidor LAN.
            val ipL = Utiles.ipLan(this)
            val handle = try { Uri.parse(url0).pathSegments.getOrNull(1) } catch (t: Throwable) { null }
            val nodo = if (handle != null) servidorMega?.nodo(handle) else null
            val cli = clienteMega
            if (nodo == null || cli == null) {
                srv.detener()
                avisoUi = "El archivo de Mega aún no está listo para enviarlo"
                return
            }
            if (ipL == null) {
                srv.detener()
                avisoUi = "No se pudo enviar: el móvil no está conectado a una red Wi-Fi"
                return
            }
            val ml = megaLan ?: ServidorMega(cli, { msg: String ->
                errorMega = msg
                error = msg
            }, true).also { megaLan = it }
            url = ml.urlLan(nodo, ipL)
            val t = Utiles.tipoMime(nodo.nombre)
            mime = if (t == "application/octet-stream") "video/mp4" else t
        } else if (esTorrent) {
            if (ta == null) {
                avisoUi = "El torrent aún no está listo para enviarlo"
                return
            }
            url = srv.urlTorrent(ta)
            mime = Utiles.tipoMime(ta.nombre)
        } else if (url0.startsWith("http", true)) {
            url = url0
            mime = Utiles.mimeDeUrl(url0)
            val ipL = Utiles.ipLan(this)
            val dificil = url0.startsWith("https", true) || directo || mime == "application/vnd.apple.mpegurl" || mime == "application/dash+xml"
            if (d.tipo == Destino.DLNA && dificil && ipL != null) {
                // Las teles DLNA antiguas no entienden HLS ni https: el móvil lo re-emite como MPEG-TS por HTTP.
                retrans?.liberar()
                retrans = null
                val r = Retransmision(applicationContext, url0, f.referer, f.ua)
                val p = try { r.iniciar() } catch (t: Throwable) { -1 }
                if (p > 0) {
                    retrans = r
                    url = "http://" + ipL + ":" + p + "/live.ts"
                    mime = "video/vnd.dlna.mpeg-tts"
                    retransmitido = true
                } else {
                    r.liberar()
                }
            }
            if (!retransmitido && !f.referer.isNullOrBlank()) {
                avisoUi = "Esta dirección exigía cabeceras especiales (Referer): la tele puede que no consiga abrirla"
            }
        } else if (url0.startsWith("content:", true) || url0.startsWith("file:", true)) {
            var nombre = f.titulo
            if (!nombre.contains('.')) nombre += ".mp4"
            url = srv.urlArchivo(Uri.parse(url0), nombre)
            val t = Utiles.tipoMime(nombre)
            mime = if (t == "application/octet-stream") "video/mp4" else t
        }
        if (url == null) {
            srv.detener()
            avisoUi = "No se pudo enviar: el móvil no está conectado a una red Wi-Fi"
            return
        }
        // Subtítulos: el último archivo externo cargado, convertido al formato que entiende cada tele.
        var urlSubs: String? = null
        val fs = archivoSubsActual()
        if (fs != null) {
            val prep = Subs.preparar(fs, retardoSubs / 1000L, d.tipo == Destino.CHROMECAST)
            if (prep != null) urlSubs = srv.urlTexto(prep.bytes, prep.ext, prep.mime)
            else avisoUi = "Estos subtítulos (" + fs.name.substringAfterLast('.') + ") no se pueden enviar a la tele; solo SRT o WebVTT"
        }
        srv.subCabecera = if (d.tipo == Destino.DLNA) urlSubs else null
        val e: Emisor = if (reutilizar) previo!! else try {
            if (d.tipo == Destino.DLNA) Dlna.EmisorDlna(d.datos as DlnaDispositivo)
            else CastGoogle.EmisorCast(applicationContext, d.datos as androidx.mediarouter.media.MediaRouter.RouteInfo)
        } catch (t: Throwable) {
            srv.detener()
            avisoUi = "No se pudo conectar con " + d.nombre
            return
        }
        lan = srv
        emisor = e
        destinoEmision = d
        enviarPausaLocal = true
        main.removeCallbacks(vigilanteInicio)
        try { m?.pause() } catch (t: Throwable) { /* nada */ }
        tomarBloqueos()
        if (retransmitido) {
            // libVLC necesita unos segundos para abrir el canal antes de que la tele pueda conectarse.
            val u2: String = url
            main.postDelayed({ if (emisor === e) e.cargar(u2, mime, titulo.ifEmpty { f.titulo }, true, 0L, null) }, 3500L)
        } else {
            e.cargar(url, mime, titulo.ifEmpty { f.titulo }, directo, pos, urlSubs)
        }
        actualizarSesion()
        notificar()
    }

    /** Deja de emitir. Con [reanudar] el móvil sigue por donde iba la tele. */
    fun volverALocal(reanudar: Boolean = true) {
        val e = emisor
        emisor = null
        destinoEmision = null
        val st = try { e?.estado() } catch (t: Throwable) { null }
        try { e?.parar() } catch (t: Throwable) { /* nada */ }
        lan?.detener()
        lan = null
        megaLan?.detener()
        megaLan = null
        retrans?.liberar()
        retrans = null
        if (e == null) return
        val m = mp
        if (reanudar && m != null) {
            val directo = hayCanales || (try { m.length <= 0L && m.isPlaying } catch (t: Throwable) { true })
            val f = fuenteActiva ?: fuente
            val u = ultimaUrl
            if (f != null && u != null) {
                // Se reabre en el móvil por donde iba la tele (un directo sigue por el punto actual).
                posReanudar = if (directo) 0L else (st?.posMs ?: 0L)
                reproducir(u, f)
            } else {
                try { m.play() } catch (t: Throwable) { /* nada */ }
            }
        }
        if (reanudar && mp == null && !hayGuardadoPendiente()) {
            // Se emitía en segundo plano y no queda nada local: el servicio ya no hace falta.
            detener()
            return
        }
        actualizarSesion()
        notificar()
    }

    fun emisorAlternar() {
        val e = emisor ?: return
        if (e.estado().pausa) e.reproducir() else e.pausar()
    }

    fun emisorSaltar(ms: Long) {
        val e = emisor ?: return
        val s = e.estado()
        var destino = (s.posMs + ms).coerceAtLeast(0L)
        if (s.durMs > 2000L) destino = minOf(destino, s.durMs - 1500L).coerceAtLeast(0L)
        e.buscar(destino)
    }

    fun emisorBuscar(ms: Long) {
        emisor?.buscar(ms)
    }

    // ---------------------------------------------------------------- controles

    fun alternar() {
        if (emisor != null) { emisorAlternar(); return }
        val m = mp ?: return
        if (m.isPlaying) m.pause() else m.play()
    }

    fun pausar() {
        if (emisor != null) return
        val m = mp ?: return
        if (m.isPlaying) m.pause()
    }

    fun saltar(ms: Long) {
        if (emisor != null) { emisorSaltar(ms); return }
        val m = mp ?: return
        val len = m.length
        val destino = (m.time + ms).coerceAtLeast(0L)
        m.setTime(if (len > 0) minOf(destino, len - 500L).coerceAtLeast(0L) else destino)
    }

    /** Detiene todo, borra lo descargado y cierra el servicio. */
    fun detener() {
        volverALocal(false)
        guardarPos()
        hiloTorrent?.interrupt()
        motor.cerrar()
        destruirReproductor()
        liberarBloqueos()
        abandonarFoco()
        sesion?.isActive = false
        enPrimerPlano = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Cierra la reproducción pero deja el torrent vivo para que acaben de bajarse y copiarse los archivos marcados
     * para guardar. Cuando terminan, el servicio se cierra solo (bucleGuardado).
     */
    fun soloDescarga() {
        guardarPos()
        hiloTorrent?.interrupt()
        destruirReproductor()
        abandonarFoco()
        sesion?.isActive = false
        estado = ""
        notificar()
    }

    private fun guardarPos() {
        val m = mp ?: return
        if (claveRep.isEmpty() || fin) return
        try {
            val t = m.time
            val len = m.length
            if (t > 15_000 && (len <= 0 || t < len - 30_000)) ajustes.guardarPosicion(claveRep, t) else
                ajustes.guardarPosicion(claveRep, 0L)
        } catch (e: Throwable) {
            // Reproductor ya liberado.
        }
    }

    /** Los estilos de subtítulos son opciones de LibVLC: se recrea el motor y se continúa donde iba. */
    fun reconstruir() {
        val m = mp ?: return
        val t = m.time
        val f = fuenteActiva ?: fuente ?: return
        val url = ultimaUrl ?: return
        crearReproductor()
        posReanudar = t
        reproducir(url, f)
    }

    private var ultimaUrl: String? = null

    // ---------------------------------------------------------------- lista del torrent (⏮ ⏭)

    /** Vídeos y audios del torrent en orden «natural» (CD2 antes que CD10), de carpeta en carpeta. */
    @Synchronized
    fun listaReproducible(): List<ArchivoTorrent> {
        val o = archivosTorrent
        if (o !== memoOrigen) {
            memoLista = o.filter { it.esReproducible }.sortedWith { x, y -> Utiles.comparaNatural(x.ruta, y.ruta) }
            memoOrigen = o
        }
        return memoLista
    }

    val hayLista: Boolean get() = canales.size > 1 || cola.size > 1 || (esTorrent && listaReproducible().size > 1)

    private fun posicionEnLista(l: List<ArchivoTorrent>): Int {
        val c = objetivoTorrent ?: archivoTorrentActual ?: return -1
        return l.indexOfFirst { it.indice == c.indice }
    }

    private fun siguienteEnLista(): ArchivoTorrent? {
        val l = listaReproducible()
        val i = posicionEnLista(l)
        return if (i >= 0 && i < l.size - 1) l[i + 1] else null
    }

    fun haySiguiente(): Boolean = canales.size > 1 || cola.size > 1 || (esTorrent && siguienteEnLista() != null)

    fun siguiente(): Boolean {
        if (cola.size > 1) return avanzarCola(false)
        val n = canales.size
        if (n > 1) {
            elegirCanal((maxOf(canalActual, -1) + 1) % n)
            return true
        }
        val s = siguienteEnLista() ?: return false
        elegirArchivoTorrent(s.indice)
        return true
    }

    /** Si ya llevas más de 5 s vuelve al principio del archivo; si no, va al anterior. */
    fun anterior() {
        if (cola.size > 1) {
            anteriorCola()
            return
        }
        val n = canales.size
        if (n > 1) {
            elegirCanal(if (canalActual <= 0) n - 1 else canalActual - 1)
            return
        }
        val l = listaReproducible()
        val i = posicionEnLista(l)
        val m = mp
        val t = try { m?.time ?: 0L } catch (e: Throwable) { 0L }
        if (i > 0 && t <= 5000L) {
            elegirArchivoTorrent(l[i - 1].indice)
        } else {
            m?.setTime(0L)
        }
    }

    // ---------------------------------------------------------------- guardar en el dispositivo

    /** -2 sin marcar, 0 descargando, 1 copiando, 2 guardado, -1 error. */
    fun estadoGuardado(a: ArchivoTorrent): Int = motor.guardado[a.indice] ?: -2

    fun hayGuardadoPendiente(): Boolean = esTorrent && motor.pendientesGuardar() > 0

    /** Espacio libre donde se descarga (la caché de la app). */
    fun espacioLibre(): Long = cacheDir.usableSpace

    /** Los archivos que ya están en Descargas/OptimoPlay (misma ruta y tamaño) se marcan como guardados: no se vuelven a bajar. */
    private fun marcarYaGuardados(lista: List<ArchivoTorrent>) {
        for (a in lista) {
            if (a.tam > 0 && Exportar.yaGuardado(this, a.ruta, a.tam)) motor.guardado[a.indice] = 2
        }
    }

    /** Marca o desmarca archivos para conservarlos. Los que ya estaban guardados se omiten. */
    fun guardarArchivos(lista: List<ArchivoTorrent>, si: Boolean) {
        if (!si) {
            for (a in lista) motor.marcarGuardar(a, false)
            notificar()
            return
        }
        Thread {
            var omitidos = 0
            for (a in lista) {
                if (motor.guardado[a.indice] == 2) {
                    omitidos++
                } else if (a.tam > 0 && Exportar.yaGuardado(this, a.ruta, a.tam)) {
                    motor.guardado[a.indice] = 2
                    omitidos++
                } else {
                    motor.marcarGuardar(a, true)
                }
            }
            main.post {
                if (omitidos > 0) avisoUi = "Ya estaban guardados (se omiten): " + omitidos
                if (motor.pendientesGuardar() > 0) arrancarGuardado()
                notificar()
            }
        }.start()
    }

    /** «2 archivos · 37 %» para la notificación. */
    fun resumenGuardado(): String {
        val g = motor.pendientesGuardar()
        if (g == 0) return ""
        var suma = 0
        var n = 0
        for ((idx, st) in motor.guardado.entries) {
            if (st == 1) {
                suma += 100
                n++
            } else if (st == 0) {
                val a = archivosTorrent.firstOrNull { it.indice == idx } ?: continue
                suma += motor.progresoArchivo(a)
                n++
            }
        }
        val pct = if (n > 0) suma / n else 0
        return (if (g == 1) "1 archivo" else "$g archivos") + " · " + pct + " %"
    }

    private fun arrancarGuardado() {
        synchronized(candadoGuardado) {
            if (hiloGuardado != null && genGuardado == motor.generacion) return
            tomarBloqueos()
            val gen = motor.generacion
            val t = Thread { bucleGuardado(gen) }
            hiloGuardado = t
            genGuardado = gen
            t.start()
        }
    }

    /**
     * Hilo de fondo: cuando un archivo marcado termina de bajarse lo copia a Descargas. Dura mientras haya archivos
     * pendientes; si ya no se está reproduciendo nada, al acabar cierra el servicio (y se borra la caché).
     */
    private fun bucleGuardado(gen: Int) {
        val intentos = HashMap<Int, Int>()
        while (true) {
            val salir = synchronized(candadoGuardado) {
                if (motor.generacion != gen || motor.pendientesGuardar() == 0) {
                    if (hiloGuardado === Thread.currentThread()) hiloGuardado = null
                    true
                } else {
                    false
                }
            }
            if (salir) break
            for ((idx, st) in motor.guardado.entries.toList()) {
                if (st != 0) continue
                val a = archivosTorrent.firstOrNull { it.indice == idx } ?: continue
                if (motor.progresoArchivo(a) < 100) continue
                val f = motor.rutaLocal(a)
                if (f == null || !f.exists() || f.length() < a.tam) {
                    val n = (intentos[idx] ?: 0) + 1
                    intentos[idx] = n
                    if (n > 20) {
                        motor.errorGuardado[idx] = "El archivo descargado está incompleto en el disco"
                        motor.guardado[idx] = -1
                    }
                    continue
                }
                motor.guardado[idx] = 1
                main.post { notificar() }
                val err = Exportar.aDescargas(this, f, a.ruta) { motor.generacion != gen }
                if (motor.generacion != gen) break
                if (err == null) {
                    motor.guardado[idx] = 2
                    avisoUi = "Guardado en " + Exportar.destinoTexto() + ": " + a.nombre
                } else {
                    motor.errorGuardado[idx] = err
                    motor.guardado[idx] = -1
                    avisoUi = "No se pudo guardar " + a.nombre + ": " + err
                }
            }
            main.post { notificar() }
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                synchronized(candadoGuardado) { if (hiloGuardado === Thread.currentThread()) hiloGuardado = null }
                break
            }
        }
        main.post {
            if (motor.generacion == gen && motor.pendientesGuardar() == 0) {
                if (emisor != null) { /* se sigue emitiendo */ } else if (mp == null) detener() else if (mp?.isPlaying != true) soltarBloqueos()
                if (mp != null) notificar()
            }
        }
    }

    // ---------------------------------------------------------------- subtitulos

    /** Último archivo de subtítulos externo/descargado cargado (para sincronizar por líneas). */
    fun archivoSubsActual(): File? = subsExternas.lastOrNull()

    fun anadirSubtitulo(f: File) {
        val m = mp ?: return
        subsExternas.add(f)
        m.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
        // Llegaron subtítulos mientras se emite: se vuelve a mandar el vídeo con ellos, por donde iba.
        val d = destinoEmision
        val em = emisor
        if (d != null && em != null) {
            val p = em.estado().posMs
            main.post { if (emisor === em) emitirA(d, p) }
        }
    }

    /**
     * Elige una pista de subtítulos. En los directos (HLS) algunas pistas dejan el canal congelado; si a los 7 s
     * la imagen no avanza, se quita la pista y se avisa.
     */
    /** Los subtítulos incluidos congelaron este canal: se reabre sin ellos hasta cambiar de canal. */
    @Volatile private var sinSubsTemporal = false

    fun elegirSubtitulo(id: Int) {
        val m = mp ?: return
        val t0 = try { m.time } catch (e: Throwable) { 0L }
        Thread { try { m.setSpuTrack(id) } catch (e: Throwable) { /* nada */ } }.start()
        if (id < 0 || emisor != null) return
        main.postDelayed({
            val mm = mp ?: return@postDelayed
            if (mm !== m || emisor != null) return@postDelayed
            try {
                val t1 = mm.time
                val pausado = mm.playerState == IMedia.State.Paused
                if (!pausado && t1 <= t0 + 1000L) {
                    Thread { try { mm.setSpuTrack(-1) } catch (e: Throwable) { /* nada */ } }.start()
                    avisoUi = "Esa pista de subtítulos bloquea el canal: se ha desactivado"
                    // Si aun así sigue congelado, se reabre el canal sin cargar subtítulos incluidos.
                    main.postDelayed({
                        val m2 = mp ?: return@postDelayed
                        if (m2 !== m || emisor != null) return@postDelayed
                        try {
                            val t2 = m2.time
                            if (m2.playerState != IMedia.State.Paused && t2 <= t1 + 500L) {
                                val f = fuenteActiva
                                val u = ultimaUrl
                                if (f != null && u != null) {
                                    sinSubsTemporal = true
                                    posReanudar = 0L
                                    reproducir(u, f)
                                    avisoUi = "Canal reabierto sin los subtítulos incluidos (bloqueaban la imagen)"
                                }
                            }
                        } catch (e: Throwable) {
                            // Reproductor liberado entre medias.
                        }
                    }, 4000L)
                }
            } catch (e: Throwable) {
                // Reproductor liberado entre medias.
            }
        }, 7000L)
    }

    /** Desfase de los subtítulos en milisegundos (positivo = más tarde). */
    fun fijarRetardoSubs(ms: Long) {
        retardoSubs = ms * 1000L
        mp?.setSpuDelay(retardoSubs)
    }

    /** Hash de OpenSubtitles del archivo que suena ahora, si se puede leer (local o torrent). */
    fun calcularHash(): String? {
        val f = fuente ?: return null
        return try {
            val a = archivoTorrentActual
            val u = Uri.parse(f.uri)
            when {
                a != null -> {
                    val dir = motor.carpeta ?: return null
                    RandomAccessFile(File(dir, a.ruta), "r").use { Utiles.hashOpenSubs(it.channel) }
                }
                u.scheme == "content" -> {
                    val p = contentResolver.openFileDescriptor(u, "r") ?: return null
                    ParcelFileDescriptor.AutoCloseInputStream(p).use { Utiles.hashOpenSubs(it.channel) }
                }
                u.scheme == "file" -> RandomAccessFile(File(u.path ?: return null), "r").use { Utiles.hashOpenSubs(it.channel) }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Nombre del que sale la búsqueda de subtítulos. */
    fun nombreParaSubs(): String = Utiles.consultaSubs(titulo)

    private fun programarSubsAuto() {
        if (!ajustes.subAuto || ajustes.osApiKey.isEmpty()) return
        main.postDelayed({
            val m = mp ?: return@postDelayed
            val n = m.spuTracks?.size ?: 0
            if (n <= 1 && subsExternas.isEmpty()) buscarSubsAuto()
        }, 4000)
    }

    private fun buscarSubsAuto() {
        val consulta = nombreParaSubs()
        val idioma = ajustes.subIdioma
        Thread {
            try {
                val hash = calcularHash()
                val r = OpenSubtitles.buscar(ajustes, consulta, idioma, hash)
                val mejor = r.firstOrNull() ?: return@Thread
                val f = OpenSubtitles.descargar(ajustes, mejor, File(cacheDir, "subs"))
                main.post {
                    anadirSubtitulo(f)
                    avisoUi = "Subtítulos de OpenSubtitles: ${mejor.nombre}"
                }
            } catch (e: Exception) {
                avisoUi = "Subtítulos automáticos: ${e.message}"
            }
        }.start()
    }

    // ---------------------------------------------------------------- audio, bloqueos y sesion

    private fun pedirFoco() {
        if (foco != null) return
        val am = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { cambio ->
                main.post {
                    when (cambio) {
                        AudioManager.AUDIOFOCUS_LOSS -> pausar()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            if (mp?.isPlaying == true) {
                                pausadoPorFoco = true
                                mp?.pause()
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(35)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            mp?.setVolume(100)
                            if (pausadoPorFoco) {
                                pausadoPorFoco = false
                                mp?.play()
                            }
                        }
                    }
                }
            }
            .build()
        foco = req
        am.requestAudioFocus(req)
    }

    private fun abandonarFoco() {
        val req = foco ?: return
        foco = null
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(req)
    }

    private fun tomarBloqueos() {
        if (wake == null) {
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "optimoplay:reproduccion")
        }
        if (wake?.isHeld != true) wake?.acquire(6L * 60 * 60 * 1000)
        val u = fuente?.uri ?: ""
        val enRed = esTorrent || u.startsWith("http", true) || emisor != null
        if (enRed) {
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val bloqueoWifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "optimoplay:wifi")
                wifi = bloqueoWifi
            }
            if (wifi?.isHeld != true) wifi?.acquire()
        }
    }

    /** Mientras se estén guardando archivos no se sueltan los bloqueos (la descarga sigue con la pantalla apagada). */
    private fun liberarBloqueos() {
        if (hayGuardadoPendiente() || emisor != null) return
        soltarBloqueos()
    }

    private fun soltarBloqueos() {
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { /* nada */ }
        try { if (wifi?.isHeld == true) wifi?.release() } catch (e: Exception) { /* nada */ }
    }

    private fun actualizarSesion() {
        val s = sesion ?: return
        val m = mp
        val sonando = m?.isPlaying == true
        var acciones = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND
        if (hayLista) acciones = acciones or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val pos = try { m?.time ?: 0L } catch (e: Throwable) { 0L }
        val len = try { m?.length ?: 0L } catch (e: Throwable) { 0L }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(acciones)
                .setState(
                    if (sonando) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    pos,
                    if (sonando) 1f else 0f
                )
                .build()
        )
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, len)
                .build()
        )
        s.isActive = true
    }

    // ---------------------------------------------------------------- notificacion

    private fun construirNotificacion(): Notification {
        val sonando = try { mp?.isPlaying == true } catch (e: Throwable) { false }
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun accion(icono: Int, texto: String, a: String): Notification.Action {
            val pi = PendingIntent.getService(
                this, a.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(a),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icono), texto, pi).build()
        }

        val guardando = if (esTorrent) resumenGuardado() else ""
        val texto = when {
            emisor != null -> "Emitiendo a " + (destinoEmision?.nombre ?: "la tele")
            error != null -> error!!
            estado.isNotEmpty() -> estado
            mp == null && guardando.isNotEmpty() -> "Guardando en el dispositivo: $guardando"
            sonando -> "Reproduciendo" + (if (guardando.isNotEmpty()) " · guardando $guardando" else "")
            else -> "En pausa" + (if (guardando.isNotEmpty()) " · guardando $guardando" else "")
        }
        val estilo = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        sesion?.let { estilo.setMediaSession(it.sessionToken) }
        return Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (titulo.isEmpty()) "Optimo Play" else titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(sonando)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(accion(android.R.drawable.ic_media_rew, "−10 s", A_ATRAS))
            .addAction(
                accion(
                    if (sonando) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (sonando) "Pausa" else "Reproducir",
                    A_PLAYPAUSE
                )
            )
            .addAction(accion(android.R.drawable.ic_media_ff, "+10 s", A_ADELANTE))
            .addAction(accion(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", A_CERRAR))
            .setStyle(estilo)
            .build()
    }

    private fun primerPlano() {
        val n = construirNotificacion()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID_NOTIF, n)
        }
        enPrimerPlano = true
    }

    private fun notificar() {
        if (!enPrimerPlano) return
        getSystemService(NotificationManager::class.java).notify(ID_NOTIF, construirNotificacion())
    }
}
