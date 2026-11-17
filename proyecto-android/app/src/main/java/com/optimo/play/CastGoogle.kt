package com.optimo.play

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * Chromecast con el SDK de Google Cast. Todo lo de Google está en ESTE archivo y en CastOpciones.kt:
 * si algún día diera problemas, se puede quitar con sus dos dependencias de gradle sin tocar el resto.
 * Todo va protegido: sin Google Play services simplemente no aparece ningún Chromecast.
 * Debe usarse desde el hilo principal.
 */
object CastGoogle {

    private fun selector(ctx: Context): MediaRouteSelector =
        CastContext.getSharedInstance(ctx).mergedSelector ?: MediaRouteSelector.EMPTY

    /** Empieza a buscar Chromecast. Devuelve la función que detiene la búsqueda. */
    fun buscar(ctx: Context, alEncontrar: (Destino) -> Unit): () -> Unit {
        return try {
            val app = ctx.applicationContext
            val sel = selector(app)
            val mr = MediaRouter.getInstance(app)
            val vistos = HashSet<String>()
            fun revisar(r: MediaRouter.RouteInfo) {
                if (r.isDefaultOrBluetooth || !r.matchesSelector(sel)) return
                if (vistos.add(r.id)) alEncontrar(Destino(Destino.CHROMECAST, r.name, r.id, r))
            }
            val cb = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { revisar(route) }
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { revisar(route) }
            }
            mr.addCallback(sel, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            for (r in mr.routes) revisar(r)
            val parar: () -> Unit = {
                try { mr.removeCallback(cb) } catch (e: Throwable) { /* nada */ }
            }
            parar
        } catch (e: Throwable) {
            val nada: () -> Unit = { }
            nada
        }
    }

    class EmisorCast(private val ctx: Context, private val ruta: MediaRouter.RouteInfo) : Emisor {

        override val nombre: String get() = ruta.name

        private val ui = Handler(Looper.getMainLooper())
        @Volatile private var est = EstadoRemoto(true, false, false, false, 0L, 0L, null)
        private var pendiente: Array<Any?>? = null
        private var escuchando = false
        private var cliente: RemoteMediaClient? = null
        private var vivo = true
        private var haSonado = false
        private var registrado = false

        private val progreso = RemoteMediaClient.ProgressListener { pos, dur ->
            val e = est
            est = EstadoRemoto(e.cargando, e.sonando, e.pausa, e.fin, pos, if (dur > 0) dur else e.durMs, e.error)
        }

        private val llamada = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                refrescar()
            }
        }

        private val escucha = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionStarted(s: CastSession, id: String) { listo(s) }
            override fun onSessionStartFailed(s: CastSession, error: Int) { fallo("No se pudo conectar con el Chromecast (código " + error + ")") }
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionEnded(s: CastSession, error: Int) {
                if (vivo) {
                    val e = est
                    est = EstadoRemoto(false, false, false, true, e.posMs, e.durMs, null)
                }
            }
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionResumed(s: CastSession, enSuspension: Boolean) { listo(s) }
            override fun onSessionResumeFailed(s: CastSession, error: Int) { fallo("No se pudo reanudar la conexión con el Chromecast") }
            override fun onSessionSuspended(s: CastSession, motivo: Int) {}
        }

        private fun fallo(msg: String) {
            val e = est
            est = EstadoRemoto(false, false, false, false, e.posMs, e.durMs, msg)
        }

        private fun refrescar() {
            val c = cliente ?: return
            val ms = c.mediaStatus ?: return
            val st = ms.playerState
            val sonando = st == MediaStatus.PLAYER_STATE_PLAYING
            if (sonando) haSonado = true
            val fin = st == MediaStatus.PLAYER_STATE_IDLE && haSonado && ms.idleReason == MediaStatus.IDLE_REASON_FINISHED
            val error = if (st == MediaStatus.PLAYER_STATE_IDLE && ms.idleReason == MediaStatus.IDLE_REASON_ERROR)
                "El Chromecast no pudo reproducir esto (formato no compatible o no alcanza al móvil)" else null
            val e = est
            est = EstadoRemoto(
                st == MediaStatus.PLAYER_STATE_LOADING || st == MediaStatus.PLAYER_STATE_BUFFERING || (st == MediaStatus.PLAYER_STATE_IDLE && !haSonado && error == null),
                sonando, st == MediaStatus.PLAYER_STATE_PAUSED, fin, c.approximateStreamPosition,
                if (c.streamDuration > 0) c.streamDuration else e.durMs, error
            )
        }

        private fun listo(s: CastSession) {
            val p = pendiente ?: return
            val c = s.remoteMediaClient
            if (c == null) {
                fallo("El Chromecast no respondió")
                return
            }
            pendiente = null
            if (cliente !== c || !registrado) {
                registrado = true
                try { c.registerCallback(llamada) } catch (e: Throwable) { /* nada */ }
                try { c.addProgressListener(progreso, 1000L) } catch (e: Throwable) { /* nada */ }
            }
            cliente = c
            haSonado = false
            val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
            meta.putString(MediaMetadata.KEY_TITLE, p[2] as String)
            val ib = MediaInfo.Builder(p[0] as String)
                .setStreamType(if (p[3] as Boolean) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(p[1] as String)
                .setMetadata(meta)
            val subs = p[5] as String?
            if (subs != null) {
                val pista = MediaTrack.Builder(1L, MediaTrack.TYPE_TEXT)
                    .setContentId(subs)
                    .setContentType("text/vtt")
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setName("Subtítulos")
                    .setLanguage("es")
                    .build()
                ib.setMediaTracks(listOf(pista))
                val estilo = TextTrackStyle()
                estilo.setForegroundColor(Color.WHITE)
                estilo.setBackgroundColor(0x99000000.toInt())
                estilo.setEdgeType(TextTrackStyle.EDGE_TYPE_OUTLINE)
                estilo.setEdgeColor(Color.BLACK)
                estilo.setFontScale(1.2f)
                ib.setTextTrackStyle(estilo)
            }
            val info = ib.build()
            val pos = p[4] as Long
            val req = MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime(if (pos > 5000L) pos else 0L)
                .apply { if (subs != null) setActiveTrackIds(longArrayOf(1L)) }
                .build()
            c.load(req)
        }

        override fun cargar(url: String, mime: String, titulo: String, directo: Boolean, posMs: Long, subs: String?) {
            pendiente = arrayOf(url, mime, titulo, directo, posMs, subs)
            ui.post {
                try {
                    val sm = CastContext.getSharedInstance(ctx).sessionManager
                    if (!escuchando) {
                        escuchando = true
                        sm.addSessionManagerListener(escucha, CastSession::class.java)
                    }
                    val actual = sm.currentCastSession
                    if (actual != null && actual.isConnected) {
                        listo(actual)
                    } else {
                        MediaRouter.getInstance(ctx).selectRoute(ruta)
                    }
                } catch (e: Throwable) {
                    fallo("Google Cast no está disponible en este móvil")
                }
            }
        }

        override fun reproducir() { ui.post { try { cliente?.play() } catch (e: Throwable) { /* nada */ } } }
        override fun pausar() { ui.post { try { cliente?.pause() } catch (e: Throwable) { /* nada */ } } }
        override fun buscar(ms: Long) {
            ui.post {
                try {
                    cliente?.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(ms).build())
                } catch (e: Throwable) { /* nada */ }
            }
        }

        override fun estado(): EstadoRemoto {
            // Pide al hilo principal que refresque; el valor sale de la última lectura.
            ui.post { refrescar() }
            return est
        }

        override fun parar() {
            vivo = false
            ui.post {
                try { cliente?.unregisterCallback(llamada) } catch (e: Throwable) { /* nada */ }
                try { cliente?.removeProgressListener(progreso) } catch (e: Throwable) { /* nada */ }
                try {
                    val sm = CastContext.getSharedInstance(ctx).sessionManager
                    sm.removeSessionManagerListener(escucha, CastSession::class.java)
                    sm.endCurrentSession(true)
                } catch (e: Throwable) { /* nada */ }
                try { MediaRouter.getInstance(ctx).unselect(MediaRouter.UNSELECT_REASON_STOPPED) } catch (e: Throwable) { /* nada */ }
            }
        }
    }
}
