package com.optimo.play

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.IOException
import java.util.concurrent.Executors

/** Logos de los canales: descarga pequeña en segundo plano con caché en memoria. Si falla, se queda sin logo. */
object Logos {

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val fallidos = HashSet<String>()

    fun cargar(url: String?, iv: ImageView) {
        iv.tag = url
        if (url.isNullOrEmpty()) {
            iv.setImageDrawable(null)
            return
        }
        val b = cache.get(url)
        if (b != null) {
            iv.setImageBitmap(b)
            return
        }
        iv.setImageDrawable(null)
        synchronized(fallidos) { if (fallidos.contains(url)) return }
        pool.execute {
            try {
                val c = Red.conexion(url, null, null)
                c.connectTimeout = 6000
                c.readTimeout = 8000
                val datos = c.inputStream.use { Red.leerBytes(it, 1_500_000) }
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(datos, 0, datos.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 240) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                val bmp = BitmapFactory.decodeByteArray(datos, 0, datos.size, o2) ?: throw IOException("imagen no válida")
                cache.put(url, bmp)
                iv.post { if (iv.tag == url) iv.setImageBitmap(bmp) }
            } catch (e: Exception) {
                synchronized(fallidos) { fallidos.add(url) }
            }
        }
    }
}
