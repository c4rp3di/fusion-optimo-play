package com.optimo.play

import android.content.Context
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Guarda en Descargas/OptimoPlay los archivos de un enlace de Mega, de forma análoga a lo que se hace con los torrents.
 *
 * A diferencia de los torrents no hay copia temporal: cada archivo se baja de Mega, se descifra al vuelo y se escribe directamente en
 * su destino final (así solo ocupa su tamaño). Los archivos se bajan de uno en uno, en el orden en que se marcan, con su propia conexión
 * (independiente de la que alimenta al reproductor), de modo que siguen aunque se cambie de pista o se salga del reproductor.
 * Si la conexión se corta a mitad, se reanuda por el byte donde iba (AES-CTR permite empezar en cualquier bloque).
 */
class MegaGuardado(
    private val ctx: Context,
    private val alCambio: () -> Unit,
    private val alAviso: (String) -> Unit,
    private val alEmpezar: () -> Unit,
    private val alTerminar: () -> Unit
) {

    class Tarea(val cli: MegaCliente, val nodo: MegaNodo, val ruta: String)

    /** Por identificador del archivo: 0 en cola o bajando, 2 guardado, -1 error. Sin entrada = sin marcar. */
    val estado = ConcurrentHashMap<String, Int>()
    val errores = ConcurrentHashMap<String, String>()
    private val hecho = ConcurrentHashMap<String, Long>()
    private val tam = ConcurrentHashMap<String, Long>()

    private val tareas = ArrayList<Tarea>()
    private val candado = Any()
    private var hilo: Thread? = null
    @Volatile private var actual: String? = null
    @Volatile private var cancelarActual = false
    @Volatile private var gen = 0

    fun estadoDe(handle: String): Int = estado[handle] ?: -2

    fun pendientes(): Int = estado.values.count { it == 0 }

    /** Marca como guardado un archivo que ya estaba en Descargas (no hace falta bajarlo). */
    fun marcarGuardado(handle: String) {
        estado[handle] = 2
        errores.remove(handle)
    }

    /** «2 archivos · 37 %» (vacío si no hay nada pendiente). */
    fun resumen(): String {
        var n = 0
        var total = 0L
        var bajado = 0L
        for ((h, st) in estado.entries.toList()) {
            if (st != 0) continue
            n++
            val t = tam[h] ?: 0L
            total += t
            bajado += minOf(hecho[h] ?: 0L, t)
        }
        if (n == 0) return ""
        val pct = if (total > 0) (bajado * 100 / total).toInt() else 0
        return (if (n == 1) "1 archivo" else "$n archivos") + " · " + pct + " %"
    }

    private fun limpiar(h: String) {
        estado.remove(h)
        errores.remove(h)
        hecho.remove(h)
        tam.remove(h)
    }

    /** Pone en cola archivos para guardar (los que ya están en cola o bajándose se ignoran). */
    fun anadir(lista: List<Tarea>) {
        var arrancado = false
        synchronized(candado) {
            for (t in lista) {
                val h = t.nodo.handle
                if (estado[h] == 0) continue
                estado[h] = 0
                errores.remove(h)
                hecho[h] = 0L
                tam[h] = t.nodo.tam
                tareas.add(t)
            }
            if (hilo == null && tareas.isNotEmpty()) {
                val g = gen
                val t = Thread { bucle(g) }
                t.isDaemon = true
                hilo = t
                arrancado = true
                t.start()
            }
        }
        if (arrancado) alEmpezar()
        alCambio()
    }

    /** Quita un archivo de la cola, o corta su descarga si es el que se está bajando. */
    fun quitar(handle: String) {
        synchronized(candado) {
            val i = tareas.indexOfFirst { it.nodo.handle == handle }
            if (i >= 0) {
                tareas.removeAt(i)
                limpiar(handle)
                return
            }
        }
        if (actual == handle) cancelarActual = true else if (estado[handle] == -1) limpiar(handle)
    }

    /** Cancela todo lo pendiente (lo ya guardado en Descargas se queda). */
    fun cancelarTodo() {
        synchronized(candado) {
            gen++
            tareas.clear()
            for ((h, st) in estado.entries.toList()) if (st == 0) limpiar(h)
            hilo = null
        }
        cancelarActual = true
    }

    private fun bucle(g: Int) {
        while (true) {
            var siguiente: Tarea? = null
            synchronized(candado) {
                if (g == gen && tareas.isNotEmpty()) {
                    siguiente = tareas.removeAt(0)
                } else if (hilo === Thread.currentThread()) {
                    hilo = null
                }
            }
            val t = siguiente ?: break
            val h = t.nodo.handle
            actual = h
            cancelarActual = false
            val cancelado = { g != gen || cancelarActual }
            alCambio()

            if (t.nodo.tam > 0 && Exportar.yaGuardado(ctx, t.ruta, t.nodo.tam)) {
                estado[h] = 2
                hecho[h] = t.nodo.tam
                alCambio()
                continue
            }

            var fallo: MegaException? = null
            val err = Exportar.aDescargasFlujo(ctx, t.ruta) { o ->
                try {
                    bajar(t, o, cancelado)
                } catch (e: MegaException) {
                    fallo = e
                    throw e
                }
            }
            if (g != gen) break // se canceló todo: el estado ya está limpio
            if (cancelarActual) {
                limpiar(h)
                alCambio()
                continue
            }
            if (err == null) {
                estado[h] = 2
                hecho[h] = t.nodo.tam
                alAviso("Guardado en " + Exportar.destinoTexto() + ": " + t.nodo.nombre)
            } else {
                estado[h] = -1
                errores[h] = err
                alAviso("No se pudo guardar " + t.nodo.nombre + ": " + err)
                if (fallo?.codigo == MegaException.CUOTA) {
                    // Con la cuota agotada el resto fallaría igual: se marcan con el mismo motivo y se para.
                    synchronized(candado) {
                        for (r in tareas) {
                            estado[r.nodo.handle] = -1
                            errores[r.nodo.handle] = err
                        }
                        tareas.clear()
                    }
                    alCambio()
                    break
                }
            }
            alCambio()
        }
        synchronized(candado) { if (hilo === Thread.currentThread()) hilo = null }
        actual = null
        if (g == gen) alTerminar()
    }

    /** Baja un archivo entero de Mega, lo descifra y lo escribe en [destino]; si la conexión se corta, sigue por donde iba. */
    private fun bajar(t: Tarea, destino: OutputStream, cancelado: () -> Boolean) {
        val n = t.nodo
        val sal = BufferedOutputStream(destino, 1 shl 20)
        var escritos = 0L
        var fallos = 0
        while (escritos < n.tam) {
            if (cancelado()) throw IOException("Cancelado")
            val antes = escritos
            val bloque = escritos and 15L.inv()
            var con: HttpURLConnection? = null
            try {
                con = MegaFlujo.conectar(t.cli, n, bloque, n.tam - 1)
                MegaFlujo.descifrar(con.inputStream, n, escritos, bloque, n.tam - 1, sal, cancelado) { w ->
                    escritos += w
                    hecho[n.handle] = escritos
                }
                if (escritos < n.tam) throw IOException("Mega cortó la conexión")
            } catch (e: MegaException) {
                throw e
            } catch (e: IOException) {
                val m = e.message ?: ""
                if (m.contains("ENOSPC") || m.contains("No space", true)) {
                    throw MegaException(MegaException.GENERAL, "No queda espacio libre en el dispositivo")
                }
                if (cancelado()) throw e
                if (escritos > antes) fallos = 0
                if (++fallos > 5) throw e
                try {
                    Thread.sleep(1500L * fallos)
                } catch (x: InterruptedException) {
                    throw IOException("Cancelado")
                }
            } finally {
                try { con?.disconnect() } catch (e: Exception) { /* nada */ }
            }
        }
        sal.flush()
    }

    companion object {
        /** Quita de un nombre lo que Android o el sistema de archivos no admiten. */
        fun limpiarNombre(s: String): String {
            val t = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trimStart(' ').trimEnd(' ', '.')
            return if (t.isEmpty() || t == "." || t == "..") "_" else t
        }

        /** Ruta dentro de Descargas/OptimoPlay: «Carpeta de Mega/Subcarpeta/archivo.mkv» (o solo el nombre si es un archivo suelto). */
        fun ruta(raiz: String?, n: MegaNodo): String {
            val partes = ArrayList<String>()
            if (!raiz.isNullOrEmpty()) partes.add(limpiarNombre(raiz))
            if (n.ruta.isNotEmpty()) for (p in n.ruta.split('/')) if (p.isNotEmpty()) partes.add(limpiarNombre(p))
            partes.add(limpiarNombre(n.nombre))
            return partes.joinToString("/")
        }
    }
}
