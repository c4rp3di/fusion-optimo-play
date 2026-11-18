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
    private val PEDIR_VARIOS = 44

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
        entrada.hint = "URL, magnet, .torrent o enlace de Mega"
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

        val fila2 = LinearLayout(this)
        fila2.orientation = LinearLayout.HORIZONTAL
        val bVarios = Button(this)
        bVarios.text = "Varios archivos"
        bVarios.setOnClickListener { abrirVarios() }
        fila2.addView(bVarios, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bCarpetas = Button(this)
        bCarpetas.text = "Carpetas"
        bCarpetas.setOnClickListener { startActivity(Intent(this, CarpetasActivity::class.java)) }
        fila2.addView(bCarpetas, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(fila2)

        val fila3 = LinearLayout(this)
        fila3.orientation = LinearLayout.HORIZONTAL
        val bListas = Button(this)
        bListas.text = "Mis listas"
        bListas.setOnClickListener { startActivity(Intent(this, ListasActivity::class.java)) }
        fila3.addView(bListas, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bDesc = Button(this)
        bDesc.text = "Descargas"
        bDesc.setOnClickListener { startActivity(Intent(this, DescargasActivity::class.java)) }
        fila3.addView(bDesc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(fila3)

        val rec = TextView(this)
        rec.text = "Recientes"
        rec.textSize = 14f
        rec.alpha = 0.7f
        rec.setPadding(0, dp(16), 0, dp(4))
        raiz.addView(rec)

        lista = ListView(this)
        lista.setOnItemClickListener { _, _, i, _ ->
            val r = recientes.getOrNull(i) ?: return@setOnItemClickListener
            // En los enlaces de Mega la clave va tras el #: no se puede quitar.
            reproducir(if (MegaEnlace.esMega(r.uri)) r.uri else r.uri.substringBefore("#"), r.titulo)
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
        val texto = entrada.text.toString().trim()
        val t = Utiles.primerEnlace(texto) ?: texto
        if (t.isEmpty()) {
            Toast.makeText(this, "Pega una URL, un magnet o un enlace de Mega", Toast.LENGTH_SHORT).show()
            return
        }
        // Enlace de Mega protegido pegado junto con su «Contraseña: xxx»: se pasa ya; si no, el reproductor la pide.
        val clave = if (MegaEnlace.esMega(t)) MegaEnlace.claveEnTexto(texto) else null
        reproducir(t, Utiles.nombreDe(this, t), clave)
    }

    private fun reproducir(uri: String, titulo: String, clave: String? = null) {
        val i = Intent(this, PlayerActivity::class.java)
        i.action = Intent.ACTION_VIEW
        i.data = Uri.parse(uri)
        i.putExtra("title", titulo)
        if (clave != null) i.putExtra("password", clave)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun abrirArchivo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*", "application/x-bittorrent", "application/octet-stream"))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        Gestor.lanzar(this, i, PEDIR_ARCHIVO)
    }

    /** Elegir varios archivos de audio o vídeo a la vez: se reproducen como una cola. */
    private fun abrirVarios() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        Gestor.lanzar(this, i, PEDIR_VARIOS)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        if (codigo == PEDIR_VARIOS && resultado == RESULT_OK && datos != null) {
            val uris = ArrayList<Uri>()
            val clip = datos.clipData
            if (clip != null) {
                for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
            } else {
                datos.data?.let { uris.add(it) }
            }
            val pistas = ArrayList<Pista>()
            for (u in uris) {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // Vale mientras la app siga abierta.
                }
                val nombre = Utiles.nombreDe(this, u.toString())
                if (Utiles.esReproducible(nombre)) pistas.add(Pista(u.toString(), nombre))
            }
            pistas.sortWith { a, b -> Utiles.comparaNatural(a.nombre, b.nombre) }
            if (pistas.isEmpty()) {
                Toast.makeText(this, "No hay audio ni vídeo entre lo elegido", Toast.LENGTH_SHORT).show()
            } else {
                ColaIntent.lanzar(this, pistas, 0, pistas.size.toString() + " archivos")
            }
            return
        }
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
