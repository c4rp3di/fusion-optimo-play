package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView

/** Diálogos de subtítulos reutilizables: estilo (tamaño, color, fondo, contorno…) y desfase. */
object DialogoSubs {

    private val COLORES = listOf(
        "Blanco" to 0xFFFFFF,
        "Amarillo" to 0xFFFF00,
        "Verde" to 0x66FF66,
        "Cian" to 0x00FFFF,
        "Naranja" to 0xFFA500,
        "Gris claro" to 0xCCCCCC
    )
    private val CONTORNOS = listOf("Ninguno" to 0, "Fino" to 2, "Normal" to 4, "Grueso" to 6)
    private val CODIFICACIONES = listOf(
        "Automática" to "",
        "UTF-8" to "UTF-8",
        "Windows-1252 (español, occidental)" to "Windows-1252",
        "ISO-8859-1" to "ISO-8859-1",
        "ISO-8859-15" to "ISO-8859-15",
        "Windows-1250 (centroeuropeo)" to "Windows-1250"
    )

    private fun dp(a: Activity, v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    private fun spinner(a: Activity, nombres: List<String>, sel: Int): Spinner {
        val s = Spinner(a)
        s.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, nombres)
        s.setSelection(sel.coerceAtLeast(0))
        return s
    }

    private fun seek(a: Activity, max: Int, valor: Int, alCambiar: (Int) -> Unit): SeekBar {
        val s = SeekBar(a)
        s.max = max
        s.progress = valor.coerceIn(0, max)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, deUsuario: Boolean) { alCambiar(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        return s
    }

    /** Panel de estilo. Al pulsar «Aplicar» se guardan los valores y se llama a `alAplicar` (que recrea el motor). */
    fun estilo(a: Activity, aj: Ajustes, alAplicar: () -> Unit) {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 4))

        fun etiqueta(t: String): TextView {
            val tv = TextView(a)
            tv.text = t
            tv.setPadding(0, dp(a, 14), 0, 0)
            col.addView(tv)
            return tv
        }

        var tam = aj.subTamano
        val tTam = etiqueta("Tamaño: $tam %")
        col.addView(seek(a, 200, tam - 50) { p -> tam = p + 50; tTam.text = "Tamaño: $tam %" })

        etiqueta("Color")
        val sColor = spinner(a, COLORES.map { it.first }, COLORES.indexOfFirst { it.second == aj.subColor })
        col.addView(sColor)

        var fondo = aj.subFondo
        val tFondo = etiqueta("Fondo detrás del texto: $fondo %")
        col.addView(seek(a, 100, fondo) { p -> fondo = p; tFondo.text = "Fondo detrás del texto: $fondo %" })

        etiqueta("Contorno")
        val sContorno = spinner(a, CONTORNOS.map { it.first }, CONTORNOS.indexOfFirst { it.second == aj.subContorno })
        col.addView(sContorno)

        val cNegrita = CheckBox(a)
        cNegrita.text = "Negrita"
        cNegrita.isChecked = aj.subNegrita
        col.addView(cNegrita)

        var elevar = aj.subElevar
        val tElevar = etiqueta("Subir desde el borde inferior: $elevar px")
        col.addView(seek(a, 300, elevar) { p -> elevar = p; tElevar.text = "Subir desde el borde inferior: $elevar px" })

        etiqueta("Codificación de subtítulos externos")
        val sCod = spinner(a, CODIFICACIONES.map { it.first }, CODIFICACIONES.indexOfFirst { it.second == aj.subCodificacion })
        col.addView(sCod)

        val nota = TextView(a)
        nota.text = "Los subtítulos ASS/SSA con estilo propio conservan su formato. Al aplicar, el motor se reinicia un instante y sigues donde ibas."
        nota.textSize = 12f
        nota.alpha = 0.7f
        nota.setPadding(0, dp(a, 16), 0, 0)
        col.addView(nota)

        val sv = ScrollView(a)
        sv.addView(col)
        AlertDialog.Builder(a)
            .setTitle("Estilo de subtítulos")
            .setView(sv)
            .setPositiveButton("Aplicar") { _, _ ->
                aj.subTamano = tam
                aj.subColor = COLORES[sColor.selectedItemPosition.coerceIn(0, COLORES.size - 1)].second
                aj.subFondo = fondo
                aj.subContorno = CONTORNOS[sContorno.selectedItemPosition.coerceIn(0, CONTORNOS.size - 1)].second
                aj.subNegrita = cNegrita.isChecked
                aj.subElevar = elevar
                aj.subCodificacion = CODIFICACIONES[sCod.selectedItemPosition.coerceIn(0, CODIFICACIONES.size - 1)].second
                alAplicar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Ajuste de desfase con botones. `actualMs` es el valor de partida; `alCambiar` recibe el nuevo valor en ms.
     * Positivo = más tarde (úsalo si el texto sale antes de lo que se dice).
     */
    fun desfase(a: Activity, titulo: String, actualMs: Long, alCambiar: (Long) -> Unit) {
        var valor = actualMs
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 4))

        val tv = TextView(a)
        tv.textSize = 28f
        tv.gravity = Gravity.CENTER
        fun pintar() { tv.text = (if (valor > 0) "+" else "") + valor + " ms" }
        pintar()
        col.addView(tv)

        fun fila(vararg pasos: Long): LinearLayout {
            val f = LinearLayout(a)
            f.orientation = LinearLayout.HORIZONTAL
            f.gravity = Gravity.CENTER
            for (p in pasos) {
                val b = Button(a)
                b.text = (if (p > 0) "+" else "") + p
                b.minWidth = 0
                b.minimumWidth = 0
                b.setOnClickListener {
                    valor += p
                    pintar()
                    alCambiar(valor)
                }
                f.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            return f
        }
        col.addView(fila(-1000L, -500L, -100L, -50L))
        col.addView(fila(50L, 100L, 500L, 1000L))

        val cero = Button(a)
        cero.text = "Restablecer a 0"
        cero.setOnClickListener {
            valor = 0L
            pintar()
            alCambiar(0L)
        }
        col.addView(cero)

        AlertDialog.Builder(a)
            .setTitle(titulo)
            .setView(col)
            .setPositiveButton("Listo", null)
            .show()
    }
}
