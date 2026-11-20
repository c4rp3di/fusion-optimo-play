package com.optimo.play

import android.content.Context
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.VolumedArchiveInStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** El archivo está cifrado: [incorrecta] es true si ya se dio una contraseña y no era buena. */
class ClaveNecesaria(val incorrecta: Boolean) :
    Exception(if (incorrecta) "Contraseña incorrecta" else "El archivo está protegido con contraseña")

/**
 * Extrae RAR (también RAR5), 7z, ZIP y partes numeradas (.001) con el motor 7-Zip (7-Zip-JBinding), directamente a
 * Descargas/OptimoPlay, sin copia intermedia: cada archivo se escribe según se va descomprimiendo.
 * Los archivos que ya estén en Descargas con la misma ruta y tamaño se saltan, así que repetir una extracción interrumpida
 * solo hace lo que falta.
 */
object Descompresor {

    class Extraido(
        /** Rutas (dentro de Descargas/OptimoPlay) de todo lo que ha quedado: lo extraído y lo que ya estaba. */
        val rutas: List<String>,
        /** Archivos que no se pudieron extraer (CRC, datos dañados…), con el motivo. */
        val fallos: List<String>
    )

    /** null si el motor 7-Zip está listo; si no, el motivo. */
    fun comprobarMotor(): String? {
        return try {
            SevenZip.getSevenZipVersion()
            if (SevenZip.isInitializedSuccessfully()) null else "El motor 7-Zip no se ha podido iniciar"
        } catch (t: Throwable) {
            "El motor 7-Zip no está disponible (" + (t.message ?: t.javaClass.simpleName) + ")"
        }
    }

    private class ItemArchivo(val idx: Int, val ruta: String, val tam: Long, val cifrado: Boolean)

    /** Abre los volúmenes que pide el motor (siguiente parte de un RAR, de un ZIP dividido…) y da la contraseña si se pide. */
    private class AbrirCb(private val dir: File, private val clave: String?) :
        IArchiveOpenVolumeCallback, IArchiveOpenCallback, ICryptoGetTextPassword {

        private val abiertos = HashMap<String, RandomAccessFile>()
        private var nombre = ""
        @Volatile var pidioClave = false

        override fun getProperty(propID: PropID?): Any? = if (propID == PropID.NAME) nombre else null

        override fun getStream(filename: String?): IInStream? {
            if (filename == null) return null
            var f = File(filename)
            if (!f.isFile) f = File(dir, f.name) // a veces pide solo el nombre
            if (!f.isFile) return null
            return try {
                val raf = abiertos.getOrPut(f.path) { RandomAccessFile(f, "r") }
                raf.seek(0)
                nombre = f.path
                RandomAccessFileInStream(raf)
            } catch (e: IOException) {
                null
            }
        }

        override fun setTotal(files: Long?, bytes: Long?) {}

        override fun setCompleted(files: Long?, bytes: Long?) {}

        override fun cryptoGetTextPassword(): String {
            if (clave == null) {
                pidioClave = true
                throw SevenZipException("Hace falta contraseña")
            }
            return clave
        }

        fun cerrar() {
            for (r in abiertos.values) try { r.close() } catch (e: Exception) { /* nada */ }
            abiertos.clear()
        }
    }

    private class ExtraerCb(
        private val ctx: Context,
        private val porIndice: Map<Int, ItemArchivo>,
        private val destinos: Map<Int, String>,
        private val clave: String?,
        private val cancelado: () -> Boolean,
        private val alProgreso: (Long) -> Unit
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {

        var salida: Exportar.Salida? = null
        private var rutaActual: String? = null
        private var itemActual: ItemArchivo? = null
        private var hecho = 0L
        val ok = ArrayList<String>()
        val fallos = ArrayList<String>()
        @Volatile var claveMala = false
        @Volatile var pidioClave = false

        override fun getStream(index: Int, extractAskMode: ExtractAskMode?): ISequentialOutStream? {
            if (cancelado()) throw SevenZipException("Cancelado")
            if (extractAskMode != ExtractAskMode.EXTRACT) return null
            val item = porIndice[index] ?: return null // carpetas y lo desconocido se saltan
            val ruta = destinos[index] ?: return null
            if (item.tam > 0 && Exportar.yaGuardado(ctx, ruta, item.tam)) {
                hecho += item.tam
                alProgreso(hecho)
                ok.add(ruta)
                return null
            }
            val s = try {
                Exportar.abrirSalida(ctx, ruta)
            } catch (e: IOException) {
                throw SevenZipException(e.message ?: "No se puede crear $ruta")
            }
            salida = s
            rutaActual = ruta
            itemActual = item
            return object : ISequentialOutStream {
                override fun write(data: ByteArray?): Int {
                    if (data == null || data.isEmpty()) return 0
                    if (cancelado()) throw SevenZipException("Cancelado")
                    try {
                        s.flujo.write(data)
                    } catch (e: IOException) {
                        throw SevenZipException(e.message ?: "Error al escribir en Descargas")
                    }
                    hecho += data.size
                    alProgreso(hecho)
                    return data.size
                }
            }
        }

        override fun prepareOperation(extractAskMode: ExtractAskMode?) {}

        override fun setOperationResult(extractOperationResult: ExtractOperationResult?) {
            val s = salida
            val ruta = rutaActual
            val item = itemActual
            salida = null
            rutaActual = null
            itemActual = null
            if (s == null || ruta == null) return // se saltó (ya estaba guardado)
            if (extractOperationResult == ExtractOperationResult.OK) {
                try {
                    s.terminar()
                    ok.add(ruta)
                } catch (e: Exception) {
                    s.abortar()
                    fallos.add(ruta + ": " + (e.message ?: e.javaClass.simpleName))
                }
            } else {
                s.abortar()
                if (item != null && item.cifrado) {
                    // Con contraseña mala el primer archivo ya falla: no se sigue con los demás.
                    claveMala = true
                    throw SevenZipException("Contraseña incorrecta")
                }
                fallos.add(ruta + ": " + extractOperationResult)
            }
        }

        override fun setTotal(total: Long) {}

        override fun setCompleted(complete: Long) {}

        override fun cryptoGetTextPassword(): String {
            if (clave == null) {
                pidioClave = true
                throw SevenZipException("Hace falta contraseña")
            }
            return clave
        }
    }

    /**
     * Extrae el archivo cuya primera parte es [primero] (el resto de partes, en [dir] con los nombres de [MegaPaquete.nombreCache]).
     * [rutaBase] es la carpeta de Descargas/OptimoPlay donde va el contenido («Mi carpeta de Mega/Temporada 1»).
     * Lanza [ClaveNecesaria] si hace falta (o falló) la contraseña, e IOException («Cancelado») si se corta con [cancelado].
     */
    fun extraer(
        ctx: Context, dir: File, primero: File, tipo: MegaPaquete.Tipo, clave: String?, rutaBase: String,
        cancelado: () -> Boolean, progreso: (Long, Long) -> Unit
    ): Extraido {
        val cb = AbrirCb(dir, clave)
        var arc: IInArchive? = null
        try {
            val ins: IInStream = if (tipo == MegaPaquete.Tipo.NUMERICO) {
                VolumedArchiveInStream(primero.path, cb)
            } else {
                cb.getStream(primero.path) ?: throw IOException("No se puede abrir " + primero.name)
            }
            val abierto: IInArchive = try {
                SevenZip.openInArchive(null, ins, cb)
            } catch (e: SevenZipException) {
                if (cancelado()) throw IOException("Cancelado")
                if (cb.pidioClave) throw ClaveNecesaria(false)
                val m = e.message ?: ""
                if (clave != null && (m.contains("password", true) || m.contains("encrypt", true) || m.contains("wrong", true))) {
                    throw ClaveNecesaria(true)
                }
                throw IOException("No se puede abrir el archivo comprimido: " + (m.ifEmpty { "formato no reconocido, partes dañadas o faltan volúmenes" }))
            }

            arc = abierto
            val items = ArrayList<ItemArchivo>()
            for (i in 0 until abierto.numberOfItems) {
                if (abierto.getProperty(i, PropID.IS_FOLDER) == true) continue
                val ruta = ((abierto.getProperty(i, PropID.PATH) as? String) ?: "archivo$i").replace('\\', '/').trim('/')
                val tam = (abierto.getProperty(i, PropID.SIZE) as? Number)?.toLong() ?: 0L
                val enc = abierto.getProperty(i, PropID.ENCRYPTED) == true
                items.add(ItemArchivo(i, ruta, tam, enc))
            }
            if (items.isEmpty()) throw IOException("El archivo comprimido no contiene nada que extraer")
            if (clave == null && items.any { it.cifrado }) throw ClaveNecesaria(false)

            val destinos = HashMap<Int, String>()
            for (item in items) {
                val partes = ArrayList<String>()
                if (rutaBase.isNotEmpty()) partes.add(rutaBase)
                for (seg in item.ruta.split('/')) if (seg.isNotEmpty()) partes.add(MegaGuardado.limpiarNombre(seg))
                destinos[item.idx] = partes.joinToString("/")
            }
            val total = items.sumOf { it.tam }
            val cbx = ExtraerCb(ctx, items.associateBy { it.idx }, destinos, clave, cancelado) { h -> progreso(h, total) }
            try {
                abierto.extract(items.map { it.idx }.sorted().toIntArray(), false, cbx)
            } catch (e: SevenZipException) {
                cbx.salida?.abortar()
                if (cancelado()) throw IOException("Cancelado")
                if (cbx.claveMala) throw ClaveNecesaria(true)
                if (cbx.pidioClave) throw ClaveNecesaria(false)
                throw IOException(e.message ?: "Error al extraer")
            }
            return Extraido(cbx.ok, cbx.fallos)
        } finally {
            try { arc?.close() } catch (e: Exception) { /* nada */ }
            cb.cerrar()
        }
    }
}
