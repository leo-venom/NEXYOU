package com.leo.nexyou

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Item persistido da fila de reproducao. */
data class Track(
    val id: String,
    val url: String,
    val title: String,
    val uploader: String,
    val duration: Long,
    val thumb: String,
)

/** Fila persistida em SharedPreferences com listener simples. */
object PlaylistStore {

    private const val PREFS = "nexyou"
    private const val KEY_ITEMS = "playlist_items"
    private const val KEY_CURRENT = "playlist_current"

    private val items = ArrayList<Track>()
    private var currentIndex = -1

    private var prefs: SharedPreferences? = null
    private val listeners = ArrayList<() -> Unit>()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY_ITEMS, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val url = o.optString("url")
                    if (id.isBlank() || url.isBlank()) continue
                    items.add(
                        Track(
                            id = id,
                            url = url,
                            title = o.optString("title"),
                            uploader = o.optString("uploader"),
                            duration = o.optLong("dur"),
                            thumb = o.optString("thumb"),
                        )
                    )
                }
            }
        }
        val saved = p.getInt(KEY_CURRENT, -1)
        currentIndex = if (saved in items.indices) saved else -1
    }

    @Synchronized
    fun addListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    @Synchronized
    fun snapshot(): List<Track> = ArrayList(items)

    @get:Synchronized
    val current: Track?
        get() = items.getOrNull(currentIndex)

    @Synchronized
    fun indexOfId(id: String): Int = items.indexOfFirst { it.id == id }

    /** Adiciona no TOPO (ou atualiza por id); retorna o indice na fila. */
    @Synchronized
    fun add(track: Track): Int {
        val existing = items.indexOfFirst { it.id == track.id }
        val index: Int
        if (existing >= 0) {
            items[existing] = track
            index = existing
        } else {
            items.add(0, track)
            index = 0
            // A faixa atual desce uma posicao quando algo entra no topo.
            if (currentIndex >= 0) currentIndex += 1 else currentIndex = 0
        }
        persist()
        notifyListeners()
        return index
    }

    @Synchronized
    fun removeAt(index: Int): Boolean {
        if (index !in items.indices) return false
        items.removeAt(index)
        when {
            items.isEmpty() -> currentIndex = -1
            index < currentIndex -> currentIndex -= 1
            index == currentIndex -> currentIndex = currentIndex.coerceAtMost(items.size - 1)
        }
        persist()
        notifyListeners()
        return true
    }

    @Synchronized
    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        val t = items.removeAt(from)
        items.add(to, t)
        when {
            from == currentIndex -> currentIndex = to
            from < currentIndex && to >= currentIndex -> currentIndex -= 1
            from > currentIndex && to <= currentIndex -> currentIndex += 1
        }
        persist()
        notifyListeners()
        return true
    }

    @Synchronized
    fun clear() {
        items.clear()
        currentIndex = -1
        persist()
        notifyListeners()
    }

    /** Marca que nada da fila esta tocando (sem alterar os itens). */
    @Synchronized
    fun clearCurrent() {
        if (currentIndex < 0) return
        currentIndex = -1
        persist()
        notifyListeners()
    }

    @Synchronized
    fun setCurrent(index: Int) {
        val clamped = if (items.isEmpty()) -1 else index.coerceIn(0, items.size - 1)
        if (clamped == currentIndex) return
        currentIndex = clamped
        persist()
        notifyListeners()
    }

    private fun persist() {
        val p = prefs ?: return
        val arr = JSONArray()
        for (t in items) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("url", t.url)
            o.put("title", t.title)
            o.put("uploader", t.uploader)
            o.put("dur", t.duration)
            o.put("thumb", t.thumb)
            arr.put(o)
        }
        p.edit().putString(KEY_ITEMS, arr.toString()).putInt(KEY_CURRENT, currentIndex).apply()
    }

    private fun notifyListeners() {
        val copy: List<() -> Unit>
        synchronized(this) { copy = ArrayList(listeners) }
        for (l in copy) l()
    }
}
