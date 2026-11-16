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
