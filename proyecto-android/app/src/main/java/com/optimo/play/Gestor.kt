package com.optimo.play

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract

/**
 * Integración con el gestor de archivos propio (com.carpe.gestorarchivos). Es un proveedor de documentos del sistema,
 * así que ya sale en el selector de Android; aquí se le pide directamente a él (sin pasar por el selector del sistema)
 * todo lo que sea elegir archivos o carpetas del dispositivo, y se le puede mandar abrir una carpeta.
 * Si no está instalado, o se desactiva en Ajustes, se usa el selector normal del sistema.
 */
object Gestor {

    const val PAQUETE = "com.carpe.gestorarchivos"

    fun instalado(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PAQUETE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun activo(ctx: Context): Boolean = Ajustes(ctx).usarGestor && instalado(ctx)

    /**
     * Lanza un selector (OPEN_DOCUMENT, OPEN_DOCUMENT_TREE…) en el gestor si está activo; si no, o si el gestor no
     * lo atiende, en el del sistema. El gestor decide qué mostrar según el tipo MIME que adivina por la extensión y no
     * conoce .m3u ni .torrent, así que con él se pide «cualquier archivo» y no se manda lista de tipos.
     */
    fun lanzar(act: Activity, i: Intent, codigo: Int) {
        if (activo(act)) {
            val copia = Intent(i)
            copia.setPackage(PAQUETE)
            if (copia.action == Intent.ACTION_OPEN_DOCUMENT || copia.action == Intent.ACTION_GET_CONTENT) {
                copia.removeExtra(Intent.EXTRA_MIME_TYPES)
                copia.type = "*/*"
            }
            try {
                act.startActivityForResult(copia, codigo)
                return
            } catch (e: ActivityNotFoundException) {
                // El gestor no responde: se usa el selector del sistema.
            } catch (e: SecurityException) {
                // Idem.
            }
        }
        act.startActivityForResult(i, codigo)
    }

    /** Abre en el gestor una carpeta del almacenamiento principal («Download/OptimoPlay»). False si no se pudo. */
    fun verCarpeta(act: Activity, relativa: String): Boolean {
        if (!instalado(act)) return false
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relativa")
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        i.setPackage(PAQUETE)
        return try {
            act.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
