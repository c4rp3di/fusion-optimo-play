package com.optimo.play

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Descarga y descifrado de archivos de Mega, compartido por [ServidorMega] (reproducir y enviar a la tele) y
 * [MegaGuardado] (guardar en el dispositivo).
 */
object MegaFlujo {

    /** Abre la descarga de Mega del rango [desde, hasta] (ambos incluidos). Renueva la dirección si ha caducado. */
    fun conectar(cli: MegaCliente, n: MegaNodo, desde: Long, hasta: Long): HttpURLConnection {
        var url = cli.urlDescarga(n, false)
        var renovada = false
        while (true) {
            val con = URL("$url/$desde-$hasta").openConnection() as HttpURLConnection
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.setRequestProperty("User-Agent", "Mozilla/5.0")
            val codigo = try { con.responseCode } catch (e: Exception) { con.disconnect(); throw e }
            if (codigo in 200..299) return con
            val espera = con.getHeaderField("X-MEGA-Time-Left")?.toLongOrNull()
            con.disconnect()
            when {
                codigo == 509 || codigo == 429 -> {
                    val extra = if (espera != null && espera > 0) " Vuelve a intentarlo en " + maxOf(1L, espera / 60) + " min." else ""
                    throw MegaException(
                        MegaException.CUOTA,
                        "Se ha superado el límite de transferencia de Mega.$extra Prueba más tarde o con otra conexión."
                    )
                }
                (codigo == 403 || codigo == 404 || codigo == 410) && !renovada -> {
                    renovada = true
                    url = cli.urlDescarga(n, true)
                }
                else -> throw MegaException(MegaException.GENERAL, "Mega respondió HTTP $codigo al descargar")
            }
        }
    }

    /**
     * Lee de [entrada] (el rango pedido a Mega desde [bloque], alineado a 16 bytes), lo descifra y escribe en [sal] los bytes
     * [ini, fin]. Con [cancelado] se corta lanzando IOException («Cancelado»). [alEscribir] recibe cuántos bytes se acaban de escribir.
     */
    fun descifrar(
        entrada: InputStream, n: MegaNodo, ini: Long, bloque: Long, fin: Long, sal: OutputStream,
        cancelado: () -> Boolean = { false }, alEscribir: ((Int) -> Unit)? = null
    ) {
        // El contador de AES-CTR de Mega: 8 bytes de nonce y 8 bytes con el nº de bloque de 16 bytes (big-endian).
        val iv = ByteArray(16)
        System.arraycopy(n.nonce, 0, iv, 0, 8)
        var b = bloque / 16
        for (i in 15 downTo 8) {
            iv[i] = (b and 0xFF).toByte()
            b = b ushr 8
        }
        val cifra = Cipher.getInstance("AES/CTR/NoPadding")
        cifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(n.aes, "AES"), IvParameterSpec(iv))
        var saltar = (ini - bloque).toInt()
        var restante = fin - ini + 1
        val buf = ByteArray(65536)
        val claro = ByteArray(65536 + 64)
        while (restante > 0) {
            if (cancelado()) throw IOException("Cancelado")
            val r = entrada.read(buf)
            if (r < 0) break
            val k = cifra.update(buf, 0, r, claro, 0)
            var off = 0
            var cnt = k
            if (saltar > 0) {
                val s = minOf(saltar, cnt)
                off = s
                cnt -= s
                saltar -= s
            }
            if (cnt > 0) {
                val w = minOf(cnt.toLong(), restante).toInt()
                sal.write(claro, off, w)
                restante -= w
                alEscribir?.invoke(w)
            }
        }
        sal.flush()
    }
}
