package com.optimo.play

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Un archivo guardado en Descargas/OptimoPlay. `sub` es la carpeta dentro de OptimoPlay («Mi torrent/CD1»). */
class ArchivoGuardado(val uri: Uri, val sub: String, val nombre: String, val tam: Long, val archivo: File?) {
    val ruta: String get() = if (sub.isEmpty()) nombre else "$sub/$nombre"
}

/**
 * Copia un archivo ya descargado de un torrent (que vive en la caché y se borra al cerrar) a la carpeta Descargas
 * del dispositivo, dentro de «OptimoPlay», conservando las carpetas del torrent.
 *
 * Android 10 o superior: MediaStore.Downloads (no pide permisos). Android 9: carpeta propia de la app en el
 * almacenamiento externo (Android/data/…/files/Download/OptimoPlay), sin permisos tampoco.
 *
 * Sin duplicados: antes de bajar un archivo se mira si ya está guardado con la misma ruta y tamaño ([yaGuardado]);
 * y al copiar, si hubiera uno con el mismo nombre (incompleto o distinto) se sustituye en vez de crear «nombre (1)».
 */
object Exportar {

    const val CARPETA = "OptimoPlay"

    private fun relativo(sub: String): String = "Download/" + CARPETA + "/" + (if (sub.isEmpty()) "" else "$sub/")

    private fun baseAndroid9(ctx: Context): File? =
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, CARPETA) }

    /** ¿Está ya en Descargas/OptimoPlay este archivo, con la misma ruta y el mismo tamaño? */
    fun yaGuardado(ctx: Context, ruta: String, tam: Long): Boolean {
        val nombre = ruta.substringAfterLast('/')
        val sub = ruta.substringBeforeLast('/', "")
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                var hay = false
                ctx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    MediaStore.MediaColumns.RELATIVE_PATH + " = ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
                    arrayOf(relativo(sub), nombre),
                    null
                )?.use { c ->
                    while (c.moveToNext()) if (c.getLong(0) == tam) hay = true
                }
                hay
            } else {
                val base = baseAndroid9(ctx) ?: return false
                val f = File(base, ruta)
                f.isFile && f.length() == tam
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Devuelve null si todo fue bien, o el texto del error. */
    fun aDescargas(ctx: Context, origen: File, ruta: String, cancelado: () -> Boolean): String? {
        if (!origen.exists()) return "No se encuentra el archivo descargado"
        return aDescargasFlujo(ctx, ruta) { o -> origen.inputStream().use { i -> copiar(i, o, cancelado) } }
    }

    /**
     * Crea Descargas/OptimoPlay/[ruta] (sustituyendo uno con el mismo nombre) y deja que [llenar] escriba su contenido.
     * Si [llenar] falla o se cancela, no queda un archivo a medias. Devuelve null si todo fue bien, o el texto del error.
     */
    fun aDescargasFlujo(ctx: Context, ruta: String, llenar: (OutputStream) -> Unit): String? {
        val nombre = ruta.substringAfterLast('/')
        val sub = ruta.substringBeforeLast('/', "")
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val res = ctx.contentResolver
                // Si ya hay uno con ese nombre (a medias o distinto) se sustituye: nunca «nombre (1)».
                try {
                    res.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID),
                        MediaStore.MediaColumns.RELATIVE_PATH + " = ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
                        arrayOf(relativo(sub), nombre),
                        null
                    )?.use { c ->
                        val ids = ArrayList<Long>()
                        while (c.moveToNext()) ids.add(c.getLong(0))
                        for (id in ids) {
                            try {
                                res.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
                            } catch (e: Exception) {
                                // Si no se puede quitar, el sistema pondrá un número al nuevo.
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Sin consulta: se sigue igualmente.
                }
                val valores = ContentValues()
                valores.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                valores.put(MediaStore.MediaColumns.MIME_TYPE, Utiles.tipoMime(nombre))
                valores.put(MediaStore.MediaColumns.RELATIVE_PATH, relativo(sub).trimEnd('/'))
                valores.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                    ?: return "No se pudo crear el archivo en Descargas"
                try {
                    val salida = res.openOutputStream(uri) ?: throw IOException("No se puede escribir en Descargas")
                    salida.use { o -> llenar(o) }
                    val listo = ContentValues()
                    listo.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    res.update(uri, listo, null, null)
                } catch (e: Exception) {
                    try { res.delete(uri, null, null) } catch (x: Exception) { /* nada */ }
                    throw e
                }
            } else {
                val base = baseAndroid9(ctx) ?: return "Sin almacenamiento externo"
                val dir = if (sub.isEmpty()) base else File(base, sub)
                dir.mkdirs()
                val destino = File(dir, nombre)
                val tmp = File(dir, "$nombre.parte")
                try {
                    tmp.outputStream().use { o -> llenar(o) }
                    if (destino.exists()) destino.delete()
                    if (!tmp.renameTo(destino)) throw IOException("No se pudo guardar el archivo")
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Guarda un texto (p. ej. una lista M3U) en Descargas/OptimoPlay/[sub]/[nombre], sustituyendo el anterior. */
    fun textoADescargas(ctx: Context, sub: String, nombre: String, texto: String): String? {
        val tmp = File(ctx.cacheDir, "exportar-" + System.currentTimeMillis() + ".tmp")
        return try {
            tmp.writeText(texto, Charsets.UTF_8)
            aDescargas(ctx, tmp, if (sub.isEmpty()) nombre else "$sub/$nombre") { false }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            tmp.delete()
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

    /** Ruta de la carpeta dentro del almacenamiento principal, para abrirla en un gestor. */
    fun rutaRelativa(): String =
        if (Build.VERSION.SDK_INT >= 29) "Download/$CARPETA" else "Android/data/com.optimo.play/files/Download/$CARPETA"

    /** Dónde queda, en palabras. */
    fun destinoTexto(): String =
        if (Build.VERSION.SDK_INT >= 29) "Descargas/$CARPETA" else "Android/data/com.optimo.play/files/Download/$CARPETA"

    // ---------------------------------------------------------------- gestión de lo guardado

    /**
     * Todo lo que hay en Descargas/OptimoPlay (salvo las listas M3U exportadas, que van en «Listas»).
     * En Android 10+ solo se ven los archivos creados por esta instalación de la app (límite del sistema:
     * si desinstalas y vuelves a instalar, lo guardado antes sigue en Descargas pero la app ya no lo ve).
     */
    fun listar(ctx: Context): List<ArchivoGuardado> {
        val salida = ArrayList<ArchivoGuardado>()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val prefijo = "Download/$CARPETA/"
                ctx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE
                    ),
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    arrayOf("$prefijo%"),
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val nombre = c.getString(1) ?: continue
                        val rel = c.getString(2) ?: continue
                        val sub = rel.removePrefix(prefijo).trimEnd('/')
                        if (sub == "Listas" || sub.startsWith("Listas/")) continue
                        salida.add(
                            ArchivoGuardado(
                                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                                sub, nombre, c.getLong(3), null
                            )
                        )
                    }
                }
            } else {
                val base = baseAndroid9(ctx)
                if (base != null && base.isDirectory) {
                    for (f in base.walkTopDown()) {
                        if (!f.isFile || f.name.endsWith(".parte")) continue
                        val rel = f.parentFile?.relativeTo(base)?.path?.replace('\\', '/') ?: ""
                        val sub = if (rel == ".") "" else rel
                        if (sub == "Listas" || sub.startsWith("Listas/")) continue
                        salida.add(ArchivoGuardado(Uri.fromFile(f), sub, f.name, f.length(), f))
                    }
                }
            }
        } catch (e: Exception) {
            // Lo que se haya podido leer.
        }
        return salida
    }

    fun borrar(ctx: Context, a: ArchivoGuardado): Boolean {
        return try {
            if (a.archivo != null) a.archivo.delete() else ctx.contentResolver.delete(a.uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    private val NUMERADO = Regex("^(.*) \\((\\d+)\\)(\\.[^./]*)?$")

    /**
     * Copias sobrantes del estilo «nombre (1).ext» cuyo original «nombre.ext» está en la misma carpeta con el mismo
     * tamaño (las dejaban las versiones anteriores al volver a guardar un torrent).
     */
    fun duplicados(todos: List<ArchivoGuardado>): List<ArchivoGuardado> {
        val porRuta = HashMap<String, ArchivoGuardado>()
        for (a in todos) porRuta[a.ruta] = a
        val sobran = ArrayList<ArchivoGuardado>()
        for (a in todos) {
            val m = NUMERADO.matchEntire(a.nombre) ?: continue
            val original = m.groupValues[1] + m.groupValues[3]
            val ruta = if (a.sub.isEmpty()) original else a.sub + "/" + original
            val o = porRuta[ruta] ?: continue
            if (o.tam == a.tam && o.uri != a.uri) sobran.add(a)
        }
        return sobran
    }
}
