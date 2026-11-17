package com.optimo.play

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Un archivo o carpeta dentro de una carpeta a la que se ha dado acceso (Storage Access Framework). */
class EntradaDoc(val docId: String, val nombre: String, val esCarpeta: Boolean, val tam: Long, val uri: Uri)

/**
 * Acceso a carpetas del dispositivo SIN permisos de almacenamiento: el usuario elige una carpeta una vez
 * (selector del sistema) y Android deja a la app conservar ese acceso. Con él se resuelven las rutas relativas de una
 * lista .m3u de un disco y se listan los archivos hermanos de una canción.
 */
object Carpetas {

    /** Carpetas (árboles) con permiso de lectura conservado. */
    fun persistidas(ctx: Context): List<Uri> =
        ctx.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }

    fun conservar(ctx: Context, arbol: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(arbol, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Algunos proveedores no lo permiten: valdrá solo mientras la app siga abierta.
        }
    }

    fun olvidar(ctx: Context, arbol: Uri) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(arbol, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Ya no estaba.
        }
    }

    fun raizId(arbol: Uri): String = DocumentsContract.getTreeDocumentId(arbol)

    fun uriDoc(arbol: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(arbol, docId)

    fun nombreRaiz(ctx: Context, arbol: Uri): String {
        val id = raizId(arbol)
        try {
            ctx.contentResolver.query(
                uriDoc(arbol, id), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrEmpty()) return n
                }
            }
        } catch (e: Exception) {
            // Se usa el identificador.
        }
        return id.substringAfterLast('/').substringAfter(':').ifEmpty { id }
    }

    /** Contenido de una carpeta del árbol (sin ordenar). */
    fun hijos(ctx: Context, arbol: Uri, padreId: String): List<EntradaDoc> {
        val salida = ArrayList<EntradaDoc>()
        try {
            val uriHijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, padreId)
            ctx.contentResolver.query(
                uriHijos,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val nombre = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val tam = if (c.isNull(3)) 0L else c.getLong(3)
                    salida.add(EntradaDoc(id, nombre, mime == DocumentsContract.Document.MIME_TYPE_DIR, tam, uriDoc(arbol, id)))
                }
            }
        } catch (e: Exception) {
            // Carpeta ilegible o fuera del árbol: lista vacía.
        }
        return salida
    }

    /**
     * Carpeta con acceso concedido que contiene este documento (el .m3u o el audio que se abrió), o null si no hay.
     * Vale tanto para URIs de selector («…/document/primary%3AMusic%2Fx.mp3») como para las del explorador de la app.
     */
    fun arbolDe(ctx: Context, doc: Uri): Uri? {
        if (doc.scheme != "content") return null
        try {
            val t = DocumentsContract.getTreeDocumentId(doc)
            return DocumentsContract.buildTreeDocumentUri(doc.authority, t)
        } catch (e: Exception) {
            // No es una URI de árbol: se mira entre las carpetas conservadas.
        }
        val id = try { DocumentsContract.getDocumentId(doc) } catch (e: Exception) { return null }
        for (a in persistidas(ctx)) {
            if (a.authority != doc.authority) continue
            val raiz = try { raizId(a) } catch (e: Exception) { continue }
            val pref = if (raiz.endsWith("/") || raiz.endsWith(":")) raiz else "$raiz/"
            if (id == raiz || id.startsWith(pref)) return a
        }
        return null
    }

    /** Identificador de la carpeta que contiene [docId] dentro del árbol, o null si no se encuentra. */
    fun padreDe(ctx: Context, arbol: Uri, docId: String): String? {
        // Almacenamiento interno y tarjetas: el id es una ruta («primary:Music/Disco/01.mp3»).
        val cand = when {
            docId.contains('/') -> docId.substringBeforeLast('/')
            docId.contains(':') -> docId.substringBefore(':') + ":"
            else -> null
        }
        if (cand != null && hijos(ctx, arbol, cand).any { it.docId == docId }) return cand
        // Proveedores con ids opacos: se busca recorriendo el árbol.
        return buscar(ctx, arbol) { it.docId == docId }?.first
    }

    /** Carpeta que contiene el documento: por su id si el árbol lo cubre, o buscando por nombre si no. */
    fun ubicarPadre(ctx: Context, arbol: Uri, doc: Uri, nombre: String): String? {
        val id = try { DocumentsContract.getDocumentId(doc) } catch (e: Exception) { null }
        if (id != null) {
            val p = padreDe(ctx, arbol, id)
            if (p != null) return p
        }
        return buscar(ctx, arbol) { !it.esCarpeta && it.nombre == nombre }?.first
    }

    /** Recorre el árbol a lo ancho (con límite) buscando una entrada. Devuelve (carpeta que la contiene, entrada). */
    private fun buscar(ctx: Context, arbol: Uri, coincide: (EntradaDoc) -> Boolean): Pair<String, EntradaDoc>? {
        val pendientes = ArrayDeque<String>()
        pendientes.addLast(raizId(arbol))
        var vistos = 0
        val limite = System.currentTimeMillis() + 5000
        while (pendientes.isNotEmpty() && vistos < 8000 && System.currentTimeMillis() < limite) {
            val p = pendientes.removeFirst()
            for (h in hijos(ctx, arbol, p)) {
                vistos++
                if (coincide(h)) return Pair(p, h)
                if (h.esCarpeta) pendientes.addLast(h.docId)
            }
        }
        return null
    }

    /**
     * Archivo al que apunta una entrada de lista, partiendo de la carpeta [carpetaId]. Prueba la ruta tal cual, con
     * los %XX decodificados y, si es una ruta absoluta o de otra máquina («C:\Música\x.mp3»), solo con el nombre.
     * `cache` guarda el contenido de las carpetas ya leídas (se comparte entre todas las entradas de la lista).
     */
    fun resolver(
        ctx: Context, arbol: Uri, carpetaId: String, ruta: String, cache: HashMap<String, List<EntradaDoc>>
    ): EntradaDoc? {
        var r = ruta.trim().replace('\\', '/')
        if (r.startsWith("file://", true)) r = Uri.decode(r.substring(7))
        val intentos = LinkedHashSet<String>()
        intentos.add(r)
        if (r.contains('%')) intentos.add(Uri.decode(r))
        intentos.add(r.substringAfterLast('/'))
        for (i in intentos) {
            val e = resolverRuta(ctx, arbol, carpetaId, i, cache)
            if (e != null) return e
        }
        return null
    }

    private fun resolverRuta(
        ctx: Context, arbol: Uri, carpetaId: String, ruta: String, cache: HashMap<String, List<EntradaDoc>>
    ): EntradaDoc? {
        var actual = carpetaId
        val partes = ruta.split('/').filter { it.isNotEmpty() && it != "." }
        for ((i, p) in partes.withIndex()) {
            if (p == "..") {
                actual = padreDe(ctx, arbol, actual) ?: return null
                continue
            }
            val lista = cache.getOrPut(actual) { hijos(ctx, arbol, actual) }
            val e = lista.firstOrNull { it.nombre == p } ?: lista.firstOrNull { it.nombre.equals(p, true) } ?: return null
            if (i == partes.size - 1) return if (e.esCarpeta) null else e
            if (!e.esCarpeta) return null
            actual = e.docId
        }
        return null
    }

    /**
     * Archivos de una carpeta que pasan el filtro, en orden natural: primero los de la propia carpeta y luego los de
     * cada subcarpeta (CD1, CD2…) si [recursivo]. Con tope de 5000.
     */
    fun medios(
        ctx: Context, arbol: Uri, carpetaId: String, recursivo: Boolean, filtro: (String) -> Boolean
    ): List<EntradaDoc> {
        val salida = ArrayList<EntradaDoc>()
        fun recorrer(id: String, nivel: Int) {
            if (salida.size >= 5000) return
            val h = hijos(ctx, arbol, id)
            for (e in h.filter { !it.esCarpeta && filtro(it.nombre) }.sortedWith { a, b -> Utiles.comparaNatural(a.nombre, b.nombre) }) {
                if (salida.size < 5000) salida.add(e)
            }
            if (recursivo && nivel < 6) {
                for (c in h.filter { it.esCarpeta }.sortedWith { a, b -> Utiles.comparaNatural(a.nombre, b.nombre) }) {
                    recorrer(c.docId, nivel + 1)
                }
            }
        }
        recorrer(carpetaId, 0)
        return salida
    }
}
