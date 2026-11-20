package com.optimo.play

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Error de Mega con un mensaje ya listo para mostrar. [codigo] distingue los casos que la interfaz trata aparte. */
class MegaException(val codigo: Int, mensaje: String) : Exception(mensaje) {
    companion object {
        const val GENERAL = 0
        const val CLAVE_INCORRECTA = 1
        const val CUOTA = 2
    }
}

/** Lo que vuelca una lectura abierta con [MegaFuente.abrir]. Se cierra con [close] (también si no se llegó a volcar). */
interface MegaLectura : java.io.Closeable {
    /** Escribe en [sal] los bytes pedidos, ya descifrados. Con [cancelado] se corta lanzando IOException. */
    fun volcar(sal: java.io.OutputStream, cancelado: () -> Boolean = { false })
}

/**
 * Algo que se puede leer por rangos de bytes y servir a libVLC o a la tele: un archivo de Mega ([MegaNodo]) o un vídeo que
 * está guardado sin comprimir dentro de un RAR repartido en varias partes ([MegaVirtual]).
 */
interface MegaFuente {
    val handle: String
    val nombre: String
    val tam: Long

    /** Conecta con Mega para leer [ini, fin] (ambos incluidos). Si falla lo hace aquí, antes de volcar nada. */
    fun abrir(cli: MegaCliente, ini: Long, fin: Long): MegaLectura
}

/** Un archivo de Mega listo para descifrar: [clave] son los 32 bytes de la clave del archivo (tal cual en el enlace). */
class MegaNodo(
    override val handle: String,
    override val nombre: String,
    override val tam: Long,
    val clave: ByteArray,
    /** Carpetas por las que se llega (sin la carpeta raíz del enlace), p. ej. «Temporada 1/Extras». */
    val ruta: String = "",
    /** Identificadores de todas las carpetas que lo contienen (para limitar a una subcarpeta). */
    val padres: Set<String> = emptySet()
) : MegaFuente {
    /** Clave AES-128 del contenido: las dos mitades de la clave, con XOR. */
    val aes: ByteArray = MegaCripto.plegar(clave)

    /** Los 8 bytes del contador inicial de AES-CTR. */
    val nonce: ByteArray = if (clave.size >= 24) clave.copyOfRange(16, 24) else ByteArray(8)

    override fun abrir(cli: MegaCliente, ini: Long, fin: Long): MegaLectura {
        val bloque = ini and 15L.inv()
        val con = MegaFlujo.conectar(cli, this, bloque, fin)
        val nodo = this
        return object : MegaLectura {
            override fun volcar(sal: java.io.OutputStream, cancelado: () -> Boolean) {
                MegaFlujo.descifrar(con.inputStream, nodo, ini, bloque, fin, sal, cancelado)
            }

            override fun close() {
                try { con.disconnect() } catch (e: Exception) { /* nada */ }
            }
        }
    }
}

class MegaCarpeta(val nombre: String, val archivos: List<MegaNodo>)

/** Contraseñas de enlaces protegidos que ya se han escrito en esta sesión (solo en memoria; no se guardan en disco). */
object MegaClaves {
    private val m = ConcurrentHashMap<String, String>()
    fun obtener(uri: String): String? = m[uri]
    fun guardar(uri: String, clave: String) { m[uri] = clave }
    fun olvidar(uri: String) { m.remove(uri) }
}

object MegaCripto {

    /** Base64 «seguro para URL» de Mega (sin relleno, con - y _). */
    fun b64d(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/').replace(",", "").trim()
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Clave de 32 bytes de un archivo → clave AES de 16 (mitad A xor mitad B). Una clave de 16 (carpeta) queda igual. */
    fun plegar(k: ByteArray): ByteArray {
        if (k.size < 32) return k.copyOf(16.coerceAtMost(k.size))
        return ByteArray(16) { (k[it].toInt() xor k[it + 16].toInt()).toByte() }
    }

    fun ecbDescifrar(datos: ByteArray, clave: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(clave, "AES"))
        return c.doFinal(datos)
    }

    /** Descifra los atributos de un nodo (AES-CBC con IV cero, texto «MEGA{json}») y devuelve el nombre. */
    fun nombreDe(atributos: String, clave16: ByteArray): String? {
        return try {
            val raw = b64d(atributos)
            if (raw.isEmpty() || raw.size % 16 != 0) return null
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(clave16, "AES"), IvParameterSpec(ByteArray(16)))
            val claro = String(c.doFinal(raw), Charsets.UTF_8).trimEnd('\u0000')
            if (!claro.startsWith("MEGA{")) return null
            val n = JSONObject(claro.substring(4)).optString("n", "")
            n.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** PBKDF2 con HMAC-SHA512 hecho a mano (la contraseña entra como bytes UTF-8, sin depender de la codificación de char[]). */
    fun pbkdf2Sha512(clave: ByteArray, sal: ByteArray, vueltas: Int, largo: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(clave, "HmacSHA512"))
        val salida = ByteArray(largo)
        var pos = 0
        var bloque = 1
        while (pos < largo) {
            mac.update(sal)
            mac.update(
                byteArrayOf(
                    (bloque ushr 24).toByte(), (bloque ushr 16).toByte(), (bloque ushr 8).toByte(), bloque.toByte()
                )
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until vueltas) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(t.size, largo - pos)
            System.arraycopy(t, 0, salida, pos, n)
            pos += n
            bloque++
        }
        return salida
    }

    fun hmacSha256(clave: ByteArray, datos: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clave, "HmacSHA256"))
        return mac.doFinal(datos)
    }
}

/** Lo que dice un enlace de Mega. */
class MegaDatos(
    val carpeta: Boolean,
    val handle: String,
    /** 32 bytes (archivo) o 16 (carpeta). Null si al enlace le falta la clave. */
    val clave: ByteArray?,
    /** Contenido (base64) de un enlace #P!…: hace falta la contraseña para sacar handle y clave. */
    val protegido: String? = null,
    /** Archivo o carpeta concretos dentro de una carpeta compartida. */
    val sub: String? = null,
    val subCarpeta: Boolean = false
)

object MegaEnlace {

    private val B = "[A-Za-z0-9_\\-]"
    private val RE_PROTEGIDO = Regex("#P!($B+)")
    private val RE_NUEVO = Regex(
        "mega\\.(?:nz|io|co\\.nz)/(file|folder)/($B{8})(?:#($B+))?(?:/(file|folder)/($B{8}))?",
        RegexOption.IGNORE_CASE
    )
    private val RE_VIEJO = Regex("#(F?)!($B{8})(?:!($B+))?(?:([!?])($B{8}))?")
    private val RE_HOST = Regex("^(?:https?://)?(?:www\\.)?mega\\.(?:nz|io|co\\.nz)(?:/|$)", RegexOption.IGNORE_CASE)
    private val RE_CLAVE_TEXTO = Regex(
        "\\b(?:pass(?:word)?|contrase[ñn]a|clave|pwd|pw)\\s*[:=]\\s*(\\S+)",
        RegexOption.IGNORE_CASE
    )

    fun esMega(uri: String?): Boolean = uri != null && RE_HOST.containsMatchIn(uri.trim())

    /** «Contraseña: xxx» (o «pass:», «password=», «clave:»…) en un texto compartido junto al enlace. */
    fun claveEnTexto(texto: String?): String? {
        if (texto == null) return null
        val v = RE_CLAVE_TEXTO.find(texto)?.groupValues?.get(1) ?: return null
        return v.trim('"', '\'', '«', '»', '“', '”', '.', ',', ';').ifEmpty { null }
    }

    /** Interpreta un enlace de Mega (formatos nuevo y antiguo, archivo, carpeta y protegido con contraseña). */
    fun analizar(uri: String): MegaDatos? {
        return try {
            RE_PROTEGIDO.find(uri)?.let { return MegaDatos(false, "", null, protegido = it.groupValues[1]) }
            RE_NUEVO.find(uri)?.let { m ->
                val carpeta = m.groupValues[1].equals("folder", true)
                val clave = m.groupValues[3].ifEmpty { null }?.let { MegaCripto.b64d(it) }
                val sub = m.groupValues[5].ifEmpty { null }
                return MegaDatos(carpeta, m.groupValues[2], clave, null, sub, m.groupValues[4].equals("folder", true))
            }
            RE_VIEJO.find(uri)?.let { m ->
                val carpeta = m.groupValues[1].isNotEmpty()
                val clave = m.groupValues[3].ifEmpty { null }?.let { MegaCripto.b64d(it) }
                val sub = m.groupValues[5].ifEmpty { null }
                return MegaDatos(carpeta, m.groupValues[2], clave, null, sub, m.groupValues[4] == "!")
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Abre un enlace protegido con contraseña (#P!…). Formato: algoritmo(1) · tipo(1: archivo, 0: carpeta) · handle(6) ·
     * sal(32) · clave cifrada(32 o 16) · MAC(32). La clave derivada son 64 bytes de PBKDF2-HMAC-SHA512 (100 000 vueltas):
     * los primeros se aplican con XOR a la clave y los últimos 32 son la clave del HMAC-SHA256 que comprueba la contraseña.
     */
    fun desproteger(d: MegaDatos, contrasena: String): MegaDatos {
        val blob = d.protegido ?: return d
        val b = try { MegaCripto.b64d(blob) } catch (e: Exception) {
            throw MegaException(MegaException.GENERAL, "El enlace protegido está dañado o incompleto")
        }
        if (b.size < 2) throw MegaException(MegaException.GENERAL, "El enlace protegido está dañado o incompleto")
        val algoritmo = b[0].toInt() and 0xFF
        val esArchivo = (b[1].toInt() and 0xFF) == 1
        val largoClave = if (esArchivo) 32 else 16
        if (b.size != 2 + 6 + 32 + largoClave + 32) {
            throw MegaException(MegaException.GENERAL, "El enlace protegido está dañado o incompleto")
        }
        if (algoritmo != 2) {
            throw MegaException(
                MegaException.GENERAL,
                "Este enlace protegido usa un algoritmo antiguo (versión $algoritmo) que no está soportado"
            )
        }
        if (contrasena.isEmpty()) throw MegaException(MegaException.CLAVE_INCORRECTA, "Escribe la contraseña")
        val handle = MegaCripto.b64e(b.copyOfRange(2, 8))
        val sal = b.copyOfRange(8, 40)
        val cifrada = b.copyOfRange(40, 40 + largoClave)
        val mac = b.copyOfRange(40 + largoClave, 40 + largoClave + 32)
        val dk = MegaCripto.pbkdf2Sha512(contrasena.toByteArray(Charsets.UTF_8), sal, 100_000, 64)
        val calculado = MegaCripto.hmacSha256(dk.copyOfRange(32, 64), b.copyOfRange(0, 40 + largoClave))
        if (!MessageDigest.isEqual(calculado, mac)) {
            throw MegaException(MegaException.CLAVE_INCORRECTA, "Contraseña incorrecta")
        }
        val clave = ByteArray(largoClave) { (cifrada[it].toInt() xor dk[it].toInt()).toByte() }
        return MegaDatos(!esArchivo, handle, clave)
    }
}

/**
 * Cliente mínimo de la API pública de Mega: solo lo necesario para enlaces (sin cuenta). Resuelve el nombre y tamaño de un
 * archivo o lista una carpeta compartida, y pide direcciones temporales de descarga. Los datos de Mega llegan cifrados:
 * el descifrado del contenido lo hace ServidorMega al pasarlo a libVLC.
 */
class MegaCliente(private val carpeta: String?) {

    private var seq = (System.nanoTime() and 0x3FFFFFFFL)
    private val cacheUrl = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun textoError(c: Int): String = when (c) {
        -3, -4 -> "Mega está saturado; vuelve a intentarlo en unos segundos"
        -9 -> "El archivo o la carpeta ya no existe en Mega (enlace caducado o borrado)"
        -11 -> "Mega no permite acceder a este enlace"
        -14 -> "La clave del enlace no es correcta"
        -16 -> "Mega ha bloqueado este enlace"
        -17 -> "Se ha superado el límite de transferencia de Mega. Espera un rato o prueba con otra conexión"
        -18 -> "Mega no está disponible en este momento"
        else -> "Error de Mega ($c)"
    }

    @Synchronized
    private fun siguiente(): Long = seq++

    private fun llamar(cuerpo: JSONObject): JSONObject {
        var intento = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val url = "https://g.api.mega.co.nz/cs?id=" + siguiente() + (if (carpeta != null) "&n=$carpeta" else "")
            var texto = ""
            try {
                val con = URL(url).openConnection() as HttpURLConnection
                try {
                    con.requestMethod = "POST"
                    con.doOutput = true
                    con.connectTimeout = 15000
                    con.readTimeout = 25000
                    con.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                    con.outputStream.use { it.write(("[" + cuerpo.toString() + "]").toByteArray(Charsets.UTF_8)) }
                    val codigo = con.responseCode
                    if (codigo !in 200..299) throw MegaException(MegaException.GENERAL, "Mega respondió HTTP $codigo")
                    texto = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    con.disconnect()
                }
            } catch (e: MegaException) {
                throw e
            } catch (e: IOException) {
                throw MegaException(
                    MegaException.GENERAL,
                    "No se pudo conectar con Mega (" + (e.message ?: e.javaClass.simpleName) + ")"
                )
            }
            val r: Any = try {
                JSONArray(texto).get(0)
            } catch (e: Exception) {
                texto.trim().toIntOrNull() ?: throw MegaException(MegaException.GENERAL, "Respuesta inesperada de Mega")
            }
            if (r is Int) {
                if ((r == -3 || r == -4) && intento < 5) {
                    Thread.sleep(300L shl intento)
                    intento++
                    continue
                }
                throw MegaException(if (r == -17) MegaException.CUOTA else MegaException.GENERAL, textoError(r))
            }
            if (r is JSONObject) {
                if (!r.has("g") && !r.has("f") && r.has("e")) {
                    val c = r.optInt("e", 0)
                    throw MegaException(if (c == -17) MegaException.CUOTA else MegaException.GENERAL, textoError(c))
                }
                return r
            }
            throw MegaException(MegaException.GENERAL, "Respuesta inesperada de Mega")
        }
    }

    private fun extraerUrl(r: JSONObject): String {
        val g = r.opt("g")
        val u = when (g) {
            is String -> g
            is JSONArray -> g.optString(0, "")
            else -> ""
        }
        if (u.isEmpty()) throw MegaException(MegaException.GENERAL, "Mega no ha dado dirección de descarga")
        return u
    }

    /** Nombre, tamaño y dirección de un enlace a archivo. */
    fun infoArchivo(handle: String, clave: ByteArray): MegaNodo {
        if (clave.size != 32) throw MegaException(MegaException.GENERAL, "La clave del enlace no tiene el tamaño correcto")
        val r = llamar(JSONObject().put("a", "g").put("g", 1).put("ssl", 2).put("p", handle))
        val tam = r.optLong("s", -1L)
        if (tam < 0) throw MegaException(MegaException.GENERAL, "Mega no ha dado el tamaño del archivo")
        val nombre = MegaCripto.nombreDe(r.optString("at", ""), MegaCripto.plegar(clave))
            ?: throw MegaException(MegaException.GENERAL, "No se pueden leer los datos del archivo: ¿la clave del enlace es correcta?")
        cacheUrl[handle] = Pair(extraerUrl(r), System.currentTimeMillis())
        return MegaNodo(handle, nombre, tam, clave)
    }

    private class Info(val h: String, val p: String, val t: Int, val nombre: String, val clave: ByteArray, val tam: Long)

    /** Todos los archivos (con sus subcarpetas) de una carpeta compartida. */
    fun listarCarpeta(claveCarpeta: ByteArray): MegaCarpeta {
        if (carpeta == null || claveCarpeta.size != 16) {
            throw MegaException(MegaException.GENERAL, "La clave del enlace no tiene el tamaño correcto")
        }
        val r = llamar(JSONObject().put("a", "f").put("c", 1).put("r", 1))
        val arr = r.optJSONArray("f") ?: throw MegaException(MegaException.GENERAL, "Mega no ha devuelto el contenido de la carpeta")
        val info = LinkedHashMap<String, Info>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val t = n.optInt("t", -1)
            if (t != 0 && t != 1) continue
            val h = n.optString("h", "")
            if (h.isEmpty()) continue
            var clave: ByteArray? = null
            var nombre: String? = null
            // «k» trae «quien:clave» (a veces varias, separadas por /). Vale la que, descifrada con la clave del enlace, deje atributos legibles.
            for (parte in n.optString("k", "").split('/')) {
                val c = parte.indexOf(':')
                if (c < 0) continue
                try {
                    val enc = MegaCripto.b64d(parte.substring(c + 1))
                    if (enc.isEmpty() || enc.size % 16 != 0) continue
                    val dec = MegaCripto.ecbDescifrar(enc, claveCarpeta)
                    if (t == 0 && dec.size != 32) continue
                    if (t == 1 && dec.size != 16) continue
                    val nom = MegaCripto.nombreDe(n.optString("a", ""), MegaCripto.plegar(dec))
                    if (nom != null) {
                        clave = dec
                        nombre = nom
                        break
                    }
                } catch (e: Exception) {
                    // Esta parte no es para nosotros.
                }
            }
            if (clave == null || nombre == null) continue
            info[h] = Info(h, n.optString("p", ""), t, nombre, clave, n.optLong("s", 0L))
        }
        val raiz = info.values.firstOrNull { it.t == 1 && !info.containsKey(it.p) }
        val archivos = ArrayList<MegaNodo>()
        for (i in info.values) {
            if (i.t != 0) continue
            val partes = ArrayList<String>()
            val ids = LinkedHashSet<String>()
            var p = i.p
            var guarda = 0
            while (guarda++ < 64) {
                val padre = info[p] ?: break
                ids.add(padre.h)
                if (padre !== raiz) partes.add(0, padre.nombre)
                p = padre.p
            }
            archivos.add(MegaNodo(i.h, i.nombre, i.tam, i.clave, partes.joinToString("/"), ids))
        }
        return MegaCarpeta(raiz?.nombre ?: "Carpeta de Mega", archivos)
    }

    /** Dirección temporal de descarga (caduca en horas; se guarda 20 min). Con [renovar] pide una nueva. */
    fun urlDescarga(n: MegaNodo, renovar: Boolean = false): String {
        val previa = cacheUrl[n.handle]
        if (!renovar && previa != null && System.currentTimeMillis() - previa.second < 20 * 60 * 1000L) return previa.first
        val q = JSONObject().put("a", "g").put("g", 1).put("ssl", 2)
        if (carpeta != null) q.put("n", n.handle) else q.put("p", n.handle)
        val u = extraerUrl(llamar(q))
        cacheUrl[n.handle] = Pair(u, System.currentTimeMillis())
        return u
    }
}
