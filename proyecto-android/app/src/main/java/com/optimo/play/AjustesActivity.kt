package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.content.Intent
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
    private lateinit var cColaAuto: CheckBox
    private lateinit var cSinSubsCanales: CheckBox
    private lateinit var cGestor: CheckBox
    private lateinit var grupoOrient: RadioGroup

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

        cSinSubsCanales = CheckBox(this)
        cSinSubsCanales.text = "Quitar por completo los subtítulos incluidos en los canales (solo si aun así algún canal se queda parado)"
        cSinSubsCanales.isChecked = aj.sinSubsCanales
        col.addView(cSinSubsCanales)

        titulo("Orientación del reproductor")
        grupoOrient = RadioGroup(this)
        val nombresOr = listOf("Automática (sigue el bloqueo de rotación del sistema)", "Siempre horizontal", "Siempre vertical")
        for ((i, n) in nombresOr.withIndex()) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = n
            rb.isChecked = i == aj.orientacion
            grupoOrient.addView(rb)
        }
        col.addView(grupoOrient)

        titulo("Archivos del dispositivo")
        cGestor = CheckBox(this)
        cGestor.text = "Elegir archivos y carpetas con mi gestor de archivos (com.carpe.gestorarchivos)"
        cGestor.isChecked = aj.usarGestor
        col.addView(cGestor)
        val notaG = TextView(this)
        notaG.text = if (Gestor.instalado(this)) "Gestor instalado. También aparece en el selector del sistema."
        else "El gestor no está instalado: se usa el selector del sistema."
        notaG.textSize = 12f
        notaG.alpha = 0.7f
        col.addView(notaG)

        titulo("Música y carpetas")
        cColaAuto = CheckBox(this)
        cColaAuto.text = "Al abrir un audio, poner en cola el resto de audios de su carpeta (si la app tiene acceso a ella)"
        cColaAuto.isChecked = aj.colaAuto
        col.addView(cColaAuto)
        val bCarpetas = Button(this)
        bCarpetas.text = "Carpetas con acceso concedido…"
        bCarpetas.setOnClickListener { startActivity(Intent(this, CarpetasActivity::class.java)) }
        col.addView(bCarpetas)

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

        titulo("Acerca de")
        val acerca = TextView(this)
        var ver = "?"
        var cod = "?"
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            ver = pi.versionName ?: "?"
            cod = pi.longVersionCode.toString()
        } catch (e: Exception) {
            // Sin datos de versión.
        }
        acerca.text = "Optimo Play\nVersión " + ver + " (compilación " + cod + ")\n" + packageName +
            "\nMotor: libVLC 3.6.0 · Torrents: libtorrent4j 2.1.0 · Enviar a la tele: DLNA y Google Cast"
        acerca.textSize = 14f
        acerca.setTextIsSelectable(true)
        col.addView(acerca)

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
        aj.colaAuto = cColaAuto.isChecked
        aj.sinSubsCanales = cSinSubsCanales.isChecked
        aj.usarGestor = cGestor.isChecked
        val elegido = grupoOrient.indexOfChild(grupoOrient.findViewById<View>(grupoOrient.checkedRadioButtonId))
        if (elegido >= 0) aj.orientacion = elegido
        super.onPause()
    }
}
