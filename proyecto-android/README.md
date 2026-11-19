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

## 0.6.2 — Canales y https en teles DLNA

- Las teles DLNA antiguas (Samsung 2012…) no entienden HLS (.m3u8) ni https. Al enviarles un canal o una dirección de ese tipo, el móvil
  la abre con libVLC (con su Referer y User-Agent) y la re-emite por HTTP como MPEG-TS sin recodificar. Es un directo: sin saltar de posición,
  sin subtítulos, y el móvil debe seguir encendido (con la pantalla bloqueada vale).
- Chromecast no cambia: recibe la dirección original.

## 0.6.3 — Mando minimizable y cambio de contenido emitiendo

- El mando de la emisión se puede **minimizar** (▾) a una franja inferior con pausa, ⤢ (ampliar) y ✕ (parar). Se minimiza solo al abrir la lista
  de canales, de archivos o la cola, que quedan completos por encima de la franja.
- Cambiar de canal, de archivo del torrent, de pista de la cola o abrir otro contenido **mientras se emite** ya no corta la emisión:
  el contenido nuevo se manda a la misma pantalla.
- Al parar y volver al móvil, sigue por donde iba la tele.
- **Ajustes › Acerca de** muestra la versión, la compilación y los motores usados.

## 0.6.4 — Subtítulos de canales

- **Información › Sondeo del flujo HLS** ahora lista las pistas de subtítulos del manifiesto (idioma, si son por defecto/automáticas/forzadas, o CC
  incrustados), y comprueba la primera: respuesta del servidor, nº de segmentos, si es WebVTT y si usa X-TIMESTAMP-MAP o EXT-X-MAP.
- Al elegir una pista de subtítulos en un directo, si a los 7 s la imagen no avanza, se quita sola la pista y se avisa.
- **Ajustes › Ignorar subtítulos incluidos en los canales:** no carga las pistas de subtítulos de los canales (`:no-spu`).

## 0.6.5 — Recuperar canales congelados por sus subtítulos

- Si al elegir un subtítulo incluido en un canal la imagen se congela y quitar la pista no la despierta, el canal se reabre solo
  sin subtítulos incluidos (hasta cambiar de canal) y se avisa.

## 0.6.6 — Cierre inesperado al abrir un torrent

- Corregido un fallo nativo (SIGSEGV en `torrent_handle::is_valid`, desde `MotorTorrent.abrir`): el torrent se seguía consultando con un objeto
  `TorrentHandle` compartido entre hilos mientras otro lo cerraba. Ahora cada consulta pide su propio handle por info-hash y, si el torrent ya no
  existe o se abrió otro, el hilo antiguo sale sin tocar memoria nativa.

## 0.6.7 / 0.6.8 — Subtítulos de canales sin congelar la imagen

- En los canales HLS, libVLC abre un manifiesto local (127.0.0.1) que es el original con los trozos de subtítulos «de relleno»
  (`/placeholder/`, con una marca de tiempo fija que no casa con el vídeo y que congelaba la imagen) sustituidos por un WebVTT vacío.
  Los trozos con texto real pasan tal cual, así que **los subtítulos de los canales siguen disponibles y se pueden elegir**.
  Si el manifiesto no tiene subtítulos, libVLC lo abre directamente.
- **Ajustes › Quitar por completo los subtítulos incluidos en los canales:** elimina también las pistas (último recurso). Se usa solo
  tras reabrir un canal que sigue congelado.
- Elegir una pista de subtítulos ya no se hace en el hilo principal.
- Información › Sondeo HLS cuenta los trozos de relleno de la pista de subtítulos.

## 0.6.9

- Información › Sondeo HLS: baja cada trozo distinto de la pista de subtítulos y dice cuántos subtítulos trae cada uno
  (así se ve si el canal manda texto de verdad o solo trozos vacíos).
- Los trozos `empty_sub.vtt` (Rakuten) también se sustituyen por un WebVTT vacío local, igual que los `placeholder`.

## 0.7.0 — Enlaces de Mega (con o sin contraseña)

- **Qué admite:** enlaces a **archivo** y a **carpeta** de Mega, en formato nuevo (`mega.nz/file/…#clave`, `mega.nz/folder/…#clave`, también con
  `/file/ID` o `/folder/ID` detrás para abrir un archivo o subcarpeta concretos), en formato antiguo (`#!ID!clave`, `#F!ID!clave`) y los
  **protegidos con contraseña** (`mega.nz/#P!…`). Valen los dominios `mega.nz`, `mega.io` y `mega.co.nz`, con o sin `https://`.
- **Cómo se abre:** pegándolo en la pantalla de inicio, con «Compartir» desde otra app, o con el Intent de siempre (`ACTION_VIEW` con la URL).
  Se reproduce **mientras se descarga** y se puede saltar de posición.
- **Contraseña:** si el enlace es `#P!…` y no se ha dado, sale un diálogo para escribirla (y otro igual si era incorrecta). Se puede adelantar de tres
  formas: pegando el texto con «Contraseña: xxx» (también «pass:», «password=», «clave:»), con «Compartir» de ese mismo texto, o con el extra
  `password` del Intent. La contraseña se recuerda mientras la app siga abierta; **no se guarda en disco**.
- **Carpetas:** se listan todos los audios y vídeos (con subcarpetas, en orden natural) y se reproducen como **cola** (☰: saltar, aleatorio, repetir…).
  Cada archivo se pide a Mega solo cuando suena.
- **Cómo funciona:** Mega guarda los archivos cifrados (AES-128-CTR) y la clave va en el propio enlace, así que el móvil lo descifra al vuelo. Un servidor
  local en 127.0.0.1 (`ServidorMega.kt`, con token aleatorio) pide a Mega cada rango de bytes que reclama libVLC, lo descifra y se lo entrega.
  Para los enlaces protegidos, la clave real sale de PBKDF2-HMAC-SHA512 (100 000 vueltas) y se verifica con un HMAC-SHA256 (`Mega.kt`).
- **Dónde mirar si algo falla:** `Mega.kt` (enlaces, contraseña, API: `g.api.mega.co.nz`) y `ServidorMega.kt` (descarga por rangos `url/inicio-fin`).
- **Límites:**
  - Mega limita la transferencia de las conexiones sin cuenta: si se agota, el reproductor lo avisa («límite de transferencia») y hay que esperar o cambiar de red.
  - Los enlaces protegidos con el algoritmo antiguo (versión 1) no están soportados; los actuales usan la versión 2.
  - No hay cuenta de Mega: solo enlaces públicos. Carpetas muy grandes tardan en listarse.
  - **Enviar a la tele** con Mega: ver 0.7.1.
  - En «Información» la clave del enlace se oculta (el botón «Copiar» no la saca del móvil).
  - Esto no se pudo compilar ni probar contra Mega real en el entorno donde se escribió; la criptografía (contraseña, AES-CTR por rangos, atributos)
    se comprobó con un script aparte contra el formato documentado.

## 0.7.1 — Enlaces de Mega a la tele

- **⋮ › Enviar a la tele…** ya funciona con enlaces de Mega (archivo suelto o carpeta), a DLNA/Samsung y a Chromecast.
- **Cómo:** la tele no sabe descifrar el AES-CTR de Mega, así que el móvil sigue descargando y descifrando y lo sirve por la Wi-Fi
  (`ServidorMega` con `lan = true`: escucha en la red local, ruta con token aleatorio, Range, CORS y cabeceras DLNA). Solo existe mientras se emite.
- **Carpetas:** cambiar de pista de la cola mientras se emite manda el archivo nuevo a la misma pantalla. Los subtítulos SRT/WebVTT cargados se envían como siempre.
- **Límites:** el móvil debe seguir encendido y en la misma Wi-Fi; la cuota de transferencia de Mega se gasta igual (el móvil no descarga por duplicado:
  mientras emite, el reproductor local queda en pausa). La tele debe entender el códec (H.264/AAC/AC3; HEVC en teles antiguas no).
- **Dónde mirar si algo falla:** `PlaybackService.emitirA` (rama `megaOrigen`) y `ServidorMega.kt` (`lan`, `urlLan`).
- No compilado ni probado contra una tele real en el entorno donde se escribió.

## 0.7.2 — Compatibilidad de la tele

- **⋮ › Compatibilidad de la tele (formatos)** (aparece mientras se emite a una tele DLNA): pregunta a la tele por `ConnectionManager › GetProtocolInfo`
  y muestra modelo y lista de formatos aceptados (vídeo, audio, otros), con «Copiar». Sirve para saber qué contenedor y códec admite
  (p. ej. si una Samsung antigua acepta MKV, MP4, MPEG-TS o solo MPEG-PS) antes de decidir si hace falta recodificar.

## 0.7.3 — Perfil DLNA en la retransmisión TS

- Según `GetProtocolInfo` de una Samsung UE37ES5500, la tele solo anuncia MPEG-TS como `video/vnd.dlna.mpeg-tts` con perfil `AVC_TS_*`.
  La retransmisión de canales (HLS → TS) se declaraba como `video/mpeg` sin perfil; ahora se declara con `AVC_TS_MP_HD_AAC_MULT5`.
  Es una hipótesis sin probar en la tele; Chromecast no cambia.

## 0.7.4 — Una sola calidad al retransmitir HLS

- La retransmisión a teles DLNA fija la variante más alta del HLS (`:adaptive-logic=highest`). Antes libVLC podía saltar entre 480p mono y 720p estéreo
  dentro del mismo TS, algo que los decodificadores antiguos no admiten. Hipótesis a partir de la pantalla Información de un canal; sin probar en la tele.

## 0.7.5 — Elegir la calidad de los canales

- **⋮ › Calidad del vídeo (canales)** (canales y flujos HLS): automática, máxima, mínima, hasta 720p o hasta 480p. Se guarda y se aplica reabriendo el canal.
  Usa las opciones del módulo adaptive de libVLC (`adaptive-logic`, `adaptive-maxheight`). Vale para el móvil y para la tele: al emitir a una tele DLNA,
  «automática» equivale a «máxima» (una variante fija evita cambios de resolución dentro del TS). Si `adaptive-maxheight` no surte efecto en esta
  versión de libVLC, «hasta 720p/480p» se comportará como «máxima».

## 0.7.6 — Registro de la emisión y MIME de MKV

- **⋮ › Registro y compatibilidad de la tele** ahora empieza por un **registro** de la emisión (`RegistroEmision.kt`): cada petición que la tele hace al móvil
  (método, ruta, `Range`, `User-Agent`, bytes enviados y si cortó), las acciones SOAP y su código HTTP, los cambios de estado de la tele
  (`TRANSITIONING`, `PLAYING`, `STOPPED`…) y lo que se le declara (MIME, perfil DLNA). Debajo, la lista de formatos que acepta. «Copiar» lo saca todo.
- **Sondeo del TS local:** en canales retransmitidos, a los 6 s el móvil se pide a sí mismo `/live.ts` y anota código, `Content-Type`, si el primer byte es `0x47`
  y cuántos bytes salen en 4 s. Distingue «libVLC no emite» de «emite y la tele lo rechaza».
- **MKV:** a teles DLNA se les sirve con `Content-Type: video/x-mkv` (el que anuncian en `GetProtocolInfo`) en vez de `video/x-matroska`. Chromecast no cambia.

## 0.7.7 — Canales HLS a la tele sin libVLC

- **Motivo:** el registro de 0.7.6 mostró que la retransmisión con libVLC (`sout`) abría el canal pero no emitía ningún byte (`Sondeo del TS local: 0 B`).
  El MKV de Mega ya funcionaba con `Content-Type: video/x-mkv` (0.7.6).
- **`RetransmisionHls.kt`:** para canales HLS cuyos segmentos son `.ts` (H.264 + AAC), el móvil elige una sola variante (según ⋮ › Calidad del vídeo),
  baja los segmentos y los sirve concatenados en `/live.ts` como `video/vnd.dlna.mpeg-tts` con perfil `AVC_TS_MP_HD_AAC_MULT5`.
  Empieza por los 2 últimos segmentos y sigue sondeando la lista. Referer y User-Agent del canal se respetan.
- **Respaldo:** si los segmentos son fMP4/CMAF, están cifrados (AES-128) o no son `.ts`, se usa libVLC (`Retransmision`) en el mismo puerto.
- **Calidad:** ahora «Máxima/Mínima/Hasta 720p/Hasta 480p» elige de verdad la variante del manifiesto (en la tele).
- El registro (⋮ › Registro y compatibilidad de la tele) anota variantes, elección, segmentos enviados y por qué se cierra cada conexión.
- Sin probar en la tele; el cambio se hizo a partir del registro de una Samsung UE37ES5500.

## 0.7.8 — Canales fMP4 a la tele

- Algunos canales (p. ej. con 24 variantes y `EXT-X-MAP`) usan segmentos fMP4: no se pueden concatenar, así que se usa libVLC. libVLC emitía un TS válido
  pero con `Content-Type: application/octet-stream` y sin cabeceras DLNA, y la Samsung no arrancaba. Ahora libVLC emite en un puerto interno y
  `RetransmisionHls` lo canaliza a la tele con `video/vnd.dlna.mpeg-tts`, perfil y cabeceras DLNA, y lo anota en el registro (peticiones y bytes).
- Sin probar en la tele.

## 0.7.9 — Modo compatible para canales en el móvil

- Motivo: un canal con anuncios insertados desde el servidor (La 1, Ottera) no arrancaba en el móvil —libVLC se quedaba en la variante más baja con el búfer a 0 %—
  pero sí se veía en la tele, donde `RetransmisionHls` encadena los segmentos de una sola variante.
- **⋮ › Modo compatible para canales** (se guarda; por defecto desactivado): en el móvil, libVLC lee `http://127.0.0.1:puerto/live.ts` en vez del HLS adaptativo.
  Respeta la calidad de ⋮ › Calidad del vídeo («Automática» = máxima). Solo escucha en loopback. Si el canal no se puede encadenar (fMP4, cifrado),
  la conexión se cierra, el registro lo explica y hay que desactivar el modo. Solo para canales (listas M3U), no afecta a archivos ni a Mega.
- Con el modo activo el registro se limpia al abrir cada canal.

## 0.8.0 — Modo compatible automático

- Si un canal HLS no empieza a reproducir en **10 s** (antes 12), se reabre solo una vez en modo compatible (HLS→TS propio en 127.0.0.1)
  y se avisa en pantalla. Es temporal (`compatTemporal`): dura hasta cambiar de canal y no modifica el ajuste ⋮ › Modo compatible para canales.
- Si tampoco arranca así, aparece el mensaje de error de siempre (ahora «tras 10 s»). El registro anota el cambio.

## 0.8.1 — Vigilante de canales que se quedan en «Cargando 0 %»

- **Fallo de 0.8.0:** libVLC emite `Playing` aunque el canal siga con el búfer a 0 % y sin avanzar (La 1 / Ottera), y `alReproducir()` marca `inicioHecho`, así que
  `vigilanteInicio` nunca saltaba. Nuevo `vigilanteCanal`: a los 10 s de abrir un canal HLS en modo normal comprueba si el tiempo avanza (`m.time > 0`);
  si no, activa `compatTemporal` y reabre en modo compatible. Si avanza, lo anota y no hace nada. `vigilanteInicio` se mantiene para los casos sin evento `Playing`.
- **⋮ › Registro del canal** (siempre que se abre un canal, no solo al emitir): muestra el registro con «Copiar». En modo normal anota la apertura,
  qué vio el vigilante (tiempo, búfer, último evento) y si reabrió en modo compatible; en modo compatible, lo de `RetransmisionHls`.

## 0.8.2 — Subtítulos propios para directos y guardado de Mega

(Fusión de dos ramas hechas a partir de la 0.8.1.)

### Subtítulos propios para directos (reloj independiente)

- En un canal en directo, los subtítulos cargados (archivo o OpenSubtitles) ya no se pasan a libVLC: los dibuja la app con su propio reloj,
  con el estilo de Ajustes (tamaño, color, fondo, contorno, negrita, altura). Así no dependen del reloj del vídeo y no se descuadran si el canal
  reinicia el suyo.
- **CC › Sincronizar** abre una columna semitransparente con todas las líneas y su minuto. Se toca la frase que se está oyendo y los subtítulos
  siguen desde ahí (la columna sigue la línea actual, resaltada). Incluye búsqueda por texto, barra de desplazamiento rápida, ajuste fino
  −500/−100/+100/+500 y «⏸ Anuncio» / «▶ Reanudar», que congela el reloj de los subtítulos durante un corte.
- CC › «Quitar subtítulos del directo».
- En directos con reloj propio, los subtítulos no se envían a la tele al emitir.
- «Buscar en OpenSubtitles» propone inglés si el audio es inglés y español si es otro idioma conocido; se puede cambiar.
- Archivos de subtítulos: SRT, WebVTT y ASS/SSA.

### Guardar en el dispositivo lo que se reproduce de Mega

- **⋮ › Guardar este archivo en el dispositivo** y, en carpetas, **⋮ › Guardar toda la carpeta de Mega (N archivos)**. También en la pulsación larga de una pista de la cola.
  Va a *Descargas/OptimoPlay/…* (Android 10+ sin permisos; Android 9, a la carpeta propia de la app), igual que los torrents, y aparece en la pantalla **Descargas**
  (que ya no es «solo de torrents»). Una carpeta conserva su nombre y sus subcarpetas: `Descargas/OptimoPlay/<carpeta de Mega>/<subcarpeta>/<archivo>`.
  Se guarda también lo que no es audio ni vídeo (subtítulos, portadas…).
- **Cómo:** `MegaGuardado.kt` baja cada archivo de Mega con su propia conexión, lo descifra al vuelo y lo escribe directamente en su destino (`Exportar.aDescargasFlujo`).
  **No hay copia temporal**: cada archivo ocupa solo su tamaño (los torrents necesitan el doble). Los archivos se bajan de uno en uno; si la conexión se corta a mitad,
  se reanuda por el byte donde iba (hasta 5 reintentos). El mismo descifrado AES-CTR se comparte con el reproductor (`MegaFlujo.kt`).
- **Estado:** el progreso («⬇ Guardando en el dispositivo: 2 archivos · 37 %») sale en pantalla y en la notificación. Tocar de nuevo «Guardar este archivo» sobre uno en cola lo cancela;
  **⋮ › Cancelar los guardados** corta todo (lo ya guardado se queda).
- **Segundo plano:** como con los torrents, al salir con guardados pendientes se ofrece «Seguir en segundo plano»; el servicio se cierra solo al acabar. Abrir otra fuente
  con guardados en curso pregunta antes de cancelarlos.
- **Sin duplicados:** si el archivo ya está en Descargas con la misma ruta y tamaño se omite.
- **Cuota de Mega:** guardar gasta la misma cuota de transferencia que ver. Si se agota, el guardado para y lo marca como error con ese motivo (no se reintentan los demás).
  Reproducir y guardar el mismo archivo a la vez lo baja dos veces.
- **Límites:** no se reanuda tras cerrar la app (un archivo a medias se descarta y se empieza de nuevo). Hace falta espacio libre igual al tamaño de lo que se guarda (se avisa).
- **Dónde mirar si algo falla:** `MegaGuardado.kt`, `PlaybackService` (sección «guardar archivos de Mega») y `PlayerActivity.pedirGuardarMega`.
- No compilado ni probado contra Mega real en el entorno donde se escribió.
