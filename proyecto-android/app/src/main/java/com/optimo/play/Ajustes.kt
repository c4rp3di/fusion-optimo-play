package com.optimo.play

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un elemento de «Recientes»: lo último que se abrió y por dónde iba. */
data class Reciente(val uri: String, val titulo: String, val pos: Long, val fecha: Long)

/** Lo que hay que reproducir: la URI original más las cabeceras que quiera mandar quien la pasa (Optimo). */
data class Fuente(
    val uri: String,
    val titulo: String,
    val referer: String? = null,
    val ua: String? = null,
    /** Contraseña de un enlace de Mega protegido (#P!…), si quien lo pasa ya la conoce. */
    val clave: String? = null,
    /** Si la fuente es una lista de canales: dirección del canal con el que debe arrancar (el buscador de «Mis listas»). */
    val canalUrl: String? = null
)

/**
 * Preferencias de Optimo Play. Las credenciales de OpenSubtitles y la lista de recientes (que contiene URLs)
 * se guardan cifradas con AlmacenSeguro (AES-256-GCM, clave en el Keystore).
 */
class Ajustes(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("play", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- estilo de subtitulos

    /** Tamaño en %, 50..250 (100 = normal). */
    var subTamano: Int
        get() = sp.getInt("sub_tam", 100)
        set(v) { sp.edit().putInt("sub_tam", v.coerceIn(50, 250)).apply() }

    /** Color RGB (sin alfa). */
    var subColor: Int
        get() = sp.getInt("sub_color", 0xFFFFFF)
        set(v) { sp.edit().putInt("sub_color", v and 0xFFFFFF).apply() }

    /** Opacidad del fondo tras el texto, 0..100 %. */
    var subFondo: Int
        get() = sp.getInt("sub_fondo", 0)
        set(v) { sp.edit().putInt("sub_fondo", v.coerceIn(0, 100)).apply() }

    /** Grosor del contorno: 0 ninguno, 2 fino, 4 normal, 6 grueso. */
    var subContorno: Int
        get() = sp.getInt("sub_contorno", 4)
        set(v) { sp.edit().putInt("sub_contorno", v).apply() }

    var subNegrita: Boolean
        get() = sp.getBoolean("sub_negrita", false)
        set(v) { sp.edit().putBoolean("sub_negrita", v).apply() }

    /** Elevar los subtítulos desde el borde inferior (px). */
    var subElevar: Int
        get() = sp.getInt("sub_elevar", 0)
        set(v) { sp.edit().putInt("sub_elevar", v.coerceIn(0, 400)).apply() }

    /** Codificación de los subtítulos externos. Vacío = automática. */
    var subCodificacion: String
        get() = sp.getString("sub_cod", "") ?: ""
        set(v) { sp.edit().putString("sub_cod", v).apply() }

    // ---------------------------------------------------------------- subtitulos en linea

    var subIdioma: String
        get() = sp.getString("sub_idioma", "es") ?: "es"
        set(v) { sp.edit().putString("sub_idioma", v.trim().lowercase()).apply() }

    /** Si el archivo no trae subtítulos, buscarlos solos en OpenSubtitles. */
    var subAuto: Boolean
        get() = sp.getBoolean("sub_auto", true)
        set(v) { sp.edit().putBoolean("sub_auto", v).apply() }

    var osApiKey: String
        get() = leerSeguro("os_key")
        set(v) { escribirSeguro("os_key", v.trim()) }

    var osUsuario: String
        get() = leerSeguro("os_user")
        set(v) { escribirSeguro("os_user", v.trim()) }

    var osClave: String
        get() = leerSeguro("os_pass")
        set(v) { escribirSeguro("os_pass", v) }

    // ---------------------------------------------------------------- reproduccion

    /** Seguir sonando al bloquear la pantalla o salir al escritorio (con notificación de control). */
    /** Decodificación de vídeo: 0 automática (hardware si puede), 1 solo software, 2 hardware forzado. */
    var decodificacion: Int
        get() = sp.getInt("decod", 0)
        set(v) { sp.edit().putInt("decod", v).apply() }

    /**
     * Calidad de los directos y flujos HLS con varias variantes: 0 automática, 1 máxima, 2 mínima, 3 hasta 720p, 4 hasta 480p.
     * Al emitir a una tele DLNA, «automática» se trata como «máxima» (si la variante cambia a mitad, la tele no lo aguanta).
     */
    var calidadHls: Int
        get() = sp.getInt("calidad_hls", 0)
        set(v) { sp.edit().putInt("calidad_hls", v.coerceIn(0, 4)).apply() }

    /**
     * Canales HLS en el móvil por «modo compatible»: el móvil baja los segmentos .ts de una sola variante y se los pasa a libVLC como un
     * único flujo continuo (como se hace para la tele), en vez de dejar que libVLC gestione el HLS adaptativo. Para canales que no arrancan.
     */
    var canalesCompatibles: Boolean
        get() = sp.getBoolean("canales_compat", false)
        set(v) { sp.edit().putBoolean("canales_compat", v).apply() }

    /** Opciones de media de libVLC (módulo adaptive) para la calidad elegida. */
    fun opcionesCalidad(retransmision: Boolean): List<String> {
        var c = calidadHls
        if (c == 0 && retransmision) c = 1
        return when (c) {
            1 -> listOf(":adaptive-logic=highest")
            2 -> listOf(":adaptive-logic=lowest")
            3 -> listOf(":adaptive-logic=highest", ":adaptive-maxheight=720")
            4 -> listOf(":adaptive-logic=highest", ":adaptive-maxheight=480")
            else -> emptyList()
        }
    }

    /** Panel con velocidad, progreso y peers mientras suena un torrent. */
    var mostrarStats: Boolean
        get() = sp.getBoolean("stats", true)
        set(v) { sp.edit().putBoolean("stats", v).apply() }

    /** Pista de audio que se elige sola: "" automática, "primera" (suele ser el original) o un idioma (en, es, fr…). */
    var audioPreferido: String
        get() = sp.getString("audio_pref", "") ?: ""
        set(v) { sp.edit().putString("audio_pref", v).apply() }

    var segundoPlano: Boolean
        get() = sp.getBoolean("segundo_plano", true)
        set(v) { sp.edit().putBoolean("segundo_plano", v).apply() }

    /** Opciones con las que se crea LibVLC. Cambiar el estilo de subtítulos exige recrearlo. */
    fun opcionesVlc(): ArrayList<String> {
        val o = ArrayList<String>()
        o.add("--audio-time-stretch")
        o.add("--no-video-title-show")
        // En VLC, un valor MENOR de rel-fontsize = letra MÁS grande (alto del vídeo / valor). 16 es lo normal.
        val rel = (1600 / subTamano.coerceIn(50, 250)).coerceIn(5, 32)
        o.add("--freetype-rel-fontsize=$rel")
        o.add("--freetype-color=${subColor and 0xFFFFFF}")
        o.add("--freetype-opacity=255")
        o.add("--freetype-background-color=0")
        o.add("--freetype-background-opacity=${subFondo.coerceIn(0, 100) * 255 / 100}")
        o.add("--freetype-outline-thickness=$subContorno")
        o.add(if (subNegrita) "--freetype-bold" else "--no-freetype-bold")
        if (subElevar > 0) o.add("--sub-margin=$subElevar")
        if (subCodificacion.isNotEmpty()) o.add("--subsdec-encoding=$subCodificacion")
        return o
    }

    /** Orientación del reproductor: 0 automática (respeta el bloqueo de rotación del sistema), 1 horizontal, 2 vertical. */
    var orientacion: Int
        get() = sp.getInt("orient", 0)
        set(v) { sp.edit().putInt("orient", v.coerceIn(0, 2)).apply() }

    /** Al abrir un audio de una carpeta con acceso concedido, poner en cola el resto de audios de esa carpeta. */
    var colaAuto: Boolean
        get() = sp.getBoolean("cola_auto", true)
        set(v) { sp.edit().putBoolean("cola_auto", v).apply() }

    /** No cargar las pistas de subtítulos que traen los canales en directo (algunas dejan el canal parado). */
    var sinSubsCanales: Boolean
        get() = sp.getBoolean("sin_subs_canales", false)
        set(v) { sp.edit().putBoolean("sin_subs_canales", v).apply() }

    /** Usar el gestor de archivos propio (com.carpe.gestorarchivos) para elegir archivos y carpetas, si está instalado. */
    var usarGestor: Boolean
        get() = sp.getBoolean("usar_gestor", true)
        set(v) { sp.edit().putBoolean("usar_gestor", v).apply() }

    /** Cola: 0 sin repetir, 1 repetir toda la cola, 2 repetir la pista. */
    var colaRepetir: Int
        get() = sp.getInt("cola_rep", 0)
        set(v) { sp.edit().putInt("cola_rep", v.coerceIn(0, 2)).apply() }

    var colaAleatorio: Boolean
        get() = sp.getBoolean("cola_azar", false)
        set(v) { sp.edit().putBoolean("cola_azar", v).apply() }

    // ---------------------------------------------------------------- recientes y posicion

    private fun cargarRecientes(): MutableList<Reciente> {
        val salida = ArrayList<Reciente>()
        try {
            val b = sp.getString("recientes", null) ?: return salida
            val txt = AlmacenSeguro.descifrarTexto(b, "recientes") ?: return salida
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                salida.add(Reciente(o.getString("u"), o.optString("t"), o.optLong("p"), o.optLong("f")))
            }
        } catch (e: Exception) {
            // Lista ilegible: se empieza de cero.
        }
        return salida
    }

    private fun guardarRecientes(lista: List<Reciente>) {
        try {
            val arr = JSONArray()
            for (r in lista.take(40)) {
                arr.put(JSONObject().put("u", r.uri).put("t", r.titulo).put("p", r.pos).put("f", r.fecha))
            }
            val c = AlmacenSeguro.cifrarTexto(arr.toString(), "recientes") ?: return
            sp.edit().putString("recientes", c).apply()
        } catch (e: Exception) {
            // Sin Keystore no se guarda nada (privacidad antes que persistencia).
        }
    }

    fun recientes(): List<Reciente> = cargarRecientes()

    /** Pone al principio de «Recientes» (conserva la posición ya guardada). */
    fun registrar(uri: String, titulo: String) {
        val l = cargarRecientes()
        val previo = l.firstOrNull { it.uri == uri }
        l.removeAll { it.uri == uri }
        l.add(0, Reciente(uri, titulo, previo?.pos ?: 0L, System.currentTimeMillis()))
        guardarRecientes(l)
    }

    fun guardarPosicion(uri: String, pos: Long) {
        val l = cargarRecientes()
        val i = l.indexOfFirst { it.uri == uri }
        if (i < 0) return
        l[i] = l[i].copy(pos = pos)
        guardarRecientes(l)
    }

    fun posicion(uri: String): Long = cargarRecientes().firstOrNull { it.uri == uri }?.pos ?: 0L

    fun borrarReciente(uri: String) {
        val l = cargarRecientes()
        l.removeAll { it.uri == uri }
        guardarRecientes(l)
    }

    fun borrarRecientes() {
        sp.edit().remove("recientes").apply()
    }

    // ---------------------------------------------------------------- listas de canales

    /** Canales marcados con ★ (clave estable: id + nombre). */
    val favoritos: Set<String>
        get() = HashSet(sp.getStringSet("favs", null) ?: emptySet<String>())

    fun alternarFavorito(clave: String) {
        val f = HashSet(favoritos)
        if (!f.add(clave)) f.remove(clave)
        sp.edit().putStringSet("favs", f).apply()
    }

    fun agregarFavoritos(claves: Collection<String>) {
        val f = HashSet(favoritos)
        if (f.addAll(claves)) sp.edit().putStringSet("favs", f).apply()
    }

    fun quitarFavoritos(claves: Collection<String>) {
        val f = HashSet(favoritos)
        if (f.removeAll(claves.toSet())) sp.edit().putStringSet("favs", f).apply()
    }

    /** URL de la guía (XMLTV) puesta a mano para una lista. Vacío = la que indique la propia lista. */
    fun guiaDe(lista: String): String = leerSeguro("guia_" + Integer.toHexString(lista.hashCode()))

    fun fijarGuia(lista: String, url: String) {
        escribirSeguro("guia_" + Integer.toHexString(lista.hashCode()), url.trim())
    }

    // ---------------------------------------------------------------- cifrado

    private fun leerSeguro(clave: String): String {
        val b = sp.getString(clave, null) ?: return ""
        return AlmacenSeguro.descifrarTexto(b, clave) ?: ""
    }

    private fun escribirSeguro(clave: String, valor: String) {
        if (valor.isEmpty()) {
            sp.edit().remove(clave).apply()
            return
        }
        val c = AlmacenSeguro.cifrarTexto(valor, clave) ?: return
        sp.edit().putString(clave, c).apply()
    }
}
