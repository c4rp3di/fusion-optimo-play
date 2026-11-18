package com.optimo.play

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/** Un receptor DLNA (UPnP MediaRenderer) y la dirección de su servicio AVTransport. */
class DlnaDispositivo(
    val nombre: String,
    val controlUrl: String,
    val servicio: String,
    /** Servicio ConnectionManager (dice qué formatos acepta la tele); null si no lo anuncia. */
    val conexionUrl: String? = null,
    val conexionTipo: String? = null,
    /** Fabricante, modelo y número de modelo tal como los anuncia la tele. */
    val modelo: String = ""
)

/**
 * DLNA sin librerías: búsqueda SSDP por multidifusión y control SOAP del servicio AVTransport.
 * Es lo que entienden las Smart TV de Samsung de 2012 (AllShare) y muchas otras.
 */
object Dlna {

    private val BUSQUEDAS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1"
    )

    /** Busca receptores durante [ms] milisegundos. Bloquea: llamar desde un hilo; [alEncontrar] se llama desde él. */
    fun descubrir(ctx: Context, ms: Long, alEncontrar: (DlnaDispositivo) -> Unit) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val cerrojo = try { wifi?.createMulticastLock("optimoplay-ssdp") } catch (e: Exception) { null }
        var sock: DatagramSocket? = null
        try {
            cerrojo?.setReferenceCounted(false)
            cerrojo?.acquire()
            val s = DatagramSocket(null)
            sock = s
            s.reuseAddress = true
            s.bind(InetSocketAddress(0))
            s.soTimeout = 600
            val dir = InetAddress.getByName("239.255.255.250")
            val vistos = HashSet<String>()
            val buf = ByteArray(4096)
            val fin = System.currentTimeMillis() + ms
            var ultimo = 0L
            while (System.currentTimeMillis() < fin) {
                if (System.currentTimeMillis() - ultimo > 1500) {
                    ultimo = System.currentTimeMillis()
                    for (st in BUSQUEDAS) {
                        val m = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " +
                            st + "\r\n\r\n").toByteArray(Charsets.UTF_8)
                        try { s.send(DatagramPacket(m, m.size, dir, 1900)) } catch (e: Exception) { /* sin red */ }
                    }
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val txt = String(p.data, 0, p.length, Charsets.UTF_8)
                    val loc = Regex("(?im)^location:\\s*(\\S+)").find(txt)?.groupValues?.get(1) ?: continue
                    if (!vistos.add(loc)) continue
                    val d = describir(loc) ?: continue
                    alEncontrar(d)
                } catch (e: SocketTimeoutException) {
                    // Siguiente vuelta.
                }
            }
        } catch (e: Exception) {
            // Sin Wi-Fi o sin permiso: no hay nada que encontrar.
        } finally {
            try { sock?.close() } catch (e: Exception) { /* nada */ }
            try { if (cerrojo?.isHeld == true) cerrojo.release() } catch (e: Exception) { /* nada */ }
        }
    }

    private fun desescapar(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun escapar(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun describir(location: String): DlnaDispositivo? {
        return try {
            val c = URL(location).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            val xml = c.inputStream.use { String(Red.leerBytes(it, 200_000), Charsets.UTF_8) }
            try { c.disconnect() } catch (e: Throwable) { /* nada */ }
            if (!xml.contains("MediaRenderer")) return null
            val dotall = RegexOption.DOT_MATCHES_ALL
            val nombre = Regex("<friendlyName>(.*?)</friendlyName>", dotall).find(xml)?.groupValues?.get(1)?.trim()
            var control: String? = null
            var tipo: String? = null
            var conUrl: String? = null
            var conTipo: String? = null
            for (bloque in Regex("<service>(.*?)</service>", dotall).findAll(xml)) {
                val b = bloque.groupValues[1]
                if (b.contains("ConnectionManager") && conUrl == null) {
                    conUrl = Regex("<controlURL>(.*?)</controlURL>", dotall).find(b)?.groupValues?.get(1)?.trim()
                    conTipo = Regex("<serviceType>(.*?)</serviceType>", dotall).find(b)?.groupValues?.get(1)?.trim()
                }
                if (b.contains("AVTransport")) {
                    control = Regex("<controlURL>(.*?)</controlURL>", dotall).find(b)?.groupValues?.get(1)?.trim()
                    tipo = Regex("<serviceType>(.*?)</serviceType>", dotall).find(b)?.groupValues?.get(1)?.trim()
                }
            }
            if (control == null || tipo == null) return null
            val baseTxt = Regex("<URLBase>(.*?)</URLBase>", dotall).find(xml)?.groupValues?.get(1)?.trim()
            val base = URL(if (!baseTxt.isNullOrEmpty()) baseTxt else location)
            fun campo(n: String) = Regex("<$n>(.*?)</$n>", dotall).find(xml)?.groupValues?.get(1)?.trim() ?: ""
            val modelo = listOf(campo("manufacturer"), campo("modelName"), campo("modelNumber"))
                .filter { it.isNotEmpty() }.joinToString(" · ") { desescapar(it) }
            DlnaDispositivo(
                desescapar(nombre ?: "Receptor DLNA"), URL(base, control).toString(), tipo,
                if (conUrl != null && conTipo != null) URL(base, conUrl).toString() else null, conTipo, modelo
            )
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ compatibilidad de la tele

    /**
     * Pregunta a la tele qué formatos acepta (ConnectionManager › GetProtocolInfo) y devuelve un texto legible.
     * Bloquea: llamar desde un hilo.
     */
    fun compatibilidad(d: DlnaDispositivo): String {
        val sb = StringBuilder()
        sb.append("Tele: ").append(d.nombre).append('\n')
        if (d.modelo.isNotEmpty()) sb.append("Modelo: ").append(d.modelo).append('\n')
        val url = d.conexionUrl
        val tipo = d.conexionTipo
        if (url == null || tipo == null) {
            sb.append("\nLa tele no anuncia el servicio ConnectionManager: no se puede saber qué formatos acepta.")
            return sb.toString()
        }
        try {
            val cuerpo = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:GetProtocolInfo xmlns:u=\"" + tipo + "\"></u:GetProtocolInfo></s:Body></s:Envelope>"
            val c = URL(url).openConnection() as HttpURLConnection
            var txt = ""
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 8000
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                c.setRequestProperty("SOAPACTION", "\"" + tipo + "#GetProtocolInfo\"")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
                val cod = c.responseCode
                val flujo = if (cod >= 400) c.errorStream else c.inputStream
                txt = if (flujo != null) flujo.use { String(Red.leerBytes(it, 400_000), Charsets.UTF_8) } else ""
                if (cod >= 400) {
                    sb.append("\nLa tele respondió HTTP ").append(cod).append(" a GetProtocolInfo")
                    return sb.toString()
                }
            } finally {
                try { c.disconnect() } catch (e: Throwable) { /* nada */ }
            }
            val sink = Regex("<Sink>(.*?)</Sink>", RegexOption.DOT_MATCHES_ALL).find(txt)?.groupValues?.get(1)?.trim() ?: ""
            val items = desescapar(sink).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isEmpty()) {
                sb.append("\nLa tele no devolvió ninguna lista de formatos.")
                return sb.toString()
            }
            val video = items.filter { it.contains(":video/", true) }
            val audio = items.filter { it.contains(":audio/", true) }
            val resto = items.filter { !it.contains(":video/", true) && !it.contains(":audio/", true) && !it.contains(":image/", true) }
            sb.append("\nVÍDEO (").append(video.size).append("):\n")
            for (i in video) sb.append("  ").append(i.removePrefix("http-get:*:")).append('\n')
            sb.append("\nAUDIO (").append(audio.size).append("):\n")
            for (i in audio) sb.append("  ").append(i.removePrefix("http-get:*:")).append('\n')
            if (resto.isNotEmpty()) {
                sb.append("\nOTROS (").append(resto.size).append("):\n")
                for (i in resto) sb.append("  ").append(i.removePrefix("http-get:*:")).append('\n')
            }
        } catch (e: Exception) {
            sb.append("\nNo se pudo consultar a la tele: ").append(e.message ?: e.javaClass.simpleName)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ envío de un vídeo

    private fun hms(ms: Long): String {
        val s = maxOf(0L, ms) / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** «0:01:23», «00:01:23.500» → ms; null si no es un tiempo. */
    private fun leerHms(t: String?): Long? {
        if (t == null) return null
        val m = Regex("^(\\d+):(\\d{2}):(\\d{2})").find(t.trim()) ?: return null
        return (m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000
    }

    class EmisorDlna(private val d: DlnaDispositivo) : Emisor {

        override val nombre: String get() = d.nombre

        @Volatile private var est = EstadoRemoto(true, false, false, false, 0L, 0L, null)
        @Volatile private var vivo = true
        @Volatile private var haSonado = false
        @Volatile private var posInicial = 0L
        @Volatile private var directo = false
        private val ejecutor = Executors.newSingleThreadExecutor()
        private var sondeo: Thread? = null

        private fun accion(nombre: String, args: String): String {
            val cuerpo = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:" + nombre + " xmlns:u=\"" + d.servicio + "\">" + args + "</u:" + nombre + ">" +
                "</s:Body></s:Envelope>"
            val c = URL(d.controlUrl).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 8000
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                c.setRequestProperty("SOAPACTION", "\"" + d.servicio + "#" + nombre + "\"")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
                val cod = c.responseCode
                val flujo = if (cod >= 400) c.errorStream else c.inputStream
                val txt = if (flujo != null) flujo.use { String(Red.leerBytes(it, 200_000), Charsets.UTF_8) } else ""
                if (cod >= 400) {
                    val det = Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.DOT_MATCHES_ALL)
                        .find(txt)?.groupValues?.get(1)?.trim()
                    throw IOException("La tele respondió " + cod + " a " + nombre + (if (det != null) " (" + det + ")" else ""))
                }
                return txt
            } finally {
                try { c.disconnect() } catch (e: Throwable) { /* nada */ }
            }
        }

        private fun fallo(msg: String) {
            val e = est
            est = EstadoRemoto(false, false, false, false, e.posMs, e.durMs, msg)
        }

        override fun cargar(url: String, mime: String, titulo: String, directo: Boolean, posMs: Long, subs: String?) {
            this.directo = directo
            posInicial = posMs
            haSonado = false
            est = EstadoRemoto(true, false, false, false, 0L, 0L, null)
            ejecutor.execute {
                try {
                    try { accion("Stop", "<InstanceID>0</InstanceID>") } catch (e: Exception) { /* nada que parar */ }
                    // Samsung y otras exigen los «DLNA.ORG_*»; el resto los ignora.
                    val dlnaMime = if (mime == "video/x-matroska") "video/x-mkv" else mime
                    val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                        "xmlns:sec=\"http://www.sec.co.kr/\">" +
                        "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + escapar(titulo) + "</dc:title>" +
                        "<upnp:class>" + (if (mime.startsWith("audio")) "object.item.audioItem" else "object.item.videoItem") +
                        "</upnp:class><res protocolInfo=\"http-get:*:" + dlnaMime +
                        ":DLNA.ORG_OP=" + (if (directo) "00" else "01") + ";DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\">" +
                        escapar(url) + "</res>" +
                        (if (subs != null) "<res protocolInfo=\"http-get:*:text/srt:*\">" + escapar(subs) + "</res>" +
                            "<sec:CaptionInfoEx sec:type=\"srt\">" + escapar(subs) + "</sec:CaptionInfoEx>" +
                            "<sec:CaptionInfo sec:type=\"srt\">" + escapar(subs) + "</sec:CaptionInfo>" else "") +
                        "</item></DIDL-Lite>"
                    accion(
                        "SetAVTransportURI",
                        "<InstanceID>0</InstanceID><CurrentURI>" + escapar(url) + "</CurrentURI><CurrentURIMetaData>" +
                            escapar(didl) + "</CurrentURIMetaData>"
                    )
                    accion("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                    if (sondeo == null) iniciarSondeo()
                } catch (e: Exception) {
                    fallo(e.message ?: e.javaClass.simpleName)
                }
            }
        }

        private fun iniciarSondeo() {
            val t = Thread {
                var fallos = 0
                while (vivo) {
                    try {
                        val ti = accion("GetTransportInfo", "<InstanceID>0</InstanceID>")
                        val pi = accion("GetPositionInfo", "<InstanceID>0</InstanceID>")
                        fallos = 0
                        val estado = Regex("<CurrentTransportState>(.*?)</CurrentTransportState>").find(ti)?.groupValues?.get(1) ?: ""
                        val pos = leerHms(Regex("<RelTime>(.*?)</RelTime>").find(pi)?.groupValues?.get(1)) ?: est.posMs
                        val dur = leerHms(Regex("<TrackDuration>(.*?)</TrackDuration>").find(pi)?.groupValues?.get(1)) ?: 0L
                        val sonando = estado == "PLAYING"
                        if (sonando) haSonado = true
                        val fin = haSonado && (estado == "STOPPED" || estado == "NO_MEDIA_PRESENT")
                        est = EstadoRemoto(
                            estado == "TRANSITIONING" || (!haSonado && !sonando && estado != "PAUSED_PLAYBACK"),
                            sonando, estado == "PAUSED_PLAYBACK", fin, pos, dur, null
                        )
                        // Reanudar por donde ibas: se salta en cuanto la tele empieza a reproducir.
                        if (sonando && posInicial > 5000 && !directo) {
                            val p = posInicial
                            posInicial = 0L
                            accion("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + hms(p) + "</Target>")
                        }
                    } catch (e: Exception) {
                        fallos++
                        if (fallos >= 6) {
                            fallo("Se perdió la conexión con la tele")
                            return@Thread
                        }
                    }
                    try { Thread.sleep(1000) } catch (e: InterruptedException) { return@Thread }
                }
            }
            t.isDaemon = true
            sondeo = t
            t.start()
        }

        override fun reproducir() {
            ejecutor.execute { try { accion("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") } catch (e: Exception) { fallo(e.message ?: "Error") } }
        }

        override fun pausar() {
            ejecutor.execute { try { accion("Pause", "<InstanceID>0</InstanceID>") } catch (e: Exception) { fallo(e.message ?: "Error") } }
        }

        override fun buscar(ms: Long) {
            ejecutor.execute {
                try {
                    accion("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + hms(ms) + "</Target>")
                } catch (e: Exception) {
                    fallo("Esta tele no permite saltar: " + (e.message ?: ""))
                }
            }
        }

        override fun estado(): EstadoRemoto = est

        override fun parar() {
            vivo = false
            sondeo?.interrupt()
            ejecutor.execute {
                try { accion("Stop", "<InstanceID>0</InstanceID>") } catch (e: Exception) { /* ya estaba parada */ }
                ejecutor.shutdown()
            }
        }
    }
}
