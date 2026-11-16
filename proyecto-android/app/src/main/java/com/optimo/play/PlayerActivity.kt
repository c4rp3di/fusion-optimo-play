package com.optimo.play

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Rational
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.media.AudioManager
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/**
 * Pantalla del reproductor. No es dueña del reproductor: se conecta a PlaybackService, le presta la superficie
 * de vídeo mientras está visible y la retira al salir (el audio sigue en el servicio).
 *
 * Contrato de entrada (Optimo y otras apps): ACTION_VIEW con la URL / magnet / archivo como `data`.
 * Extras opcionales: "title", "referer", "user_agent".
 */
class PlayerActivity : Activity() {

    private val PEDIR_SUBTITULO = 42

    private var svc: PlaybackService? = null
    private var pendiente: Fuente? = null
    private lateinit var aj: Ajustes

    private lateinit var raiz: FrameLayout
    private lateinit var video: VLCVideoLayout
    private lateinit var estadoTv: TextView
    private lateinit var indicador: TextView
    private lateinit var barraSup: LinearLayout
    private lateinit var barraInf: LinearLayout
    private lateinit var tituloTv: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tiempoTv: TextView
    private lateinit var btnPlay: TextView

    private lateinit var panelStats: TextView
    private lateinit var barraSync: LinearLayout
    private lateinit var syncTv: TextView
    private var cuesSync: List<Long> = emptyList()
    private var cueObjetivo = -1L

    private var adjunto: MediaPlayer? = null
    private var arrastrando = false
    private var capaVisible = true
    private var ocultarEn = 0L
    private lateinit var panelArchivos: LinearLayout
    private lateinit var listaArchivos: LinearLayout
    private lateinit var btnAnt: TextView
    private lateinit var btnSig: TextView
    private lateinit var btnLista: TextView
    private lateinit var panelCan: PanelCanales
    private var panelCanAbierto = false
    private var canalesAuto = false
    private var panelAbierto = false
    private var panelAuto = false
    private var conservarAlSalir = false
    /** índice del archivo del torrent → (nombre, estado de guardado) de su fila en el panel lateral. */
    private val filasArchivos = HashMap<Int, Pair<TextView, TextView>>()
    private var acumVol = 0f
    private val h = Handler(Looper.getMainLooper())

    private val tic = object : Runnable {
        override fun run() {
            actualizar()
            h.postDelayed(this, 400)
        }
    }
    private val quitarIndicador = Runnable { indicador.visibility = View.GONE }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- ciclo de vida

    private val conexion = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val s = (b as PlaybackService.Enlace).servicio
            svc = s
            val p = pendiente
            pendiente = null
            if (p != null) abrirConfirmando(s, p)
            adjuntarSiHaceFalta()
        }

        override fun onServiceDisconnected(n: ComponentName?) {
            svc = null
            adjunto = null
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        aj = Ajustes(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val atr = window.attributes
            atr.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = atr
        }
        construirUi()
        setContentView(raiz)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        pendiente = fuenteDesde(intent)
        // Servicio en primer plano + conexión: el servicio sobrevive a la actividad (segundo plano).
        startForegroundService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), conexion, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        val p = fuenteDesde(i) ?: return
        val s = svc
        if (s == null) pendiente = p else abrirConfirmando(s, p)
    }

    /** Abre una fuente nueva; si hay archivos guardándose de un torrent, pregunta antes de cancelarlos. */
    private fun abrirConfirmando(s: PlaybackService, p: Fuente) {
        if (s.hayGuardadoPendiente()) {
            if (s.fuente?.uri == p.uri) return
            AlertDialog.Builder(this)
                .setTitle("Hay archivos guardándose")
                .setMessage("Se están descargando para guardarlos en el dispositivo (" + s.resumenGuardado() +
                    "). Si abres otra cosa se cancelarán.")
                .setPositiveButton("Cancelar y abrir") { _, _ -> s.abrir(p) }
                .setNegativeButton("Seguir guardando", null)
                .show()
            return
        }
        if (s.debeAbrir(p.uri)) s.abrir(p)
    }

    /** Salir del reproductor; si quedan archivos por guardar, ofrece seguir en segundo plano. */
    private fun salir() {
        val s = svc
        if (s != null && s.hayGuardadoPendiente()) {
            AlertDialog.Builder(this)
                .setTitle("Guardando en el dispositivo")
                .setMessage("Aún se están descargando archivos para guardar (" + s.resumenGuardado() + ").")
                .setPositiveButton("Seguir en segundo plano") { _, _ ->
                    conservarAlSalir = true
                    finish()
                }
                .setNegativeButton("Cancelar descargas y salir") { _, _ ->
                    conservarAlSalir = false
                    finish()
                }
                .setNeutralButton("Volver", null)
                .show()
        } else {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (panelCanAbierto) cerrarPanelCanales() else if (panelAbierto) cerrarPanel() else salir()
    }

    override fun onStart() {
        super.onStart()
        inmersivo()
        h.post(tic)
    }

    override fun onStop() {
        h.removeCallbacks(tic)
        if (!isInPictureInPictureMode) {
            try { adjunto?.detachViews() } catch (e: Throwable) { /* nada */ }
            adjunto = null
            if (!aj.segundoPlano) svc?.pausar()
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(tieneFoco: Boolean) {
        super.onWindowFocusChanged(tieneFoco)
        if (tieneFoco) inmersivo()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { unbindService(conexion) } catch (e: Exception) { /* nada */ }
        // Salir con «atrás» detiene la reproducción; ir al escritorio o bloquear NO destruye la actividad.
        if (isFinishing) {
            val s = svc
            if (conservarAlSalir && s != null && s.hayGuardadoPendiente()) s.soloDescarga() else s?.detener()
        }
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(enPip: Boolean, cfg: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(enPip, cfg)
        if (enPip) ocultarCapa() else mostrarCapa()
    }

    private fun adjuntarSiHaceFalta() {
        val m = svc?.mp ?: return
        if (adjunto === m) return
        m.attachViews(video, null, true, false)
        adjunto = m
        m.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
    }

    // ---------------------------------------------------------------- entrada

    @Suppress("DEPRECATION")
    private fun fuenteDesde(i: Intent?): Fuente? {
        if (i == null) return null
        var uri: String? = null
        when (i.action) {
            Intent.ACTION_VIEW -> uri = i.data?.toString()
            Intent.ACTION_SEND -> {
                uri = Utiles.primerEnlace(i.getStringExtra(Intent.EXTRA_TEXT))
                if (uri == null) uri = (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.toString()
            }
        }
        if (uri == null) return null
        val titulo = i.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Utiles.nombreDe(this, uri)
        return Fuente(uri, titulo, i.getStringExtra("referer"), i.getStringExtra("user_agent"))
    }

    // ---------------------------------------------------------------- interfaz

    private fun texto(t: String, tam: Float = 16f): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = tam
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        return tv
    }

    private fun boton(t: String, alPulsar: () -> Unit): TextView {
        val tv = texto(t, 18f)
        // Zona de toque cómoda (48 dp mínimo) para no fallar los botones pequeños.
        tv.minWidth = dp(56)
        tv.minHeight = dp(48)
        tv.setOnClickListener {
            alPulsar()
            ocultarEn = SystemClock.uptimeMillis() + 4000
        }
        return tv
    }

    private fun construirUi() {
        raiz = FrameLayout(this)
        raiz.setBackgroundColor(Color.BLACK)

        video = VLCVideoLayout(this)
        raiz.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        estadoTv = texto("", 16f)
        estadoTv.setBackgroundColor(0x99000000.toInt())
        estadoTv.visibility = View.GONE
        raiz.addView(estadoTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        indicador = texto("", 22f)
        indicador.setBackgroundColor(0x99000000.toInt())
        indicador.visibility = View.GONE
        raiz.addView(indicador, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // Barra superior: cerrar + título + ajustes
        barraSup = LinearLayout(this)
        barraSup.orientation = LinearLayout.HORIZONTAL
        barraSup.gravity = Gravity.CENTER_VERTICAL
        barraSup.setBackgroundColor(0x88000000.toInt())
        // Margen lateral: con navegación por gestos (MIUI) los ~30 dp del borde los coge el sistema (gesto «atrás»)
        // y un botón pegado a la esquina no recibe el toque.
        barraSup.setPadding(dp(40), dp(4), dp(40), dp(4))
        barraSup.addView(boton("✕") { salir() })
        tituloTv = texto("", 16f)
        tituloTv.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        tituloTv.maxLines = 1
        tituloTv.ellipsize = android.text.TextUtils.TruncateAt.END
        barraSup.addView(tituloTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnLista = boton("☰") { alternarPanel() }
        btnLista.visibility = View.GONE
        barraSup.addView(btnLista)
        barraSup.addView(boton("⋮") { menuMas() })
        raiz.addView(barraSup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Barra inferior: progreso + controles
        barraInf = LinearLayout(this)
        barraInf.orientation = LinearLayout.VERTICAL
        barraInf.setBackgroundColor(0x88000000.toInt())

        val filaSeek = LinearLayout(this)
        filaSeek.orientation = LinearLayout.HORIZONTAL
        filaSeek.gravity = Gravity.CENTER_VERTICAL
        seekBar = SeekBar(this)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, deUsuario: Boolean) {
                if (deUsuario) tiempoTv.text = Utiles.fmt(p.toLong()) + " / " + Utiles.fmt(seekBar.max.toLong())
            }

            override fun onStartTrackingTouch(s: SeekBar?) { arrastrando = true }

            override fun onStopTrackingTouch(s: SeekBar?) {
                arrastrando = false
                svc?.mp?.setTime((s?.progress ?: 0).toLong())
                ocultarEn = SystemClock.uptimeMillis() + 4000
            }
        })
        filaSeek.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tiempoTv = texto("0:00 / 0:00", 13f)
        filaSeek.addView(tiempoTv)
        barraInf.addView(filaSeek)

        val filaBot = LinearLayout(this)
        filaBot.orientation = LinearLayout.HORIZONTAL
        filaBot.gravity = Gravity.CENTER
        fun w(v: View) = filaBot.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnAnt = boton("⏮") { svc?.anterior() }
        btnAnt.visibility = View.GONE
        w(btnAnt)
        w(boton("−10") { svc?.saltar(-10_000) })
        btnPlay = boton("▶") { svc?.alternar() }
        w(btnPlay)
        w(boton("+10") { svc?.saltar(10_000) })
        btnSig = boton("⏭") { svc?.siguiente() }
        btnSig.visibility = View.GONE
        w(btnSig)
        w(boton("CC") { menuSubtitulos() })
        w(boton("Audio") { menuAudio() })
        w(boton("Aspecto") { menuAspecto() })
        barraInf.addView(filaBot)
        raiz.addView(barraInf, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Panel de estadísticas del torrent (arriba a la izquierda, pequeño y translúcido).
        panelStats = texto("", 12f)
        panelStats.gravity = Gravity.START
        panelStats.setBackgroundColor(0x77000000)
        panelStats.setPadding(dp(8), dp(4), dp(8), dp(4))
        panelStats.visibility = View.GONE
        val lpStats = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        lpStats.topMargin = dp(60)
        lpStats.leftMargin = dp(40)
        raiz.addView(panelStats, lpStats)

        construirSync()
        construirPanel()
        construirPanelCanales()

        // Gestos sobre el vídeo: un toque muestra/oculta controles, doble toque salta, deslizar = brillo / volumen.
        val gestos = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                acumVol = 0f
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (capaVisible) ocultarCapa() else mostrarCapa()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x < raiz.width / 2f) {
                    svc?.saltar(-10_000)
                    aviso("−10 s")
                } else {
                    svc?.saltar(10_000)
                    aviso("+10 s")
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (e1 == null || Math.abs(dy) < Math.abs(dx)) return false
                if (e1.x < raiz.width / 2f) cambiarBrillo(dy) else cambiarVolumen(dy)
                return true
            }
        })
        raiz.setOnTouchListener { _, ev -> gestos.onTouchEvent(ev) }
        // Pedir al sistema que no use los bordes laterales para sus gestos mientras se ve el reproductor.
        if (Build.VERSION.SDK_INT >= 29) {
            raiz.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val ancho = r - l
                val alto = minOf(b - t, dp(200))
                v.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, dp(48), alto),
                    android.graphics.Rect(ancho - dp(48), 0, ancho, alto)
                )
            }
        }
        ocultarEn = SystemClock.uptimeMillis() + 4000
    }

    // ---------------------------------------------------------------- panel lateral de archivos del torrent

    private fun construirPanel() {
        panelArchivos = LinearLayout(this)
        panelArchivos.orientation = LinearLayout.VERTICAL
        panelArchivos.setBackgroundColor(0xEE111111.toInt())
        panelArchivos.isClickable = true // se queda los toques: no deben llegar a los gestos del vídeo
        panelArchivos.setPadding(dp(8), dp(8), dp(40), dp(8))
        panelArchivos.visibility = View.GONE

        val cab = LinearLayout(this)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        val tit = texto("Archivos del torrent", 16f)
        tit.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        cab.addView(tit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cab.addView(miniBoton("⬇ Todo") { svc?.let { pedirGuardar(it.archivosTorrent) } })
        cab.addView(miniBoton("✕") { cerrarPanel() })
        panelArchivos.addView(cab)

        val sv = ScrollView(this)
        listaArchivos = LinearLayout(this)
        listaArchivos.orientation = LinearLayout.VERTICAL
        sv.addView(listaArchivos)
        panelArchivos.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val ancho = minOf((resources.displayMetrics.widthPixels * 0.85f).toInt(), dp(420))
        raiz.addView(panelArchivos, FrameLayout.LayoutParams(ancho, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    private fun alternarPanel() {
        if (svc?.hayCanales == true) {
            if (panelCanAbierto) cerrarPanelCanales() else abrirPanelCanales()
        } else {
            if (panelAbierto) cerrarPanel() else abrirPanel()
        }
    }

    // ---------------------------------------------------------------- canales (lista M3U) y guía

    private fun construirPanelCanales() {
        panelCan = PanelCanales(
            this,
            { i ->
                svc?.elegirCanal(i)
                cerrarPanelCanales()
            },
            { c -> alternarFavorito(c) },
            { dialogoGuia() },
            { cerrarPanelCanales() }
        )
        panelCan.vista.visibility = View.GONE
        val ancho = minOf((resources.displayMetrics.widthPixels * 0.85f).toInt(), dp(440))
        raiz.addView(panelCan.vista, FrameLayout.LayoutParams(ancho, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    private fun abrirPanelCanales() {
        val s = svc ?: return
        if (!s.hayCanales) return
        panelCan.cargar(s.canales, aj.favoritos, s.guia, s.canalActual)
        panelCan.vista.visibility = View.VISIBLE
        panelCanAbierto = true
        mostrarCapa()
    }

    private fun cerrarPanelCanales() {
        panelCan.vista.visibility = View.GONE
        panelCanAbierto = false
    }

    private fun alternarFavorito(c: Canal) {
        aj.alternarFavorito(c.clave)
        val s = svc ?: return
        panelCan.actualizar(aj.favoritos, s.guia, s.canalActual)
    }

    /** Estado de la guía y campo para poner a mano su dirección (XMLTV, .xml o .xml.gz). */
    private fun dialogoGuia() {
        val s = svc ?: return
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.setPadding(dp(20), dp(8), dp(20), dp(0))
        val estado = TextView(this)
        val anuncia = s.guiaDeLaLista()
        estado.text = (if (s.estadoGuia.isEmpty()) "Sin guía cargada." else s.estadoGuia) +
            (if (anuncia != null) "\n\nLa lista anuncia: " + anuncia else "")
        caja.addView(estado)
        val campo = EditText(this)
        campo.hint = "https://…/guia.xml  o  .xml.gz"
        campo.isSingleLine = true
        campo.setText(aj.guiaDe(s.fuente?.uri ?: ""))
        caja.addView(campo)
        AlertDialog.Builder(this)
            .setTitle("Guía de programación")
            .setView(caja)
            .setPositiveButton("Cargar") { _, _ ->
                s.fijarGuiaManual(campo.text.toString())
                aviso("Cargando la guía…")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Lo que queda por emitir en el canal que suena, según la guía. */
    private fun dialogoProgramacion() {
        val s = svc ?: return
        val c = s.canalActivo
        if (c == null) {
            aviso("Elige un canal")
            return
        }
        val g = s.guia
        if (g == null) {
            AlertDialog.Builder(this)
                .setTitle(c.nombre)
                .setMessage(if (s.estadoGuia.isEmpty()) "Sin guía cargada." else s.estadoGuia)
                .setPositiveButton("Configurar guía") { _, _ -> dialogoGuia() }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        val ps = g.pendientes(c)
        if (ps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(c.nombre)
                .setMessage("La guía no tiene programación para este canal. Puede que su identificador no coincida con el de la lista.")
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(c.nombre)
            .setItems(ps.map { Utiles.hora(it.inicio) + "–" + Utiles.hora(it.fin) + "  " + it.titulo }.toTypedArray()) { _, i ->
                val p = ps[i]
                AlertDialog.Builder(this)
                    .setTitle(p.titulo)
                    .setMessage(Utiles.hora(p.inicio) + " – " + Utiles.hora(p.fin) + "\n\n" + (if (p.desc.isEmpty()) "(sin descripción)" else p.desc))
                    .setPositiveButton("Cerrar", null)
                    .show()
            }
            .show()
    }

    private fun abrirPanel() {
        val s = svc ?: return
        if (s.archivosTorrent.isEmpty()) return
        llenarPanel(s)
        panelArchivos.visibility = View.VISIBLE
        panelAbierto = true
        refrescarPanel(s)
        mostrarCapa()
    }

    private fun cerrarPanel() {
        panelArchivos.visibility = View.GONE
        panelAbierto = false
    }

    /** Construye las filas una vez al abrir (carpetas y archivos en orden natural); el refresco solo cambia textos. */
    private fun llenarPanel(s: PlaybackService) {
        listaArchivos.removeAllViews()
        filasArchivos.clear()
        val todos = s.archivosTorrent.sortedWith { x, y -> Utiles.comparaNatural(x.ruta, y.ruta) }
        // Si todo cuelga de una misma carpeta raíz (lo habitual) no se repite en cada cabecera.
        val raices = todos.map { it.ruta.substringBefore('/', "") }.distinct()
        val raizComun = if (raices.size == 1 && raices[0].isNotEmpty() && todos.all { it.ruta.contains('/') }) raices[0] else ""
        for ((carpeta, archivos) in todos.groupBy { it.ruta.substringBeforeLast('/', "") }) {
            var nombre = carpeta
            if (raizComun.isNotEmpty()) nombre = nombre.removePrefix(raizComun).removePrefix("/")
            if (nombre.isNotEmpty() || todos.size > 1) {
                val cab = LinearLayout(this)
                cab.orientation = LinearLayout.HORIZONTAL
                cab.gravity = Gravity.CENTER_VERTICAL
                cab.setBackgroundColor(0x33FFFFFF)
                val t = texto("📁 " + (if (nombre.isEmpty()) "(carpeta principal)" else nombre), 13f)
                t.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                t.maxLines = 2
                t.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                cab.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                cab.addView(miniBoton("⬇") { pedirGuardar(archivos) })
                val lpc = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lpc.topMargin = dp(8)
                listaArchivos.addView(cab, lpc)
            }
            for (a in archivos) {
                val fila = LinearLayout(this)
                fila.orientation = LinearLayout.HORIZONTAL
                fila.gravity = Gravity.CENTER_VERTICAL
                val nom = texto("", 14f)
                nom.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                nom.setPadding(dp(16), dp(8), dp(8), dp(8))
                if (a.esReproducible) nom.setOnClickListener { elegirDelPanel(s, a) }
                val est = miniBoton("⬇") { pedirGuardar(listOf(a)) }
                fila.addView(nom, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fila.addView(est)
                listaArchivos.addView(fila)
                filasArchivos[a.indice] = Pair(nom, est)
            }
        }
    }

    private fun refrescarPanel(s: PlaybackService) {
        val actual = s.archivoTorrentActual?.indice ?: -1
        for (a in s.archivosTorrent) {
            val par = filasArchivos[a.indice] ?: continue
            val suena = a.indice == actual
            par.first.text = (if (suena) "▶ " else "") + a.nombre + "\n" + Utiles.tam(a.tam)
            par.first.setTextColor(
                if (suena) 0xFF4FC3F7.toInt() else if (a.esReproducible) Color.WHITE else 0xFF999999.toInt()
            )
            par.second.text = when (s.estadoGuardado(a)) {
                0 -> "⬇ " + s.motor.progresoArchivo(a) + " %"
                1 -> "copiando…"
                2 -> "✓ guardado"
                -1 -> "⚠ reintentar"
                else -> "⬇"
            }
        }
    }

    private fun elegirDelPanel(s: PlaybackService, a: ArchivoTorrent) {
        if (s.archivoTorrentActual?.indice != a.indice || s.mp == null) s.elegirArchivoTorrent(a.indice)
        cerrarPanel()
    }

    /** Marca archivos para guardarlos en el dispositivo (o quita la marca si ya estaba y aún no se ha copiado). */
    private fun pedirGuardar(lista: List<ArchivoTorrent>) {
        val s = svc ?: return
        if (lista.size == 1) {
            val a = lista[0]
            when (s.estadoGuardado(a)) {
                0 -> {
                    s.guardarArchivos(lista, false)
                    aviso("Guardado cancelado")
                    return
                }
                1 -> {
                    aviso("Copiándose a Descargas…")
                    return
                }
                2 -> {
                    aviso("Ya está guardado en " + Exportar.destinoTexto())
                    return
                }
                -1 -> aviso("Error: " + (s.motor.errorGuardado[a.indice] ?: "desconocido") + " · reintentando")
            }
        }
        val nuevos = lista.filter { val e = s.estadoGuardado(it); e == -2 || e == -1 }
        if (nuevos.isEmpty()) {
            aviso("Ya marcados")
            return
        }
        val bytes = nuevos.sumOf { it.tam }
        val necesario = bytes * 2
        val libre = s.espacioLibre()
        val hacer = { s.guardarArchivos(nuevos, true); aviso("Guardando " + nuevos.size + " archivo(s)…") }
        val pocoEspacio = libre < necesario + 200L * 1024 * 1024
        if (pocoEspacio) {
            AlertDialog.Builder(this)
                .setTitle("Poco espacio libre")
                .setMessage(
                    "Guardar " + nuevos.size + " archivo(s) (" + Utiles.tam(bytes) + ") necesita unos " + Utiles.tam(necesario) +
                        " libres mientras se hace (la descarga temporal y la copia final), y ahora hay " + Utiles.tam(libre) + "."
                )
                .setPositiveButton("Intentar igualmente") { _, _ -> hacer() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else if (nuevos.size > 1) {
            AlertDialog.Builder(this)
                .setTitle("Guardar en el dispositivo")
                .setMessage(
                    nuevos.size.toString() + " archivos (" + Utiles.tam(bytes) + ") irán a " + Exportar.destinoTexto() +
                        ", con las mismas carpetas del torrent."
                )
                .setPositiveButton("Guardar") { _, _ -> hacer() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            hacer()
        }
    }

    // ---------------------------------------------------------------- sincronización de subtítulos

    private fun miniBoton(t: String, alPulsar: () -> Unit): TextView {
        val tv = texto(t, 14f)
        tv.minWidth = dp(44)
        tv.minHeight = dp(40)
        tv.setBackgroundColor(0x33FFFFFF)
        tv.setPadding(dp(8), dp(4), dp(8), dp(4))
        tv.setOnClickListener { alPulsar() }
        return tv
    }

    private fun construirSync() {
        barraSync = LinearLayout(this)
        barraSync.orientation = LinearLayout.VERTICAL
        barraSync.setBackgroundColor(0xAA000000.toInt())
        barraSync.setPadding(dp(8), dp(4), dp(8), dp(6))
        barraSync.isClickable = true
        barraSync.visibility = View.GONE

        syncTv = texto("", 14f)
        barraSync.addView(syncTv)

        fun fila(vararg items: View): LinearLayout {
            val f = LinearLayout(this)
            f.orientation = LinearLayout.HORIZONTAL
            f.gravity = Gravity.CENTER
            for (v in items) {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(dp(2), dp(2), dp(2), dp(2))
                f.addView(v, lp)
            }
            return f
        }

        val pasos = longArrayOf(-500L, -100L, -50L, 50L, 100L, 500L)
        val botonesPaso = ArrayList<View>()
        for (p in pasos) botonesPaso.add(miniBoton((if (p > 0) "+" else "") + p) { cambiarDesfase(p) })
        barraSync.addView(fila(*botonesPaso.toTypedArray()))

        barraSync.addView(
            fila(
                miniBoton("◀ Línea") { irALinea(-1) },
                miniBoton("Línea ▶") { irALinea(1) },
                miniBoton("● AHORA") { fijarAhora() },
                miniBoton("0") { fijarDesfase(0L) },
                miniBoton("✕") { barraSync.visibility = View.GONE }
            )
        )

        val ayuda = texto("«Línea ▶» salta un poco antes de un diálogo. Pulsa «AHORA» justo cuando EMPIECES a oír esa frase.", 11f)
        ayuda.alpha = 0.75f
        barraSync.addView(ayuda)

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        lp.topMargin = dp(60)
        lp.rightMargin = dp(40)
        raiz.addView(barraSync, lp)
    }

    private fun abrirSync() {
        val s = svc ?: return
        val f = s.archivoSubsActual()
        cuesSync = if (f != null) Utiles.tiemposSubs(f) else emptyList()
        cueObjetivo = -1L
        barraSync.visibility = View.VISIBLE
        pintarSync()
        if (cuesSync.isEmpty()) aviso("Sin archivo externo: solo ajuste con los botones ±")
    }

    private fun pintarSync() {
        val ms = (svc?.retardoSubs ?: 0L) / 1000L
        val linea = if (cueObjetivo >= 0 && cuesSync.isNotEmpty()) "  ·  línea ${cuesSync.indexOf(cueObjetivo) + 1}/${cuesSync.size}" else ""
        syncTv.text = "Desfase: " + (if (ms > 0) "+" else "") + ms + " ms" + linea
    }

    private fun fijarDesfase(ms: Long) {
        svc?.fijarRetardoSubs(ms)
        pintarSync()
    }

    private fun cambiarDesfase(delta: Long) {
        val actual = (svc?.retardoSubs ?: 0L) / 1000L
        fijarDesfase(actual + delta)
    }

    /** Salta a un instante un poco anterior a la línea siguiente/anterior para poder oírla entera. */
    private fun irALinea(dir: Int) {
        val s = svc ?: return
        val m = s.mp ?: return
        if (cuesSync.isEmpty()) {
            aviso("Esta función necesita subtítulos externos")
            return
        }
        val desfase = s.retardoSubs / 1000L
        val t = m.time
        val idx = if (cueObjetivo >= 0) {
            cuesSync.indexOf(cueObjetivo) + dir
        } else if (dir > 0) {
            cuesSync.indexOfFirst { it + desfase > t + 300L }
        } else {
            cuesSync.indexOfLast { it + desfase < t - 1500L }
        }
        if (idx < 0 || idx >= cuesSync.size) {
            aviso("No hay más líneas")
            return
        }
        cueObjetivo = cuesSync[idx]
        m.setTime((cueObjetivo + desfase - 1200L).coerceAtLeast(0L))
        m.play()
        pintarSync()
    }

    /** El usuario oye AHORA la línea objetivo: el desfase es la diferencia entre ese instante y el de su subtítulo. */
    private fun fijarAhora() {
        val m = svc?.mp ?: return
        if (cueObjetivo < 0) {
            aviso("Pulsa «Línea ▶» primero")
            return
        }
        // -250 ms: el tiempo que tarda un dedo en reaccionar. Se afina con los botones ±.
        val nuevo = m.time - cueObjetivo - 250L
        fijarDesfase(nuevo)
        aviso("Desfase " + nuevo + " ms")
    }

    private fun mostrarCapa() {
        capaVisible = true
        barraSup.visibility = View.VISIBLE
        barraInf.visibility = View.VISIBLE
        ocultarEn = SystemClock.uptimeMillis() + 4000
    }

    private fun ocultarCapa() {
        capaVisible = false
        barraSup.visibility = View.GONE
        barraInf.visibility = View.GONE
    }

    private fun aviso(t: String) {
        indicador.text = t
        indicador.visibility = View.VISIBLE
        h.removeCallbacks(quitarIndicador)
        h.postDelayed(quitarIndicador, 900)
    }

    private fun cambiarBrillo(dy: Float) {
        val a = window.attributes
        var b = if (a.screenBrightness < 0) 0.5f else a.screenBrightness
        b = (b + dy / raiz.height).coerceIn(0.02f, 1f)
        a.screenBrightness = b
        window.attributes = a
        aviso("Brillo ${(b * 100).toInt()} %")
    }

    private fun cambiarVolumen(dy: Float) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        acumVol += dy / raiz.height * max * 1.5f
        val pasos = acumVol.toInt()
        if (pasos != 0) {
            acumVol -= pasos
            val nuevo = (am.getStreamVolume(AudioManager.STREAM_MUSIC) + pasos).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, nuevo, 0)
        }
        aviso("Volumen ${am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max} %")
    }

    @Suppress("DEPRECATION")
    private fun inmersivo() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    // ---------------------------------------------------------------- refresco periódico

    private fun actualizar() {
        val s = svc ?: return
        adjuntarSiHaceFalta()
        val m = s.mp
        if (m != null) {
            val len = try { m.length } catch (e: Throwable) { 0L }
            val t = try { m.time } catch (e: Throwable) { 0L }
            if (!arrastrando) {
                seekBar.max = if (len > 0) len.toInt() else 1
                seekBar.progress = t.toInt().coerceAtLeast(0)
                tiempoTv.text = if (s.hayCanales && len <= 0) "● EN DIRECTO" else Utiles.fmt(t) + " / " + (if (len > 0) Utiles.fmt(len) else "--")
            }
            btnPlay.text = if (m.isPlaying) "❚❚" else "▶"
        }
        var tit = s.titulo
        val canalAct = s.canalActivo
        val gu = s.guia
        if (canalAct != null && gu != null) {
            val ahoraProg = gu.ahora(canalAct)
            if (ahoraProg != null) tit += "  ·  " + ahoraProg.titulo
        }
        tituloTv.text = tit

        if (aj.mostrarStats && s.esTorrent && m != null && s.error == null && s.estado.isEmpty()) {
            val len2 = try { m.length } catch (e: Throwable) { 0L }
            val t2 = try { m.time } catch (e: Throwable) { 0L }
            val st = s.motor.estadisticasDetalle(if (len2 > 0) t2.toDouble() / len2 else 0.0, len2)
            panelStats.text = st
            panelStats.visibility = if (st.isEmpty()) View.GONE else View.VISIBLE
        } else {
            panelStats.visibility = View.GONE
        }

        val msg = when {
            s.error != null -> s.error!!
            s.estado.isNotEmpty() -> s.estado
            s.esTorrent && s.mp?.isPlaying == true && s.motor.estadisticas().isNotEmpty() && s.buffering < 100f ->
                "Cargando ${s.buffering.toInt()} % · " + s.motor.estadisticas()
            s.buffering < 99.5f && s.mp?.isPlaying == true -> "Cargando ${s.buffering.toInt()} %"
            else -> ""
        }
        estadoTv.text = msg
        estadoTv.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE

        val av = s.avisoUi
        if (av != null) {
            s.avisoUi = null
            aviso(av)
        }

        val conLista = s.hayLista
        btnAnt.visibility = if (conLista) View.VISIBLE else View.GONE
        btnSig.visibility = if (conLista) View.VISIBLE else View.GONE
        btnLista.visibility =
            if ((s.esTorrent && s.archivosTorrent.isNotEmpty()) || s.hayCanales) View.VISIBLE else View.GONE

        // Lista de canales: el panel se abre solo al cargarla, hasta que se elija uno.
        if (s.hayCanales) {
            if (s.necesitaElegirCanal) {
                if (!canalesAuto) {
                    canalesAuto = true
                    abrirPanelCanales()
                }
            } else {
                canalesAuto = false
            }
            if (panelCanAbierto) panelCan.actualizar(aj.favoritos, s.guia, s.canalActual)
        }

        // El panel de archivos se abre solo cuando hay que elegir, o al volver con descargas en curso sin reproducir.
        val debeElegir = s.necesitaElegir || (s.mp == null && s.hayGuardadoPendiente() && s.archivosTorrent.isNotEmpty())
        if (debeElegir) {
            if (!panelAuto) {
                panelAuto = true
                abrirPanel()
            }
        } else {
            panelAuto = false
        }
        if (panelAbierto) refrescarPanel(s)

        if (s.fin || s.error != null) {
            if (!capaVisible) mostrarCapa()
        } else if (capaVisible && m?.isPlaying == true && SystemClock.uptimeMillis() > ocultarEn) {
            ocultarCapa()
        }
    }

    // ---------------------------------------------------------------- menús

    private fun lista(titulo: String, items: List<String>, alElegir: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setItems(items.toTypedArray()) { _, i -> alElegir(i) }
            .show()
    }

    private fun menuMas() {
        val s = svc ?: return
        val etiquetas = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()
        etiquetas.add("Información"); acciones.add { Informacion.mostrar(this, s) }
        if (s.hayCanales) {
            etiquetas.add("Canales"); acciones.add { abrirPanelCanales() }
            etiquetas.add("Programación de este canal"); acciones.add { dialogoProgramacion() }
            etiquetas.add("Guía de programación…"); acciones.add { dialogoGuia() }
        }
        etiquetas.add("Velocidad"); acciones.add { menuVelocidad() }
        etiquetas.add("Decodificación de vídeo"); acciones.add { menuDecodificacion() }
        if (s.esTorrent) {
            etiquetas.add("Estadísticas del torrent: " + (if (aj.mostrarStats) "sí" else "no"))
            acciones.add {
                aj.mostrarStats = !aj.mostrarStats
                aviso(if (aj.mostrarStats) "Estadísticas activadas" else "Estadísticas ocultas")
            }
        }
        etiquetas.add("Retardo del audio"); acciones.add {
            DialogoSubs.desfase(this, "Retardo del audio", (s.mp?.audioDelay ?: 0L) / 1000L) { ms ->
                s.mp?.setAudioDelay(ms * 1000L)
            }
        }
        if (s.esTorrent && s.archivosTorrent.isNotEmpty()) {
            etiquetas.add("Archivos del torrent"); acciones.add { abrirPanel() }
            val actual = s.archivoTorrentActual
            if (actual != null) {
                etiquetas.add("Guardar este archivo en el dispositivo"); acciones.add { pedirGuardar(listOf(actual)) }
            }
            if (s.archivosTorrent.size > 1) {
                etiquetas.add("Guardar todo el torrent"); acciones.add { pedirGuardar(s.archivosTorrent) }
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            etiquetas.add("Imagen en imagen"); acciones.add { entrarPip() }
        }
        etiquetas.add("Ajustes"); acciones.add { startActivity(Intent(this, AjustesActivity::class.java)) }
        etiquetas.add("Cerrar reproductor"); acciones.add { salir() }
        lista("Opciones", etiquetas) { acciones[it]() }
    }

    private fun menuVelocidad() {
        val vel = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        lista("Velocidad", vel.map { "${it}×" }) {
            svc?.mp?.setRate(vel[it])
            aviso("Velocidad ${vel[it]}×")
        }
    }

    /** Si un vídeo (p. ej. HEVC/x265) sale en negro con audio, casi siempre basta cambiar a «Solo software». */
    private fun menuDecodificacion() {
        val nombres = listOf(
            "Automática (hardware si puede)",
            "Solo software (CPU) · recomendado si sale en negro",
            "Hardware forzado"
        )
        val actual = aj.decodificacion
        lista("Decodificación de vídeo", nombres.mapIndexed { i, n -> (if (i == actual) "● " else "") + n }) {
            aj.decodificacion = it
            aviso("Aplicando…")
            svc?.reconstruir()
        }
    }

    private fun menuAspecto() {
        val nombres = listOf("Ajustar", "Llenar pantalla", "Recortar", "16:9", "4:3", "Original")
        val tipos = listOf(
            MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, MediaPlayer.ScaleType.SURFACE_16_9,
            MediaPlayer.ScaleType.SURFACE_4_3, MediaPlayer.ScaleType.SURFACE_ORIGINAL
        )
        lista("Relación de aspecto", nombres) { svc?.mp?.setVideoScale(tipos[it]) }
    }

    private fun menuAudio() {
        val m = svc?.mp ?: return
        val pistas = m.audioTracks
        if (pistas == null || pistas.isEmpty()) {
            aviso("Sin pistas de audio")
            return
        }
        val actual = m.audioTrack
        lista("Pista de audio", pistas.map { (if (it.id == actual) "● " else "") + it.name }) {
            m.setAudioTrack(pistas[it].id)
        }
    }

    private fun menuSubtitulos() {
        val s = svc ?: return
        val m = s.mp ?: return
        val etiquetas = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()
        val pistas = m.spuTracks
        if (pistas != null) {
            val actual = m.spuTrack
            for (p in pistas) {
                etiquetas.add((if (p.id == actual) "● " else "") + p.name)
                acciones.add { m.setSpuTrack(p.id) }
            }
        }
        etiquetas.add("Cargar archivo de subtítulos…"); acciones.add { elegirArchivoSubs() }
        etiquetas.add("Buscar en OpenSubtitles…"); acciones.add { buscarOnline() }
        etiquetas.add("Sincronizar / desfase…"); acciones.add { abrirSync() }
        etiquetas.add("Estilo (tamaño, color, fondo…)"); acciones.add {
            DialogoSubs.estilo(this, aj) {
                aviso("Aplicando estilo…")
                s.reconstruir()
            }
        }
        lista("Subtítulos", etiquetas) { acciones[it]() }
    }

    @Suppress("DEPRECATION")
    private fun entrarPip() {
        try {
            val p = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            enterPictureInPictureMode(p)
        } catch (e: Exception) {
            aviso("Imagen en imagen no disponible")
        }
    }

    // ---------------------------------------------------------------- subtítulos externos y en línea

    private fun elegirArchivoSubs() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, PEDIR_SUBTITULO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        if (codigo != PEDIR_SUBTITULO || resultado != RESULT_OK) return
        val u = datos?.data ?: return
        Thread {
            try {
                var nombre = "subs"
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) nombre = c.getString(0) ?: nombre
                }
                val dir = File(cacheDir, "subs")
                dir.mkdirs()
                val f = File(dir, nombre.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
                contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                h.post {
                    svc?.anadirSubtitulo(f)
                    aviso("Subtítulos cargados")
                }
            } catch (e: Exception) {
                h.post { aviso("No se pudo cargar: ${e.message}") }
            }
        }.start()
    }

    private fun buscarOnline() {
        val s = svc ?: return
        if (aj.osApiKey.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("Falta la API key de OpenSubtitles. Configúrala en Ajustes.")
                .setPositiveButton("Ajustes") { _, _ -> startActivity(Intent(this, AjustesActivity::class.java)) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val campo = EditText(this)
        campo.setText(s.nombreParaSubs() + " ")
        campo.setSingleLine(true)
        val idioma = EditText(this)
        idioma.hint = "Idioma (es, en…)"
        idioma.setText(aj.subIdioma)
        idioma.setSingleLine(true)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), 0)
        col.addView(campo)
        col.addView(idioma)
        AlertDialog.Builder(this)
            .setTitle("Buscar en OpenSubtitles")
            .setView(col)
            .setPositiveButton("Buscar") { _, _ ->
                val q = campo.text.toString().trim()
                val lang = idioma.text.toString().trim().lowercase()
                if (lang.isNotEmpty()) aj.subIdioma = lang
                ejecutarBusqueda(q, lang)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ejecutarBusqueda(q: String, lang: String) {
        val s = svc ?: return
        aviso("Buscando subtítulos…")
        Thread {
            try {
                val hash = s.calcularHash()
                val r = OpenSubtitles.buscar(aj, q, lang, hash)
                h.post {
                    if (r.isEmpty()) {
                        aviso("Sin resultados")
                    } else {
                        val etiquetas = r.take(30).map {
                            (if (it.porHash) "✔ " else "") + it.nombre + "  [" + it.idioma + " · " + it.descargas + "↓]"
                        }
                        lista("Resultados", etiquetas) { i -> descargarSub(r[i]) }
                    }
                }
            } catch (e: Exception) {
                h.post { aviso(e.message ?: "Error de búsqueda") }
            }
        }.start()
    }

    private fun descargarSub(r: OpenSubtitles.Resultado) {
        aviso("Descargando…")
        Thread {
            try {
                val f = OpenSubtitles.descargar(aj, r, File(cacheDir, "subs"))
                h.post {
                    svc?.anadirSubtitulo(f)
                    aviso("Subtítulos cargados")
                }
            } catch (e: Exception) {
                h.post { aviso(e.message ?: "Error de descarga") }
            }
        }.start()
    }
}
