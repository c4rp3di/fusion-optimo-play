package com.optimo.play

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copia un archivo ya descargado de un torrent (que vive en la caché y se borra al cerrar) a la carpeta Descargas
 * del dispositivo, dentro de «OptimoPlay», conservando las carpetas del torrent.
 *
 * Android 10 o superior: MediaStore.Downloads (no pide permisos). Android 9: carpeta propia de la app en el
 * almacenamiento externo (Android/data/…/files/Download/OptimoPlay), sin permisos tampoco.
 */
object Exportar {

    const val CARPETA = "OptimoPlay"

    /** Devuelve null si todo fue bien, o el texto del error. */
    fun aDescargas(ctx: Context, origen: File, ruta: String, cancelado: () -> Boolean): String? {
        if (!origen.exists()) return "No se encuentra el archivo descargado"
        val nombre = ruta.substringAfterLast('/')
        val sub = ruta.substringBeforeLast('/', "")
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val valores = ContentValues()
                valores.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                valores.put(MediaStore.MediaColumns.MIME_TYPE, Utiles.tipoMime(nombre))
                valores.put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/" + CARPETA + (if (sub.isEmpty()) "" else "/$sub")
                )
                valores.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val res = ctx.contentResolver
                val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                    ?: return "No se pudo crear el archivo en Descargas"
                try {
                    val salida = res.openOutputStream(uri) ?: throw IOException("No se puede escribir en Descargas")
                    salida.use { o -> origen.inputStream().use { i -> copiar(i, o, cancelado) } }
                    val listo = ContentValues()
                    listo.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    res.update(uri, listo, null, null)
                } catch (e: Exception) {
                    try { res.delete(uri, null, null) } catch (x: Exception) { /* nada */ }
                    throw e
                }
            } else {
                val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return "Sin almacenamiento externo"
                val dir = File(base, CARPETA + (if (sub.isEmpty()) "" else "/$sub"))
                dir.mkdirs()
                var destino = File(dir, nombre)
                var n = 1
                while (destino.exists()) {
                    destino = File(dir, nombre.substringBeforeLast('.') + " ($n)." + nombre.substringAfterLast('.', ""))
                    n++
                }
                try {
                    destino.outputStream().use { o -> origen.inputStream().use { i -> copiar(i, o, cancelado) } }
                } catch (e: Exception) {
                    destino.delete()
                    throw e
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun copiar(i: InputStream, o: OutputStream, cancelado: () -> Boolean) {
        val buf = ByteArray(1 shl 20)
        while (true) {
            if (cancelado()) throw IOException("Cancelado")
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
        }
        o.flush()
    }

    /** Dónde queda, en palabras. */
    fun destinoTexto(): String =
        if (Build.VERSION.SDK_INT >= 29) "Descargas/$CARPETA" else "Android/data/com.optimo.play/files/Download/$CARPETA"
}
