package com.leo.nexyou

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Activity unica: WebView com a UI em assets/app.html, ponte JS (NxBridge)
 * e envio de eventos para a pagina (player/playlist/search).
 */
class MainActivity : Activity() {

    private var web: WebView? = null
    private val searchExecutor = Executors.newSingleThreadExecutor()

    private val playbackListener = Listener { snap -> pushPlayer(snap) }

    private val playlistListener: () -> Unit = {
        pushPlaylist()
        if (AudioService.isAlive()) sendToService(Intent(AudioService.ACTION_PLAYLIST_UPDATED))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaylistStore.init(this)
        NxService.attach(this)
        Thread { NxService.warmUp() }.start()

        val w = WebView(this)
        web = w
        w.setBackgroundColor(0xFF0B0F14.toInt())
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            @Suppress("DEPRECATION")
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        w.addJavascriptInterface(NxBridge(), "NexusAndroid")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                maybeRequestNotificationPermission()
                pushAll()
            }
        }
        setContentView(w)

        val html = runCatching {
            assets.open("app.html").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse {
            "<!DOCTYPE html><html><body style='background:#0B0F14;color:#E8EDF5;" +
                "font-family:sans-serif;padding:24px'>Falha ao carregar a interface.</body></html>"
        }
        w.loadDataWithBaseURL("https://nexyou.local/", html, "text/html", "UTF-8", null)
    }

    override fun onStart() {
        super.onStart()
        PlaylistStore.addListener(playlistListener)
        AudioService.listener = playbackListener
        pushAll()
    }

    override fun onStop() {
        AudioService.listener = null
        PlaylistStore.removeListener(playlistListener)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        searchExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ envio JS

    private fun pushEvent(name: String, payload: JSONObject) {
        val js = "window.__nexus&&window.__nexus.handle(" +
            JSONObject.quote(name) + "," + JSONObject.quote(payload.toString()) + ");"
        runOnUiThread { web?.evaluateJavascript(js, null) }
    }

    private fun pushAll() {
        pushPlaylist()
        if (AudioService.isAlive()) {
            AudioService.pushUi()
        } else {
            pushPlayer(PlaybackSnapshot.stopped())
        }
    }

    private fun pushPlaylist() {
        val snap = PlaylistStore.snapshot()
        val arr = JSONArray()
        for (t in snap) {
            arr.put(
                JSONObject()
                    .put("url", t.url)
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("uploader", t.uploader)
                    .put("dur", t.duration)
                    .put("thumb", t.thumb)
            )
        }
        val curId = PlaylistStore.current?.id
        val current = if (curId != null) snap.indexOfFirst { it.id == curId } else -1
        pushEvent("playlist", JSONObject().put("current", current).put("items", arr))
    }

    private fun pushPlayer(snap: PlaybackSnapshot) {
        val o = JSONObject()
            .put("state", AudioService.stateName(snap.state))
            .put("pos", snap.positionSec)
            .put("dur", snap.durationSec)
            .put("index", snap.index)
            .put("id", snap.id ?: JSONObject.NULL)
            .put("title", snap.title ?: JSONObject.NULL)
            .put("uploader", snap.uploader ?: JSONObject.NULL)
        snap.id?.let { id -> NxService.streamLabelById(id)?.let { o.put("fmt", it) } }
        if (snap.error != null) o.put("error", snap.error)
        pushEvent("player", o)
    }

    private fun pushSearch(reqId: Int, ok: Boolean, error: String?, items: List<SearchResult>) {
        val arr = JSONArray()
        for (r in items) {
            arr.put(
                JSONObject()
                    .put("url", r.url)
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("uploader", r.uploader)
                    .put("dur", r.duration)
                    .put("thumb", r.thumb)
            )
        }
        val o = JSONObject().put("reqId", reqId).put("ok", ok)
        if (ok) {
            o.put("items", arr)
        } else {
            o.put("error", error ?: "Erro inesperado.")
        }
        pushEvent("search", o)
    }

    private fun sendToService(intent: Intent) {
        intent.setClass(this, AudioService::class.java)
        runCatching { startService(intent) }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    private fun trackOf(
        url: String?,
        id: String?,
        title: String?,
        uploader: String?,
        dur: Double,
        thumb: String?,
    ): Track? {
        val u = url?.trim().orEmpty()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        val vid = id?.takeIf { it.isNotBlank() } ?: NxService.videoIdFromUrl(u)
        val duration = if (dur.isFinite() && dur > 0) dur.toLong() else 0L
        return Track(
            id = vid,
            url = u,
            title = title?.takeIf { it.isNotBlank() } ?: "Sem título",
            uploader = uploader.orEmpty(),
            duration = duration,
            thumb = thumb?.takeIf { it.isNotBlank() } ?: "https://i.ytimg.com/vi/$vid/mqdefault.jpg",
        )
    }

    /** Ponte exposta ao JavaScript como window.NexusAndroid. */
    inner class NxBridge {

        @JavascriptInterface
        fun search(query: String?, reqId: Int) {
            val q = query?.trim().orEmpty()
            if (q.isEmpty()) {
                pushSearch(reqId, false, "Digite algo para buscar.", emptyList())
                return
            }
            searchExecutor.execute {
                try {
                    val items = NxService.search(q)
                    pushSearch(reqId, true, null, items)
                    NxService.warmUpSearchResults(items)
                } catch (t: Throwable) {
                    pushSearch(reqId, false, NxService.friendlyError(t), emptyList())
                }
            }
        }

        @JavascriptInterface
        fun getState() {
            if (AudioService.isAlive()) {
                AudioService.pushUi()
            } else {
                pushPlayer(PlaybackSnapshot.stopped())
            }
        }

        @JavascriptInterface
        fun setBoost(level: Int) {
            AudioService.setBoostLevel(level)
        }

        @JavascriptInterface
        fun getBoost(): Int = AudioService.boostLevel

        @JavascriptInterface
        fun urlState(url: String?): String = NxService.urlState(url?.trim().orEmpty())

        /** Reaquece a fila em ordem circular a partir da faixa atual (lotes novos supersedem). */
        @JavascriptInterface
        fun warmQueue() {
            val snap = PlaylistStore.snapshot()
            if (snap.isEmpty()) return
            val curId = PlaylistStore.current?.id
            val start = if (curId != null) snap.indexOfFirst { it.id == curId } else -1
            val ordered: List<String>
            if (start >= 0) {
                val out = ArrayList<String>(snap.size)
                for (i in snap.indices) out.add(snap[(start + i) % snap.size].url)
                ordered = out
            } else {
                ordered = snap.map { it.url }
            }
            NxService.warmUpQueue(ordered)
        }

        @JavascriptInterface
        fun addToPlaylist(
            url: String?, id: String?, title: String?, uploader: String?, dur: Double, thumb: String?,
        ): Int {
            val t = trackOf(url, id, title, uploader, dur, thumb) ?: return -1
            return PlaylistStore.add(t)
        }

        @JavascriptInterface
        fun playItem(
            url: String?, id: String?, title: String?, uploader: String?, dur: Double, thumb: String?,
        ) {
            val t = trackOf(url, id, title, uploader, dur, thumb) ?: return
            sendToService(
                Intent(AudioService.ACTION_PLAY_TRACK)
                    .putExtra(AudioService.EXTRA_TRACK_URL, t.url)
                    .putExtra(AudioService.EXTRA_TRACK_ID, t.id)
                    .putExtra(AudioService.EXTRA_TRACK_TITLE, t.title)
                    .putExtra(AudioService.EXTRA_TRACK_UPLOADER, t.uploader)
                    .putExtra(AudioService.EXTRA_TRACK_DURATION, t.duration)
                    .putExtra(AudioService.EXTRA_TRACK_THUMB, t.thumb)
            )
        }

        @JavascriptInterface
        fun playIndex(index: Int) {
            sendToService(Intent(AudioService.ACTION_PLAY_INDEX).putExtra(AudioService.EXTRA_INDEX, index))
        }

        @JavascriptInterface
        fun toggle() {
            sendToService(Intent(AudioService.ACTION_TOGGLE))
        }

        @JavascriptInterface
        fun next() {
            sendToService(Intent(AudioService.ACTION_NEXT))
        }

        @JavascriptInterface
        fun prev() {
            sendToService(Intent(AudioService.ACTION_PREV))
        }

        @JavascriptInterface
        fun seekTo(seconds: Double) {
            if (!seconds.isFinite() || seconds < 0) return
            sendToService(
                Intent(AudioService.ACTION_SEEK)
                    .putExtra(AudioService.EXTRA_POSITION_MS, (seconds * 1000.0).toLong())
            )
        }

        @JavascriptInterface
        fun stopPlayback() {
            sendToService(Intent(AudioService.ACTION_STOP))
        }

        @JavascriptInterface
        fun removeFromPlaylist(index: Int) {
            val track = PlaylistStore.snapshot().getOrNull(index)
            PlaylistStore.removeAt(index)
            if (track != null) NxService.forget(track.url)
        }

        @JavascriptInterface
        fun moveInPlaylist(from: Int, to: Int) {
            PlaylistStore.move(from, to)
        }

        @JavascriptInterface
        fun clearPlaylist() {
            PlaylistStore.clear()
        }
    }

    companion object {
        private const val REQ_NOTIF = 100
    }
}
