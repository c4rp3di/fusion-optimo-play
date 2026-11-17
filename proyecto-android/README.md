# Optimo Play 0.1.0

Reproductor multimedia nativo para Android, pensado como compañero de **Optimo** (el navegador).

- Motor: **libVLC 3.6.0** (MKV, MP4, WebM, HLS/DASH, AC3/DTS/HEVC… por software si el móvil no los decodifica).
- Torrents: **libtorrent4j 2.1.0-39**. Reproduce **mientras descarga** (magnet o .torrent), solo el archivo elegido.
- Subtítulos: los del propio archivo, cargar archivo externo, o **OpenSubtitles** (búsqueda por hash + nombre; automática si no hay).
  Estilo (tamaño, color, fondo, contorno, negrita, altura, codificación) y desfase (subtítulos y audio).
- Segundo plano con pantalla bloqueada: servicio en primer plano + sesión multimedia + notificación con controles.
- Gestos: toque = controles, doble toque = ±10 s, deslizar izquierda = brillo, derecha = volumen. Imagen en imagen.
- Sin cast (decidido para esta versión). minSdk 28. Solo arm64-v8a y armeabi-v7a.
- Permisos: INTERNET, WAKE_LOCK, FOREGROUND_SERVICE(_MEDIA_PLAYBACK), POST_NOTIFICATIONS. Sin acceso al almacenamiento:
  los archivos se abren con el selector del sistema o desde otras apps.

## Contrato con Optimo (o cualquier app)

`Intent(ACTION_VIEW, Uri)` a `com.optimo.play/.PlayerActivity` con la URL http(s), `magnet:`, `.torrent`, `content://` o `file://`.
Extras opcionales: `title`, `referer`, `user_agent`. También acepta «Compartir» de texto con un enlace.
Optimo 1.6.0 ya lo usa (descargas de vídeo, enlaces magnet, menú de enlace y flujos m3u8/mpd/mp4 detectados en la página).

## Compilar con Fusion

Igual que Optimo: repositorio propio para esta app. El workflow genera `apk-build/debug.keystore` la primera vez.
Versiones: AGP 8.5.0, Kotlin 1.9.24, JDK 17, compileSdk/targetSdk 34.

## Puntos donde mirar si Fusion da error de compilación

Este código no se pudo compilar en el entorno donde se escribió. Las dependencias nativas se aíslan así:
- `MotorTorrent.kt` es el único archivo que toca libtorrent4j (`download(...)`, `TorrentFlags`, `torrentFile()`, `status()`, `setPieceDeadline`).
- `PlaybackService.kt` y `PlayerActivity.kt` son los que tocan libVLC (`MediaPlayer`, `Media`, `addSlave`, `attachViews`).
- Si `libvlc-all:3.6.0` no resuelve, probar otra 3.6.x publicada en Maven Central.

## Limitaciones conocidas

- Los subtítulos ASS/SSA con estilo propio conservan su formato (el panel de estilo no los sobrescribe).
- Cambiar el estilo reinicia el motor un instante (son opciones de LibVLC) y continúa donde ibas.
- `--sub-margin` (subir subtítulos) depende de la versión de VLC; si no surte efecto, es ese ajuste.
- Cookies de la página no se pasan (solo Referer y User-Agent).
- Lo descargado de un torrent vive en la caché y se borra al cerrar.
- MIUI: batería «Sin restricciones» e inicio automático, o cierra la reproducción al bloquear.

## 0.2.1 — Diagnóstico y reapertura

Motivo: con vídeos de YouTube (que Optimo no debería ofrecer y que libVLC no puede abrir) la pantalla se quedaba solo con el título, sin error.
- **Aviso a los 12 s:** si libVLC no llega a reproducir y no hay error, se muestra el último evento de libVLC y la URL, con la pista de abrir «Información».
  Si después empieza a sonar, el aviso desaparece.
- **⋮ › Información** (para URLs, archivos locales, manifiestos y torrents): tipo de fuente, URI recibida, cabeceras, estado de libVLC, posición, búfer,
  decodificación, pistas (códec, resolución, fps, canales, bitrate, idioma) y, para URLs de red, un sondeo HTTP sin cookies (código, tipo, rangos, redirecciones;
  avisa si el servidor devuelve una página web en vez de un vídeo). El botón «Copiar» sirve para pegarlo aquí sin logcat. Las pistas se leen por reflexión.
- **Reabrir la misma URL:** si la anterior falló, terminó o no llegó a sonar, reenviar la misma URL desde Optimo vuelve a intentarlo.

## 0.3.0 — Torrents con varios archivos y guardar en el dispositivo

- **⏮ / ⏭** en la barra inferior (aparecen si el torrent tiene más de un vídeo o audio): siguiente y anterior archivo, en orden natural
  (CD2 antes que CD10). ⏮ vuelve al principio si llevas más de 5 s. Al terminar un archivo pasa solo al siguiente.
  También responden los botones de siguiente/anterior de auriculares y de la pantalla de bloqueo.
- **Panel lateral ☰** (arriba, junto a ⋮): todo el torrent agrupado por carpetas. Toca un archivo para reproducirlo; marca el que suena.
  Se abre solo cuando hay que elegir. Incluye archivos que no son multimedia (portadas, PDF…) solo para guardarlos.
- **Guardar en el dispositivo:** ⬇ por archivo, por carpeta o «⬇ Todo» (también en ⋮). Se descarga completo, aunque no lo estés viendo, y al terminar
  se copia a *Descargas/OptimoPlay/…* conservando las carpetas del torrent (Android 10+ sin permisos; en Android 9, a la carpeta
  propia de la app). Estados en el panel: ⬇ %, copiando…, ✓ guardado, ⚠ reintentar.
- **Segundo plano:** al salir con descargas pendientes se ofrece «Seguir en segundo plano»; la notificación muestra el progreso y el servicio se
  cierra solo al acabar. Abrir otra fuente con descargas en curso pregunta antes de cancelarlas.
- **Espacio:** mientras se guarda, cada archivo ocupa el doble (descarga temporal en la caché + copia final), y se avisa si no hay sitio.
  Lo descargado en la caché se sigue borrando al cerrar; lo guardado en Descargas queda.
- Solo un torrent a la vez.

## 0.4.0 — Listas de canales M3U y guía de programación

- **Listas .m3u / .m3u8:** al abrir una URL o archivo con esa extensión se lee y se decide por el contenido: si son canales (varias entradas `#EXTINF`
  sin etiquetas HLS) se abre el **panel de canales**; si es un solo vídeo HLS, se reproduce como siempre.
- **Panel de canales (☰):** agrupado por `group-title`, con logo, buscador (nombre, número o grupo), ★ favoritos y filtro de favoritos.
  Si casi todos los nombres empiezan por un número («100 LG 1») se separa como número de canal y se ordena por él.
  ⏮ / ⏭ cambian de canal (dando la vuelta al llegar al final).
- **Marcas de la lista:** las direcciones con `[UA]`, `[DEVICE_ID]`, `[LMT]`… se rellenan: publicidad personalizada limitada (`LMT=1`, `DNS=1`),
  un identificador aleatorio por sesión (nunca el real) y vacío lo que no se conoce.
- **Guía (EPG):** si la cabecera trae `url-tvg`/`x-tvg-url` se descarga sola (XMLTV, .xml o .xml.gz, caché de 6 h); si no, se puede poner a mano en
  *Guía* (panel de canales) o en ⋮. Se enlaza por `tvg-id` y, si falta, por nombre. Muestra «Ahora» en cada canal, el programa en la barra superior y
  la lista de lo que queda en ⋮ › Programación de este canal.
- Los canales son directos: no se guarda posición. Los que declaran DRM (`#KODIPROP` con licencia) se marcan con 🔒 y probablemente no se reproduzcan.
- Se permite tráfico http sin cifrar (listas, guías y logos suelen estar así).

## 0.4.1

- Corrige un error de compilación en el panel de canales (`btnFav` usado en su propio inicializador).

## 0.5.0

- **Vertical**: el reproductor ya gira. En Ajustes (y en ⋮ › Orientación) eliges automática (respeta el bloqueo de rotación), siempre horizontal o siempre vertical. En vertical los botones pasan a dos filas y se respeta la muesca de la pantalla.
- **Listas .m3u/.m3u8 locales** (típico de un disco): si sus entradas son archivos, se reproducen como cola. Se buscan junto a la lista; la primera vez se pide acceso a esa carpeta (selector de carpetas, se recuerda; sin permisos de almacenamiento). Admite rutas relativas, `..`, `%20`, rutas de Windows y codificación Windows-1252.
- **Cola de reproducción** (solo archivos locales; no torrents ni canales): panel ☰ con pista actual, saltar, subir/bajar, quitar, «a continuación», aleatorio, repetir (todo / una) y vaciar. Una pista rota se salta sola. Con solo audio los controles no se ocultan.
- **Carpetas** (pantalla de inicio): añade varias carpetas, navega, reproduce una carpeta (con o sin subcarpetas), un archivo con el resto de su carpeta en cola, o una lista .m3u. «Varios archivos» elige varios a la vez.
- Al abrir un audio de una carpeta con acceso concedido se pone en cola el resto de audios (se desactiva en Ajustes). Desde ⋮ › «Poner en cola el resto de la carpeta…» se pide el acceso si falta.
- **Mis listas**: guarda listas M3U/M3U8 dentro de la app (desde una dirección o un archivo, o desde ⋮ › «Guardar esta lista en la app» al verla). Cifradas, se pueden actualizar desde su dirección, renombrar, copiar a Descargas/OptimoPlay/Listas y borrar.
- **Torrents sin duplicados**: lo que ya está en Descargas/OptimoPlay (misma ruta y tamaño) se marca «✓ guardado» y no se vuelve a bajar; al copiar se sustituye en vez de crear «nombre (1)».
- **Descargas**: gestiona lo guardado de torrents (por carpetas): reproducir, borrar archivo o carpeta, y borrar de una vez las copias «(1)» sobrantes de versiones anteriores. Límite de Android 10+: tras reinstalar, la app no ve lo guardado por la instalación anterior.
- **Gestor de archivos propio** (`com.carpe.gestorarchivos`): si está instalado, todo lo de elegir archivos y carpetas (abrir archivo, varios archivos, carpetas con acceso, importar listas, subtítulos) se pide directamente a él en vez de al selector del sistema (desactivable en Ajustes). Como el gestor adivina el tipo por la extensión y no conoce `.m3u`/`.torrent`, con él no se manda filtro de tipos. «Descargas» tiene un botón para ver la carpeta de OptimoPlay en el gestor.

## 0.5.1 — Audio preferido (fusión con la 0.5.0)

- **Audio › Audio preferido:** elige solo la pista al empezar (también en canales): automático, la primera pista (suele ser el original) o un idioma
  (inglés, español, francés, alemán, italiano, portugués, japonés, coreano). Si eliges una pista a mano, esa manda.
- **Información** muestra la lista de pistas de audio tal como las ve el menú (id y nombre).

## 0.5.2

- **Información › Sondeo del flujo HLS:** para URLs HLS baja el manifiesto, la lista de la primera variante y el primer segmento, y muestra qué responde
  el servidor en cada paso (variantes y resoluciones, nº de segmentos, publicidad/cifrado, código del segmento). Sirve para saber por qué un canal se
  queda en «Cargando 0 %».

## 0.6.0 — Enviar a la tele

- **⋮ › Enviar a la tele…** busca en la red Wi-Fi televisores DLNA/UPnP (p. ej. Samsung con AllShare) y Chromecast, y manda lo que suena.
  El móvil pasa a ser un mando: pausa, ±10 s, barra de posición y «Parar y volver al móvil» (sigue por donde iba la tele).
- Direcciones web: se las pasa directamente a la tele. Torrent y archivos del dispositivo: el móvil los sirve por la red local
  (servidor temporal con clave aleatoria, solo mientras emites) — el móvil debe seguir encendido y en la misma Wi-Fi.
- Salir mientras se emite ofrece «Seguir en segundo plano».
- Límites: la tele debe entender el códec (H.264/AAC/AC3 en MP4/MKV; HEVC en teles de 2012 no). Las direcciones que exigen Referer/User-Agent
  propios pueden no abrirse. Los subtítulos no se envían. Chromecast necesita Google Play services.
- Chromecast está aislado en CastGoogle.kt y CastOpciones.kt: si diera problemas al compilar se quita junto con las dos dependencias
  de gradle y el `meta-data` del manifiesto.

## 0.6.1 — Subtítulos al enviar a la tele

- Al enviar, se manda también el subtítulo cargado (archivo externo, el de OpenSubtitles o el de un torrent), con el retardo que tengas puesto:
  a **Chromecast** como WebVTT (blanco con borde y fondo semitransparente) y a **DLNA/Samsung** como SRT (cabecera y etiquetas `CaptionInfo.sec`).
- Si los subtítulos llegan después de enviar (búsqueda automática), el vídeo se vuelve a mandar con ellos por donde iba.
- Solo SRT o WebVTT; los .ass con estilos y los subtítulos incrustados en el MKV no se envían.
- Con la pantalla bloqueada la emisión sigue (servicio en primer plano con bloqueos de CPU y Wi‑Fi). En Xiaomi/MIUI pon la app en
  «Sin restricciones» de batería y fíjala en recientes.
