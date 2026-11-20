package com.optimo.play

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
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
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
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
    private val PEDIR_CARPETA = 43

    private var svc: PlaybackService? = null
    private var pendiente: Fuente? = null
    private var pendienteCola: PedidoCola? = null
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
    private lateinit var panelCola: PanelCola
    private var panelColaAbierto = false
    private var pidiendoCarpeta = false
    private var pidiendoClaveMega = false
    // Diálogos de archivos comprimidos que están en pantalla (no banderas: un diálogo que se cierra al abrir el siguiente no debe
    // hacer que la lista se vuelva a abrir encima).
    private var dlgPaquete: android.app.Dialog? = null
    private var dlgClavePaquete: android.app.Dialog? = null
    private var audioVisto = false
    private lateinit var filaBot: LinearLayout
    private lateinit var filaOpc: LinearLayout
    private lateinit var lpStats: FrameLayout.LayoutParams
    private val botonesTransporte = ArrayList<View>()
    private val botonesOpciones = ArrayList<View>()
    private var insIzq = 0
    private var insSup = 0
    private var insDer = 0
    private var insInf = 0
    private var panelCanAbierto = false
    private var canalesAuto = false
    private var panelAbierto = false
    private var panelAuto = false
    private var conservarAlSalir = false

    // ---- emisión a la tele (mando remoto)
    private lateinit var panelRemoto: LinearLayout
    private lateinit var remTitulo: TextView
    private lateinit var remEstado: TextView
    private lateinit var remTiempo: TextView
    private lateinit var remSeek: SeekBar
    private lateinit var remBtnPlay: TextView
    private var remArrastrando = false
    private var remMin = false
    private var remPanelPrev = false
    private var remModo = 0
    private lateinit var remMini: LinearLayout
    private lateinit var remMiniTxt: TextView
    private lateinit var remMiniPlay: TextView
    private var detenerCast: (() -> Unit)? = null
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
    /** Cada 150 ms: pone el subtítulo propio de un directo y mueve la columna de líneas. */
    private val ticSubs = object : Runnable {
        override fun run() {
            try { pintarSubsDirecto() } catch (e: Throwable) { /* nada: no debe tumbar la pantalla */ }
            h.postDelayed(this, 150)
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
            val pc = pendienteCola
            pendienteCola = null
            if (pc != null) abrirColaConfirmando(s, pc) else if (p != null) abrirConfirmando(s, p)
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
        aplicarOrientacion()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val atr = window.attributes
            atr.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = atr
        }
        construirUi()
        setContentView(raiz)
        acomodar()
        raiz.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        pendienteCola = ColaIntent.tomar(intent)
        pendiente = if (pendienteCola != null) null else fuenteDesde(intent)
        // Servicio en primer plano + conexión: el servicio sobrevive a la actividad (segundo plano).
        startForegroundService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), conexion, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        val pc = ColaIntent.tomar(i)
        if (pc != null) {
            val s = svc
            if (s == null) pendienteCola = pc else abrirColaConfirmando(s, pc)
            return
        }
        val p = fuenteDesde(i) ?: return
        val s = svc
        if (s == null) pendiente = p else abrirConfirmando(s, p)
    }

    /** Empieza una cola de archivos locales; si hay archivos de un torrent guardándose, pregunta antes de cancelarlos. */
    private fun abrirColaConfirmando(s: PlaybackService, pc: PedidoCola) {
        if (s.hayGuardadoPendiente()) {
            AlertDialog.Builder(this)
                .setTitle("Hay archivos guardándose")
                .setMessage("Se están descargando para guardarlos en el dispositivo (" + s.resumenGuardado() +
                    "). Si abres otra cosa se cancelarán.")
                .setPositiveButton("Cancelar y abrir") { _, _ -> s.abrirCola(pc.pistas, pc.indice, pc.titulo) }
                .setNegativeButton("Seguir guardando", null)
                .show()
            return
        }
        s.abrirCola(pc.pistas, pc.indice, pc.titulo)
    }

    // ---------------------------------------------------------------- orientación e insets

    /** Automática (sigue el bloqueo de rotación del sistema), siempre horizontal o siempre vertical. */
    private fun aplicarOrientacion() {
        requestedOrientation = when (aj.orientacion) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    override fun onConfigurationChanged(nueva: Configuration) {
        super.onConfigurationChanged(nueva)
        acomodar()
        inmersivo()
    }

    @Suppress("DEPRECATION")
    private fun aplicarInsets(ins: WindowInsets) {
        var izq = 0
        var der = 0
        var inf = 0
        var sup = ins.systemWindowInsetTop
        if (Build.VERSION.SDK_INT >= 28) {
            val c = ins.displayCutout
            if (c != null) {
                izq = c.safeInsetLeft
                der = c.safeInsetRight
                inf = c.safeInsetBottom
                sup = maxOf(sup, c.safeInsetTop)
            }
        }
        if (izq != insIzq || der != insDer || inf != insInf || sup != insSup) {
            insIzq = izq
            insDer = der
            insInf = inf
            insSup = sup
            acomodar()
        }
    }

    /**
     * Ajusta la interfaz a la orientación y a la muesca de la pantalla: márgenes laterales (en horizontal los bordes los
     * usa el gesto «atrás»), anchos de los paneles y reparto de los botones (en vertical, dos filas).
     */
    private fun acomodar() {
        val v = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val lat = if (v) dp(8) else dp(40)
        barraSup.setPadding(maxOf(lat, insIzq), insSup + dp(4), maxOf(lat, insDer), dp(4))
        val latInf = if (v) dp(8) else 0
        barraInf.setPadding(maxOf(latInf, insIzq), 0, maxOf(latInf, insDer), insInf + (if (v) dp(16) else 0))
        lpStats.leftMargin = maxOf(lat, insIzq)
        lpStats.topMargin = insSup + dp(60)
        panelStats.layoutParams = lpStats
        val ls = barraSync.layoutParams as FrameLayout.LayoutParams
        ls.rightMargin = maxOf(lat, insDer)
        ls.topMargin = insSup + dp(60)
        barraSync.layoutParams = ls
        val ancho = resources.displayMetrics.widthPixels
        val fraccion = if (v) 0.94f else 0.85f
        val padDer = maxOf(if (v) dp(8) else dp(40), insDer)
        fun panel(vista: View, maxDp: Int) {
            val lp = vista.layoutParams as FrameLayout.LayoutParams
            lp.width = minOf((ancho * fraccion).toInt(), dp(maxDp))
            vista.layoutParams = lp
            vista.setPadding(dp(8), insSup + dp(8), padDer, dp(8))
        }
        panel(panelArchivos, 420)
        panel(panelCan.vista, 440)
        panel(panelCola.vista, 440)
        reubicarBotones(v)
    }

    private fun reubicarBotones(vertical: Boolean) {
        filaBot.removeAllViews()
        filaOpc.removeAllViews()
        for (b in botonesTransporte) filaBot.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val destino = if (vertical) filaOpc else filaBot
        for (b in botonesOpciones) destino.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filaOpc.visibility = if (vertical) View.VISIBLE else View.GONE
    }

    private fun menuOrientacion() {
        val nombres = listOf("Automática (según el bloqueo de rotación)", "Siempre horizontal", "Siempre vertical")
        lista("Orientación", nombres.mapIndexed { i, n -> (if (i == aj.orientacion) "● " else "") + n }) {
            aj.orientacion = it
            aplicarOrientacion()
        }
    }

    /** Abre una fuente nueva; si hay archivos guardándose de un torrent, pregunta antes de cancelarlos. */
    private fun abrirConfirmando(s: PlaybackService, p: Fuente) {
        if (s.hayGuardadoPendiente()) {
            if (s.fuente?.uri == p.uri || s.esMismoEnlaceMega(p.uri)) return
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
        val emitiendo = s != null && s.emisor != null
        val guardando = s != null && s.hayGuardadoPendiente()
        if (s != null && (emitiendo || guardando)) {
            var msg = ""
            if (emitiendo) msg += "Se está emitiendo a " + (s.destinoEmision?.nombre ?: "la tele") + "."
            if (guardando) msg += (if (emitiendo) "\n" else "") + "Aún se están descargando archivos para guardar (" + s.resumenGuardado() + ")."
            AlertDialog.Builder(this)
                .setTitle(if (emitiendo) "Emitiendo a la tele" else "Guardando en el dispositivo")
                .setMessage(msg)
                .setPositiveButton("Seguir en segundo plano") { _, _ ->
                    conservarAlSalir = true
                    finish()
                }
                .setNegativeButton("Parar y salir") { _, _ ->
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
        if (panelColaAbierto) cerrarPanelCola() else if (panelCanAbierto) cerrarPanelCanales() else if (panelAbierto) cerrarPanel() else salir()
    }

    override fun onStart() {
        super.onStart()
        aplicarOrientacion()
        inmersivo()
        h.post(tic)
        h.post(ticSubs)
    }

    override fun onStop() {
        h.removeCallbacks(tic)
        h.removeCallbacks(ticSubs)
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
        try { detenerCast?.invoke() } catch (e: Throwable) { /* nada */ }
        detenerCast = null
        try { unbindService(conexion) } catch (e: Exception) { /* nada */ }
        // Salir con «atrás» detiene la reproducción; ir al escritorio o bloquear NO destruye la actividad.
        if (isFinishing) {
            val s = svc
            if (conservarAlSalir && s != null && (s.hayGuardadoPendiente() || s.emisor != null)) s.soloDescarga() else s?.detener()
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
        // Contraseña de un enlace de Mega protegido: como extra «password» o, al compartir texto, «Contraseña: xxx».
        var clave: String? = null
        if (MegaEnlace.esMega(uri)) {
            clave = i.getStringExtra("password")?.takeIf { it.isNotEmpty() }
            if (clave == null && i.action == Intent.ACTION_SEND) clave = MegaEnlace.claveEnTexto(i.getStringExtra(Intent.EXTRA_TEXT))
        }
        return Fuente(uri, titulo, i.getStringExtra("referer"), i.getStringExtra("user_agent"), clave)
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

        filaBot = LinearLayout(this)
        filaBot.orientation = LinearLayout.HORIZONTAL
        filaBot.gravity = Gravity.CENTER
        filaOpc = LinearLayout(this)
        filaOpc.orientation = LinearLayout.HORIZONTAL
        filaOpc.gravity = Gravity.CENTER
        btnAnt = boton("⏮") { svc?.anterior() }
        btnAnt.visibility = View.GONE
        botonesTransporte.add(btnAnt)
        botonesTransporte.add(boton("−10") { svc?.saltar(-10_000) })
        btnPlay = boton("▶") { svc?.alternar() }
        botonesTransporte.add(btnPlay)
        botonesTransporte.add(boton("+10") { svc?.saltar(10_000) })
        btnSig = boton("⏭") { svc?.siguiente() }
        btnSig.visibility = View.GONE
        botonesTransporte.add(btnSig)
        botonesOpciones.add(boton("CC") { menuSubtitulos() })
        botonesOpciones.add(boton("Audio") { menuAudio() })
        botonesOpciones.add(boton("Aspecto") { menuAspecto() })
        barraInf.addView(filaBot)
        barraInf.addView(filaOpc)
        reubicarBotones(false)
        raiz.addView(barraInf, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Panel de estadísticas del torrent (arriba a la izquierda, pequeño y translúcido).
        panelStats = texto("", 12f)
        panelStats.gravity = Gravity.START
        panelStats.setBackgroundColor(0x77000000)
        panelStats.setPadding(dp(8), dp(4), dp(8), dp(4))
        panelStats.visibility = View.GONE
        lpStats = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        lpStats.topMargin = dp(60)
        lpStats.leftMargin = dp(40)
        raiz.addView(panelStats, lpStats)

        construirSubOverlay()
        construirSync()
        construirPanel()
        construirPanelCanales()
        construirPanelCola()
        construirRemoto()
        raiz.setOnApplyWindowInsetsListener { _, ins ->
            aplicarInsets(ins)
            ins
        }

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
        } else if (svc?.hayCola == true) {
            if (panelColaAbierto) cerrarPanelCola() else abrirPanelCola()
        } else {
            if (panelAbierto) cerrarPanel() else abrirPanel()
        }
    }

    // ---------------------------------------------------------------- cola de reproducción

    private fun construirPanelCola() {
        panelCola = PanelCola(
            this,
            { i -> svc?.saltarACola(i) },
            { i -> menuPistaCola(i) },
            { svc?.let { it.fijarAleatorio(!it.aleatorio) } },
            { svc?.let { it.fijarRepetir((it.repetir + 1) % 3) } },
            { dialogoVaciarCola() },
            { cerrarPanelCola() }
        )
        panelCola.vista.visibility = View.GONE
        val ancho = minOf((resources.displayMetrics.widthPixels * 0.85f).toInt(), dp(440))
        raiz.addView(panelCola.vista, FrameLayout.LayoutParams(ancho, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    private fun abrirPanelCola() {
        val s = svc ?: return
        if (!s.hayCola) return
        panelCola.cargar(s.cola, s.colaActual, s.repetir, s.aleatorio, s.tituloCola)
        panelCola.vista.visibility = View.VISIBLE
        panelColaAbierto = true
        mostrarCapa()
    }

    private fun cerrarPanelCola() {
        panelCola.vista.visibility = View.GONE
        panelColaAbierto = false
    }

    private fun menuPistaCola(i: Int) {
        val s = svc ?: return
        val p = s.cola.getOrNull(i) ?: return
        val etiquetas = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()
        if (i != s.colaActual) {
            etiquetas.add("Reproducir a continuación"); acciones.add { s.ponerSiguiente(i) }
        }
        if (i > 0) { etiquetas.add("Subir"); acciones.add { s.moverEnCola(i, -1) } }
        if (i < s.cola.size - 1) { etiquetas.add("Bajar"); acciones.add { s.moverEnCola(i, 1) } }
        etiquetas.add("Quitar de la cola"); acciones.add { s.quitarDeCola(i) }
        val nodoMega = s.megaNodoDe(p.uri)
        if (nodoMega != null) {
            etiquetas.add(
                when (s.estadoGuardadoMega(nodoMega)) {
                    0 -> "Cancelar el guardado de este archivo"
                    2 -> "Este archivo ya está guardado ✓"
                    else -> "Guardar este archivo en el dispositivo"
                }
            )
            acciones.add { pedirGuardarMega(listOf(nodoMega)) }
        }
        lista(p.titulo, etiquetas) { acciones[it]() }
    }

    private fun dialogoVaciarCola() {
        val s = svc ?: return
        AlertDialog.Builder(this)
            .setMessage("¿Vaciar la cola? Lo que suena ahora seguirá sonando.")
            .setPositiveButton("Vaciar") { _, _ ->
                s.vaciarCola()
                cerrarPanelCola()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** El reproductor necesita acceso a la carpeta de la lista (o del audio): se explica y se abre el selector de carpetas. */
    private fun dialogoClaveMega(s: PlaybackService) {
        val campo = EditText(this)
        campo.hint = "Contraseña"
        campo.setSingleLine(true)
        campo.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val marco = FrameLayout(this)
        marco.setPadding(dp(20), dp(8), dp(20), 0)
        marco.addView(campo)
        val aviso = s.avisoClaveMega
        AlertDialog.Builder(this)
            .setTitle("Enlace de Mega con contraseña")
            .setMessage(
                if (aviso != null) "$aviso. Vuelve a escribirla."
                else "Este enlace está protegido. Escribe la contraseña para abrirlo."
            )
            .setView(marco)
            .setPositiveButton("Abrir") { _, _ -> s.darClaveMega(campo.text.toString()) }
            .setNegativeButton("Cancelar") { _, _ -> s.claveMegaRechazada() }
            .setOnCancelListener { s.claveMegaRechazada() }
            .show()
    }

    private fun dialogoClavePaquete(s: PlaybackService, p: MegaPaquete) {
        val campo = EditText(this)
        campo.hint = "Contraseña"
        campo.setSingleLine(true)
        campo.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val marco = FrameLayout(this)
        marco.setPadding(dp(20), dp(8), dp(20), 0)
        marco.addView(campo)
        val aviso = s.avisoClavePaquete
        val d = AlertDialog.Builder(this)
            .setTitle("«" + p.base + "» tiene contraseña")
            .setMessage(
                if (aviso != null) "$aviso. Vuelve a escribirla."
                else "Escribe la contraseña para extraerlo. Las partes ya están descargadas."
            )
            .setView(marco)
            .setPositiveButton("Extraer") { _, _ -> s.darClavePaquete(campo.text.toString()) }
            .setNegativeButton("Cancelar") { _, _ -> s.claveRechazadaPaquete() }
            .setOnCancelListener { s.claveRechazadaPaquete() }
            .create()
        dlgClavePaquete = d
        d.setOnDismissListener { if (dlgClavePaquete === d) dlgClavePaquete = null }
        d.show()
    }

    /** Enseña un diálogo de paquete y lo recuerda como «el que está en pantalla» (se olvida solo cuando ese mismo se cierra). */
    private fun mostrarDlgPaquete(b: AlertDialog.Builder) {
        val d = b.create()
        dlgPaquete = d
        d.setOnDismissListener { if (dlgPaquete === d) dlgPaquete = null }
        d.show()
    }

    private fun dialogoFalloPaquete(mensaje: String) {
        AlertDialog.Builder(this)
            .setTitle("No se pudo extraer")
            .setMessage(mensaje)
            .setPositiveButton("Ver registro") { _, _ -> dialogoRegistroMega() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Registro de lo que ha pasado al bajar y extraer (para saber por qué falla algo), con «Copiar». */
    private fun dialogoRegistroMega() {
        val tv = android.widget.TextView(this)
        tv.text = RegistroMega.texto()
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.textSize = 11f
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(16), dp(8), dp(16), dp(8))
        val scroll = android.widget.ScrollView(this)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Registro de extracción")
            .setView(scroll)
            .setPositiveButton("Copiar") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Optimo Play", RegistroMega.texto()))
                aviso("Registro copiado")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Lista de los archivos comprimidos del enlace de Mega: tocar uno para ver qué se puede hacer con él. */
    private fun dialogoPaquetes(s: PlaybackService, automatico: Boolean) {
        val ps = s.megaPaquetes
        if (ps.isEmpty()) return
        val filas = ps.map { p ->
            val est = s.estadoPaquete(p)
            val marca = when {
                p.faltan != null -> "⚠ " + p.faltan
                est == 0 -> "⏳ " + s.fasePaquete(p).substringAfter(": ")
                est == 1 -> "🔒 espera la contraseña"
                est == 2 -> "✓ extraído"
                est == -1 -> "✗ " + (s.errorPaquete(p) ?: "error")
                else -> ""
            }
            "📦 " + p.base + "\n" + p.etiqueta + " · " + Utiles.tam(p.tam) + (if (marca.isNotEmpty()) "\n" + marca else "")
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Archivos comprimidos (" + ps.size + ")")
            .setItems(filas.toTypedArray()) { _, i -> menuPaquete(s, ps[i]) }
        if (automatico) {
            // Si no hay nada que reproducir, la lista no se puede descartar sin más: o se elige o se cierra el reproductor.
            b.setCancelable(false).setNegativeButton("Cerrar el reproductor") { _, _ -> finish() }
        } else {
            b.setNegativeButton("Cerrar", null)
        }
        mostrarDlgPaquete(b)
    }

    private fun menuPaquete(s: PlaybackService, p: MegaPaquete) {
        if (p.faltan != null) {
            mostrarDlgPaquete(
                AlertDialog.Builder(this)
                    .setTitle(p.base)
                    .setMessage(p.faltan + ". Sin todas las partes no se puede extraer ni reproducir.")
                    .setPositiveButton("Entendido", null)
            )
            return
        }
        val etiquetas = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()
        val est = s.estadoPaquete(p)
        if (est == 2) {
            etiquetas.add("Reproducir lo extraído"); acciones.add { s.reproducirExtraido(p) }
        }
        if (est == 0 || est == 1) {
            etiquetas.add("Cancelar"); acciones.add { s.cancelarPaquete(p) }
        } else {
            if (p.tipo == MegaPaquete.Tipo.RAR) {
                etiquetas.add("Reproducir sin extraer (si el vídeo no está comprimido)"); acciones.add { s.reproducirSinExtraer(p) }
            }
            etiquetas.add(if (est == 2) "Volver a extraer" else "Descargar y extraer en el dispositivo"); acciones.add { pedirExtraer(p) }
        }
        mostrarDlgPaquete(
            AlertDialog.Builder(this)
                .setTitle(p.base)
                .setItems(etiquetas.toTypedArray()) { _, i -> acciones[i]() }
                .setNegativeButton("Cerrar", null)
        )
    }

    /** Confirma (y avisa del espacio) antes de bajar las partes de un archivo comprimido y extraerlo. */
    private fun pedirExtraer(p: MegaPaquete) {
        val s = svc ?: return
        val libre = s.espacioLibre()
        val necesario = p.tam * 2 + 200L * 1024 * 1024
        val hacer = { s.extraerPaquete(p); aviso("Bajando «" + p.base + "»…") }
        val b = AlertDialog.Builder(this)
        if (libre < necesario) {
            b.setTitle("Poco espacio libre")
                .setMessage(
                    "Hace falta sitio para las partes (" + Utiles.tam(p.tam) + ") y para lo extraído (más o menos otro tanto), y ahora hay " +
                        Utiles.tam(libre) + " libres."
                )
                .setPositiveButton("Intentar igualmente") { _, _ -> hacer() }
        } else {
            b.setTitle("Descargar y extraer")
                .setMessage(
                    "Se bajarán " + p.partes.size + " parte(s) (" + Utiles.tam(p.tam) + "), se juntarán y se extraerá su contenido a " +
                        Exportar.destinoTexto() + ". Las partes se borran al terminar. Cuenta para el límite de transferencia de Mega."
                )
                .setPositiveButton("Empezar") { _, _ -> hacer() }
        }
        mostrarDlgPaquete(b.setNegativeButton("Cancelar", null))
    }

    private fun dialogoCarpeta(s: PlaybackService, doc: Uri) {
        val nombre = Utiles.nombreDe(this, doc.toString())
        AlertDialog.Builder(this)
            .setTitle("Acceso a la carpeta")
            .setMessage(
                "Para reproducir «" + nombre + "» junto con los demás archivos necesito acceso a su carpeta. " +
                    "Elígela en la pantalla siguiente (basta con una vez: se recuerda). La carpeta puede ser la de la lista " +
                    "o una que la contenga, por ejemplo «Música»."
            )
            .setPositiveButton("Elegir carpeta") { _, _ -> elegirCarpeta(doc) }
            .setNegativeButton("Ahora no") { _, _ -> s.carpetaRechazada() }
            .setOnCancelListener { s.carpetaRechazada() }
            .show()
    }

    private fun elegirCarpeta(doc: Uri) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        try {
            val id = DocumentsContract.getDocumentId(doc)
            val padre = if (id.contains('/')) id.substringBeforeLast('/') else id
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(doc.authority, padre))
        } catch (e: Exception) {
            // Sin carpeta inicial: el selector abre donde quiera.
        }
        try {
            Gestor.lanzar(this, i, PEDIR_CARPETA)
        } catch (e: Exception) {
            svc?.carpetaRechazada()
            aviso("No hay selector de carpetas disponible")
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
        val cs = s.canales
        val favs = aj.favoritos
        val appCtx = applicationContext
        val guia = guiaEfectiva()
        Thread { Biblioteca.adoptarFavoritos(appCtx, cs, favs, guia) }.start()
        panelCan.vista.visibility = View.VISIBLE
        panelCanAbierto = true
        mostrarCapa()
    }

    private fun cerrarPanelCanales() {
        panelCan.vista.visibility = View.GONE
        panelCanAbierto = false
    }

    /** Guía de la lista que se está viendo: la puesta a mano o, si no hay, la que anuncia la propia lista. */
    private fun guiaEfectiva(): String? {
        val s = svc ?: return null
        val manual = aj.guiaDe(s.fuente?.uri ?: "")
        return if (manual.isNotEmpty()) manual else s.guiaDeLaLista()
    }

    private fun alternarFavorito(c: Canal) {
        aj.alternarFavorito(c.clave)
        val marcado = aj.favoritos.contains(c.clave)
        val appCtx = applicationContext
        val guia = guiaEfectiva()
        Thread { Biblioteca.marcarFavorito(appCtx, c, marcado, guia) }.start()
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

    // ---------------------------------------------------------------- subtítulos de directos con reloj propio

    private lateinit var subOverlay: TextView
    private var claveEstiloSub = ""
    private lateinit var panelLineas: LinearLayout
    private lateinit var lineasTv: TextView
    private lateinit var lineasBusca: EditText
    private lateinit var lineasLista: ListView
    private lateinit var lineasPausa: TextView
    private var lineasFiltro: List<Subs.Cue> = emptyList()
    private var lineasSeguir = true
    private var cueActual: Subs.Cue? = null
    private var ultimoSeguido = -2
    private var lineasConstruido = false

    /** Inglés si el audio del canal es inglés; español si es otro idioma conocido; si no se sabe, el idioma de Ajustes. */
    private fun idiomaSugerido(s: PlaybackService): String {
        try {
            val m = s.mp ?: return aj.subIdioma
            val id = m.audioTrack
            val nombre = m.audioTracks?.firstOrNull { it.id == id }?.name ?: return aj.subIdioma
            if (Utiles.coincideIdioma(nombre, "en")) return "en"
            for (c in listOf("es", "fr", "de", "it", "pt", "ja", "ko")) {
                if (Utiles.coincideIdioma(nombre, c)) return "es"
            }
        } catch (e: Throwable) {
            // Sin pistas de audio todavía.
        }
        return aj.subIdioma
    }

    private fun construirSubOverlay() {
        subOverlay = TextView(this)
        subOverlay.gravity = Gravity.CENTER
        subOverlay.visibility = View.GONE
        subOverlay.setPadding(dp(8), dp(2), dp(8), dp(2))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dp(40) + aj.subElevar
        lp.leftMargin = dp(32)
        lp.rightMargin = dp(32)
        raiz.addView(subOverlay, lp)
    }

    private fun estiloSubOverlay() {
        val clave = "" + aj.subTamano + "|" + aj.subColor + "|" + aj.subFondo + "|" + aj.subContorno + "|" + aj.subNegrita + "|" + aj.subElevar
        if (clave == claveEstiloSub) return
        claveEstiloSub = clave
        subOverlay.textSize = 18f * aj.subTamano.coerceIn(50, 250) / 100f
        subOverlay.setTextColor(0xFF000000.toInt() or aj.subColor)
        subOverlay.setBackgroundColor((aj.subFondo.coerceIn(0, 100) * 255 / 100) shl 24)
        subOverlay.typeface = if (aj.subNegrita) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        if (aj.subContorno > 0) subOverlay.setShadowLayer(aj.subContorno * 1.5f, 0f, 0f, Color.BLACK)
        else subOverlay.setShadowLayer(0f, 0f, 0f, 0)
        val lp = subOverlay.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = dp(40) + aj.subElevar
        subOverlay.layoutParams = lp
    }

    /** Índice del último subtítulo que empieza en o antes de [t] (búsqueda binaria), -1 si ninguno. */
    private fun indiceCue(cues: List<Subs.Cue>, t: Long): Int {
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (cues[mid].ini <= t) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return idx
    }

    private fun pintarSubsDirecto() {
        val s = svc
        if (s == null || !s.subsConRelojPropio) {
            if (subOverlay.visibility != View.GONE) subOverlay.visibility = View.GONE
            if (lineasConstruido && panelLineas.visibility != View.GONE) panelLineas.visibility = View.GONE
            return
        }
        val cues = s.cuesDirecto
        val t = s.tiempoSubs()
        var actual: Subs.Cue? = null
        if (t >= 0L) {
            estiloSubOverlay()
            val idx = indiceCue(cues, t)
            val sb = StringBuilder()
            var k = idx
            var n = 0
            val activas = ArrayList<Subs.Cue>()
            while (k >= 0 && n < 4) {
                if (cues[k].fin > t) activas.add(0, cues[k])
                k--
                n++
            }
            for (c in activas) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(c.texto)
            }
            actual = if (idx >= 0) cues[idx] else null
            if (sb.isEmpty()) {
                if (subOverlay.visibility != View.GONE) subOverlay.visibility = View.GONE
            } else {
                if (subOverlay.text.toString() != sb.toString()) subOverlay.text = sb.toString()
                if (subOverlay.visibility != View.VISIBLE) subOverlay.visibility = View.VISIBLE
            }
        } else if (subOverlay.visibility != View.GONE) {
            subOverlay.visibility = View.GONE
        }
        if (!lineasConstruido || panelLineas.visibility != View.VISIBLE) return
        lineasTv.text = when {
            t < 0L -> "Sin sincronizar: toca la frase que estás oyendo"
            s.subsPausados -> "⏸ Subtítulos en pausa (anuncio)"
            else -> "Subtítulos en marcha · reloj propio"
        }
        lineasPausa.text = if (s.subsPausados) "▶ Reanudar" else "⏸ Anuncio"
        if (actual !== cueActual) {
            cueActual = actual
            (lineasLista.adapter as BaseAdapter).notifyDataSetChanged()
        }
        if (lineasSeguir && lineasBusca.text.isNullOrEmpty() && actual != null) {
            val pos = lineasFiltro.indexOf(actual)
            if (pos >= 0 && pos != ultimoSeguido) {
                ultimoSeguido = pos
                lineasLista.setSelectionFromTop(pos, dp(70))
            }
        }
    }

    private fun construirLineas() {
        lineasConstruido = true
        panelLineas = LinearLayout(this)
        panelLineas.orientation = LinearLayout.VERTICAL
        panelLineas.setBackgroundColor(0x99000000.toInt())
        panelLineas.setPadding(dp(6), dp(4), dp(6), dp(4))
        panelLineas.isClickable = true
        panelLineas.visibility = View.GONE

        lineasTv = texto("", 12f)
        lineasTv.gravity = Gravity.START or Gravity.CENTER_VERTICAL

        fun fila(vararg items: View): LinearLayout {
            val f = LinearLayout(this)
            f.orientation = LinearLayout.HORIZONTAL
            f.gravity = Gravity.CENTER
            for (v in items) {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(dp(2), dp(1), dp(2), dp(1))
                f.addView(v, lp)
            }
            return f
        }

        val cabecera = LinearLayout(this)
        cabecera.orientation = LinearLayout.HORIZONTAL
        cabecera.gravity = Gravity.CENTER_VERTICAL
        cabecera.addView(lineasTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cabecera.addView(miniBoton("✕") { panelLineas.visibility = View.GONE })
        panelLineas.addView(cabecera)

        panelLineas.addView(
            fila(
                miniBoton("−500") { svc?.ajustarSubs(-500L) },
                miniBoton("−100") { svc?.ajustarSubs(-100L) },
                miniBoton("+100") { svc?.ajustarSubs(100L) },
                miniBoton("+500") { svc?.ajustarSubs(500L) }
            )
        )
        lineasPausa = miniBoton("⏸ Anuncio") {
            val s = svc
            if (s != null) { if (s.subsPausados) s.reanudarSubs() else s.pausarSubs() }
        }
        panelLineas.addView(
            fila(
                lineasPausa,
                miniBoton("⦿ Seguir") {
                    lineasSeguir = true
                    ultimoSeguido = -2
                }
            )
        )

        lineasBusca = EditText(this)
        lineasBusca.hint = "Buscar frase…"
        lineasBusca.setHintTextColor(0x99FFFFFF.toInt())
        lineasBusca.setTextColor(Color.WHITE)
        lineasBusca.textSize = 14f
        lineasBusca.setSingleLine(true)
        lineasBusca.setBackgroundColor(0x33FFFFFF)
        lineasBusca.setPadding(dp(8), dp(6), dp(8), dp(6))
        lineasBusca.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val todas = svc?.cuesDirecto ?: emptyList()
                val q = (e?.toString() ?: "").trim().lowercase()
                lineasFiltro = if (q.isEmpty()) todas else todas.filter { it.texto.lowercase().contains(q) }
                if (q.isNotEmpty()) lineasSeguir = false
                ultimoSeguido = -2
                (lineasLista.adapter as BaseAdapter).notifyDataSetChanged()
                if (q.isNotEmpty()) lineasLista.setSelection(0)
            }
        })
        panelLineas.addView(lineasBusca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lineasLista = ListView(this)
        lineasLista.divider = null
        lineasLista.isFastScrollEnabled = true
        lineasLista.adapter = object : BaseAdapter() {
            override fun getCount(): Int = lineasFiltro.size
            override fun getItem(i: Int): Any = lineasFiltro[i]
            override fun getItemId(i: Int): Long = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val tv = (v as? TextView) ?: TextView(this@PlayerActivity)
                val c = lineasFiltro[i]
                tv.text = Utiles.fmt(c.ini) + "  " + c.texto
                tv.textSize = 13f
                tv.setTextColor(Color.WHITE)
                tv.setPadding(dp(8), dp(6), dp(8), dp(6))
                tv.setBackgroundColor(if (c === cueActual) 0x6600A5A5 else 0)
                return tv
            }
        }
        lineasLista.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, st: Int) {
                if (st == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) lineasSeguir = false
            }
            override fun onScroll(view: AbsListView?, a: Int, b: Int, c: Int) {}
        })
        lineasLista.setOnItemClickListener { _, _, pos, _ ->
            val c = lineasFiltro.getOrNull(pos)
            if (c != null) {
                // Se toca la frase justo después de oírla: unos 400 ms de reacción.
                svc?.fijarSubsEnTiempo(c.ini + 400L)
                lineasSeguir = true
                ultimoSeguido = -2
                try {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(lineasBusca.windowToken, 0)
                } catch (e: Throwable) { /* nada */ }
                lineasBusca.setText("")
                lineasBusca.clearFocus()
                aviso("Subtítulos sincronizados")
            }
        }
        panelLineas.addView(lineasLista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val ancho = minOf(dp(360), (resources.displayMetrics.widthPixels * 0.7f).toInt())
        val lp = FrameLayout.LayoutParams(ancho, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        lp.topMargin = dp(60)
        lp.bottomMargin = dp(110)
        raiz.addView(panelLineas, lp)
    }

    private fun abrirLineas() {
        val s = svc ?: return
        if (!lineasConstruido) construirLineas()
        lineasBusca.setText("")
        lineasFiltro = s.cuesDirecto
        cueActual = null
        ultimoSeguido = -2
        lineasSeguir = true
        (lineasLista.adapter as BaseAdapter).notifyDataSetChanged()
        panelLineas.visibility = View.VISIBLE
        barraSync.visibility = View.GONE
    }

    /** Pone en cola para guardar en el dispositivo archivos de un enlace de Mega (o quita la marca si ya estaba en cola). */
    private fun pedirGuardarMega(lista: List<MegaNodo>) {
        val s = svc ?: return
        if (lista.size == 1) {
            val n = lista[0]
            when (s.estadoGuardadoMega(n)) {
                0 -> {
                    s.cancelarGuardadoMega(lista)
                    aviso("Guardado cancelado")
                    return
                }
                2 -> {
                    aviso("Ya está guardado en " + Exportar.destinoTexto())
                    return
                }
                -1 -> aviso("Error: " + (s.errorGuardadoMega(n) ?: "desconocido") + " · reintentando")
            }
        }
        val nuevos = lista.filter { val e = s.estadoGuardadoMega(it); e == -2 || e == -1 }
        if (nuevos.isEmpty()) {
            aviso("Ya marcados")
            return
        }
        val bytes = nuevos.sumOf { it.tam }
        val libre = s.espacioLibre()
        val hacer = { s.guardarMega(nuevos); aviso("Guardando " + nuevos.size + " archivo(s)…") }
        if (libre < bytes + 200L * 1024 * 1024) {
            AlertDialog.Builder(this)
                .setTitle("Poco espacio libre")
                .setMessage(
                    "Guardar " + nuevos.size + " archivo(s) ocupa " + Utiles.tam(bytes) + " y ahora hay " + Utiles.tam(libre) + " libres."
                )
                .setPositiveButton("Intentar igualmente") { _, _ -> hacer() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else if (nuevos.size > 1) {
            AlertDialog.Builder(this)
                .setTitle("Guardar en el dispositivo")
                .setMessage(
                    nuevos.size.toString() + " archivos (" + Utiles.tam(bytes) + ") irán a " + Exportar.destinoTexto() +
                        ", con las mismas carpetas de Mega. Cuentan para el límite de transferencia de Mega."
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
        if (s.hayCanales && s.cuesDirecto.isNotEmpty()) {
            abrirLineas()
            return
        }
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
        if (s.hayCola && s.cola.size > 1 && s.colaActual >= 0) tit += "  ·  " + (s.colaActual + 1) + "/" + s.cola.size
        tituloTv.text = tit

        if (aj.mostrarStats && s.esTorrent && m != null && s.error == null && s.estado.isEmpty()) {
            val len2 = try { m.length } catch (e: Throwable) { 0L }
            val t2 = try { m.time } catch (e: Throwable) { 0L }
            val st = s.motor.estadisticasDetalle(if (len2 > 0) t2.toDouble() / len2 else 0.0, len2)
            panelStats.text = st
            panelStats.visibility = if (st.isEmpty()) View.GONE else View.VISIBLE
        } else if (s.esMega && s.hayGuardadoPendiente()) {
            panelStats.text = "⬇ Guardando en el dispositivo: " + s.resumenGuardado()
            panelStats.visibility = View.VISIBLE
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
            if ((s.esTorrent && s.archivosTorrent.isNotEmpty()) || s.hayCanales || s.hayCola) View.VISIBLE else View.GONE

        if (panelColaAbierto) {
            if (s.hayCola) panelCola.actualizar(s.cola, s.colaActual, s.repetir, s.aleatorio, s.tituloCola) else cerrarPanelCola()
        }

        // Falta acceso a la carpeta de la lista: se pide una vez.
        val nc = s.necesitaCarpeta
        if (nc != null) {
            if (!pidiendoCarpeta) {
                pidiendoCarpeta = true
                dialogoCarpeta(s, nc)
            }
        } else {
            pidiendoCarpeta = false
        }

        // Enlace de Mega protegido: se pide la contraseña (otra vez si la dada era incorrecta).
        if (s.necesitaClaveMega) {
            if (!pidiendoClaveMega) {
                pidiendoClaveMega = true
                dialogoClaveMega(s)
            }
        } else {
            pidiendoClaveMega = false
        }

        // Archivos comprimidos de Mega: si no hay nada que reproducir directamente, la lista sale sola hasta que se elija qué hacer.
        if (s.necesitaElegirPaquete && dlgPaquete?.isShowing != true) dialogoPaquetes(s, true)

        // Si una extracción falla, el motivo se enseña en un diálogo (no solo en un aviso que se va solo).
        val fallo = s.tomarFalloPaquete()
        if (fallo != null) dialogoFalloPaquete(fallo)

        // Contraseña de un archivo comprimido que se está extrayendo (otra vez si la dada era incorrecta).
        val esperando = s.paqueteEsperandoClave
        if (esperando != null && dlgClavePaquete?.isShowing != true) dialogoClavePaquete(s, esperando)

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

        actualizarRemoto(s)

        if (s.fin || s.error != null) {
            if (!capaVisible) mostrarCapa()
        } else if (s.esAudioActual) {
            // Con solo audio no hay imagen que ver: los controles se quedan a la vista.
            if (!audioVisto) {
                audioVisto = true
                if (!capaVisible) mostrarCapa()
            }
        } else {
            audioVisto = false
            if (capaVisible && m?.isPlaying == true && SystemClock.uptimeMillis() > ocultarEn) ocultarCapa()
        }
    }

    // ---------------------------------------------------------------- enviar a la tele

    private fun construirRemoto() {
        panelRemoto = LinearLayout(this)
        panelRemoto.orientation = LinearLayout.VERTICAL
        panelRemoto.gravity = Gravity.CENTER
        panelRemoto.setBackgroundColor(Color.BLACK)
        panelRemoto.isClickable = true // se queda los toques
        panelRemoto.setPadding(dp(24), dp(16), dp(24), dp(16))
        panelRemoto.visibility = View.GONE

        remTitulo = texto("", 20f)
        remEstado = texto("", 15f)
        remEstado.setTextColor(0xFFBBBBBB.toInt())
        remSeek = SeekBar(this)
        remSeek.max = 1000
        remSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, delUsuario: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { remArrastrando = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                remArrastrando = false
                val e = svc?.emisor ?: return
                val dur = e.estado().durMs
                if (dur > 0) svc?.emisorBuscar(dur * sb.progress / 1000L)
            }
        })
        remTiempo = texto("", 14f)

        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER
        fila.addView(miniBoton("−10") { svc?.emisorSaltar(-10_000L) })
        remBtnPlay = miniBoton("❚❚") { svc?.emisorAlternar() }
        fila.addView(remBtnPlay)
        fila.addView(miniBoton("+10") { svc?.emisorSaltar(10_000L) })

        panelRemoto.addView(remTitulo)
        panelRemoto.addView(remEstado)
        panelRemoto.addView(remSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panelRemoto.addView(remTiempo)
        panelRemoto.addView(fila)
        panelRemoto.addView(miniBoton("Parar y volver al móvil") { svc?.volverALocal(true) })
        panelRemoto.addView(miniBoton("▾ Minimizar (seguir viendo canales)") { remMin = true })

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.topMargin = dp(72) // la barra superior (⋮) sigue al alcance
        raiz.addView(panelRemoto, lp)

        // Versión mínima: una franja abajo para seguir usando los canales y la lista mientras se emite.
        remMini = LinearLayout(this)
        remMini.orientation = LinearLayout.HORIZONTAL
        remMini.gravity = Gravity.CENTER_VERTICAL
        remMini.setBackgroundColor(0xF0222222.toInt())
        remMini.isClickable = true
        remMini.visibility = View.GONE
        remMiniTxt = texto("", 13f)
        remMiniTxt.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        remMiniTxt.setSingleLine(true)
        remMiniTxt.ellipsize = android.text.TextUtils.TruncateAt.END
        remMini.addView(remMiniTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        remMiniPlay = miniBoton("❚❚") { svc?.emisorAlternar() }
        remMini.addView(remMiniPlay)
        remMini.addView(miniBoton("⤢") { remMin = false })
        remMini.addView(miniBoton("✕") { svc?.volverALocal(true) })
        raiz.addView(remMini, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun actualizarRemoto(s: PlaybackService) {
        val e = s.emisor
        if (e == null) {
            remMin = false
            ponerModoRemoto(0)
            return
        }
        // Si se abre una lista (canales, archivos, cola) el mando se hace pequeño para no taparla.
        val algunoAbierto = panelAbierto || panelCanAbierto || panelColaAbierto
        if (algunoAbierto && !remPanelPrev) remMin = true
        remPanelPrev = algunoAbierto
        ponerModoRemoto(if (remMin) 2 else 1)
        val st = e.estado()
        remMiniTxt.text = "📺 " + e.nombre + " · " + s.titulo +
            (if (st.error != null) " ⚠" else if (st.cargando) " …" else "")
        remMiniPlay.text = if (st.sonando) "❚❚" else "▶"

        remTitulo.text = s.titulo
        remEstado.text = when {
            st.error != null -> "⚠ " + st.error
            st.fin -> "Terminó en " + e.nombre
            st.cargando -> "Conectando con " + e.nombre + "…"
            st.pausa -> "En pausa en " + e.nombre
            else -> "Emitiendo a " + e.nombre
        }
        remBtnPlay.text = if (st.sonando) "❚❚" else "▶"
        if (!remArrastrando) {
            if (st.durMs > 0) {
                remSeek.visibility = View.VISIBLE
                remSeek.progress = (st.posMs * 1000L / st.durMs).toInt().coerceIn(0, 1000)
                remTiempo.text = Utiles.fmt(st.posMs) + " / " + Utiles.fmt(st.durMs)
            } else {
                remSeek.visibility = View.INVISIBLE
                remTiempo.text = if (s.hayCanales) "● EN DIRECTO" else "--"
            }
        }
    }

    /** 0 sin emisión, 1 mando grande, 2 franja mínima. En la franja, los paneles laterales se acortan para no quedar tapados. */
    private fun ponerModoRemoto(modo: Int) {
        if (modo == remModo) return
        remModo = modo
        panelRemoto.visibility = if (modo == 1) View.VISIBLE else View.GONE
        remMini.visibility = if (modo == 2) View.VISIBLE else View.GONE
        val margen = if (modo == 2) dp(52) else 0
        val vistas = listOf<View>(panelArchivos, panelCan.vista, panelCola.vista)
        for (v in vistas) {
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            lp.bottomMargin = margen
            v.layoutParams = lp
        }
    }

    /** Busca tele DLNA y Chromecast en la red y deja elegir. */
    private fun dialogoDestinos() {
        val s = svc ?: return
        val nombres = ArrayList<String>()
        val destinos = ArrayList<Destino>()
        val adaptador = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, nombres)
        val lv = ListView(this)
        lv.adapter = adaptador
        val dlg = AlertDialog.Builder(this)
            .setTitle("Buscando pantallas…")
            .setView(lv)
            .setNegativeButton("Cancelar", null)
            .create()
        var activa = true

        fun agregar(d: Destino) {
            h.post {
                if (!activa) return@post
                if (destinos.any { it.tipo == d.tipo && it.id == d.id }) return@post
                destinos.add(d)
                nombres.add((if (d.tipo == Destino.CHROMECAST) "Chromecast · " else "TV (DLNA) · ") + d.nombre)
                adaptador.notifyDataSetChanged()
                dlg.setTitle("Elige una pantalla")
            }
        }

        try { detenerCast?.invoke() } catch (e: Throwable) { /* nada */ }
        detenerCast = CastGoogle.buscar(this) { d -> agregar(d) }
        Thread {
            Dlna.descubrir(this, 6000L) { dev -> agregar(Destino(Destino.DLNA, dev.nombre, dev.controlUrl, dev)) }
            h.post {
                if (activa && destinos.isEmpty()) {
                    dlg.setTitle("No se encontró ninguna pantalla")
                    nombres.add("Comprueba que el móvil y la tele están en la misma Wi-Fi")
                    adaptador.notifyDataSetChanged()
                }
            }
        }.start()

        lv.setOnItemClickListener { _, _, i, _ ->
            if (i in destinos.indices) {
                activa = false
                dlg.dismiss()
                s.emitirA(destinos[i])
                // La búsqueda de Chromecast se mantiene un rato para que pueda conectar.
                val parar = detenerCast
                h.postDelayed({ try { parar?.invoke() } catch (e: Throwable) { /* nada */ } }, 25_000L)
                detenerCast = null
            }
        }
        dlg.setOnDismissListener { activa = false }
        dlg.show()
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
        if (s.hayCola) {
            etiquetas.add("Cola de reproducción (" + s.cola.size + ")"); acciones.add { abrirPanelCola() }
        } else if (!s.esTorrent && !s.hayCanales && s.mp != null && (s.fuente?.uri ?: "").startsWith("content:")) {
            etiquetas.add("Poner en cola el resto de la carpeta…"); acciones.add { s.pedirColaCarpeta() }
        }
        if (s.hayCanales && !(s.fuente?.uri ?: "").startsWith(Biblioteca.PREFIJO)) {
            etiquetas.add("Guardar esta lista en la app"); acciones.add { guardarListaEnApp() }
        }
        if (s.emisor != null) {
            val dd = s.destinoEmision?.datos
            if (dd is DlnaDispositivo) {
                etiquetas.add("Registro y compatibilidad de la tele"); acciones.add { Informacion.compatibilidadTele(this, dd) }
            }
            etiquetas.add("Dejar de emitir (volver al móvil)"); acciones.add { s.volverALocal(true) }
        } else if (s.urlReproduccion != null && s.mp != null) {
            etiquetas.add("Enviar a la tele…"); acciones.add { dialogoDestinos() }
        }
        etiquetas.add("Velocidad"); acciones.add { menuVelocidad() }
        etiquetas.add("Decodificación de vídeo"); acciones.add { menuDecodificacion() }
        if (s.hayCanales || (s.urlReproduccion ?: "").contains("m3u8", true)) {
            etiquetas.add("Calidad del vídeo (canales)"); acciones.add { menuCalidad() }
            etiquetas.add("Registro del canal"); acciones.add { Informacion.registro(this) }
            etiquetas.add("Modo compatible para canales: " + (if (aj.canalesCompatibles) "sí" else "no") + " (si un canal no arranca)")
            acciones.add {
                aj.canalesCompatibles = !aj.canalesCompatibles
                aviso(if (aj.canalesCompatibles) "Modo compatible activado" else "Modo compatible desactivado")
                svc?.reconstruir()
            }
        }
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
        if (s.esMega && s.megaPaquetes.isNotEmpty()) {
            etiquetas.add("Archivos comprimidos (" + s.megaPaquetes.size + ")…"); acciones.add { dialogoPaquetes(s, false) }
            etiquetas.add("Registro de extracción"); acciones.add { dialogoRegistroMega() }
        }
        if (s.esMega && s.megaArchivos.isNotEmpty()) {
            val actualMega = s.megaNodoActual()
            if (actualMega != null) {
                etiquetas.add(
                    when (s.estadoGuardadoMega(actualMega)) {
                        0 -> "Cancelar el guardado de este archivo"
                        2 -> "Este archivo ya está guardado ✓"
                        else -> "Guardar este archivo en el dispositivo"
                    }
                )
                acciones.add { pedirGuardarMega(listOf(actualMega)) }
            }
            if (s.megaArchivos.size > 1) {
                etiquetas.add("Guardar toda la carpeta de Mega (" + s.megaArchivos.size + " archivos)")
                acciones.add { pedirGuardarMega(s.megaArchivos) }
            }
            if (s.hayGuardadoPendiente()) {
                etiquetas.add("Cancelar los guardados (" + s.resumenGuardado() + ")")
                acciones.add {
                    AlertDialog.Builder(this)
                        .setMessage("¿Cancelar lo que se está guardando? Lo que ya esté en Descargas se queda.")
                        .setPositiveButton("Cancelar guardados") { _, _ ->
                            s.cancelarTodoGuardadoMega()
                            aviso("Guardados cancelados")
                        }
                        .setNegativeButton("Seguir", null)
                        .show()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            etiquetas.add("Imagen en imagen"); acciones.add { entrarPip() }
        }
        etiquetas.add("Orientación…"); acciones.add { menuOrientacion() }
        etiquetas.add("Ajustes"); acciones.add { startActivity(Intent(this, AjustesActivity::class.java)) }
        etiquetas.add("Cerrar reproductor"); acciones.add { salir() }
        lista("Opciones", etiquetas) { acciones[it]() }
    }

    /** Guarda la lista de canales que se está viendo en «Mis listas», para tenerla sin depender de la dirección. */
    private fun guardarListaEnApp() {
        val s = svc ?: return
        val f = s.fuente ?: return
        aviso("Guardando la lista…")
        Thread {
            try {
                val txt = ListaM3u.leer(this, f) ?: throw java.io.IOException("No se pudo leer la lista")
                val origen = if (f.uri.startsWith("http", true)) f.uri else ""
                val r = Biblioteca.agregar(this, f.titulo, origen, txt)
                h.post {
                    aviso(
                        if (r == null) "No se pudo guardar"
                        else if (r.second) "Lista guardada en «Mis listas»" else "Lista actualizada en «Mis listas»"
                    )
                }
            } catch (e: Exception) {
                h.post { aviso("No se pudo guardar: " + (e.message ?: e.javaClass.simpleName)) }
            }
        }.start()
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

    /** Calidad de los directos HLS (también la que se manda a la tele). Se reabre el canal para aplicarla. */
    private fun menuCalidad() {
        val nombres = listOf(
            "Automática (en la tele, equivale a máxima)",
            "Máxima",
            "Mínima",
            "Hasta 720p",
            "Hasta 480p"
        )
        val actual = aj.calidadHls
        lista("Calidad del vídeo", nombres.mapIndexed { i, n -> (if (i == actual) "● " else "") + n }) {
            aj.calidadHls = it
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
        val etiquetas = ArrayList<String>()
        for (p in pistas) etiquetas.add((if (p.id == actual) "● " else "") + p.name)
        etiquetas.add("Audio preferido: " + nombreAudioPreferido() + "…")
        lista("Pista de audio", etiquetas) {
            if (it < pistas.size) svc?.elegirAudio(pistas[it].id) else menuAudioPreferido()
        }
    }

    private val CLAVES_AUDIO = listOf("", "primera", "en", "es", "fr", "de", "it", "pt", "ja", "ko")
    private val NOMBRES_AUDIO = listOf(
        "Automático (el que marque el servidor)", "La primera pista (suele ser el original)",
        "Inglés", "Español", "Francés", "Alemán", "Italiano", "Portugués", "Japonés", "Coreano"
    )

    private fun nombreAudioPreferido(): String {
        val i = CLAVES_AUDIO.indexOf(aj.audioPreferido)
        return if (i < 0) "automático" else NOMBRES_AUDIO[i].substringBefore(" (").lowercase()
    }

    /** Qué pista de audio se elige sola al empezar (también en los canales de una lista). */
    private fun menuAudioPreferido() {
        lista("Audio preferido", NOMBRES_AUDIO) {
            aj.audioPreferido = CLAVES_AUDIO[it]
            svc?.aplicarAudioPreferido(true)
            aviso("Audio preferido: " + nombreAudioPreferido())
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
                acciones.add { s.elegirSubtitulo(p.id) }
            }
        }
        etiquetas.add("Cargar archivo de subtítulos…"); acciones.add { elegirArchivoSubs() }
        etiquetas.add("Buscar en OpenSubtitles…"); acciones.add { buscarOnline() }
        etiquetas.add("Sincronizar / desfase…"); acciones.add { abrirSync() }
        if (s.subsConRelojPropio) { etiquetas.add("Quitar subtítulos del directo"); acciones.add { s.quitarSubsDirecto(); if (lineasConstruido) panelLineas.visibility = View.GONE } }
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
        Gestor.lanzar(this, i, PEDIR_SUBTITULO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        if (codigo == PEDIR_CARPETA) {
            val t = if (resultado == RESULT_OK) datos?.data else null
            if (t != null) {
                Carpetas.conservar(this, t)
                svc?.carpetaConcedida(t)
            } else {
                svc?.carpetaRechazada()
            }
            return
        }
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
        idioma.setText(idiomaSugerido(s))
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
