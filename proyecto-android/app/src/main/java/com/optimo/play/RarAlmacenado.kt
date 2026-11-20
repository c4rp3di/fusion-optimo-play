package com.optimo.play

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Un trozo de un archivo de dentro del RAR: [tam] bytes que están en la parte [parte], a partir de [desde]. */
class RarSegmento(val parte: Int, val desde: Long, val tam: Long)

class RarEntrada(
    val nombre: String,
    val segmentos: List<RarSegmento>,
    /** Método 0 («almacenar»): los bytes del archivo están tal cual dentro del RAR, sin comprimir. */
    val almacenado: Boolean,
    val cifrado: Boolean,
    /** El último trozo no continúa en otro volumen: el archivo está entero. */
    val completo: Boolean
) {
    val tam: Long = segmentos.sumOf { it.tam }

    /** Se puede reproducir leyendo directamente de las partes, sin extraer. */
    val reproducibleSinExtraer: Boolean
        get() = almacenado && !cifrado && completo && tam > 0 && Utiles.esReproducible(nombre.substringAfterLast('/'))
}

class RarIndice(val entradas: List<RarEntrada>) {
    val reproducibles: List<RarEntrada> get() = entradas.filter { it.reproducibleSinExtraer }

    /** Por qué no se puede reproducir sin extraer (para decírselo al usuario). */
    fun motivoNo(): String = when {
        entradas.isEmpty() -> "No he encontrado archivos dentro del RAR"
        entradas.any { it.cifrado } -> "Está cifrado con contraseña: hay que descargarlo y extraerlo"
        entradas.any { !it.almacenado && Utiles.esReproducible(it.nombre.substringAfterLast('/')) } ->
            "El vídeo está comprimido dentro del RAR: hay que descargarlo y extraerlo"
        entradas.none { Utiles.esReproducible(it.nombre.substringAfterLast('/')) } -> "No hay audio ni vídeo dentro del RAR"
        else -> "El archivo no está completo dentro de las partes"
    }
}

/** El RAR no se puede leer por dentro sin extraerlo (versión antigua, cabeceras cifradas…). */
class RarNoIndexable(mensaje: String) : Exception(mensaje)

/** Lee un trozo pequeño de un archivo de Mega (ya descifrado). */
object MegaLeer {
    fun bytes(cli: MegaCliente, n: MegaNodo, ini: Long, fin: Long): ByteArray {
        val bloque = ini and 15L.inv()
        val con = MegaFlujo.conectar(cli, n, bloque, fin)
        try {
            val sal = ByteArrayOutputStream((fin - ini + 1).toInt().coerceIn(16, 1 shl 20))
            MegaFlujo.descifrar(con.inputStream, n, ini, bloque, fin, sal)
            return sal.toByteArray()
        } finally {
            try { con.disconnect() } catch (e: Exception) { /* nada */ }
        }
    }
}

/**
 * Lee las cabeceras de un RAR5 repartido en volúmenes, directamente de Mega y sin bajar los datos, para saber dónde está cada archivo.
 * Si un vídeo está guardado sin comprimir (método 0, muy habitual en RAR de vídeo) y sin cifrar, sus bytes están tal cual dentro
 * de las partes: se puede servir con saltos de posición leyendo solo los trozos que hagan falta ([MegaVirtual]).
 *
 * Formato RAR5: firma de 8 bytes y luego bloques «CRC32 · tamaño de cabecera (vint) · tipo (vint) · flags (vint) · [tamaño del área extra]
 * · [tamaño de los datos] · …»; tipo 2 = archivo, 4 = cabeceras cifradas, 5 = fin. Los enteros «vint» llevan 7 bits por byte.
 */
object RarAlmacenado {

    private val FIRMA5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private val FIRMA4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)

    private class Lec(val b: ByteArray, var p: Int = 0) {
        fun vint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                if (p >= b.size || s > 63) throw IOException("Cabecera RAR dañada")
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x and 0x80 == 0) return r
                s += 7
            }
        }

        fun salta(n: Int) {
            if (n < 0 || p + n > b.size) throw IOException("Cabecera RAR dañada")
            p += n
        }

        fun bytes(n: Int): ByteArray {
            if (n < 0 || p + n > b.size) throw IOException("Cabecera RAR dañada")
            val r = b.copyOfRange(p, p + n)
            p += n
            return r
        }
    }

    private fun Long.tiene(bit: Long) = (this and bit) != 0L

    private class Cabecera(
        val nombre: String, val metodo: Int, val cifrado: Boolean, val esDir: Boolean,
        val deAnterior: Boolean, val aSiguiente: Boolean, val desde: Long, val tam: Long
    )

    private fun leerVolumen(cli: MegaCliente, parte: MegaNodo, cancelado: () -> Boolean): List<Cabecera> {
        val total = parte.tam
        if (total < 16) throw RarNoIndexable("Una de las partes está vacía o incompleta")
        // Los primeros 16 KB de cada volumen (firma, cabecera principal y cabecera del archivo) se leen de una vez y se reutilizan.
        var cache = ByteArray(0)
        var cacheIni = 0L
        fun leer(ini: Long, n: Int): ByteArray {
            val fin = minOf(total, ini + n)
            if (ini >= cacheIni && fin <= cacheIni + cache.size) {
                return cache.copyOfRange((ini - cacheIni).toInt(), (fin - cacheIni).toInt())
            }
            val pedir = minOf(maxOf(fin - ini, 16384L), total - ini)
            val b = MegaLeer.bytes(cli, parte, ini, ini + pedir - 1)
            cache = b
            cacheIni = ini
            return b.copyOfRange(0, (fin - ini).toInt())
        }
        val firma = leer(0L, 8)
        if (!firma.contentEquals(FIRMA5)) {
            if (firma.size >= 7 && firma.copyOf(7).contentEquals(FIRMA4)) throw RarNoIndexable("Es un RAR antiguo (versión 4)")
            throw RarNoIndexable("Una de las partes no es un RAR válido")
        }
        val salida = ArrayList<Cabecera>()
        var pos = 8L
        var guarda = 0
        while (pos + 12 < total && guarda++ < 20000) {
            if (cancelado()) throw IOException("Cancelado")
            var buf = leer(pos, minOf(4096L, total - pos).toInt())
            var l = Lec(buf, 4) // se salta el CRC32
            val hsize = l.vint()
            val tras = l.p
            val bloque = tras + hsize
            if (hsize < 2 || bloque > total - pos || hsize > (8L shl 20)) throw IOException("Cabecera RAR dañada")
            if (bloque > buf.size) {
                buf = leer(pos, bloque.toInt())
                l = Lec(buf, tras)
            }
            val finCab = bloque.toInt()
            val tipo = l.vint()
            val flags = l.vint()
            val extra = if (flags.tiene(1L)) l.vint() else 0L
            val datos = if (flags.tiene(2L)) l.vint() else 0L
            when (tipo) {
                2L -> {
                    val ff = l.vint()
                    l.vint() // tamaño sin comprimir
                    l.vint() // atributos
                    if (ff.tiene(2L)) l.salta(4) // fecha
                    if (ff.tiene(4L)) l.salta(4) // CRC32
                    val comp = l.vint()
                    l.vint() // sistema
                    val nl = l.vint().toInt()
                    val nombre = String(l.bytes(nl), Charsets.UTF_8)
                    val metodo = ((comp shr 7) and 7L).toInt()
                    var cifrado = false
                    if (extra > 0 && extra <= finCab) {
                        val e = Lec(buf, finCab - extra.toInt())
                        while (e.p < finCab) {
                            val sz = e.vint().toInt()
                            val ini = e.p
                            if (sz <= 0) break
                            if (e.vint() == 1L) cifrado = true // registro «cifrado» del archivo
                            e.p = ini + sz
                        }
                    }
                    salida.add(Cabecera(nombre, metodo, cifrado, ff.tiene(1L), flags.tiene(8L), flags.tiene(0x10L), pos + bloque, datos))
                }
                4L -> throw RarNoIndexable("Las cabeceras están cifradas")
                5L -> return salida
            }
            pos += bloque + datos
            if (datos > 0 && total - pos <= 32) break // tras unos datos, si solo queda el bloque de fin no hace falta leerlo
        }
        return salida
    }

    /**
     * Lee las cabeceras de todas las [partes] (en orden de volumen) con unas cuantas conexiones a la vez.
     * Lanza [RarNoIndexable] si no es un RAR5 legible por dentro, o MegaException/IOException si falla la red.
     */
    fun indexar(
        cli: MegaCliente, partes: List<MegaNodo>,
        progreso: (Int, Int) -> Unit = { _, _ -> }, cancelado: () -> Boolean = { false }
    ): RarIndice {
        val pool = Executors.newFixedThreadPool(4)
        val hechas = AtomicInteger(0)
        val volumenes: List<List<Cabecera>> = try {
            val futuros = partes.map { p ->
                pool.submit(Callable {
                    val r = leerVolumen(cli, p, cancelado)
                    progreso(hechas.incrementAndGet(), partes.size)
                    r
                })
            }
            try {
                futuros.map { it.get() }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }

        class Acum(val nombre: String) {
            val segs = ArrayList<RarSegmento>()
            var almacenado = true
            var cifrado = false
            var aSiguiente = false
        }
        val mapa = LinkedHashMap<String, Acum>()
        for ((i, vol) in volumenes.withIndex()) {
            for (h in vol) {
                if (h.esDir) continue
                var a = mapa[h.nombre]
                if (a == null) {
                    if (h.deAnterior) continue // continúa algo que no hemos visto
                    a = Acum(h.nombre)
                    mapa[h.nombre] = a
                } else if (!h.deAnterior) {
                    continue // nombre repetido
                }
                a.segs.add(RarSegmento(i, h.desde, h.tam))
                a.almacenado = a.almacenado && h.metodo == 0
                a.cifrado = a.cifrado || h.cifrado
                a.aSiguiente = h.aSiguiente
            }
        }
        return RarIndice(mapa.values.map { RarEntrada(it.nombre, it.segs, it.almacenado, it.cifrado, !it.aSiguiente) })
    }
}

private class Trozo(val nodo: MegaNodo, val ini: Long, val fin: Long)

/**
 * Un archivo que está sin comprimir dentro de un RAR en varias partes: se lee por rangos de las partes, así que se puede
 * reproducir (y saltar de posición) sin bajar ni extraer nada.
 */
class MegaVirtual(
    override val handle: String,
    override val nombre: String,
    private val partes: List<MegaNodo>,
    private val segmentos: List<RarSegmento>
) : MegaFuente {

    private val inicios = LongArray(segmentos.size)
    override val tam: Long

    init {
        var acc = 0L
        for (i in segmentos.indices) {
            inicios[i] = acc
            acc += segmentos[i].tam
        }
        tam = acc
    }

    private fun conectar(cli: MegaCliente, t: Trozo): HttpURLConnection =
        MegaFlujo.conectar(cli, t.nodo, t.ini and 15L.inv(), t.fin)

    override fun abrir(cli: MegaCliente, ini: Long, fin: Long): MegaLectura {
        val trozos = ArrayList<Trozo>()
        for (i in segmentos.indices) {
            val seg = segmentos[i]
            if (seg.tam == 0L) continue
            val s0 = inicios[i]
            val s1 = s0 + seg.tam - 1
            if (s1 < ini || s0 > fin) continue
            val a = maxOf(ini, s0)
            val b = minOf(fin, s1)
            trozos.add(Trozo(partes[seg.parte], seg.desde + (a - s0), seg.desde + (b - s0)))
        }
        if (trozos.isEmpty()) throw MegaException(MegaException.GENERAL, "Rango fuera del archivo")
        var primera: HttpURLConnection? = conectar(cli, trozos[0])
        return object : MegaLectura {
            override fun volcar(sal: java.io.OutputStream, cancelado: () -> Boolean) {
                for ((k, t) in trozos.withIndex()) {
                    val con: HttpURLConnection
                    if (k == 0) {
                        con = primera ?: conectar(cli, t)
                        primera = null
                    } else {
                        con = conectar(cli, t)
                    }
                    try {
                        MegaFlujo.descifrar(con.inputStream, t.nodo, t.ini, t.ini and 15L.inv(), t.fin, sal, cancelado)
                    } finally {
                        try { con.disconnect() } catch (e: Exception) { /* nada */ }
                    }
                }
            }

            override fun close() {
                try { primera?.disconnect() } catch (e: Exception) { /* nada */ }
            }
        }
    }
}
