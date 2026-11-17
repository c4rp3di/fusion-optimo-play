package com.optimo.play

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una lista M3U/M3U8 guardada en la app. `origen` es la dirección de donde se descargó (vacío si vino de un archivo). */
class ListaGuardada(val id: String, val nombre: String, val origen: String, val fecha: Long, val entradas: Int)

/**
 * Listas M3U/M3U8 guardadas dentro de la app para tenerlas sin depender de una URL. El contenido (que puede llevar
 * direcciones con claves) se guarda cifrado con AlmacenSeguro, igual que los recientes. Se abren con una dirección
 * propia «optimo-lista:ID» que entiende el reproductor.
 */
object Biblioteca {

    const val PREFIJO = "optimo-lista:"

    private fun dir(ctx: Context): File = File(ctx.filesDir, "listas").also { it.mkdirs() }

    private fun archivo(ctx: Context, id: String): File = File(dir(ctx), "$id.m3u")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("play", Context.MODE_PRIVATE)

    fun todas(ctx: Context): List<ListaGuardada> {
        val salida = ArrayList<ListaGuardada>()
        try {
            val b = sp(ctx).getString("biblioteca", null) ?: return salida
            val txt = AlmacenSeguro.descifrarTexto(b, "biblioteca") ?: return salida
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                salida.add(ListaGuardada(o.getString("i"), o.optString("n"), o.optString("o"), o.optLong("f"), o.optInt("c")))
            }
        } catch (e: Exception) {
            // Índice ilegible: lista vacía.
        }
        return salida
    }

    private fun guardarIndice(ctx: Context, l: List<ListaGuardada>) {
        try {
            val arr = JSONArray()
            for (x in l) {
                arr.put(JSONObject().put("i", x.id).put("n", x.nombre).put("o", x.origen).put("f", x.fecha).put("c", x.entradas))
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
        val nueva = ListaGuardada(id, previa?.nombre ?: nombre, origen, System.currentTimeMillis(), contar(texto))
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
        val l = todas(ctx).map { if (it.id == id) ListaGuardada(it.id, nombre, it.origen, it.fecha, it.entradas) else it }
        guardarIndice(ctx, l)
    }

    @Synchronized
    fun borrar(ctx: Context, id: String) {
        archivo(ctx, id).delete()
        guardarIndice(ctx, todas(ctx).filter { it.id != id })
    }

    /** Copia la lista a Descargas/OptimoPlay/Listas para tenerla fuera de la app. Null si fue bien, o el error. */
    fun exportar(ctx: Context, l: ListaGuardada): String? {
        val texto = leer(ctx, l.id) ?: return "No se puede leer la lista"
        val limpio = l.nombre.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "lista" }
        return Exportar.textoADescargas(ctx, "Listas", "$limpio.m3u8", texto)
    }
}
