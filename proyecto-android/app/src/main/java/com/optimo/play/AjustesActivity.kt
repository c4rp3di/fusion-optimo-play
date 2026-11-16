package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Ajustes: cuenta de OpenSubtitles, subtítulos automáticos, segundo plano y estilo por defecto. */
class AjustesActivity : Activity() {

    private lateinit var aj: Ajustes
    private lateinit var etKey: EditText
    private lateinit var etUsuario: EditText
    private lateinit var etClave: EditText
    private lateinit var etIdioma: EditText
    private lateinit var cAuto: CheckBox
    private lateinit var cSegundoPlano: CheckBox

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        aj = Ajustes(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(24), dp(20), dp(24))

        fun titulo(t: String) {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = 18f
            tv.setPadding(0, dp(20), 0, dp(4))
            col.addView(tv)
        }

        fun campo(pista: String, valor: String, clave: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = pista
            e.setText(valor)
            e.setSingleLine(true)
            e.inputType = if (clave) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            col.addView(e)
            return e
        }

        val cab = TextView(this)
        cab.text = "Ajustes"
        cab.textSize = 24f
        col.addView(cab)

        titulo("Reproducción")
        cSegundoPlano = CheckBox(this)
        cSegundoPlano.text = "Seguir sonando con la pantalla bloqueada o en segundo plano"
        cSegundoPlano.isChecked = aj.segundoPlano
        col.addView(cSegundoPlano)
        val nota1 = TextView(this)
        nota1.text = "En Xiaomi/MIUI: pon la batería de esta app en «Sin restricciones» y activa el inicio automático, " +
            "o el sistema cerrará la reproducción al bloquear."
        nota1.textSize = 12f
        nota1.alpha = 0.7f
        col.addView(nota1)

        titulo("Subtítulos en línea (OpenSubtitles)")
        val nota2 = TextView(this)
        nota2.text = "Necesitas una API key gratuita (opensubtitles.com → tu cuenta → Consumers) y tu usuario y contraseña " +
            "para descargar. Se guardan cifradas en este dispositivo y solo se envían a api.opensubtitles.com."
        nota2.textSize = 12f
        nota2.alpha = 0.7f
        col.addView(nota2)
        etKey = campo("API key", aj.osApiKey)
        etUsuario = campo("Usuario", aj.osUsuario)
        etClave = campo("Contraseña", aj.osClave, true)
        etIdioma = campo("Idioma preferido (es, en, fr, pt-br…)", aj.subIdioma)
        cAuto = CheckBox(this)
        cAuto.text = "Buscar subtítulos solos si el archivo no los trae"
        cAuto.isChecked = aj.subAuto
        col.addView(cAuto)

        titulo("Estilo de subtítulos")
        val bEstilo = Button(this)
        bEstilo.text = "Tamaño, color, fondo, contorno…"
        bEstilo.setOnClickListener { DialogoSubs.estilo(this, aj) {} }
        col.addView(bEstilo)

        titulo("Datos")
        val bBorrar = Button(this)
        bBorrar.text = "Borrar lista de recientes"
        bBorrar.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("¿Borrar la lista de recientes y las posiciones guardadas?")
                .setPositiveButton("Borrar") { _, _ ->
                    aj.borrarRecientes()
                    Toast.makeText(this, "Borrado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        col.addView(bBorrar)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onPause() {
        // Se guarda al salir, sin botón.
        aj.osApiKey = etKey.text.toString()
        aj.osUsuario = etUsuario.text.toString()
        aj.osClave = etClave.text.toString()
        val idioma = etIdioma.text.toString().trim()
        if (idioma.isNotEmpty()) aj.subIdioma = idioma
        aj.subAuto = cAuto.isChecked
        aj.segundoPlano = cSegundoPlano.isChecked
        super.onPause()
    }
}
