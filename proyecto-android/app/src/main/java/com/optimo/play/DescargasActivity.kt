package com.optimo.play

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * «Descargas»: lo que se ha guardado de torrents en Descargas/OptimoPlay. Se ve por carpetas, se reproduce, se borra
 * (archivo o carpeta entera) y se pueden quitar las copias sobrantes «nombre (1).ext» que dejaban versiones anteriores.
 */
class DescargasActivity : Activity() {

    private class Fila(val cabecera: String?, val archivo: ArchivoGuardado?, val carpeta: List<ArchivoGuardado>)

    private lateinit var resumen: TextView
    private lateinit var lista: ListView
    private lateinit var btnDup: Button
    private var todos: List<ArchivoGuardado> = emptyList()
    private var filas: List<Fila> = emptyList()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(16), dp(28), dp(16), dp(8))

        val t = TextView(this)
        t.text = "Descargas de torrents"
        t.textSize = 24f
        raiz.addView(t)

        resumen = TextView(this)
        resumen.alpha = 0.7f
        resumen.setPadding(0, dp(4), 0, dp(4))
        raiz.addView(resumen)

        if (Gestor.instalado(this)) {
            val bGestor = Button(this)
            bGestor.text = "Ver la carpeta en el gestor de archivos"
            bGestor.setOnClickListener {
                if (!Gestor.verCarpeta(this, Exportar.rutaRelativa())) {
                    Toast.makeText(this, "No se pudo abrir el gestor", Toast.LENGTH_SHORT).show()
                }
            }
            raiz.addView(bGestor)
        }

        btnDup = Button(this)
        btnDup.setOnClickListener { borrarDuplicados() }
        raiz.addView(btnDup)

        lista = ListView(this)
        lista.setOnItemClickListener { _, _, i, _ -> filas.getOrNull(i)?.let { alPulsar(it) } }
        lista.setOnItemLongClickListener { _, _, i, _ ->
            filas.getOrNull(i)?.let { alPulsar(it) }
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
        resumen.text = "Leyendo…"
        Thread {
            val l = Exportar.listar(this)
            runOnUiThread {
                todos = l
                pintar()
            }
        }.start()
    }

    private fun pintar() {
        val total = todos.sumOf { it.tam }
        resumen.text = if (todos.isEmpty())
            "No hay nada guardado todavía. Los archivos que guardes desde un torrent (⬇) irán a " + Exportar.destinoTexto() + "."
        else todos.size.toString() + " archivos · " + Utiles.tam(total) + " en " + Exportar.destinoTexto() +
            ". Un torrent que vuelvas a guardar no repite los archivos que ya están."
        val dup = Exportar.duplicados(todos)
        btnDup.visibility = if (dup.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        btnDup.text = "Borrar " + dup.size + " copias duplicadas (" + Utiles.tam(dup.sumOf { it.tam }) + ")"

        val salida = ArrayList<Fila>()
        for ((carpeta, archivos) in todos.groupBy { it.sub }.entries.sortedWith { a, b -> Utiles.comparaNatural(a.key, b.key) }) {
            val ordenados = archivos.sortedWith { a, b -> Utiles.comparaNatural(a.nombre, b.nombre) }
            salida.add(
                Fila(
                    "📁 " + (if (carpeta.isEmpty()) "(carpeta principal)" else carpeta) + "  ·  " +
                        archivos.size + " · " + Utiles.tam(archivos.sumOf { it.tam }),
                    null, ordenados
                )
            )
            for (a in ordenados) salida.add(Fila(null, a, ordenados))
        }
        filas = salida
        lista.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            salida.map { it.cabecera ?: ("      " + it.archivo!!.nombre + "  ·  " + Utiles.tam(it.archivo.tam)) }
        )
    }

    private fun alPulsar(f: Fila) {
        val a = f.archivo
        if (a == null) {
            val et = arrayOf("Reproducir la carpeta", "Borrar la carpeta (" + f.carpeta.size + " archivos)")
            AlertDialog.Builder(this).setItems(et) { _, k ->
                if (k == 0) reproducir(f.carpeta, 0) else confirmarBorrar(f.carpeta, "la carpeta y sus " + f.carpeta.size + " archivos")
            }.show()
            return
        }
        val et = arrayOf("Reproducir", "Borrar este archivo")
        AlertDialog.Builder(this).setTitle(a.nombre).setItems(et) { _, k ->
            if (k == 0) reproducir(f.carpeta, f.carpeta.indexOf(a)) else confirmarBorrar(listOf(a), "«" + a.nombre + "»")
        }.show()
    }

    /** Reproduce desde [desde] los archivos de la carpeta del mismo tipo (audios con audio, vídeos con vídeo). */
    private fun reproducir(carpeta: List<ArchivoGuardado>, desde: Int) {
        val base = carpeta.getOrNull(desde) ?: carpeta.firstOrNull { Utiles.esReproducible(it.nombre) } ?: return
        if (!Utiles.esReproducible(base.nombre)) {
            Toast.makeText(this, "Este archivo no es de audio ni de vídeo", Toast.LENGTH_SHORT).show()
            return
        }
        val audio = Utiles.esAudio(base.nombre)
        val mismos = carpeta.filter { if (audio) Utiles.esAudio(it.nombre) else Utiles.esVideo(it.nombre) }
        val pistas = mismos.map { Pista(it.uri.toString(), it.nombre) }
        ColaIntent.lanzar(this, pistas, mismos.indexOf(base).coerceAtLeast(0), base.sub.substringAfterLast('/').ifEmpty { "Descargas" })
    }

    private fun confirmarBorrar(l: List<ArchivoGuardado>, que: String) {
        AlertDialog.Builder(this)
            .setMessage("¿Borrar " + que + " del dispositivo? Se liberan " + Utiles.tam(l.sumOf { it.tam }) + ".")
            .setPositiveButton("Borrar") { _, _ -> borrar(l) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun borrar(l: List<ArchivoGuardado>) {
        Thread {
            var n = 0
            for (a in l) if (Exportar.borrar(this, a)) n++
            runOnUiThread {
                Toast.makeText(this, "Borrados: $n de " + l.size, Toast.LENGTH_SHORT).show()
                cargar()
            }
        }.start()
    }

    private fun borrarDuplicados() {
        val dup = Exportar.duplicados(todos)
        if (dup.isEmpty()) return
        confirmarBorrar(dup, dup.size.toString() + " copias repetidas (\"nombre (1).ext\" con el mismo tamaño que el original)")
    }
}
