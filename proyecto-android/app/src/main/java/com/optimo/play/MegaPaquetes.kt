package com.optimo.play

/**
 * Un archivo comprimido de un enlace de Mega, con todas sus partes: «Peli.part1.rar … Peli.part12.rar», «Peli.rar + Peli.r00 …»,
 * «Peli.7z.001 …», «Peli.zip + Peli.z01 …» o un solo .rar/.zip/.7z. [partes] va en el orden de los volúmenes y la primera es la que
 * se abre (en un ZIP dividido, el .zip).
 */
class MegaPaquete(
    val base: String,
    val tipo: Tipo,
    val partes: List<MegaNodo>,
    /** Subcarpetas de Mega por las que se llega («Temporada 1»), vacío en la raíz. */
    val ruta: String,
    /** Qué volúmenes faltan, o null si está completo. */
    val faltan: String?
) {
    enum class Tipo { RAR, ZIP, SIETE_Z, NUMERICO }

    val id: String = "p" + (partes.firstOrNull()?.handle ?: base)
    val tam: Long = partes.sumOf { it.tam }
    val completo: Boolean get() = faltan == null

    val etiqueta: String
        get() {
            val f = when (tipo) {
                Tipo.RAR -> "RAR"
                Tipo.ZIP -> "ZIP"
                Tipo.SIETE_Z -> "7z"
                Tipo.NUMERICO -> "dividido"
            }
            return f + (if (partes.size > 1) " · " + partes.size + " partes" else "")
        }

    /**
     * Nombre de cada parte en la caché: «x» + lo que sigue al nombre base. Así el patrón de volúmenes (.part01.rar, .r00, .001, .z01)
     * se conserva exacto y los nombres no llevan caracteres raros que confundan al descompresor.
     */
    fun nombreCache(n: MegaNodo): String = "x" + n.nombre.removePrefix(base)
}

object MegaPaquetes {

    private val RE_RAR_PART = Regex("^(.+)\\.part0*(\\d+)\\.rar$", RegexOption.IGNORE_CASE)
    private val RE_RAR_R = Regex("^(.+)\\.r(\\d{2,3})$", RegexOption.IGNORE_CASE)
    private val RE_RAR = Regex("^(.+)\\.rar$", RegexOption.IGNORE_CASE)
    private val RE_ZIP_Z = Regex("^(.+)\\.z(\\d{2,3})$", RegexOption.IGNORE_CASE)
    private val RE_ZIP = Regex("^(.+)\\.zip$", RegexOption.IGNORE_CASE)
    private val RE_7Z = Regex("^(.+)\\.7z$", RegexOption.IGNORE_CASE)
    private val RE_NUM = Regex("^(.+)\\.(\\d{3})$")

    /** ¿Es un archivo que forma parte de un paquete comprimido (aunque suelto)? */
    fun esComprimido(nombre: String): Boolean =
        RE_RAR_PART.matches(nombre) || RE_RAR_R.matches(nombre) || RE_RAR.matches(nombre) || RE_ZIP_Z.matches(nombre) ||
            RE_ZIP.matches(nombre) || RE_7Z.matches(nombre) || RE_NUM.matches(nombre)

    /** Texto de los números que faltan en una secuencia que debería ir de [desde] a su máximo sin huecos. */
    private fun huecos(nums: Collection<Int>, desde: Int, formato: (Int) -> String): String? {
        if (nums.isEmpty()) return null
        val set = nums.toSet()
        val falta = (desde..nums.max()).filter { it !in set }
        return if (falta.isEmpty()) null else "Faltan " + falta.take(6).joinToString(", ") { formato(it) } + if (falta.size > 6) "…" else ""
    }

    /** Agrupa los archivos de un enlace de Mega en paquetes comprimidos, cada uno con todas sus partes. */
    fun detectar(archivos: List<MegaNodo>): List<MegaPaquete> {
        val salida = ArrayList<MegaPaquete>()
        for ((ruta, lista) in archivos.groupBy { it.ruta }) {
            val usados = HashSet<String>()

            // RAR con «.partN.rar»
            val partes = LinkedHashMap<String, MutableList<Pair<Int, MegaNodo>>>()
            for (n in lista) RE_RAR_PART.matchEntire(n.nombre)?.let {
                partes.getOrPut(it.groupValues[1].lowercase()) { ArrayList() }.add(Pair(it.groupValues[2].toInt(), n))
            }
            for ((_, g) in partes) {
                val orden = g.sortedBy { it.first }
                val base = RE_RAR_PART.matchEntire(orden[0].second.nombre)!!.groupValues[1]
                val f = huecos(orden.map { it.first }, 1) { "part$it" }
                    ?: if (orden[0].first != 1) "Falta part1" else null
                salida.add(MegaPaquete(base, MegaPaquete.Tipo.RAR, orden.map { it.second }, ruta, f))
                orden.forEach { usados.add(it.second.handle) }
            }

            // RAR clásico: «.rar» + «.r00 .r01 …»
            val rs = LinkedHashMap<String, MutableList<Pair<Int, MegaNodo>>>()
            for (n in lista) if (n.handle !in usados) RE_RAR_R.matchEntire(n.nombre)?.let {
                rs.getOrPut(it.groupValues[1].lowercase()) { ArrayList() }.add(Pair(it.groupValues[2].toInt(), n))
            }
            for ((clave, g) in rs) {
                val rar = lista.firstOrNull { it.handle !in usados && RE_RAR.matchEntire(it.nombre)?.groupValues?.get(1)?.lowercase() == clave }
                val orden = g.sortedBy { it.first }
                val base = RE_RAR_R.matchEntire(orden[0].second.nombre)!!.groupValues[1]
                val f = if (rar == null) "Falta el .rar" else huecos(orden.map { it.first }, 0) { "r" + it.toString().padStart(2, '0') }
                val todas = ArrayList<MegaNodo>()
                if (rar != null) todas.add(rar)
                todas.addAll(orden.map { it.second })
                salida.add(MegaPaquete(base, MegaPaquete.Tipo.RAR, todas, ruta, f))
                todas.forEach { usados.add(it.handle) }
            }

            // ZIP dividido: «.zip» + «.z01 .z02 …»
            val zs = LinkedHashMap<String, MutableList<Pair<Int, MegaNodo>>>()
            for (n in lista) if (n.handle !in usados) RE_ZIP_Z.matchEntire(n.nombre)?.let {
                zs.getOrPut(it.groupValues[1].lowercase()) { ArrayList() }.add(Pair(it.groupValues[2].toInt(), n))
            }
            for ((clave, g) in zs) {
                val zip = lista.firstOrNull { it.handle !in usados && RE_ZIP.matchEntire(it.nombre)?.groupValues?.get(1)?.lowercase() == clave }
                val orden = g.sortedBy { it.first }
                val base = RE_ZIP_Z.matchEntire(orden[0].second.nombre)!!.groupValues[1]
                val f = if (zip == null) "Falta el .zip" else huecos(orden.map { it.first }, 1) { "z" + it.toString().padStart(2, '0') }
                val todas = ArrayList<MegaNodo>()
                if (zip != null) todas.add(zip)
                todas.addAll(orden.map { it.second })
                salida.add(MegaPaquete(base, MegaPaquete.Tipo.ZIP, todas, ruta, f))
                todas.forEach { usados.add(it.handle) }
            }

            // Partes numeradas: «.7z.001», «.mkv.001»…
            val nums = LinkedHashMap<String, MutableList<Pair<Int, MegaNodo>>>()
            for (n in lista) if (n.handle !in usados) RE_NUM.matchEntire(n.nombre)?.let {
                nums.getOrPut(it.groupValues[1].lowercase()) { ArrayList() }.add(Pair(it.groupValues[2].toInt(), n))
            }
            for ((_, g) in nums) {
                val orden = g.sortedBy { it.first }
                val base = RE_NUM.matchEntire(orden[0].second.nombre)!!.groupValues[1]
                val f = huecos(orden.map { it.first }, 1) { it.toString().padStart(3, '0') }
                    ?: if (orden[0].first != 1) "Falta la parte 001" else null
                salida.add(MegaPaquete(base, MegaPaquete.Tipo.NUMERICO, orden.map { it.second }, ruta, f))
                orden.forEach { usados.add(it.second.handle) }
            }

            // Archivos sueltos: un .rar, .zip o .7z entero
            for (n in lista) {
                if (n.handle in usados) continue
                val tipo = when {
                    RE_RAR.matches(n.nombre) -> MegaPaquete.Tipo.RAR
                    RE_ZIP.matches(n.nombre) -> MegaPaquete.Tipo.ZIP
                    RE_7Z.matches(n.nombre) -> MegaPaquete.Tipo.SIETE_Z
                    else -> continue
                }
                val base = n.nombre.substringBeforeLast('.')
                salida.add(MegaPaquete(base, tipo, listOf(n), ruta, null))
            }
        }
        return salida.sortedWith { a, b ->
            val c = Utiles.comparaNatural(a.ruta, b.ruta)
            if (c != 0) c else Utiles.comparaNatural(a.base, b.base)
        }
    }
}
