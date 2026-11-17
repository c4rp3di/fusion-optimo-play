package com.optimo.play

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.net.ServerSocket

/**
 * Para las teles que no entienden HLS ni https (las Smart TV antiguas): libVLC abre la dirección original
 * (con su Referer y User-Agent) y la re-emite por HTTP en la red local como MPEG-TS, sin recodificar.
 * Es un directo: no se puede saltar de posición. El móvil debe seguir encendido (la pantalla puede estar bloqueada).
 */
class Retransmision(
    private val ctx: Context,
    private val url: String,
    private val referer: String?,
    private val ua: String?
) {
    private var vlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    @Volatile var error: String? = null
        private set

    /** Arranca y devuelve el puerto en el que se sirve «/live.ts». */
    fun iniciar(): Int {
        val puerto = ServerSocket(0).use { it.localPort }
        val v = LibVLC(ctx.applicationContext, arrayListOf("--no-drop-late-frames"))
        val m = MediaPlayer(v)
        m.setEventListener { ev ->
            if (ev.type == MediaPlayer.Event.EncounteredError) error = "No se pudo abrir el canal para retransmitirlo"
        }
        val media = Media(v, Uri.parse(url))
        media.addOption(":network-caching=3000")
        media.addOption(":sout=#std{access=http,mux=ts,dst=:" + puerto + "/live.ts}")
        media.addOption(":sout-keep")
        referer?.let { media.addOption(":http-referrer=$it") }
        ua?.let { media.addOption(":http-user-agent=$it") }
        m.setMedia(media)
        media.release()
        vlc = v
        mp = m
        m.play()
        return puerto
    }

    fun liberar() {
        val m = mp
        val v = vlc
        mp = null
        vlc = null
        if (m != null) {
            try { m.setEventListener(null) } catch (e: Throwable) { /* nada */ }
            try { m.stop() } catch (e: Throwable) { /* nada */ }
            try { m.release() } catch (e: Throwable) { /* nada */ }
        }
        try { v?.release() } catch (e: Throwable) { /* nada */ }
    }
}
