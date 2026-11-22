package com.optimo.play

import android.app.Activity
import android.graphics.Color
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Panel lateral con los canales de una lista: agrupados, con buscador, filtro de favoritos (★), logo y, si hay guía
 * de programación, lo que echan «ahora». Usa un ListView para que funcione igual con 30 que con 3000 canales.
 */
class PanelCanales(
    private val act: Activity,
    private val alElegir: (Int) -> Unit,
    private val alFavorito: (Canal) -> Unit,
    private val alGuia: () -> Unit,
    private val alCerrar: () -> Unit
) {

    private class Item(val cabecera: String?, val canal: Canal?, val indice: Int, val n: Int = 0)

    private class Fila(val raiz: LinearLayout, val logo: ImageView, val nombre: TextView, val prog: TextView, val estrella: TextView)

    val vista = LinearLayout(act)
    private val buscador = EditText(act)
    private val lista = ListView(act)
    private lateinit var btnFav: TextView

    private var todos: List<Canal> = emptyList()
    private var claves: List<String> = emptyList()
    private var favoritos: Set<String> = emptySet()
    private var guia: Guia? = null
    private var actual = -1
    private var soloFav = false
    private val colapsados = HashSet<String>()
    private var items: List<Item> = emptyList()
    private var firma = ""

    private fun px(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    private fun texto(t: String, tam: Float): TextView {
        val tv = TextView(act)
        tv.text = t
        tv.textSize = tam
        tv.setTextColor(Color.WHITE)
        return tv
    }

    private fun mini(t: String, alPulsar: () -> Unit): TextView {
        val tv = texto(t, 14f)
        tv.gravity = Gravity.CENTER
        tv.minWidth = px(44)
        tv.minHeight = px(40)
        tv.setBackgroundColor(0x33FFFFFF)
        tv.setPadding(px(8), px(4), px(8), px(4))
        tv.setOnClickListener { alPulsar() }
        return tv
    }

    private val adaptador = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (items[position].canal == null) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val it = items[position]
            val c = it.canal
            if (c == null) {
                val tv = (convertView as? TextView) ?: texto("", 13f).also { t ->
                    t.setBackgroundColor(0x33FFFFFF)
                    t.setPadding(px(12), px(6), px(12), px(6))
                    t.maxLines = 1
                    t.ellipsize = TextUtils.TruncateAt.END
                }
                val cab = it.cabecera ?: ""
                tv.text = (if (colapsados.contains(cab)) "▶ " else "▼ ") + "📁 " + cab + "  (" + it.n + ")"
                return tv
            }
            val f = (convertView?.tag as? Fila) ?: crearFila()
            f.nombre.text = c.etiqueta + (if (c.drm) "  🔒" else "")
            f.nombre.setTextColor(if (it.indice == actual) 0xFF4FC3F7.toInt() else Color.WHITE)
            val g = guia
            val p = if (g != null) g.ahora(c) else null
            if (p != null) {
                f.prog.text = "Ahora: " + p.titulo + "  (" + Utiles.hora(p.inicio) + "–" + Utiles.hora(p.fin) + ")"
                f.prog.visibility = View.VISIBLE
            } else {
                f.prog.visibility = View.GONE
            }
            f.estrella.text = if (favoritos.contains(c.clave)) "★" else "☆"
            f.estrella.setOnClickListener { alFavorito(c) }
            Logos.cargar(c.logo, f.logo)
            return f.raiz
        }

        private fun crearFila(): Fila {
            val raiz = LinearLayout(act)
            raiz.orientation = LinearLayout.HORIZONTAL
            raiz.gravity = Gravity.CENTER_VERTICAL
            // Margen a la derecha: en las listas largas el desplazador rápido de Android se queda con los toques de ese borde
            // (aunque no se vea), así que ahí no puede haber nada que pulsar. Por eso la ★ va a la izquierda.
            raiz.setPadding(px(0), px(4), px(28), px(4))
            val estrella = texto("☆", 22f)
            estrella.setTextColor(0xFFFFC107.toInt())
            estrella.gravity = Gravity.CENTER
            estrella.minWidth = px(48)
            estrella.minHeight = px(48)
            raiz.addView(estrella)
            val logo = ImageView(act)
            logo.scaleType = ImageView.ScaleType.FIT_CENTER
            raiz.addView(logo, LinearLayout.LayoutParams(px(64), px(28)))
            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            val nombre = texto("", 14f)
            nombre.maxLines = 1
            nombre.ellipsize = TextUtils.TruncateAt.END
            val prog = texto("", 11f)
            prog.setTextColor(0xFFAAAAAA.toInt())
            prog.maxLines = 1
            prog.ellipsize = TextUtils.TruncateAt.END
            col.addView(nombre)
            col.addView(prog)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = px(8)
            raiz.addView(col, lp)
            val f = Fila(raiz, logo, nombre, prog, estrella)
            raiz.tag = f
            return f
        }
    }

    init {
        vista.orientation = LinearLayout.VERTICAL
        vista.setBackgroundColor(0xEE111111.toInt())
        vista.isClickable = true // se queda los toques: no deben llegar a los gestos del vídeo
        vista.setPadding(px(8), px(8), px(40), px(8))

        val cab = LinearLayout(act)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        val tit = texto("Canales", 16f)
        tit.setPadding(px(8), px(4), px(8), px(4))
        cab.addView(tit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cab.addView(mini("⇕") {
            val gs = todos.map { it.grupo }.toSet()
            if (colapsados.containsAll(gs)) colapsados.clear() else colapsados.addAll(gs)
            filtrar()
        })
        btnFav = mini("☆") {
            soloFav = !soloFav
            btnFav.text = if (soloFav) "★" else "☆"
            filtrar()
        }
        cab.addView(btnFav)
        cab.addView(mini("Guía") { alGuia() })
        cab.addView(mini("✕") { alCerrar() })
        vista.addView(cab)

        buscador.hint = "Buscar canal o grupo…"
        buscador.setHintTextColor(0xFF888888.toInt())
        buscador.setTextColor(Color.WHITE)
        buscador.textSize = 15f
        buscador.isSingleLine = true
        buscador.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filtrar() }
        })
        vista.addView(buscador, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lista.adapter = adaptador
        lista.divider = null
        lista.isFastScrollEnabled = true
        // Pulsación larga sobre un canal: alterna su ★ (otra forma de marcar favoritos, lejos del desplazador).
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            val c = if (pos in items.indices) items[pos].canal else null
            if (c != null) {
                alFavorito(c)
                true
            } else {
                false
            }
        }
        lista.setOnItemClickListener { _, _, pos, _ ->
            if (pos in items.indices) {
                val it = items[pos]
                if (it.canal != null) alElegir(it.indice)
                else if (it.cabecera != null) {
                    // Pulsar una categoría la pliega o la despliega.
                    val g: String = it.cabecera
                    if (!colapsados.remove(g)) colapsados.add(g)
                    filtrar()
                }
            }
        }
        vista.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Carga la lista (al abrir el panel). */
    fun cargar(canales: List<Canal>, favs: Set<String>, g: Guia?, indiceActual: Int) {
        todos = canales
        claves = canales.map { Guia.norm(it.etiqueta + " " + it.grupo) }
        favoritos = favs
        guia = g
        actual = indiceActual
        firma = ""
        if (indiceActual >= 0) todos.getOrNull(indiceActual)?.let { colapsados.remove(it.grupo) }
        filtrar()
        if (indiceActual >= 0) {
            val pos = items.indexOfFirst { it.canal != null && it.indice == indiceActual }
            if (pos >= 0) lista.setSelection(pos)
        }
    }

    /** Refresco periódico: solo repinta si algo ha cambiado (canal, guía, favoritos o minuto). */
    fun actualizar(favs: Set<String>, g: Guia?, indiceActual: Int) {
        val f = favs.hashCode().toString() + "|" + System.identityHashCode(g) + "|" + indiceActual + "|" +
            (System.currentTimeMillis() / 60_000L)
        if (f == firma) return
        firma = f
        favoritos = favs
        guia = g
        actual = indiceActual
        if (soloFav) filtrar() else adaptador.notifyDataSetChanged()
    }

    private fun filtrar() {
        val q = Guia.norm(buscador.text.toString())
        val varios = todos.map { it.grupo }.distinct().size > 1
        val validos = ArrayList<Int>()
        val cuenta = HashMap<String, Int>()
        for ((i, c) in todos.withIndex()) {
            if (soloFav && !favoritos.contains(c.clave)) continue
            if (q.isNotEmpty() && !claves[i].contains(q)) continue
            validos.add(i)
            cuenta[c.grupo] = (cuenta[c.grupo] ?: 0) + 1
        }
        val res = ArrayList<Item>()
        var previo: String? = null
        for (i in validos) {
            val c = todos[i]
            if (varios && c.grupo != previo) {
                res.add(Item(c.grupo, null, -1, cuenta[c.grupo] ?: 0))
                previo = c.grupo
            }
            // Con una búsqueda activa se enseñan los resultados aunque su categoría esté plegada.
            if (varios && q.isEmpty() && colapsados.contains(c.grupo)) continue
            res.add(Item(null, c, i))
        }
        items = res
        adaptador.notifyDataSetChanged()
    }
}
