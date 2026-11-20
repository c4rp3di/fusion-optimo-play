package com.optimo.play

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Baja las partes de un archivo comprimido de Mega, las junta y extrae su contenido a Descargas/OptimoPlay.
 *
 * Cada paquete pasa por: 1) bajar las partes a la caché de la app (si se corta, se reanuda por el byte donde iba, incluso tras cerrar la
 * app); 2) extraerlas con [Descompresor] directamente a Descargas, sin copia intermedia; 3) borrar las partes. Si hace falta contraseña
 * el paquete queda esperándola ([esperandoClave]) con las partes ya bajadas; al darla solo se repite la extracción.
 * Se trabaja de un paquete en uno, con una conexión propia (independiente de la que alimenta al reproductor).
 */
class MegaExtractor(
    private val ctx: Context,
    private val alCambio: () -> Unit,
    private val alAviso: (String) -> Unit,
    private val alEmpezar: () -> Unit,
    private val alTerminar: () -> Unit
) {

    class Trabajo(val cli: MegaCliente, val p: MegaPaquete, val raiz: String, var clave: String?)

    /** Por id del paquete: 0 en cola o trabajando, 1 esperando contraseña, 2 listo, -1 error. Sin entrada = sin tocar. */
    val estado = ConcurrentHashMap<String, Int>()
    private val fase = ConcurrentHashMap<String, String>()
    val errores = ConcurrentHashMap<String, String>()
    /** Rutas (dentro de Descargas/OptimoPlay) de lo extraído de cada paquete. */
    val resultados = ConcurrentHashMap<String, List<String>>()
    @Volatile var esperandoClave: MegaPaquete? = null
    @Volatile var avisoClave: String? = null

    private val trabajos = ConcurrentHashMap<String, Trabajo>()
    private val cola = ArrayList<Trabajo>()
    private val candado = Any()
    private var hilo: Thread? = null
    @Volatile private var gen = 0
    @Volatile private var cancelarId: String? = null
    @Volatile private var ultimoCambio = 0L

    fun estadoDe(id: String): Int = estado[id] ?: -2

    fun faseDe(id: String): String = fase[id] ?: ""

    fun pendientes(): Int = estado.values.count { it == 0 }

    /** «📦 Peli: bajando 3 partes · 37 %» (vacío si no hay nada en marcha). */
    fun resumen(): String {
        val activos = estado.entries.filter { it.value == 0 }.mapNotNull { fase[it.key]?.takeIf { f -> f.isNotEmpty() } }
        return activos.joinToString(" · ")
    }

    private fun avisarCambio(forzar: Boolean = false) {
        val ahora = System.currentTimeMillis()
        if (forzar || ahora - ultimoCambio > 3000) {
            ultimoCambio = ahora
            alCambio()
        }
    }

    /** Pone en marcha (o reanuda) un paquete. [raiz] es la carpeta de Mega («» si el enlace es un archivo suelto). */
    fun iniciar(cli: MegaCliente, p: MegaPaquete, raiz: String, clave: String?) {
        var arrancado = false
        synchronized(candado) {
            if (estado[p.id] == 0) return
            if (cancelarId == p.id) cancelarId = null
            val t = trabajos[p.id]?.also { if (clave != null) it.clave = clave } ?: Trabajo(cli, p, raiz, clave)
            trabajos[p.id] = t
            estado[p.id] = 0
            errores.remove(p.id)
            fase[p.id] = p.base + ": en cola"
            if (esperandoClave?.id == p.id) {
                esperandoClave = null
                avisoClave = null
            }
            cola.add(t)
            if (hilo == null) {
                val g = gen
                val h = Thread { bucle(g) }
                h.isDaemon = true
                hilo = h
                arrancado = true
                h.start()
            }
        }
        if (arrancado) alEmpezar()
        avisarCambio(true)
    }

    /** El usuario escribió la contraseña del paquete que la esperaba. */
    fun darClave(clave: String) {
        val p = esperandoClave ?: return
        val t = trabajos[p.id] ?: return
        iniciar(t.cli, p, t.raiz, clave)
    }

    /** El usuario cerró el diálogo de la contraseña sin darla. */
    fun rechazarClave() {
        val p = esperandoClave ?: return
        esperandoClave = null
        avisoClave = null
        estado[p.id] = -1
        errores[p.id] = "Sin la contraseña no se puede extraer"
        fase.remove(p.id)
        borrarCache(p.id)
        alCambio()
    }

    /** Quita un paquete de la cola o corta su trabajo, y borra lo bajado. */
    fun cancelar(id: String) {
        var enCola = false
        synchronized(candado) {
            val i = cola.indexOfFirst { it.p.id == id }
            if (i >= 0) {
                cola.removeAt(i)
                enCola = true
            }
        }
        if (enCola || estado[id] == 1 || estado[id] == -1) {
            limpiar(id)
            if (esperandoClave?.id == id) {
                esperandoClave = null
                avisoClave = null
            }
            borrarCache(id)
        } else if (estado[id] == 0) {
            cancelarId = id // el hilo lo corta y lo limpia
        }
        avisarCambio(true)
    }

    fun cancelarTodo() {
        val ids: List<String>
        synchronized(candado) {
            gen++
            cola.clear()
            hilo = null
            ids = estado.entries.filter { it.value == 0 || it.value == 1 }.map { it.key }
        }
        for (id in ids) {
            limpiar(id)
            borrarCache(id)
        }
        esperandoClave = null
        avisoClave = null
        cancelarId = null
    }

    private fun limpiar(id: String) {
        estado.remove(id)
        fase.remove(id)
        errores.remove(id)
        trabajos.remove(id)
    }

    // ---------------------------------------------------------------- trabajo

    private fun carpeta(id: String): File = File(File(ctx.cacheDir, "mega_paq"), id)

    private fun borrarCache(id: String) {
        try { carpeta(id).deleteRecursively() } catch (e: Exception) { /* nada */ }
    }

    private fun bucle(g: Int) {
        while (true) {
            var siguiente: Trabajo? = null
            synchronized(candado) {
                if (g == gen && cola.isNotEmpty()) {
                    siguiente = cola.removeAt(0)
                } else if (hilo === Thread.currentThread()) {
                    hilo = null
                }
            }
            val t = siguiente ?: break
            procesar(g, t)
            if (g != gen) break
        }
        synchronized(candado) { if (hilo === Thread.currentThread()) hilo = null }
        if (g == gen) alTerminar()
    }

    private fun porcentaje(h: Long, total: Long): Int = if (total > 0) (h * 100 / total).toInt().coerceIn(0, 100) else 0

    private fun procesar(g: Int, t: Trabajo) {
        val p = t.p
        val id = p.id
        val cancelado = { g != gen || cancelarId == id }
        val dir = carpeta(id)
        try {
            Descompresor.comprobarMotor()?.let { throw IOException(it) }
            dir.mkdirs()

            // 1. Bajar las partes a la caché (reanudando lo que ya hubiera).
            var hecho = 0L
            var ultimoPct = -1
            for ((i, parte) in p.partes.withIndex()) {
                val f = File(dir, p.nombreCache(parte))
                val previo = hecho
                bajarAFichero(t.cli, parte, f, cancelado) { acumulado ->
                    hecho = previo + acumulado
                    val pct = porcentaje(hecho, p.tam)
                    if (pct != ultimoPct) {
                        ultimoPct = pct
                        fase[id] = p.base + ": bajando parte " + (i + 1) + "/" + p.partes.size + " · " + pct + " %"
                        avisarCambio()
                    }
                }
                hecho = previo + parte.tam
            }

            // 2. Extraer directamente a Descargas.
            fase[id] = p.base + ": extrayendo…"
            avisarCambio(true)
            ultimoPct = -1
            val rutaBase = ArrayList<String>()
            if (t.raiz.isNotEmpty()) rutaBase.add(MegaGuardado.limpiarNombre(t.raiz))
            for (s in p.ruta.split('/')) if (s.isNotEmpty()) rutaBase.add(MegaGuardado.limpiarNombre(s))
            val res = Descompresor.extraer(
                ctx, dir, File(dir, p.nombreCache(p.partes[0])), p.tipo, t.clave, rutaBase.joinToString("/"), cancelado
            ) { h, total ->
                val pct = porcentaje(h, total)
                if (pct != ultimoPct) {
                    ultimoPct = pct
                    fase[id] = p.base + ": extrayendo · " + pct + " %"
                    avisarCambio()
                }
            }

            // 3. Listo: se borran las partes.
            resultados[id] = res.rutas
            estado[id] = 2
            fase.remove(id)
            trabajos.remove(id)
            borrarCache(id)
            if (res.fallos.isNotEmpty()) {
                alAviso("Extraído con " + res.fallos.size + " fallo(s): " + res.fallos.first())
            } else {
                alAviso("Extraído en " + Exportar.destinoTexto() + ": " + p.base)
            }
            avisarCambio(true)
        } catch (e: ClaveNecesaria) {
            if (g == gen) {
                estado[id] = 1
                fase.remove(id)
                avisoClave = if (e.incorrecta) "Contraseña incorrecta" else null
                esperandoClave = p
                avisarCambio(true)
            }
        } catch (e: Throwable) {
            if (g != gen) return // se canceló todo: el estado ya está limpio
            if (cancelarId == id) {
                limpiar(id)
                borrarCache(id)
                avisarCambio(true)
                return
            }
            val m = when (e) {
                is MegaException -> e.message
                is InterruptedException -> "Cancelado"
                else -> e.message ?: e.javaClass.simpleName
            } ?: "Error"
            estado[id] = -1
            errores[id] = m
            fase.remove(id)
            alAviso("No se pudo extraer " + p.base + ": " + m)
            avisarCambio(true)
        }
    }

    /**
     * Baja [n] entero a [destino] (descifrado). Si el archivo ya tiene parte, sigue por ahí: AES-CTR permite empezar en cualquier
     * bloque. Si la conexión se corta reintenta (hasta 5 veces seguidas sin avanzar). [alEscribir] recibe los bytes ya presentes
     * en total (primero lo que ya estaba y luego cada trozo nuevo).
     */
    private fun bajarAFichero(cli: MegaCliente, n: MegaNodo, destino: File, cancelado: () -> Boolean, alEscribir: (Long) -> Unit) {
        var escritos = if (destino.isFile) destino.length() else 0L
        if (escritos > n.tam) {
            destino.delete()
            escritos = 0L
        }
        alEscribir(escritos)
        if (escritos == n.tam) return
        FileOutputStream(destino, true).use { fos ->
            val sal = BufferedOutputStream(fos, 1 shl 20)
            var fallos = 0
            while (escritos < n.tam) {
                if (cancelado()) throw IOException("Cancelado")
                val antes = escritos
                val bloque = escritos and 15L.inv()
                var con: HttpURLConnection? = null
                try {
                    con = MegaFlujo.conectar(cli, n, bloque, n.tam - 1)
                    MegaFlujo.descifrar(con.inputStream, n, escritos, bloque, n.tam - 1, sal, cancelado) { w ->
                        escritos += w
                        alEscribir(escritos)
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
    }

    companion object {
        /** Borra de la caché los paquetes a medias de hace más de 3 días (si alguien cerró la app a mitad y no volvió). */
        fun limpiarCacheAntigua(ctx: Context) {
            try {
                val base = File(ctx.cacheDir, "mega_paq")
                val limite = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
                base.listFiles()?.forEach { if (it.lastModified() < limite) it.deleteRecursively() }
            } catch (e: Exception) {
                // nada
            }
        }
    }
}
