package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Explorador de carpetas con acceso concedido (sin permisos de almacenamiento): se añaden varias carpetas, se navega
 * por ellas y se reproduce una carpeta entera, un archivo (con el resto de su carpeta en cola) o una lista .m3u.
 */
class CarpetasActivity : Activity() {

    private val PEDIR_CARPETA = 51

    private lateinit var titulo: TextView
    private lateinit var lista: ListView
    private lateinit var barra: LinearLayout
    private lateinit var vacio: TextView

    private var arbol: Uri? = null
    /** Ruta de carpetas abiertas: (docId, nombre); la primera es la raíz del árbol. */
    private val pila = ArrayList<Pair<String, String>>()
    private var raices: List<Uri> = emptyList()
    private var entradas: List<EntradaDoc> = emptyList()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(16), dp(28), dp(16), dp(8))

        titulo = TextView(this)
        titulo.textSize = 20f
        titulo.maxLines = 2
        raiz.addView(titulo)

        barra = LinearLayout(this)
        barra.orientation = LinearLayout.HORIZONTAL
        raiz.addView(barra)

        vacio = TextView(this)
        vacio.alpha = 0.7f
        vacio.setPadding(0, dp(16), 0, dp(8))
        raiz.addView(vacio)

        lista = ListView(this)
        lista.setOnItemClickListener { _, _, i, _ -> alPulsar(i) }
        lista.setOnItemLongClickListener { _, _, i, _ ->
            alMantener(i)
            true
        }
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)
        pintar()
    }

    private fun boton(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { f() }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return b
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            pila.size > 1 -> {
                pila.removeAt(pila.size - 1)
                pintar()
            }
            arbol != null -> {
                arbol = null
                pila.clear()
                pintar()
            }
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- pantalla

    private fun pintar() {
        barra.removeAllViews()
        val t = arbol
        if (t == null) {
            titulo.text = "Carpetas"
            raices = Carpetas.persistidas(this)
            barra.addView(boton("＋ Añadir carpeta") { elegirCarpeta() })
            vacio.text = if (raices.isEmpty())
                "Añade las carpetas donde tienes tu música y tus vídeos (puedes añadir varias). Se recuerdan: no hace falta darles permisos de almacenamiento."
            else "Toca una carpeta para abrirla. Mantén pulsada una para olvidarla."
            entradas = emptyList()
            lista.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, raices.map { "📂 " + Carpetas.nombreRaiz(this, it) })
            return
        }
        val actual = pila.last()
        titulo.text = pila.drop(1).joinToString(" / ") { it.second }.ifEmpty { pila[0].second }
        barra.addView(boton("▶ Todo") { reproducirCarpeta(actual.first, false, 0, actual.second, false) })
        barra.addView(boton("＋ Cola") { reproducirCarpeta(actual.first, false, 0, actual.second, true) })
        barra.addView(boton("＋ Carpeta") { elegirCarpeta() })
        val todo = Carpetas.hijos(this, t, actual.first)
        entradas = todo
            .filter { it.esCarpeta || esLista(it.nombre) || Utiles.esReproducible(it.nombre) }
            .sortedWith(
                compareBy<EntradaDoc>({ if (it.esCarpeta) 0 else if (esLista(it.nombre)) 1 else 2 })
                    .thenComparator { a, b -> Utiles.comparaNatural(a.nombre, b.nombre) }
            )
        vacio.text = if (entradas.isEmpty()) "Esta carpeta no tiene música, vídeos ni listas." else ""
        lista.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entradas.map { rotulo(it) })
    }

    private fun esLista(n: String): Boolean {
        val e = n.substringAfterLast('.', "").lowercase()
        return e == "m3u" || e == "m3u8"
    }

    private fun rotulo(e: EntradaDoc): String = when {
        e.esCarpeta -> "📁 " + e.nombre
        esLista(e.nombre) -> "📃 " + e.nombre
        Utiles.esAudio(e.nombre) -> "🎵 " + e.nombre + "  ·  " + Utiles.tam(e.tam)
        else -> "🎬 " + e.nombre + "  ·  " + Utiles.tam(e.tam)
    }

    // ---------------------------------------------------------------- acciones

    private fun alPulsar(i: Int) {
        val t = arbol
        if (t == null) {
            val r = raices.getOrNull(i) ?: return
            arbol = r
            pila.clear()
            pila.add(Pair(Carpetas.raizId(r), Carpetas.nombreRaiz(this, r)))
            pintar()
            return
        }
        val e = entradas.getOrNull(i) ?: return
        when {
            e.esCarpeta -> {
                pila.add(Pair(e.docId, e.nombre))
                pintar()
            }
            esLista(e.nombre) -> {
                val intent = Intent(this, PlayerActivity::class.java)
                intent.action = Intent.ACTION_VIEW
                intent.data = e.uri
                intent.putExtra("title", e.nombre)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            }
            else -> {
                // Se reproduce este archivo con el resto de su carpeta (del mismo tipo) en cola.
                val audio = Utiles.esAudio(e.nombre)
                val hermanos = entradas.filter { !it.esCarpeta && (if (audio) Utiles.esAudio(it.nombre) else Utiles.esVideo(it.nombre)) }
                val pistas = hermanos.map { Pista(it.uri.toString(), it.nombre) }
                val idx = hermanos.indexOfFirst { it.docId == e.docId }.coerceAtLeast(0)
                ColaIntent.lanzar(this, pistas, idx, pila.last().second)
            }
        }
    }

    private fun alMantener(i: Int) {
        val t = arbol
        if (t == null) {
            val r = raices.getOrNull(i) ?: return
            AlertDialog.Builder(this)
                .setTitle(Carpetas.nombreRaiz(this, r))
                .setItems(arrayOf("Olvidar esta carpeta")) { _, _ ->
                    Carpetas.olvidar(this, r)
                    pintar()
                }
                .show()
            return
        }
        val e = entradas.getOrNull(i) ?: return
        when {
            e.esCarpeta -> AlertDialog.Builder(this)
                .setTitle(e.nombre)
                .setItems(arrayOf("Reproducir carpeta y subcarpetas", "Añadir a la cola (con subcarpetas)")) { _, k ->
                    reproducirCarpeta(e.docId, true, 0, e.nombre, k == 1)
                }
                .show()
            esLista(e.nombre) -> Toast.makeText(this, "Toca la lista para reproducirla", Toast.LENGTH_SHORT).show()
            else -> AlertDialog.Builder(this)
                .setTitle(e.nombre)
                .setItems(arrayOf("Añadir a la cola", "Reproducir a continuación")) { _, k ->
                    val p = listOf(Pista(e.uri.toString(), e.nombre))
                    val s = PlaybackService.activo
                    if (s != null && s.hayCola) {
                        s.anadirACola(p, k == 1)
                        Toast.makeText(this, if (k == 1) "Sonará a continuación" else "Añadido a la cola", Toast.LENGTH_SHORT).show()
                    } else {
                        ColaIntent.lanzar(this, p, 0, e.nombre)
                    }
                }
                .show()
        }
    }

    /** Lee la carpeta en segundo plano y reproduce (o añade a la cola) sus archivos en orden natural. */
    private fun reproducirCarpeta(docId: String, recursivo: Boolean, desde: Int, nombre: String, anadir: Boolean) {
        val t = arbol ?: return
        Toast.makeText(this, "Leyendo carpeta…", Toast.LENGTH_SHORT).show()
        Thread {
            val l = Carpetas.medios(this, t, docId, recursivo) { Utiles.esReproducible(it) }
            // Una carpeta de audio no mezcla vídeos sueltos si tiene música: se prefiere el audio.
            val audios = l.filter { Utiles.esAudio(it.nombre) }
            val elegidos = if (audios.isNotEmpty() && audios.size * 2 >= l.size) audios else l
            runOnUiThread {
                if (elegidos.isEmpty()) {
                    Toast.makeText(this, "No hay música ni vídeos en esta carpeta", Toast.LENGTH_SHORT).show()
                } else {
                    val pistas = elegidos.map { Pista(it.uri.toString(), it.nombre) }
                    val s = PlaybackService.activo
                    if (anadir && s != null && s.hayCola) {
                        s.anadirACola(pistas)
                    } else {
                        ColaIntent.lanzar(this, pistas, desde, nombre)
                    }
                }
            }
        }.start()
    }

    private fun elegirCarpeta() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        Gestor.lanzar(this, i, PEDIR_CARPETA)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        if (codigo != PEDIR_CARPETA || resultado != RESULT_OK) return
        val u = datos?.data ?: return
        Carpetas.conservar(this, u)
        Toast.makeText(this, "Carpeta añadida", Toast.LENGTH_SHORT).show()
        pintar()
    }
}
