package com.optimo.play

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente mínimo de la API REST de OpenSubtitles (api.opensubtitles.com/api/v1), solo con APIs del sistema.
 *
 * Hace falta una API key propia (gratuita, se pide en opensubtitles.com → "Consumers"). Para DESCARGAR también
 * hace falta usuario y contraseña; la cuenta gratuita tiene cuota diaria de descargas.
 * Las credenciales viajan solo a api.opensubtitles.com por HTTPS.
 */
object OpenSubtitles {

    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private const val UA = "OptimoPlay v0.1.0"

    class Resultado(
        val fileId: Long,
        val nombre: String,
        val idioma: String,
        val descargas: Int,
        val porHash: Boolean
    )

    @Volatile private var token: String? = null
    @Volatile private var tokenHora = 0L
    @Volatile private var host: String = "api.opensubtitles.com"

    private fun llamar(metodo: String, url: String, apiKey: String, tk: String?, cuerpo: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.setRequestProperty("Api-Key", apiKey)
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            if (tk != null) c.setRequestProperty("Authorization", "Bearer $tk")
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val flujo = if (codigo in 200..299) c.inputStream else c.errorStream
            val txt = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (codigo !in 200..299) {
                val msg = try { JSONObject(txt).optString("message", "") } catch (e: Exception) { "" }
                throw IOException("OpenSubtitles ($codigo)" + if (msg.isNotEmpty()) ": $msg" else "")
            }
            return JSONObject(txt)
        } finally {
            c.disconnect()
        }
    }

    private fun obtenerToken(aj: Ajustes): String? {
        val usuario = aj.osUsuario
        val clave = aj.osClave
        if (usuario.isEmpty() || clave.isEmpty()) return null
        val t = token
        if (t != null && System.currentTimeMillis() - tokenHora < 20L * 3600 * 1000) return t
        val cuerpo = JSONObject().put("username", usuario).put("password", clave).toString()
        val r = llamar("POST", "$BASE/login", aj.osApiKey, null, cuerpo)
        val nuevo = r.optString("token", "")
        if (nuevo.isEmpty()) throw IOException("OpenSubtitles: usuario o contraseña incorrectos")
        val b = r.optString("base_url", "")
        if (b.isNotEmpty()) host = b
        token = nuevo
        tokenHora = System.currentTimeMillis()
        return nuevo
    }

    /** Busca por hash del archivo (si lo hay) y por nombre. Los acertados por hash salen primero. */
    fun buscar(aj: Ajustes, consulta: String, idioma: String, hash: String?): List<Resultado> {
        if (aj.osApiKey.isEmpty()) throw IOException("Falta la API key de OpenSubtitles (Ajustes)")
        val partes = ArrayList<String>()
        if (idioma.isNotEmpty()) partes.add("languages=" + URLEncoder.encode(idioma, "UTF-8"))
        if (!hash.isNullOrEmpty()) partes.add("moviehash=$hash")
        if (consulta.isNotEmpty()) partes.add("query=" + URLEncoder.encode(consulta.lowercase(), "UTF-8"))
        if (partes.isEmpty()) return emptyList()
        val r = llamar("GET", "$BASE/subtitles?" + partes.joinToString("&"), aj.osApiKey, null, null)
        val datos = r.optJSONArray("data") ?: return emptyList()
        val salida = ArrayList<Resultado>()
        for (i in 0 until datos.length()) {
            val at = datos.getJSONObject(i).optJSONObject("attributes") ?: continue
            val ficheros = at.optJSONArray("files") ?: continue
            if (ficheros.length() == 0) continue
            val f = ficheros.getJSONObject(0)
            val nombre = at.optString("release", "").ifEmpty { f.optString("file_name", "subtítulo") }
            salida.add(
                Resultado(
                    f.optLong("file_id"),
                    nombre,
                    at.optString("language", ""),
                    at.optInt("download_count", 0),
                    at.optBoolean("moviehash_match", false)
                )
            )
        }
        return salida.sortedWith(compareByDescending<Resultado> { it.porHash }.thenByDescending { it.descargas })
    }

    /** Descarga el subtítulo a `destino` y devuelve el archivo. */
    fun descargar(aj: Ajustes, r: Resultado, destino: File): File {
        val tk = obtenerToken(aj)
            ?: throw IOException("Para descargar hace falta usuario y contraseña de OpenSubtitles (Ajustes)")
        val cuerpo = JSONObject().put("file_id", r.fileId).toString()
        val resp = llamar("POST", "$BASE/download", aj.osApiKey, tk, cuerpo)
        val enlace = resp.optString("link", "")
        if (enlace.isEmpty()) throw IOException("OpenSubtitles no devolvió el enlace de descarga")
        destino.mkdirs()
        val nombre = resp.optString("file_name", "").ifEmpty { "os_${r.fileId}.srt" }
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val f = File(destino, nombre)
        val c = URL(enlace).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.setRequestProperty("User-Agent", UA)
            c.inputStream.use { ent -> f.outputStream().use { sal -> ent.copyTo(sal) } }
        } finally {
            c.disconnect()
        }
        return f
    }
}
