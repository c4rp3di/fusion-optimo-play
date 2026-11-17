package com.optimo.play

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Un canal de una lista M3U. */
data class Canal(
    val numero: Int,
    val nombre: String,
    val url: String,
    val logo: String?,
    val grupo: String,
    val tvgId: String?,
    val referer: String?,
    val ua: String?,
    val drm: Boolean
) {
    /** «100  LG 1»: lo que se enseña en las listas. */
    val etiqueta: String get() = if (numero > 0) "$numero  $nombre" else nombre

    /** Clave estable para favoritos (la URL cambia de una sesión a otra por los parámetros). */
    val clave: String get() = (tvgId ?: "") + "|" + nombre.lowercase()
}

/** Una línea de dirección o ruta de una lista, con el título de su #EXTINF si lo trae. */
class EntradaM3u(val ruta: String, val titulo: String?)

class ListaM3u(val canales: List<Canal>, val urlGuia: String?) {

    private class Pend {
        var nombre = ""
        var logo: String? = null
        var grupo: String? = null
        var id: String? = null
        var numero = 0
        var referer: String? = null
        var ua: String? = null
        var drm = false
    }

    companion object {

        /**
         * ¿Es una lista de canales y no un vídeo HLS? Un flujo HLS lleva etiquetas #EXT-X-… (duración de segmento,
         * variantes); una lista de canales solo #EXTINF seguidos de direcciones.
         */
        fun esLista(t: String): Boolean {
            if (!t.contains("#EXTINF")) return false
            if (t.contains("#EXT-X-TARGETDURATION") || t.contains("#EXT-X-STREAM-INF") || t.contains("#EXT-X-MEDIA-SEQUENCE")) {
                return false
            }
            return true
        }

        /** Entradas de una lista (líneas que no son etiquetas), cada una con el título de su #EXTINF si lo hay. */
        fun entradas(texto: String): List<EntradaM3u> {
            val salida = ArrayList<EntradaM3u>()
            var titulo: String? = null
            for (raw in texto.removePrefix("\uFEFF").lineSequence()) {
                val l = raw.trim()
                if (l.isEmpty()) continue
                if (l.startsWith("#EXTINF", true)) {
                    val coma = l.indexOf(',')
                    titulo = if (coma >= 0) l.substring(coma + 1).trim().takeIf { it.isNotEmpty() } else null
                    continue
                }
                if (l.startsWith("#")) continue
                salida.add(EntradaM3u(l, titulo))
                titulo = null
            }
            return salida
        }

        fun esRutaLocal(r: String): Boolean =
            !r.startsWith("magnet:", true) && (r.startsWith("file://", true) || !r.contains("://"))

        /**
         * ¿Es una lista de archivos locales (típico de un disco: «01 Intro.mp3», «CD2/02 Tema.flac»…) y no de canales
         * ni un vídeo HLS? Sí si no lleva etiquetas HLS y la mayoría de sus entradas son rutas, no direcciones de red.
         */
        fun esLocal(t: String): Boolean {
            if (t.contains("#EXT-X-")) return false
            val e = entradas(t)
            if (e.isEmpty()) return false
            return e.count { esRutaLocal(it.ruta) } * 2 > e.size
        }

        private val ATRIBUTO = Regex("([A-Za-z0-9_.-]+)\\s*=\\s*\"([^\"]*)\"")

        private fun atributos(s: String): Map<String, String> {
            val m = HashMap<String, String>()
            for (r in ATRIBUTO.findAll(s)) m[r.groupValues[1].lowercase()] = r.groupValues[2]
            return m
        }

        private fun leerExtinf(linea: String): Pend {
            val p = Pend()
            var i = linea.indexOf(':') + 1
            while (i < linea.length && linea[i] != ' ' && linea[i] != ',') i++ // salta la duración
            var enComillas = false
            var coma = -1
            var j = i
            while (j < linea.length) {
                val ch = linea[j]
                if (ch == '"') enComillas = !enComillas
                else if (ch == ',' && !enComillas) {
                    coma = j
                    break
                }
                j++
            }
            val a = atributos(linea.substring(i, if (coma >= 0) coma else linea.length))
            val visible = if (coma >= 0) linea.substring(coma + 1).trim() else ""
            p.nombre = if (visible.isNotEmpty()) visible else (a["tvg-name"] ?: "")
            p.logo = a["tvg-logo"]?.takeIf { it.isNotBlank() }
            p.grupo = a["group-title"]?.takeIf { it.isNotBlank() }
            p.id = a["tvg-id"]?.takeIf { it.isNotBlank() }
            p.numero = (a["tvg-chno"] ?: a["channel-number"])?.trim()?.toIntOrNull() ?: 0
            return p
        }

        fun parsear(texto: String): ListaM3u {
            var urlGuia: String? = null
            val crudos = ArrayList<Canal>()
            var pend: Pend? = null
            for (raw in texto.removePrefix("\uFEFF").lineSequence()) {
                val l = raw.trim()
                if (l.isEmpty()) continue
                if (l.startsWith("#EXTM3U", true)) {
                    val a = atributos(l.substring(7))
                    urlGuia = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"])?.split(',')?.firstOrNull()?.trim()
                        ?.takeIf { it.startsWith("http", true) }
                    continue
                }
                if (l.startsWith("#EXTINF", true)) {
                    pend = leerExtinf(l)
                    continue
                }
                if (l.startsWith("#EXTGRP:", true)) {
                    val p = pend ?: Pend().also { pend = it }
                    p.grupo = l.substring(8).trim().takeIf { it.isNotEmpty() }
                    continue
                }
                if (l.startsWith("#EXTVLCOPT:", true)) {
                    val p = pend ?: Pend().also { pend = it }
                    val k = l.substring(11).substringBefore('=').trim().lowercase()
                    val v = l.substringAfter('=', "").trim()
                    if (k == "http-referrer" || k == "http-referer") p.referer = v
                    if (k == "http-user-agent") p.ua = v
                    continue
                }
                if (l.startsWith("#KODIPROP:", true)) {
                    val p = pend ?: Pend().also { pend = it }
                    if (l.contains("license", true)) p.drm = true
                    continue
                }
                if (l.startsWith("#")) continue

                // Línea de dirección. Formato Kodi: url|User-Agent=…&Referer=…
                val p = pend ?: Pend()
                var url = l
                if (l.contains('|') && !l.substringAfter('|').contains("://")) {
                    url = l.substringBefore('|')
                    for (par in l.substringAfter('|').split('&')) {
                        val k = par.substringBefore('=').trim().lowercase()
                        val v = par.substringAfter('=', "").trim()
                        if (k == "user-agent") p.ua = v
                        if (k == "referer" || k == "referrer") p.referer = v
                    }
                }
                val nombre = if (p.nombre.isNotEmpty()) p.nombre else url.substringBefore('?').substringAfterLast('/').take(40)
                crudos.add(
                    Canal(p.numero, if (nombre.isEmpty()) "Canal" else nombre, url, p.logo, p.grupo ?: "Canales", p.id, p.referer, p.ua, p.drm)
                )
                pend = null
            }

            // «100 LG 1»: si casi todos los nombres empiezan por un número de canal, se separa del nombre.
            val prefijo = Regex("^(\\d{1,4})[\\s.:)-]+(\\S.*)$")
            val sinNumero = crudos.count { it.numero == 0 }
            val conPrefijo = crudos.count { it.numero == 0 && prefijo.matches(it.nombre) }
            val quitar = sinNumero >= 2 && conPrefijo * 10 >= sinNumero * 7
            val canales = ArrayList<Canal>()
            for (c in crudos) {
                if (quitar && c.numero == 0) {
                    val m = prefijo.matchEntire(c.nombre)
                    if (m != null) {
                        canales.add(c.copy(numero = m.groupValues[1].toInt(), nombre = m.groupValues[2].trim()))
                        continue
                    }
                }
                canales.add(c)
            }

            // Orden: grupos en el orden en que aparecen; dentro de cada uno, por número de canal y luego como vienen.
            val orden = LinkedHashMap<String, Int>()
            for (c in canales) if (!orden.containsKey(c.grupo)) orden[c.grupo] = orden.size
            val ordenados = canales.sortedWith(
                compareBy<Canal>({ orden[it.grupo] ?: 0 }, { if (it.numero > 0) it.numero else Int.MAX_VALUE })
            )
            return ListaM3u(ordenados, urlGuia)
        }

        /** Texto de una lista (URL, archivo del dispositivo o content://). Null si no se puede leer. */
        fun leer(ctx: Context, f: Fuente): String? {
            if (f.uri.startsWith(Biblioteca.PREFIJO, true)) return Biblioteca.leer(ctx, f.uri.substring(Biblioteca.PREFIJO.length))
            val u = Uri.parse(f.uri)
            val ins: InputStream = if (u.scheme == "http" || u.scheme == "https") {
                val c = Red.conexion(f.uri, f.ua, f.referer)
                if (c.responseCode >= 400) return null
                c.inputStream
            } else {
                ctx.contentResolver.openInputStream(u) ?: return null
            }
            val datos = ins.use { Red.leerBytes(it, 6_000_000) }
            return texto(datos)
        }

        /** UTF-8 si lo es; si no (listas .m3u antiguas de Windows), Windows-1252. */
        fun texto(datos: ByteArray): String {
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(datos)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                String(datos, java.nio.charset.Charset.forName("windows-1252"))
            }
        }
    }
}

/** Utilidades de red sencillas (listas, guías y logos). */
object Red {

    fun conexion(url: String, ua: String?, referer: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", if (ua.isNullOrBlank()) Utiles.UA_MOVIL else ua)
        if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
        return c
    }

    /** Lee como mucho `max` bytes. */
    fun leerBytes(ins: InputStream, max: Int): ByteArray {
        val sal = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (sal.size() < max) {
            val n = ins.read(buf)
            if (n < 0) break
            sal.write(buf, 0, n)
        }
        return sal.toByteArray()
    }
}
