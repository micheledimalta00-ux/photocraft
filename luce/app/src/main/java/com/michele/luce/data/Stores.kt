package com.michele.luce.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.michele.luce.engine.EditParams
import com.michele.luce.engine.Json
import com.michele.luce.engine.ParamsJson
import com.michele.luce.engine.Preset
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class EditedEntry(val uri: Uri, val thumbPath: String?, val updated: Long)

/** Archivio delle modifiche non distruttive, una voce per immagine. */
class EditStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("luce_edits", Context.MODE_PRIVATE)
    private val thumbDir = File(app.filesDir, "thumbs").apply { mkdirs() }

    private fun key(uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun load(uri: Uri): EditParams? =
        prefs.getString("p_" + key(uri), null)?.let { ParamsJson.decode(it) }

    @Synchronized
    fun save(uri: Uri, params: EditParams, thumb: Bitmap?) {
        val k = key(uri)
        val editor = prefs.edit()
        if (params.isDefault()) {
            editor.remove("p_$k")
            prefs.getString("t_$k", null)?.let { File(it).delete() }
            editor.remove("t_$k")
            editor.putString("index", Json.stringify(index().filter { it.uri != uri }.map(::entryMap)))
            editor.apply()
            return
        }
        editor.putString("p_$k", ParamsJson.encode(params))
        var thumbPath: String? = prefs.getString("t_$k", null)
        if (thumb != null) {
            val f = File(thumbDir, "${k}_${System.currentTimeMillis()}.jpg")
            try {
                FileOutputStream(f).use { thumb.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                thumbPath?.let { old -> if (old != f.absolutePath) File(old).delete() }
                thumbPath = f.absolutePath
                editor.putString("t_$k", thumbPath)
            } catch (_: Exception) {
                f.delete()
            }
        }
        val list = index().filter { it.uri != uri }.toMutableList()
        list.add(0, EditedEntry(uri, thumbPath, System.currentTimeMillis()))
        editor.putString("index", Json.stringify(list.map(::entryMap)))
        editor.apply()
    }

    fun thumbFor(uri: Uri): String? = prefs.getString("t_" + key(uri), null)?.takeIf { File(it).exists() }

    fun index(): List<EditedEntry> {
        val raw = prefs.getString("index", null) ?: return emptyList()
        return try {
            (Json.parse(raw) as? List<*>)?.mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                val u = m["uri"] as? String ?: return@mapNotNull null
                EditedEntry(Uri.parse(u), m["thumb"] as? String, (m["t"] as? Number)?.toLong() ?: 0L)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun entryMap(e: EditedEntry): Map<String, Any?> =
        mapOf("uri" to e.uri.toString(), "thumb" to e.thumbPath, "t" to e.updated)
}

/** Predefiniti creati dall'utente. */
class PresetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("luce_presets", Context.MODE_PRIVATE)

    fun load(): List<Preset> {
        val raw = prefs.getString("user", null) ?: return emptyList()
        return try {
            (Json.parse(raw) as? List<*>)?.mapNotNull { e ->
                @Suppress("UNCHECKED_CAST")
                val m = e as? Map<String, Any?> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val name = m["name"] as? String ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val params = ParamsJson.fromMap(m["params"] as? Map<String, Any?> ?: emptyMap())
                Preset(id, name, "I miei predefiniti", params, builtIn = false)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun store(list: List<Preset>) {
        prefs.edit().putString("user", Json.stringify(list.map {
            mapOf("id" to it.id, "name" to it.name, "params" to ParamsJson.toMap(it.params))
        })).apply()
    }

    fun add(name: String, params: EditParams): Preset {
        // un predefinito non porta con sé geometria e maschere
        val clean = params.resetGeometry().copy(masks = emptyList(), lensDistortion = params.lensDistortion)
        val p = Preset("u_${System.currentTimeMillis()}", name.ifBlank { "Predefinito" }, "I miei predefiniti", clean, builtIn = false)
        store(load() + p)
        return p
    }

    fun delete(id: String) = store(load().filter { it.id != id })
}

/** Appunti per Copia/Incolla impostazioni tra immagini. */
object SettingsClipboard {
    @Volatile
    var params: EditParams? = null
}
