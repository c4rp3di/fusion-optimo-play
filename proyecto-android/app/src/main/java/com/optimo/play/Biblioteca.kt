package com.optimo.play

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una lista M3U/M3U8 guardada en la app. `origen` es la dirección de donde se descargó (vacío si vino de un archivo). */
class ListaGuardada(
    val id: String,
    val nombre: String,
    val origen: String,
    val fecha: Long,
    val entradas: Int,
    /** 0 = descargada o importada (solo lectura), 1 = lista propia, 2 = Favoritos. */
    val tipo: Int = 0
) {
    val esFavoritos: Boolean get() = tipo == Biblioteca.TIPO_FAVORITOS
    val esEditable: Boolean get() = tipo != Biblioteca.TIPO_DESCARGADA
}

/** Contenido de una lista de canales para editarlo: categorías en orden (también las vacías) y sus canales. */
class ContenidoLista(val grupos: MutableList<String>, val canales: MutableList<Canal>, var urlGuia: String? = null)

/**
 * Listas M3U/M3U8 guardadas dentro de la app para tenerlas sin depender de una URL. El contenido (que puede llevar
 * direcciones con claves) se guarda cifrado con AlmacenSeguro, igual que los recientes. Se abren con una dirección
 * propia «optimo-lista:ID» que entiende el reproductor.
 */
object Biblioteca {

    const val PREFIJO = "optimo-lista:"

    const val ID_FAV = "favoritos"
    const val TIPO_DESCARGADA = 0
    const val TIPO_PROPIA = 1
    const val TIPO_FAVORITOS = 2
    const val GRUPO_DEFECTO = "Sin categoría"

    /** Línea propia que guarda una categoría aunque esté vacía y fija el orden de las categorías. */
    private const val DIRECTIVA = "#OPTIMO-GRUPO:"

    private fun dir(ctx: Context): File = File(ctx.filesDir, "listas").also { it.mkdirs() }

    private fun archivo(ctx: Context, id: String): File = File(dir(ctx), "$id.m3u")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("play", Context.MODE_PRIVATE)

    /** Todas las listas en su orden. «Favoritos» existe siempre y va la primera. */
    fun todas(ctx: Context): List<ListaGuardada> {
        val salida = ArrayList<ListaGuardada>()
        try {
            val b = sp(ctx).getString("biblioteca", null)
            val txt = if (b != null) AlmacenSeguro.descifrarTexto(b, "biblioteca") else null
            if (txt != null) {
                val arr = JSONArray(txt)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    salida.add(ListaGuardada(o.getString("i"), o.optString("n"), o.optString("o"), o.optLong("f"), o.optInt("c"), o.optInt("t")))
                }
            }
        } catch (e: Exception) {
            // Índice ilegible: se queda lo que se haya podido leer.
        }
        val fav = salida.firstOrNull { it.tipo == TIPO_FAVORITOS }
        if (fav != null) salida.remove(fav)
        salida.add(0, fav ?: ListaGuardada(ID_FAV, "Favoritos", "", 0L, 0, TIPO_FAVORITOS))
        return salida
    }

    private fun guardarIndice(ctx: Context, l: List<ListaGuardada>) {
        try {
            val arr = JSONArray()
            for (x in l) {
                arr.put(JSONObject().put("i", x.id).put("n", x.nombre).put("o", x.origen).put("f", x.fecha).put("c", x.entradas).put("t", x.tipo))
            }
            val c = AlmacenSeguro.cifrarTexto(arr.toString(), "biblioteca") ?: return
            sp(ctx).edit().putString("biblioteca", c).apply()
        } catch (e: Exception) {
            // Sin Keystore no se guarda nada.
        }
    }

    fun leer(ctx: Context, id: String): String? = AlmacenSeguro.leerTexto(archivo(ctx, id), "lista:$id")

    fun uriDe(id: String): String = PREFIJO + id

    /** Cuántas entradas tiene: canales de una lista de canales, o direcciones en general. */
    private fun contar(texto: String): Int {
        return try {
            if (ListaM3u.esLista(texto)) ListaM3u.parsear(texto).canales.size else ListaM3u.entradas(texto).size
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Guarda una lista nueva. Si ya había otra con el mismo origen (misma URL) se actualiza en vez de duplicarla.
     * Devuelve la lista y si era nueva.
     */
    @Synchronized
    fun agregar(ctx: Context, nombre: String, origen: String, texto: String): Pair<ListaGuardada, Boolean>? {
        val actuales = ArrayList(todas(ctx))
        val previa = if (origen.isNotEmpty()) actuales.firstOrNull { it.origen == origen } else null
        val id = previa?.id ?: java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        if (!AlmacenSeguro.escribirTexto(archivo(ctx, id), texto, "lista:$id")) return null
        val nueva = ListaGuardada(id, previa?.nombre ?: nombre, origen, System.currentTimeMillis(), contar(texto), TIPO_DESCARGADA)
        actuales.removeAll { it.id == id }
        actuales.add(0, nueva)
        guardarIndice(ctx, actuales)
        return Pair(nueva, previa == null)
    }

    /** Vuelve a descargar la lista desde su dirección de origen. Lanza excepción si falla. */
    fun actualizar(ctx: Context, l: ListaGuardada): ListaGuardada {
        if (!l.origen.startsWith("http", true)) throw java.io.IOException("Esta lista no viene de una dirección")
        val texto = descargar(l.origen)
        return agregar(ctx, l.nombre, l.origen, texto)?.first ?: throw java.io.IOException("No se pudo guardar")
    }

    /** Descarga el texto de una lista y comprueba que lo parece. Bloquea: llamar en un hilo. */
    fun descargar(url: String): String {
        val c = Red.conexion(url, null, null)
        if (c.responseCode >= 400) throw java.io.IOException("El servidor respondió " + c.responseCode)
        val datos = c.inputStream.use { Red.leerBytes(it, 6_000_000) }
        val t = ListaM3u.texto(datos)
        if (!t.contains("#EXTM3U", true) && !t.contains("#EXTINF", true) && !t.contains("://")) {
            throw java.io.IOException("Lo descargado no parece una lista M3U")
        }
        return t
    }

    @Synchronized
    fun renombrar(ctx: Context, id: String, nombre: String) {
        val l = todas(ctx).map { if (it.id == id && it.tipo != TIPO_FAVORITOS) ListaGuardada(it.id, nombre, it.origen, it.fecha, it.entradas, it.tipo) else it }
        guardarIndice(ctx, l)
    }

    @Synchronized
    fun borrar(ctx: Context, id: String) {
        if (id == ID_FAV) return // Favoritos no se puede borrar
        archivo(ctx, id).delete()
        guardarIndice(ctx, todas(ctx).filter { it.id != id })
    }

    // ---------------------------------------------------------------- listas propias y edición

    @Synchronized
    fun crearPropia(ctx: Context, nombre: String): ListaGuardada? {
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        if (!AlmacenSeguro.escribirTexto(archivo(ctx, id), "#EXTM3U\n", "lista:$id")) return null
        val nueva = ListaGuardada(id, nombre, "", System.currentTimeMillis(), 0, TIPO_PROPIA)
        guardarIndice(ctx, todas(ctx) + nueva)
        return nueva
    }

    /** Sube (delta -1) o baja (+1) una lista en el orden. Favoritos siempre se queda la primera. */
    @Synchronized
    fun mover(ctx: Context, id: String, delta: Int) {
        val l = ArrayList(todas(ctx))
        val i = l.indexOfFirst { it.id == id }
        if (i < 1) return
        val j = i + delta
        if (j < 1 || j >= l.size) return
        val x = l.removeAt(i)
        l.add(j, x)
        guardarIndice(ctx, l)
    }

    /** Nombre de categoría o de lista sin comillas ni saltos de línea (romperían el formato M3U). */
    fun limpiarNombre(n: String): String = n.replace('"', '\'').replace('\n', ' ').replace('\r', ' ').trim()

    /** Categorías (también las vacías) y canales de una lista, para verla o editarla. */
    fun contenido(ctx: Context, id: String): ContenidoLista {
        val texto = leer(ctx, id) ?: ""
        val grupos = ArrayList<String>()
        val canales = ArrayList<Canal>()
        for (raw in texto.removePrefix("\uFEFF").lineSequence()) {
            val l = raw.trim()
            if (l.startsWith(DIRECTIVA)) {
                val g = l.substring(DIRECTIVA.length).trim()
                if (g.isNotEmpty() && !grupos.contains(g)) grupos.add(g)
            }
        }
        if (ListaM3u.esLista(texto)) {
            try {
                canales.addAll(ListaM3u.parsear(texto).canales)
            } catch (e: Exception) {
                // Lista ilegible: se enseña vacía.
            }
        }
        for (c in canales) if (!grupos.contains(c.grupo)) grupos.add(c.grupo)
        return ContenidoLista(grupos, canales, ListaM3u.guiaDeCabecera(texto))
    }

    /** Escribe de nuevo una lista (canales + categorías en su orden) y actualiza su contador. */
    @Synchronized
    fun guardarContenido(ctx: Context, id: String, c: ContenidoLista): Boolean {
        val grupos = ArrayList(c.grupos)
        for (x in c.canales) if (!grupos.contains(x.grupo)) grupos.add(x.grupo)
        val porGrupo = c.canales.groupBy { it.grupo }
        val sb = StringBuilder("#EXTM3U")
        if (!c.urlGuia.isNullOrBlank()) sb.append(" url-tvg=\"").append(limpiarNombre(c.urlGuia!!)).append('"')
        sb.append('\n')
        for (g in grupos) {
            sb.append(DIRECTIVA).append(g).append('\n')
            for (x in porGrupo[g] ?: emptyList()) {
                sb.append("#EXTINF:-1")
                if (!x.tvgId.isNullOrBlank()) sb.append(" tvg-id=\"").append(limpiarNombre(x.tvgId)).append('"')
                if (x.numero > 0) sb.append(" tvg-chno=\"").append(x.numero).append('"')
                if (!x.logo.isNullOrBlank()) sb.append(" tvg-logo=\"").append(limpiarNombre(x.logo)).append('"')
                if (!x.guia.isNullOrBlank()) sb.append(" x-guia=\"").append(limpiarNombre(x.guia)).append('"')
                sb.append(" group-title=\"").append(g).append("\",").append(limpiarNombre(x.nombre)).append('\n')
                if (!x.referer.isNullOrBlank()) sb.append("#EXTVLCOPT:http-referrer=").append(limpiarNombre(x.referer)).append('\n')
                if (!x.ua.isNullOrBlank()) sb.append("#EXTVLCOPT:http-user-agent=").append(limpiarNombre(x.ua)).append('\n')
                if (x.drm) sb.append("#KODIPROP:inputstream.adaptive.license_type=drm\n")
                sb.append(x.url).append('\n')
            }
        }
        if (!AlmacenSeguro.escribirTexto(archivo(ctx, id), sb.toString(), "lista:$id")) return false
        val l = todas(ctx).map {
            if (it.id == id) ListaGuardada(it.id, it.nombre, it.origen, System.currentTimeMillis(), c.canales.size, it.tipo) else it
        }
        guardarIndice(ctx, l)
        return true
    }

    /** Copia canales a una lista editable (sin duplicar). [grupo] null = cada uno conserva su categoría. Devuelve cuántos entraron. */
    @Synchronized
    fun anadir(ctx: Context, id: String, nuevos: List<Canal>, grupo: String?, urlGuia: String? = null): Int {
        val k = contenido(ctx, id)
        val tiene = k.canales.map { it.clave }.toHashSet()
        var n = 0
        for (c in nuevos) {
            if (!tiene.add(c.clave)) continue
            val g = conGuia(c, urlGuia)
            val d = if (grupo != null) g.copy(grupo = grupo) else g
            k.canales.add(d)
            if (!k.grupos.contains(d.grupo)) k.grupos.add(d.grupo)
            n++
        }
        if (n > 0) guardarContenido(ctx, id, k)
        return n
    }

    /** Pone o quita un canal en la lista «Favoritos» (con todos sus datos, para poder abrirlo desde ahí). */
    @Synchronized
    fun marcarFavorito(ctx: Context, c: Canal, activo: Boolean, urlGuia: String? = null) {
        val k = contenido(ctx, ID_FAV)
        val i = k.canales.indexOfFirst { it.clave == c.clave }
        if (activo && i < 0) {
            k.canales.add(conGuia(c, urlGuia))
            if (!k.grupos.contains(c.grupo)) k.grupos.add(c.grupo)
            guardarContenido(ctx, ID_FAV, k)
        } else if (!activo && i >= 0) {
            k.canales.removeAt(i)
            guardarContenido(ctx, ID_FAV, k)
        }
    }

    /** Los favoritos marcados antes de existir la lista (solo se guardaba su clave) pasan a ella cuando se ven en su lista. */
    @Synchronized
    fun adoptarFavoritos(ctx: Context, canales: List<Canal>, favs: Set<String>, urlGuia: String? = null) {
        if (favs.isEmpty()) return
        val k = contenido(ctx, ID_FAV)
        val tiene = k.canales.map { it.clave }.toHashSet()
        var cambio = false
        for (c in canales) {
            if (favs.contains(c.clave) && tiene.add(c.clave)) {
                k.canales.add(conGuia(c, urlGuia))
                if (!k.grupos.contains(c.grupo)) k.grupos.add(c.grupo)
                cambio = true
            }
        }
        if (cambio) guardarContenido(ctx, ID_FAV, k)
    }

    /** El canal se lleva la guía de la lista de donde sale (si ya trae una propia, se queda con ella). */
    private fun conGuia(c: Canal, urlGuia: String?): Canal =
        if (c.guia.isNullOrBlank() && !urlGuia.isNullOrBlank()) c.copy(guia = urlGuia) else c

    /** Copia la lista a Descargas/OptimoPlay/Listas para tenerla fuera de la app. Null si fue bien, o el error. */
    fun exportar(ctx: Context, l: ListaGuardada): String? {
        val texto = leer(ctx, l.id) ?: return "No se puede leer la lista"
        val limpio = l.nombre.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "lista" }
        return Exportar.textoADescargas(ctx, "Listas", "$limpio.m3u8", texto)
    }
}
