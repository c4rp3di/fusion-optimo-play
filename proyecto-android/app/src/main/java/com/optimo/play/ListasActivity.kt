package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * «Mis listas»: gestor de listas. «Favoritos» va siempre la primera; después las listas propias (con categorías
 * editables) y las M3U/M3U8 descargadas o importadas (con sus categorías originales). Se pueden reordenar, renombrar,
 * abrir en el gestor de canales, actualizar, copiar a Descargas y borrar.
 */
class ListasActivity : Activity() {

    private val PEDIR_ARCHIVOS = 61

    private lateinit var lista: ListView
    private lateinit var vacio: TextView
    private var listas: List<ListaGuardada> = emptyList()

    // ---- buscador global de canales (entre todas las listas guardadas)
    private lateinit var buscador: EditText
    private lateinit var infoBusqueda: TextView
    private lateinit var resultados: ListView
    private lateinit var filaAnadir: LinearLayout
    private lateinit var botonPropia: Button

    /** Un canal de una lista guardada, con el texto ya normalizado (minúsculas y sin acentos) para buscarlo rápido. */
    private class EntradaIndice(
        val canal: Canal, val lista: ListaGuardada, val urlGuia: String?, val norm: String, val normNombre: String
    )

    /** Un resultado: el canal y los nombres de las demás listas donde también está (los repetidos se juntan en uno). */
    private class Hallazgo(val e: EntradaIndice, val otras: ArrayList<String>)

    @Volatile private var indice: List<EntradaIndice>? = null
    @Volatile private var firmaIndice = ""
    @Volatile private var indexando = false
    private var hallazgos: List<Hallazgo> = emptyList()
    private val manejador = android.os.Handler(android.os.Looper.getMainLooper())
    private var busquedaPendiente: Runnable? = null
    private val reAcentos = Regex("\\p{M}+")

    private fun norm(t: String): String =
        reAcentos.replace(java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD), "")

    private fun firmaDeListas(): String = listas.joinToString("|") { it.id + ":" + it.fecha + ":" + it.entradas }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(16), dp(28), dp(16), dp(8))

        val t = TextView(this)
        t.text = "Mis listas"
        t.textSize = 24f
        raiz.addView(t)

        buscador = EditText(this)
        buscador.hint = "🔍 Buscar canales en todas mis listas"
        buscador.setSingleLine(true)
        buscador.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        buscador.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        buscador.setOnEditorActionListener { v, _, _ ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        buscador.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                busquedaPendiente?.let { manejador.removeCallbacks(it) }
                val r = Runnable { buscar(buscador.text.toString()) }
                busquedaPendiente = r
                manejador.postDelayed(r, 250)
            }
        })
        raiz.addView(buscador, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        infoBusqueda = TextView(this)
        infoBusqueda.alpha = 0.7f
        infoBusqueda.setPadding(0, dp(4), 0, dp(4))
        infoBusqueda.visibility = android.view.View.GONE
        raiz.addView(infoBusqueda)

        resultados = ListView(this)
        resultados.visibility = android.view.View.GONE
        resultados.setOnItemClickListener { _, _, i, _ -> hallazgos.getOrNull(i)?.let { abrirCanal(it) } }
        resultados.setOnItemLongClickListener { _, _, i, _ ->
            hallazgos.getOrNull(i)?.let { menuCanal(it) }
            true
        }
        raiz.addView(resultados, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val fila = LinearLayout(this)
        filaAnadir = fila
        fila.orientation = LinearLayout.HORIZONTAL
        val bUrl = Button(this)
        bUrl.text = "＋ Desde una dirección"
        bUrl.setOnClickListener { dialogoUrl() }
        fila.addView(bUrl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bArch = Button(this)
        bArch.text = "＋ Desde un archivo"
        bArch.setOnClickListener { elegirArchivos() }
        fila.addView(bArch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(fila)

        val bPropia = Button(this)
        botonPropia = bPropia
        bPropia.text = "＋ Nueva lista propia"
        bPropia.setOnClickListener { nuevaPropia() }
        raiz.addView(bPropia, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        vacio = TextView(this)
        vacio.alpha = 0.7f
        vacio.setPadding(0, dp(16), 0, dp(8))
        raiz.addView(vacio)

        lista = ListView(this)
        lista.setOnItemClickListener { _, _, i, _ -> listas.getOrNull(i)?.let { abrir(it) } }
        lista.setOnItemLongClickListener { _, _, i, _ ->
            listas.getOrNull(i)?.let { menu(it) }
            true
        }
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        cargar()
    }

    private fun cargar() {
        listas = Biblioteca.todas(this)
        if (firmaDeListas() != firmaIndice) indice = null
        vacio.text = if (listas.size <= 1 && listas.all { it.entradas == 0 })
            "Añade listas desde una dirección o un archivo .m3u/.m3u8, o crea una propia. Los canales que marques con ☆ aparecen en Favoritos; también puedes guardar la lista que estés viendo desde ⋮ › «Guardar esta lista en la app»."
        else "Toca una lista para reproducirla. Mantén pulsada para gestionar sus canales y categorías, moverla, renombrarla o borrarla."
        val f = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        // simple_list_item_2 necesita dos textos: se hace con un adaptador propio sencillo.
        lista.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = listas.size
            override fun getItem(position: Int): Any = listas[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup?): android.view.View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val l = listas[position]
                val icono = when (l.tipo) {
                    Biblioteca.TIPO_FAVORITOS -> "★ "
                    Biblioteca.TIPO_PROPIA -> "📝 "
                    else -> ""
                }
                v.findViewById<TextView>(android.R.id.text1).text = icono + l.nombre
                val origen = if (l.origen.isEmpty()) "de un archivo" else l.origen.substringAfter("://").substringBefore('/')
                v.findViewById<TextView>(android.R.id.text2).text = when (l.tipo) {
                    Biblioteca.TIPO_FAVORITOS -> l.entradas.toString() + " canales  ·  tus favoritos"
                    Biblioteca.TIPO_PROPIA -> l.entradas.toString() + " canales  ·  lista propia"
                    else -> l.entradas.toString() + " entradas  ·  " + origen + "  ·  " + f.format(java.util.Date(l.fecha))
                }
                return v
            }
        }
    }

    // ---------------------------------------------------------------- buscador global

    private fun modoBusqueda(on: Boolean) {
        val oculto = android.view.View.GONE
        val visto = android.view.View.VISIBLE
        filaAnadir.visibility = if (on) oculto else visto
        botonPropia.visibility = if (on) oculto else visto
        vacio.visibility = if (on) oculto else visto
        lista.visibility = if (on) oculto else visto
        infoBusqueda.visibility = if (on) visto else oculto
        resultados.visibility = if (on) visto else oculto
    }

    /** Lee todas las listas guardadas (en segundo plano) y deja un índice de todos sus canales. */
    private fun construirIndice(alTerminar: () -> Unit) {
        if (indexando) return
        indexando = true
        val ls = listas
        val firma = firmaDeListas()
        Thread {
            val salida = ArrayList<EntradaIndice>()
            try {
                for ((n, l) in ls.withIndex()) {
                    runOnUiThread { if (!isFinishing) infoBusqueda.text = "Leyendo las listas… " + (n + 1) + "/" + ls.size }
                    val c = try { Biblioteca.contenido(this, l.id) } catch (e: Exception) { continue }
                    for (canal in c.canales) {
                        val nn = norm(canal.nombre)
                        salida.add(
                            EntradaIndice(canal, l, c.urlGuia, nn + " " + norm(canal.grupo) + " " + canal.numero + " " + norm(canal.tvgId ?: ""), nn)
                        )
                    }
                }
                indice = salida
                firmaIndice = firma
            } finally {
                indexando = false
            }
            runOnUiThread { if (!isFinishing) alTerminar() }
        }.start()
    }

    private fun buscar(q: String) {
        val texto = q.trim()
        if (texto.isEmpty()) {
            hallazgos = emptyList()
            modoBusqueda(false)
            return
        }
        modoBusqueda(true)
        val idx = indice
        if (idx == null) {
            infoBusqueda.text = "Leyendo las listas…"
            construirIndice { buscar(buscador.text.toString()) }
            return
        }
        val terminos = norm(texto).split(' ').filter { it.isNotEmpty() }
        if (terminos.isEmpty()) return
        val mapa = LinkedHashMap<String, Hallazgo>()
        for (e in idx) {
            var coincide = true
            for (t in terminos) if (!e.norm.contains(t)) {
                coincide = false
                break
            }
            if (!coincide) continue
            val clave = e.normNombre + "|" + e.canal.url
            val h = mapa[clave]
            if (h == null) mapa[clave] = Hallazgo(e, ArrayList())
            else if (h.e.lista.nombre != e.lista.nombre && !h.otras.contains(e.lista.nombre)) h.otras.add(e.lista.nombre)
        }
        // Primero los que empiezan por lo buscado, luego los que lo tienen en el nombre y al final los que solo lo tienen en la categoría.
        val ordenados = mapa.values.sortedBy { h ->
            val n = h.e.normNombre
            if (n.startsWith(terminos[0])) 0 else if (terminos.all { n.contains(it) }) 1 else 2
        }
        hallazgos = ordenados.take(500)
        infoBusqueda.text = when {
            ordenados.isEmpty() -> "Sin resultados para «" + texto + "» en " + listas.size + " lista(s)"
            ordenados.size > 500 -> "Mostrando 500 de " + ordenados.size + " canales: añade más palabras para afinar"
            else -> ordenados.size.toString() + (if (ordenados.size == 1) " canal" else " canales") + " en " + listas.size + " lista(s)"
        }
        resultados.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = hallazgos.size
            override fun getItem(position: Int): Any = hallazgos[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup?): android.view.View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val h = hallazgos[position]
                v.findViewById<TextView>(android.R.id.text1).text = h.e.canal.etiqueta + (if (h.e.canal.drm) "  🔒" else "")
                val donde = (if (h.e.lista.esFavoritos) "★ " else "") + h.e.lista.nombre +
                    (if (h.otras.isNotEmpty()) "  (+" + h.otras.size + " más)" else "")
                v.findViewById<TextView>(android.R.id.text2).text = h.e.canal.grupo + "  ·  " + donde
                return v
            }
        }
    }

    /** Abre la lista donde está el canal y arranca directamente en él (⏮ ⏭ y el panel de canales siguen funcionando). */
    private fun abrirCanal(h: Hallazgo) {
        val i = Intent(this, PlayerActivity::class.java)
        i.action = Intent.ACTION_VIEW
        i.data = Uri.parse(Biblioteca.uriDe(h.e.lista.id))
        i.putExtra("title", h.e.lista.nombre)
        i.putExtra("canal_url", h.e.canal.url)
        startActivity(i)
    }

    private fun menuCanal(h: Hallazgo) {
        val et = ArrayList<String>()
        val ac = ArrayList<() -> Unit>()
        et.add("Reproducir"); ac.add { abrirCanal(h) }
        if (!h.e.lista.esFavoritos) {
            et.add("★ Añadir a Favoritos")
            ac.add {
                val appCtx = applicationContext
                Thread { Biblioteca.marcarFavorito(appCtx, h.e.canal, true, h.e.urlGuia) }.start()
                Toast.makeText(this, "Añadido a Favoritos", Toast.LENGTH_SHORT).show()
            }
        }
        et.add("Abrir la lista «" + h.e.lista.nombre + "» entera"); ac.add { abrir(h.e.lista) }
        AlertDialog.Builder(this).setTitle(h.e.canal.etiqueta).setItems(et.toTypedArray()) { _, i -> ac[i]() }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Con una búsqueda en curso, «atrás» la borra y vuelve a la lista de listas.
        if (buscador.text.isNotEmpty()) {
            buscador.setText("")
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun abrir(l: ListaGuardada) {
        if (l.entradas == 0 && l.esEditable) {
            // Una lista propia vacía no tiene nada que reproducir: se abre el gestor para llenarla.
            gestionar(l)
            return
        }
        val i = Intent(this, PlayerActivity::class.java)
        i.action = Intent.ACTION_VIEW
        i.data = Uri.parse(Biblioteca.uriDe(l.id))
        i.putExtra("title", l.nombre)
        startActivity(i)
    }

    private fun menu(l: ListaGuardada) {
        val et = ArrayList<String>()
        val ac = ArrayList<() -> Unit>()
        et.add("Reproducir"); ac.add { abrir(l) }
        et.add("Gestionar canales y categorías"); ac.add { gestionar(l) }
        if (l.tipo == Biblioteca.TIPO_DESCARGADA && l.origen.startsWith("http", true)) {
            et.add("Actualizar desde su dirección"); ac.add { actualizar(l) }
        }
        if (!l.esFavoritos) {
            et.add("Renombrar"); ac.add { renombrar(l) }
            val pos = listas.indexOfFirst { it.id == l.id }
            if (pos > 1) { et.add("Subir"); ac.add { Biblioteca.mover(this, l.id, -1); cargar() } }
            if (pos in 1 until listas.size - 1) { et.add("Bajar"); ac.add { Biblioteca.mover(this, l.id, 1); cargar() } }
        }
        if (l.entradas > 0) {
            et.add("Guardar copia en " + Exportar.destinoTexto() + "/Listas"); ac.add { exportar(l) }
        }
        if (!l.esFavoritos) {
            et.add("Borrar"); ac.add { borrar(l) }
        }
        AlertDialog.Builder(this).setTitle(l.nombre).setItems(et.toTypedArray()) { _, i -> ac[i]() }.show()
    }

    private fun gestionar(l: ListaGuardada) {
        firmaIndice = "" // el editor puede cambiar canales sin cambiar el recuento: se vuelve a indexar al volver
        val i = Intent(this, EditorListaActivity::class.java)
        i.putExtra("id", l.id)
        startActivity(i)
    }

    private fun nuevaPropia() {
        val e = EditText(this)
        e.hint = "Nombre de la lista"
        e.setSingleLine(true)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val caja = LinearLayout(this)
        caja.setPadding(dp(20), dp(8), dp(20), 0)
        caja.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Nueva lista propia")
            .setView(caja)
            .setPositiveButton("Crear") { _, _ ->
                val n = Biblioteca.limpiarNombre(e.text.toString()).ifEmpty { "Mi lista" }
                Thread {
                    val nueva = Biblioteca.crearPropia(this, n)
                    runOnUiThread {
                        cargar()
                        if (nueva == null) Toast.makeText(this, "No se pudo crear la lista", Toast.LENGTH_SHORT).show()
                        else gestionar(nueva)
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dialogoUrl() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), 0)
        val url = EditText(this)
        url.hint = "https://…/lista.m3u8"
        url.setSingleLine(true)
        url.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        col.addView(url)
        val nombre = EditText(this)
        nombre.hint = "Nombre (opcional)"
        nombre.setSingleLine(true)
        col.addView(nombre)
        AlertDialog.Builder(this)
            .setTitle("Descargar y guardar una lista")
            .setView(col)
            .setPositiveButton("Descargar") { _, _ ->
                val u = Utiles.primerEnlace(url.text.toString().trim()) ?: url.text.toString().trim()
                if (u.startsWith("http", true)) descargar(u, nombre.text.toString().trim())
                else Toast.makeText(this, "Pon una dirección http o https", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun descargar(url: String, nombre: String) {
        Toast.makeText(this, "Descargando…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val txt = Biblioteca.descargar(url)
                val nom = nombre.ifEmpty { url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifEmpty { "Lista" } }
                val r = Biblioteca.agregar(this, nom, url, txt)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (r == null) "No se pudo guardar" else if (r.second) "Lista guardada" else "Ya la tenías: actualizada",
                        Toast.LENGTH_SHORT
                    ).show()
                    cargar()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "No se pudo descargar: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun actualizar(l: ListaGuardada) {
        Toast.makeText(this, "Actualizando…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                Biblioteca.actualizar(this, l)
                runOnUiThread {
                    Toast.makeText(this, "Lista actualizada", Toast.LENGTH_SHORT).show()
                    cargar()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "No se pudo actualizar: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun renombrar(l: ListaGuardada) {
        val e = EditText(this)
        e.setText(l.nombre)
        e.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("Renombrar")
            .setView(e)
            .setPositiveButton("Guardar") { _, _ ->
                val n = e.text.toString().trim()
                if (n.isNotEmpty()) {
                    Biblioteca.renombrar(this, l.id, n)
                    cargar()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportar(l: ListaGuardada) {
        Thread {
            val err = Biblioteca.exportar(this, l)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (err == null) "Guardada en " + Exportar.destinoTexto() + "/Listas" else "No se pudo: $err",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun borrar(l: ListaGuardada) {
        AlertDialog.Builder(this)
            .setMessage("¿Borrar «" + l.nombre + "» de la app?" + (if (l.tipo == Biblioteca.TIPO_PROPIA) " Es una lista propia: no se puede recuperar." else ""))
            .setPositiveButton("Borrar") { _, _ ->
                Biblioteca.borrar(this, l.id)
                cargar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- importar archivos

    private fun elegirArchivos() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/octet-stream", "text/plain"))
        Gestor.lanzar(this, i, PEDIR_ARCHIVOS)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        if (codigo != PEDIR_ARCHIVOS || resultado != RESULT_OK || datos == null) return
        val uris = ArrayList<Uri>()
        val clip = datos.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
        } else {
            datos.data?.let { uris.add(it) }
        }
        Thread {
            var guardadas = 0
            val locales = ArrayList<String>()
            val fallos = ArrayList<String>()
            for (u in uris) {
                val nombre = Utiles.nombreDe(this, u.toString())
                try {
                    val datosB = contentResolver.openInputStream(u)?.use { Red.leerBytes(it, 6_000_000) } ?: throw java.io.IOException("ilegible")
                    val txt = ListaM3u.texto(datosB)
                    if (ListaM3u.esLocal(txt)) {
                        locales.add(nombre)
                    } else if (Biblioteca.agregar(this, Utiles.sinExtension(nombre), "", txt) != null) {
                        guardadas++
                    } else {
                        fallos.add(nombre)
                    }
                } catch (e: Exception) {
                    fallos.add(nombre)
                }
            }
            runOnUiThread {
                cargar()
                var msg = "Guardadas: $guardadas"
                if (locales.isNotEmpty()) {
                    msg += "\n\nEstas son listas de archivos de una carpeta (rutas locales) y no se guardan aquí; " +
                        "ábrelas desde su carpeta con «Carpetas» o «Abrir archivo»:\n" + locales.joinToString("\n")
                }
                if (fallos.isNotEmpty()) msg += "\n\nNo se pudieron leer:\n" + fallos.joinToString("\n")
                if (locales.isNotEmpty() || fallos.isNotEmpty()) AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Vale", null).show()
                else Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
