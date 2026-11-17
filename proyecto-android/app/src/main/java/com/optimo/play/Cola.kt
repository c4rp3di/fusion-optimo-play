package com.optimo.play

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Una pista de la cola de reproducción: la URI, el nombre del archivo (con extensión) y lo que se muestra. */
data class Pista(val uri: String, val nombre: String, val titulo: String = Utiles.sinExtension(nombre))

/** Lo que se le pide al reproductor: reproducir estas pistas desde [indice], o añadirlas a la cola actual. */
class PedidoCola(val pistas: List<Pista>, val indice: Int, val titulo: String, val anadir: Boolean)

/**
 * Cola de reproducción de archivos locales (una carpeta, un .m3u de un disco, varios archivos elegidos).
 * Las pistas pasan al reproductor por memoria (mismo proceso), no por el Intent, para no chocar con el límite de
 * tamaño de los Intent cuando la carpeta tiene muchos archivos.
 */
object ColaIntent {

    private const val EXTRA = "cola"

    @Volatile private var pedido: PedidoCola? = null

    /** Empieza a reproducir la cola, o (con [anadir]) la añade al final de la que ya suena. */
    fun lanzar(ctx: Context, pistas: List<Pista>, indice: Int, titulo: String, anadir: Boolean = false) {
        if (pistas.isEmpty()) return
        if (anadir) {
            val s = PlaybackService.activo
            if (s != null && s.hayCola) {
                s.anadirACola(pistas)
                return
            }
        }
        pedido = PedidoCola(pistas, indice.coerceIn(0, pistas.size - 1), titulo, false)
        val i = Intent(ctx, PlayerActivity::class.java)
        i.action = Intent.ACTION_VIEW
        i.data = Uri.parse(pistas[indice.coerceIn(0, pistas.size - 1)].uri)
        i.putExtra("title", titulo)
        i.putExtra(EXTRA, true)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    }

    /** El pedido que acompaña a este Intent, si lo hay (se entrega una sola vez). */
    @Synchronized
    fun tomar(i: Intent?): PedidoCola? {
        if (i == null || !i.getBooleanExtra(EXTRA, false)) return null
        i.removeExtra(EXTRA)
        val p = pedido
        pedido = null
        return p
    }
}
