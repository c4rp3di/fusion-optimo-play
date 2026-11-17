package com.optimo.play

/** Una pantalla a la que se puede enviar lo que suena: una tele DLNA o un Chromecast. */
class Destino(val tipo: String, val nombre: String, val id: String, val datos: Any?) {
    companion object {
        const val DLNA = "DLNA"
        const val CHROMECAST = "Chromecast"
    }
}

/** Lo que dice la pantalla remota (se refresca en segundo plano; leerlo es barato). */
class EstadoRemoto(
    val cargando: Boolean,
    val sonando: Boolean,
    val pausa: Boolean,
    val fin: Boolean,
    val posMs: Long,
    val durMs: Long,
    val error: String?
)

/**
 * Control de una pantalla remota. Todos los métodos devuelven enseguida (la red va en segundo plano);
 * los fallos aparecen en [EstadoRemoto.error].
 */
interface Emisor {
    val nombre: String
    fun cargar(url: String, mime: String, titulo: String, directo: Boolean, posMs: Long)
    fun reproducir()
    fun pausar()
    fun buscar(ms: Long)
    fun estado(): EstadoRemoto

    /** Termina la emisión y libera todo. */
    fun parar()
}
