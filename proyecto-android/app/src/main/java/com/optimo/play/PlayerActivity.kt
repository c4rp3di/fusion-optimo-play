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
    private var dialogoArchivos: AlertDialog? = null
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
            if (p != null && s.debeAbrir(p.uri)) s.abrir(p)
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
        if (s == null) pendiente = p else if (s.debeAbrir(p.uri)) s.abrir(p)
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
        if (isFinishing) svc?.detener()
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
        barraSup.addView(boton("✕") { finish() })
        tituloTv = texto("", 16f)
        tituloTv.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        tituloTv.maxLines = 1
        tituloTv.ellipsize = android.text.TextUtils.TruncateAt.END
        barraSup.addView(tituloTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        w(boton("−10") { svc?.saltar(-10_000) })
        btnPlay = boton("▶") { svc?.alternar() }
        w(btnPlay)
        w(boton("+10") { svc?.saltar(10_000) })
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
                tiempoTv.text = Utiles.fmt(t) + " / " + (if (len > 0) Utiles.fmt(len) else "--")
            }
            btnPlay.text = if (m.isPlaying) "❚❚" else "▶"
        }
        tituloTv.text = s.titulo

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

        if (s.necesitaElegir && dialogoArchivos == null) mostrarArchivosTorrent()

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
        if (s.esTorrent && s.archivosTorrent.size > 1) {
            etiquetas.add("Archivos del torrent"); acciones.add { mostrarArchivosTorrent() }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            etiquetas.add("Imagen en imagen"); acciones.add { entrarPip() }
        }
        etiquetas.add("Ajustes"); acciones.add { startActivity(Intent(this, AjustesActivity::class.java)) }
        etiquetas.add("Cerrar reproductor"); acciones.add { finish() }
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

    private fun mostrarArchivosTorrent() {
        val s = svc ?: return
        val archivos = s.archivosTorrent
        if (archivos.isEmpty()) return
        val orden = archivos.sortedBy { it.ruta }
        val d = AlertDialog.Builder(this)
            .setTitle("Archivos del torrent")
            .setItems(orden.map { it.nombre + "  (" + Utiles.tam(it.tam) + ")" }.toTypedArray()) { _, i ->
                s.elegirArchivoTorrent(orden[i].indice)
            }
            .setOnDismissListener { dialogoArchivos = null }
            .create()
        dialogoArchivos = d
        d.show()
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
