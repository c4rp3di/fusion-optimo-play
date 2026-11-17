package com.optimo.play

import android.app.Activity
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Panel lateral con la cola de reproducción (archivos locales): pista que suena marcada, toque = ir a esa pista,
 * pulsación larga = menú de la pista, y botones de aleatorio, repetir y vaciar.
 */
class PanelCola(
    private val act: Activity,
    private val alElegir: (Int) -> Unit,
    private val alMenu: (Int) -> Unit,
    private val alAleatorio: () -> Unit,
    private val alRepetir: () -> Unit,
    private val alVaciar: () -> Unit,
    private val alCerrar: () -> Unit
) {

    val vista = LinearLayout(act)
    private val lista = ListView(act)
    private lateinit var titulo: TextView
    private lateinit var btnAzar: TextView
    private lateinit var btnRep: TextView

    private var pistas: List<Pista> = emptyList()
    private var actual = -1
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
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = px(4)
        tv.layoutParams = lp
        return tv
    }

    private val adaptador = object : BaseAdapter() {
        override fun getCount(): Int = pistas.size
        override fun getItem(position: Int): Any = pistas[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: texto("", 14f).also { t ->
                t.setPadding(px(12), px(10), px(8), px(10))
                t.maxLines = 2
                t.ellipsize = TextUtils.TruncateAt.END
            }
            val suena = position == actual
            tv.text = (if (suena) "▶ " else "") + (position + 1) + "  " + pistas[position].titulo
            tv.setTextColor(if (suena) 0xFF4FC3F7.toInt() else Color.WHITE)
            return tv
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
        titulo = texto("Cola", 16f)
        titulo.setPadding(px(8), px(4), px(8), px(4))
        titulo.maxLines = 1
        titulo.ellipsize = TextUtils.TruncateAt.END
        cab.addView(titulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnAzar = mini("🔀") { alAleatorio() }
        cab.addView(btnAzar)
        btnRep = mini("🔁") { alRepetir() }
        cab.addView(btnRep)
        cab.addView(mini("Vaciar") { alVaciar() })
        cab.addView(mini("✕") { alCerrar() })
        vista.addView(cab)

        lista.adapter = adaptador
        lista.divider = null
        lista.isFastScrollEnabled = true
        lista.setOnItemClickListener { _, _, pos, _ -> if (pos in pistas.indices) alElegir(pos) }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in pistas.indices) alMenu(pos)
            true
        }
        vista.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Carga la cola al abrir el panel y deja a la vista la pista que suena. */
    fun cargar(p: List<Pista>, indiceActual: Int, repetir: Int, aleatorio: Boolean, nombre: String) {
        firma = ""
        actualizar(p, indiceActual, repetir, aleatorio, nombre)
        if (indiceActual >= 0) lista.setSelection((indiceActual - 2).coerceAtLeast(0))
    }

    /** Refresco periódico: solo repinta si algo cambió. */
    fun actualizar(p: List<Pista>, indiceActual: Int, repetir: Int, aleatorio: Boolean, nombre: String) {
        val f = System.identityHashCode(p).toString() + "|" + indiceActual + "|" + repetir + "|" + aleatorio + "|" + nombre
        if (f == firma) return
        firma = f
        pistas = p
        actual = indiceActual
        titulo.text = (if (nombre.isEmpty()) "Cola" else nombre) + " · " + p.size
        btnAzar.text = if (aleatorio) "🔀 sí" else "🔀"
        btnAzar.setTextColor(if (aleatorio) 0xFF4FC3F7.toInt() else Color.WHITE)
        btnRep.text = when (repetir) {
            1 -> "🔁 todo"
            2 -> "🔂 una"
            else -> "🔁"
        }
        btnRep.setTextColor(if (repetir != 0) 0xFF4FC3F7.toInt() else Color.WHITE)
        adaptador.notifyDataSetChanged()
    }
}
