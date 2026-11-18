package com.optimo.play

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Registro en memoria de lo que pasa al emitir a una tele (peticiones que recibe el móvil, respuestas SOAP, estados de la tele…).
 * Sirve para saber por qué una tele rechaza algo sin logcat: se ve en ⋮ › Compatibilidad de la tele, con «Copiar».
 */
object RegistroEmision {
    private val lineas = ArrayDeque<String>()
    private val formato = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun anotar(texto: String) {
        if (lineas.size >= 200) lineas.removeFirst()
        lineas.addLast(formato.format(Date()) + " " + texto)
    }

    @Synchronized
    fun limpiar() {
        lineas.clear()
    }

    @Synchronized
    fun texto(): String = if (lineas.isEmpty()) "(vacío: la tele no ha hecho ninguna petición todavía)" else lineas.joinToString("\n")
}

/** Cuenta los bytes que se escriben (para saber cuánto leyó la tele antes de cortar). */
class Contador(private val o: OutputStream) : OutputStream() {
    @Volatile var total = 0L
        private set

    override fun write(b: Int) {
        o.write(b)
        total += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        o.write(b, off, len)
        total += len
    }

    override fun flush() {
        o.flush()
    }

    override fun close() {
        o.close()
    }
}
