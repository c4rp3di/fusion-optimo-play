package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Pantalla de inicio: pegar una URL o un magnet, abrir un archivo del dispositivo y ver lo reciente.
 * Los enlaces que llegan de Optimo u otras apps van directos a PlayerActivity (no pasan por aquí).
 */
class MainActivity : Activity() {

    private lateinit var aj: Ajustes
    private lateinit var entrada: EditText
    private lateinit var lista: ListView
    private var recientes: List<Reciente> = emptyList()

    private val PEDIR_ARCHIVO = 41

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        aj = Ajustes(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(16), dp(28), dp(16), dp(8))

        val cab = LinearLayout(this)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        val titulo = TextView(this)
        titulo.text = "Optimo Play"
        titulo.textSize = 24f
        cab.addView(titulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bAjustes = Button(this)
        bAjustes.text = "Ajustes"
        bAjustes.setOnClickListener { startActivity(Intent(this, AjustesActivity::class.java)) }
        cab.addView(bAjustes)
        raiz.addView(cab)

        entrada = EditText(this)
        entrada.hint = "URL, magnet o enlace a .torrent"
        entrada.setSingleLine(true)
        entrada.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        entrada.imeOptions = EditorInfo.IME_ACTION_GO
        entrada.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_GO) {
                reproducirTexto()
                true
            } else false
        }
        raiz.addView(entrada)

        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        val bPlay = Button(this)
        bPlay.text = "Reproducir"
        bPlay.setOnClickListener { reproducirTexto() }
        fila.addView(bPlay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bArchivo = Button(this)
        bArchivo.text = "Abrir archivo"
        bArchivo.setOnClickListener { abrirArchivo() }
        fila.addView(bArchivo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(fila)

        val rec = TextView(this)
        rec.text = "Recientes"
        rec.textSize = 14f
        rec.alpha = 0.7f
        rec.setPadding(0, dp(16), 0, dp(4))
        raiz.addView(rec)

        lista = ListView(this)
        lista.setOnItemClickListener { _, _, i, _ ->
            val r = recientes.getOrNull(i) ?: return@setOnItemClickListener
            reproducir(r.uri.substringBefore("#"), r.titulo)
        }
        lista.setOnItemLongClickListener { _, _, i, _ ->
            val r = recientes.getOrNull(i) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setItems(arrayOf("Quitar de recientes")) { _, _ ->
                    aj.borrarReciente(r.uri)
                    cargar()
                }
                .show()
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
        recientes = aj.recientes()
        val textos = recientes.map { r ->
            val resto = if (r.pos > 15_000) "  ·  ${Utiles.fmt(r.pos)}" else ""
            r.titulo.ifEmpty { r.uri } + resto
        }
        lista.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, textos)
    }

    private fun reproducirTexto() {
        val t = Utiles.primerEnlace(entrada.text.toString().trim()) ?: entrada.text.toString().trim()
        if (t.isEmpty()) {
            Toast.makeText(this, "Pega una URL o un magnet", Toast.LENGTH_SHORT).show()
            return
        }
        reproducir(t, Utiles.nombreDe(this, t))
    }

    private fun reproducir(uri: String, titulo: String) {
        val i = Intent(this, PlayerActivity::class.java)
        i.action = Intent.ACTION_VIEW
        i.data = Uri.parse(uri)
        i.putExtra("title", titulo)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun abrirArchivo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*", "application/x-bittorrent", "application/octet-stream"))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, PEDIR_ARCHIVO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        if (codigo != PEDIR_ARCHIVO || resultado != RESULT_OK) return
        val u = datos?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // No todos los proveedores permiten conservar el permiso; se reproduce igualmente ahora.
        }
        reproducir(u.toString(), Utiles.nombreDe(this, u.toString()))
    }
}
