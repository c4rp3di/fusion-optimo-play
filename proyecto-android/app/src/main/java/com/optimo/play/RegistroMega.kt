package com.optimo.play

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Registro de lo que pasa al bajar y extraer archivos comprimidos de Mega: cada paso, tiempos y los errores con su causa.
 * Se ve en ⋮ › Registro de extracción (con «Copiar») y también sale en logcat con la etiqueta «OptimoMega».
 */
object RegistroMega {
    private val lineas = ArrayDeque<String>()
    private val formato = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun anotar(texto: String) {
        if (lineas.size >= 300) lineas.removeFirst()
        lineas.addLast(formato.format(Date()) + " " + texto)
        Log.i("OptimoMega", texto)
    }

    /** Anota un error con su causa y las primeras líneas de la traza (lo que hace falta para localizar el fallo). */
    @Synchronized
    fun error(texto: String, t: Throwable) {
        val traza = t.stackTrace.take(10).joinToString("\n    ") { it.toString() }
        var causa = ""
        var c = t.cause
        var n = 0
        while (c != null && n++ < 3) {
            causa += "\n  causa: " + c.javaClass.name + ": " + (c.message ?: "")
            c = c.cause
        }
        if (lineas.size >= 300) lineas.removeFirst()
        lineas.addLast(
            formato.format(Date()) + " ✗ " + texto + "\n  " + t.javaClass.name + ": " + (t.message ?: "") + causa + "\n    " + traza
        )
        Log.e("OptimoMega", texto, t)
    }

    @Synchronized
    fun texto(): String =
        if (lineas.isEmpty()) "(vacío: todavía no se ha bajado ni extraído nada)" else lineas.joinToString("\n")
}
