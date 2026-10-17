package com.michele.luce.data

import android.content.Context
import android.net.Uri
import com.michele.luce.engine.AiRaster
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Raster delle selezioni IA di un'immagine: in memoria e su disco,
 * così le maschere restano valide alla riapertura e in esportazione.
 */
class AiMaskStore(context: Context, uri: Uri) {
    private val dir: File = File(context.applicationContext.filesDir, "aimasks/" + hash(uri.toString())).apply { mkdirs() }
    private val cache = ConcurrentHashMap<String, AiRaster>()

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".bin")

    fun get(key: String): AiRaster? {
        cache[key]?.let { return it }
        val f = file(key)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { s ->
                val w = s.readInt()
                val h = s.readInt()
                require(w in 1..8192 && h in 1..8192)
                val data = ByteArray(w * h)
                s.readFully(data)
                AiRaster(w, h, data).also { cache[key] = it }
            }
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    fun put(key: String, raster: AiRaster) {
        cache[key] = raster
        try {
            val tmp = File(dir, file(key).name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.writeInt(raster.width)
                s.writeInt(raster.height)
                s.write(raster.data, 0, raster.width * raster.height)
            }
            tmp.renameTo(file(key))
        } catch (_: Exception) {
        }
    }

    fun has(key: String): Boolean = cache.containsKey(key) || file(key).exists()

    /** Raster per le chiavi indicate (solo quelli disponibili). */
    fun snapshot(keys: Collection<String>): Map<String, AiRaster> =
        keys.mapNotNull { k -> get(k)?.let { k to it } }.toMap()

    companion object {
        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}
