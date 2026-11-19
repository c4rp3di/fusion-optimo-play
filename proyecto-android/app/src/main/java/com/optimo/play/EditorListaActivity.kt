package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Gestor de una lista: sus canales agrupados por categorías que se pliegan al pulsarlas.
 *
 * En «Favoritos» y en las listas propias las categorías y los canales se editan (crear, renombrar, mover, borrar,
 * cambiar de categoría). Las listas descargadas o importadas se ven con sus categorías originales y no se modifican,
 * pero desde ellas se pueden copiar canales o categorías enteras a Favoritos o a una lista propia.
 */
class EditorListaActivity : Activity() {

    private class Fila(val grupo: String, val canal: Canal?, val n: Int)

    private var id = ""
    private var info: ListaGuardada? = null
    private var datos = ContenidoLista(ArrayList(), ArrayList())
    private val plegados = HashSet<String>()
    private var filas: List<Fila> = emptyList()

    private lateinit var aj: Ajustes
    private lateinit var titulo: TextView
    private lateinit var vacio: TextView
    private lateinit var lista: ListView
    private lateinit var bCategoria: Button
    private lateinit var bGuia: Button

    private val editable: Boolean get() = info?.esEditable == true
    private val esFav: Boolean get() = id == Biblioteca.ID_FAV

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        id = intent.getStringExtra("id") ?: ""
        if (id.isEmpty()) {
            finish()
            return
        }
        aj = Ajustes(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(16), dp(28), dp(16), dp(8))

        titulo = TextView(this)
        titulo.textSize = 22f
        raiz.addView(titulo)

        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        val bVer = Button(this)
        bVer.text = "▶ Reproducir"
        bVer.setOnClickListener { reproducir() }
        fila.addView(bVer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bCategoria = Button(this)
        bCategoria.text = "＋ Categoría"
        bCategoria.setOnClickListener { nuevaCategoria() }
        fila.addView(bCategoria, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bPlegar = Button(this)
        bPlegar.text = "⇕ Plegar"
        bPlegar.setOnClickListener {
            if (plegados.containsAll(datos.grupos)) plegados.clear() else plegados.addAll(datos.grupos)
            refrescar()
        }
        fila.addView(bPlegar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bGuia = Button(this)
        bGuia.text = "Guía"
        bGuia.setOnClickListener { dialogoGuia() }
        fila.addView(bGuia, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(fila)

        vacio = TextView(this)
        vacio.alpha = 0.7f
        vacio.setPadding(0, dp(12), 0, dp(8))
        raiz.addView(vacio)

        lista = ListView(this)
        lista.adapter = adaptador
        lista.setOnItemClickListener { _, _, pos, _ ->
            val f = filas.getOrNull(pos) ?: return@setOnItemClickListener
            if (f.canal == null) {
                if (!plegados.remove(f.grupo)) plegados.add(f.grupo)
                refrescar()
            } else {
                menuCanal(f.canal)
            }
        }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            val f = filas.getOrNull(pos)
            if (f != null) {
                if (f.canal == null) menuCategoria(f.grupo) else menuCanal(f.canal)
            }
            true
        }
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        if (id.isNotEmpty()) cargar()
    }

    private val adaptador = object : BaseAdapter() {
        override fun getCount(): Int = filas.size
        override fun getItem(position: Int): Any = filas[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (filas[position].canal == null) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val f = filas[position]
            val tv = (convertView as? TextView) ?: TextView(this@EditorListaActivity).also { t ->
                t.maxLines = 1
                t.ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val c = f.canal
            if (c == null) {
                tv.textSize = 16f
                tv.setTypeface(null, Typeface.BOLD)
                tv.setBackgroundColor(0x22FFFFFF)
                tv.setPadding(dp(12), dp(12), dp(12), dp(12))
                tv.text = (if (plegados.contains(f.grupo)) "▶ " else "▼ ") + "📁 " + f.grupo + "  (" + f.n + ")"
            } else {
                tv.textSize = 15f
                tv.setTypeface(null, Typeface.NORMAL)
                tv.setBackgroundColor(Color.TRANSPARENT)
                tv.setPadding(dp(32), dp(10), dp(12), dp(10))
                tv.text = c.etiqueta + (if (c.drm) "  🔒" else "")
            }
            return tv
        }
    }

    // ---------------------------------------------------------------- carga y pintado

    private fun cargar() {
        Thread {
            val inf = Biblioteca.todas(this).firstOrNull { it.id == id }
            val k = Biblioteca.contenido(this, id)
            runOnUiThread {
                info = inf
                datos = k
                refrescar()
            }
        }.start()
    }

    private fun refrescar() {
        val inf = info
        val icono = when {
            inf == null -> ""
            inf.esFavoritos -> "★ "
            inf.tipo == Biblioteca.TIPO_PROPIA -> "📝 "
            else -> ""
        }
        titulo.text = icono + (inf?.nombre ?: "Lista")
        bCategoria.visibility = if (editable) View.VISIBLE else View.GONE
        bGuia.visibility = if (editable) View.VISIBLE else View.GONE
        val porGrupo = datos.canales.groupBy { it.grupo }
        val res = ArrayList<Fila>()
        for (g in datos.grupos) {
            val cs = porGrupo[g] ?: emptyList()
            res.add(Fila(g, null, cs.size))
            if (!plegados.contains(g)) for (c in cs) res.add(Fila(g, c, 0))
        }
        filas = res
        vacio.text = when {
            datos.grupos.isEmpty() && esFav -> "Aún no tienes favoritos. Márcalos con ☆ en el panel de canales y aparecerán aquí; también puedes copiar canales desde otras listas."
            datos.grupos.isEmpty() && editable -> "Lista vacía. Crea categorías con «＋ Categoría» y copia canales a ella desde otras listas (mantén pulsado un canal o una categoría)."
            datos.grupos.isEmpty() -> "Esta lista no tiene canales."
            editable -> "Toca una categoría para plegarla o desplegarla. Mantén pulsada una categoría o un canal para editarlo."
            else -> "Toca una categoría para plegarla o desplegarla. Mantén pulsada una categoría o un canal para copiarlo a Favoritos o a una lista propia."
        }
        adaptador.notifyDataSetChanged()
    }

    /** Guarda en segundo plano una copia de lo editado; la pantalla ya está al día. */
    private fun guardar() {
        refrescar()
        val copia = ContenidoLista(ArrayList(datos.grupos), ArrayList(datos.canales), datos.urlGuia)
        val idLista = id
        val appCtx = applicationContext
        Thread { Biblioteca.guardarContenido(appCtx, idLista, copia) }.start()
    }

    /** Guía de esta lista: la puesta a mano o, si no hay, la guardada en la propia lista. */
    private fun guiaEfectiva(): String? {
        val manual = aj.guiaDe(Biblioteca.uriDe(id))
        return if (manual.isNotEmpty()) manual else datos.urlGuia
    }

    private fun dialogoGuia() {
        pedirNombre("Dirección de la guía (XMLTV)", guiaEfectiva() ?: "") { u ->
            datos.urlGuia = if (u.startsWith("http", true)) u else null
            aj.fijarGuia(Biblioteca.uriDe(id), "") // manda la de la lista, no una puesta a mano antes
            if (u.startsWith("http", true)) guardar()
            else Toast.makeText(this, "Pon una dirección http o https", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reproducir() {
        if (datos.canales.isEmpty()) {
            Toast.makeText(this, "Esta lista aún no tiene canales", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, PlayerActivity::class.java)
        i.action = Intent.ACTION_VIEW
        i.data = Uri.parse(Biblioteca.uriDe(id))
        i.putExtra("title", info?.nombre ?: "Lista")
        startActivity(i)
    }

    private fun pedirNombre(titulo: String, inicial: String, alAceptar: (String) -> Unit) {
        val e = EditText(this)
        e.setText(inicial)
        e.setSelection(e.text.length)
        e.setSingleLine(true)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val caja = LinearLayout(this)
        caja.setPadding(dp(20), dp(8), dp(20), 0)
        caja.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(caja)
            .setPositiveButton("Aceptar") { _, _ ->
                val n = Biblioteca.limpiarNombre(e.text.toString())
                if (n.isNotEmpty()) alAceptar(n)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Si la lista es Favoritos, lo que sale de ella deja de estar marcado con ★. */
    private fun olvidarFavoritos(cs: List<Canal>) {
        if (esFav && cs.isNotEmpty()) aj.quitarFavoritos(cs.map { it.clave })
    }

    // ---------------------------------------------------------------- categorías

    private fun nuevaCategoria() {
        pedirNombre("Nueva categoría", "") { n ->
            if (datos.grupos.contains(n)) {
                Toast.makeText(this, "Ya existe esa categoría", Toast.LENGTH_SHORT).show()
            } else {
                datos.grupos.add(n)
                guardar()
            }
        }
    }

    private fun menuCategoria(g: String) {
        val et = ArrayList<String>()
        val ac = ArrayList<() -> Unit>()
        val cs = datos.canales.filter { it.grupo == g }
        if (editable) {
            et.add("Renombrar categoría"); ac.add { renombrarCategoria(g) }
            et.add("Subir"); ac.add { moverCategoria(g, -1) }
            et.add("Bajar"); ac.add { moverCategoria(g, 1) }
        }
        if (cs.isNotEmpty()) {
            et.add("Copiar sus " + cs.size + " canales a otra lista…"); ac.add { copiarA(cs, g) }
        }
        if (editable) {
            et.add("Borrar categoría"); ac.add { borrarCategoria(g) }
        }
        if (et.isEmpty()) return
        AlertDialog.Builder(this).setTitle(g).setItems(et.toTypedArray()) { _, i -> ac[i]() }.show()
    }

    private fun renombrarCategoria(g: String) {
        pedirNombre("Renombrar categoría", g) { n ->
            if (n == g) return@pedirNombre
            if (datos.grupos.contains(n)) {
                Toast.makeText(this, "Ya existe una categoría con ese nombre", Toast.LENGTH_SHORT).show()
                return@pedirNombre
            }
            val i = datos.grupos.indexOf(g)
            if (i >= 0) datos.grupos[i] = n
            for (k in datos.canales.indices) {
                if (datos.canales[k].grupo == g) datos.canales[k] = datos.canales[k].copy(grupo = n)
            }
            if (plegados.remove(g)) plegados.add(n)
            guardar()
        }
    }

    private fun moverCategoria(g: String, delta: Int) {
        val i = datos.grupos.indexOf(g)
        val j = i + delta
        if (i < 0 || j < 0 || j >= datos.grupos.size) return
        val x = datos.grupos.removeAt(i)
        datos.grupos.add(j, x)
        guardar()
    }

    private fun borrarCategoria(g: String) {
        val cs = datos.canales.filter { it.grupo == g }
        if (cs.isEmpty()) {
            datos.grupos.remove(g)
            plegados.remove(g)
            guardar()
            return
        }
        val et = ArrayList<String>()
        val ac = ArrayList<() -> Unit>()
        if (g != Biblioteca.GRUPO_DEFECTO) {
            et.add("Mover sus " + cs.size + " canales a «" + Biblioteca.GRUPO_DEFECTO + "»")
            ac.add {
                if (!datos.grupos.contains(Biblioteca.GRUPO_DEFECTO)) datos.grupos.add(Biblioteca.GRUPO_DEFECTO)
                for (k in datos.canales.indices) {
                    if (datos.canales[k].grupo == g) datos.canales[k] = datos.canales[k].copy(grupo = Biblioteca.GRUPO_DEFECTO)
                }
                datos.grupos.remove(g)
                plegados.remove(g)
                guardar()
            }
        }
        et.add("Borrar también sus " + cs.size + " canales")
        ac.add {
            datos.canales.removeAll { it.grupo == g }
            datos.grupos.remove(g)
            plegados.remove(g)
            olvidarFavoritos(cs)
            guardar()
        }
        AlertDialog.Builder(this)
            .setTitle("Borrar «$g»")
            .setItems(et.toTypedArray()) { _, i -> ac[i]() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- canales

    private fun menuCanal(c: Canal) {
        val et = ArrayList<String>()
        val ac = ArrayList<() -> Unit>()
        if (editable) {
            et.add("Mover a otra categoría…"); ac.add { moverCanal(c) }
        }
        et.add("Copiar a otra lista…"); ac.add { copiarA(listOf(c), c.grupo) }
        if (editable) {
            et.add("Quitar de esta lista"); ac.add { quitarCanal(c) }
        }
        AlertDialog.Builder(this).setTitle(c.etiqueta).setItems(et.toTypedArray()) { _, i -> ac[i]() }.show()
    }

    private fun indiceDe(c: Canal): Int = datos.canales.indexOfFirst { it.clave == c.clave && it.url == c.url }

    private fun ponerCategoria(c: Canal, g: String) {
        if (!datos.grupos.contains(g)) datos.grupos.add(g)
        val i = indiceDe(c)
        if (i >= 0) datos.canales[i] = datos.canales[i].copy(grupo = g)
        guardar()
    }

    private fun moverCanal(c: Canal) {
        val otras = datos.grupos.filter { it != c.grupo }
        val et = ArrayList<String>(otras)
        et.add("＋ Nueva categoría…")
        AlertDialog.Builder(this)
            .setTitle("Mover a…")
            .setItems(et.toTypedArray()) { _, w ->
                if (w < otras.size) ponerCategoria(c, otras[w])
                else pedirNombre("Nueva categoría", "") { n -> ponerCategoria(c, n) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun quitarCanal(c: Canal) {
        val i = indiceDe(c)
        if (i >= 0) datos.canales.removeAt(i)
        olvidarFavoritos(listOf(c))
        guardar()
    }

    // ---------------------------------------------------------------- copiar a otras listas

    /** Elige una lista de destino (Favoritos, una propia o una nueva) y luego la categoría donde caen los canales. */
    private fun copiarA(cs: List<Canal>, grupoOrigen: String) {
        val destinos = Biblioteca.todas(this).filter { it.esEditable && it.id != id }
        val et = ArrayList<String>()
        for (d in destinos) et.add((if (d.esFavoritos) "★ " else "📝 ") + d.nombre)
        et.add("＋ Nueva lista propia…")
        AlertDialog.Builder(this)
            .setTitle("Copiar " + cs.size + (if (cs.size == 1) " canal a…" else " canales a…"))
            .setItems(et.toTypedArray()) { _, w ->
                if (w < destinos.size) {
                    elegirCategoriaDestino(destinos[w], cs, grupoOrigen)
                } else {
                    pedirNombre("Nombre de la lista", "") { n ->
                        Thread {
                            val nueva = Biblioteca.crearPropia(this, n)
                            runOnUiThread {
                                if (nueva == null) Toast.makeText(this, "No se pudo crear la lista", Toast.LENGTH_SHORT).show()
                                else elegirCategoriaDestino(nueva, cs, grupoOrigen)
                            }
                        }.start()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun elegirCategoriaDestino(dest: ListaGuardada, cs: List<Canal>, grupoOrigen: String) {
        Thread {
            val k = Biblioteca.contenido(this, dest.id)
            runOnUiThread {
                val et = ArrayList<String>()
                et.add("Misma categoría («" + grupoOrigen + "»)")
                for (g in k.grupos) et.add("📁 " + g)
                et.add("＋ Nueva categoría…")
                AlertDialog.Builder(this)
                    .setTitle("Categoría en «" + dest.nombre + "»")
                    .setItems(et.toTypedArray()) { _, w ->
                        when {
                            w == 0 -> hacerCopia(dest, cs, null)
                            w <= k.grupos.size -> hacerCopia(dest, cs, k.grupos[w - 1])
                            else -> pedirNombre("Nueva categoría", "") { n -> hacerCopia(dest, cs, n) }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }.start()
    }

    private fun hacerCopia(dest: ListaGuardada, cs: List<Canal>, grupo: String?) {
        Thread {
            val n = Biblioteca.anadir(this, dest.id, cs, grupo, guiaEfectiva())
            runOnUiThread {
                if (dest.esFavoritos) aj.agregarFavoritos(cs.map { it.clave })
                val ya = cs.size - n
                Toast.makeText(
                    this,
                    n.toString() + (if (n == 1) " canal añadido" else " canales añadidos") + " a «" + dest.nombre + "»" +
                        (if (ya > 0) " ($ya ya estaban)" else ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}
