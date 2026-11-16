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
    private val subsExternas = ArrayList<File>()
    private var archivoTorrentActual: ArchivoTorrent? = null

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
        ajustes = Ajustes(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Reproducción", NotificationManager.IMPORTANCE_LOW))

        val s = MediaSession(this, "OptimoPlay")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { mp?.play() }
            override fun onPause() { mp?.pause() }
            override fun onStop() { detener() }
            override fun onSeekTo(pos: Long) { mp?.setTime(pos) }
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
        guardarPos()
        try { unregisterReceiver(ruidoso) } catch (e: Exception) { /* nada */ }
        hiloTorrent?.interrupt()
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
        if (fuente?.uri != uri) return true
        if (mp == null) return true
        return error != null || fin
    }

    /** Empieza a reproducir una fuente nueva (URL, archivo, magnet o .torrent). */
    fun abrir(f: Fuente) {
        guardarPos()
        hiloTorrent?.interrupt()
        motor.cancelado = true
        error = null
        fin = false
        estado = ""
        ultimoEvento = ""
        necesitaElegir = false
        archivosTorrent = emptyList()
        subsExternas.clear()
        retardoSubs = 0L
        archivoTorrentActual = null
        fuente = f
        titulo = f.titulo
        claveRep = f.uri
        esTorrent = f.uri.startsWith("magnet:", true) || Utiles.esArchivoTorrent(f.uri) ||
            esContenidoTorrent(f.uri)
        if (!esTorrent) {
            // Los torrents se anotan cuando se sabe qué archivo suena (prepararArchivo).
            ajustes.registrar(f.uri, f.titulo)
            posReanudar = ajustes.posicion(claveRep)
        }
        primerPlano()
        crearReproductor()
        if (esTorrent) {
            abrirTorrent(f)
        } else {
            reproducir(f.uri, f)
        }
    }

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

    private fun destruirReproductor() {
        main.removeCallbacks(vigilanteInicio)
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

    // ---------------------------------------------------------------- torrent

    private fun abrirTorrent(f: Fuente) {
        estado = "Conectando al torrent…"
        val t = Thread {
            try {
                var archivoT: File? = null
                if (!f.uri.startsWith("magnet:", true)) archivoT = copiarTorrent(f.uri)
                val lista = motor.abrir(f.uri, archivoT) { s -> estado = s }
                archivosTorrent = lista
                val videos = lista.filter { it.esVideo }
                when {
                    videos.size == 1 -> prepararArchivo(videos[0])
                    videos.isEmpty() && lista.isNotEmpty() -> prepararArchivo(lista.maxByOrNull { it.tam }!!)
                    videos.size > 1 -> {
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
                        fin = true
                        ajustes.guardarPosicion(claveRep, 0L)
                        liberarBloqueos()
                        actualizarSesion()
                        notificar()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        error = "No se puede reproducir este contenido"
                        liberarBloqueos()
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
        }
        actualizarSesion()
        notificar()
    }

    // ---------------------------------------------------------------- controles

    fun alternar() {
        val m = mp ?: return
        if (m.isPlaying) m.pause() else m.play()
    }

    fun pausar() {
        val m = mp ?: return
        if (m.isPlaying) m.pause()
    }

    fun saltar(ms: Long) {
        val m = mp ?: return
        val len = m.length
        val destino = (m.time + ms).coerceAtLeast(0L)
        m.setTime(if (len > 0) minOf(destino, len - 500L).coerceAtLeast(0L) else destino)
    }

    /** Detiene todo, borra lo descargado y cierra el servicio. */
    fun detener() {
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
        val f = fuente ?: return
        val url = ultimaUrl ?: return
        crearReproductor()
        posReanudar = t
        reproducir(url, f)
    }

    private var ultimaUrl: String? = null

    // ---------------------------------------------------------------- subtitulos

    /** Último archivo de subtítulos externo/descargado cargado (para sincronizar por líneas). */
    fun archivoSubsActual(): File? = subsExternas.lastOrNull()

    fun anadirSubtitulo(f: File) {
        val m = mp ?: return
        subsExternas.add(f)
        m.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
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
        val enRed = esTorrent || u.startsWith("http", true)
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

    private fun liberarBloqueos() {
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { /* nada */ }
        try { if (wifi?.isHeld == true) wifi?.release() } catch (e: Exception) { /* nada */ }
    }

    private fun actualizarSesion() {
        val s = sesion ?: return
        val m = mp
        val sonando = m?.isPlaying == true
        val acciones = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND
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

        val texto = when {
            error != null -> error!!
            estado.isNotEmpty() -> estado
            sonando -> "Reproduciendo"
            else -> "En pausa"
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
