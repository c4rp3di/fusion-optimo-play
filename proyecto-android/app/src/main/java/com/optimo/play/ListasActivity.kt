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

        val fila = LinearLayout(this)
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
