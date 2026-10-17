package com.leo.nexyou

import android.content.Context
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Resultado de busca ja normalizado para a ponte JS. */
data class SearchResult(
    val url: String,
    val id: String,
    val title: String,
    val uploader: String,
    val duration: Long,
    val thumb: String,
)

/** Fachada estatica sobre o NewPipeExtractor. */
object NxService {

    private const val URL_TTL_MS = 30 * 60 * 1000L
    private const val SEARCH_TTL_MS = 5 * 60 * 1000L
    private const val URL_CACHE_ENTRIES = 48
    private const val SEARCH_CACHE_ENTRIES = 6
    private const val PREFERRED_AUDIO_KBPS = 128
    private const val WARMUP_LIMIT = 8
    private const val WARMUP_BANDS = 2
    private const val WARMUP_GAP_MS = 350L
    private const val QUEUE_WARMUP_LIMIT = 16
    private const val QUEUE_BANDS = 2
    private const val QUEUE_RESOLVE_GAP_MS = 300L
    private const val QUEUE_DEBOUNCE_MS = 250L
    private const val RESOLVE_ATTEMPTS = 2
    private const val RESOLVE_RETRY_MS = 500L
    private const val SEARCH_RETRY_MS = 500L
    private const val WARM_FAIL_PAUSE_AFTER = 3
    private const val WARM_PAUSE_MS = 45_000L
    private const val AUDIO_MAX_FILES = 30
    private const val AUDIO_MAX_BYTES = 300L * 1024 * 1024
    private const val AUDIO_CONNECT_TIMEOUT_MS = 15_000
    private const val AUDIO_READ_TIMEOUT_MS = 30_000
    private const val RANGE_PARTS = 4
    private const val RANGE_MIN_BYTES = 1_500_000L
    private const val RANGE_MIN_PART_BYTES = 600_000L
    private const val URL_CACHE_FILE = "url_cache.json"

    private class CachedUrl(val url: String, val at: Long, val format: String, val kbps: Int)

    private class ResolvedAudio(val url: String, val format: String, val kbps: Int)

    private class CachedSearch(val results: List<SearchResult>, val at: Long)

    private val service: StreamingService get() = ServiceList.YouTube

    private val urlCache = LruCache<String, CachedUrl>(URL_CACHE_ENTRIES)
    private val searchCache = LruCache<String, CachedSearch>(SEARCH_CACHE_ENTRIES)

    /** Resolucoes de URL em andamento — dedup: toques simultaneos esperam a mesma resolucao. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<String>>()

    /** Pre-aquecimento da busca: 2 bandas paralelas (1 resolucao por vez por banda, com gap). */
    private val warmBands = List(WARMUP_BANDS) { band ->
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "nx-warm$band").apply { isDaemon = true }
        }
    }

    /** Bandas paralelas da fila — cada lote novo supersede o anterior (queueGeneration). */
    private val queueBands = List(QUEUE_BANDS) { band ->
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "nx-warmq$band").apply { isDaemon = true }
        }
    }
    private val warmGeneration = AtomicLong(0)
    private val queueGeneration = AtomicLong(0)

    /** Pausa global dos warm-ups após falhas seguidas (proteção contra throttling do YouTube). */
    @Volatile
    private var warmPauseUntil = 0L
    private val warmFails = AtomicInteger(0)

    /** Debounce do warm-up da fila — rajadas de mudanças na fila viram um único lote. */
    private val warmQueueLock = Any()
    private var warmQueuePending: List<String>? = null
    private var warmQueueScheduled = false
    private val warmQueueTimer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nx-warmdeb").apply { isDaemon = true }
    }

    /** Chaves de vídeo agendadas para pré-resolução — alimenta o estado "queued" da UI. */
    private val scheduled = ConcurrentHashMap.newKeySet<String>()

    /** Contexto da aplicação — setado no attach() para o cache de áudio em disco. */
    @Volatile
    private var appContext: Context? = null

    /** Executor único do cache: downloads de áudio + persistência do cache de URLs. */
    private val cacheExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nx-cache").apply { isDaemon = true }
    }

    /** Chaves com arquivo de áudio completo no disco (estado "ready" da UI). */
    private val audioReadyKeys = ConcurrentHashMap.newKeySet<String>()

    /** Chaves em download agora (estado "loading" da UI). */
    private val downloadInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Chaves em resolução de URL agora (estado "loading" da UI). */
    private val resolveInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Chaves bloqueadas para download até o usuário agir (tocar de novo/limpar). */
    private val forgottenKeys = ConcurrentHashMap.newKeySet<String>()

    /** Gravação do cache de URLs coalescida (no máximo uma por rajada). */
    private val persistDirty = AtomicBoolean(false)

    @Volatile
    private var initialized = false

    @Synchronized
    fun init() {
        if (initialized) return
        val locale = Locale("pt", "BR")
        NewPipe.init(
            NxDownloader(),
            Localization.fromLocale(locale),
            ContentCountry("BR"),
        )
        initialized = true
    }

    /** Conecta o cache de áudio ao contexto — chamar uma vez no onCreate da MainActivity. */
    @Synchronized
    fun attach(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        NxDownloader.attach(ctx)
        init()
        val dir = File(ctx.filesDir, "audio")
        dir.mkdirs()
        runCatching {
            dir.listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".bin") }?.forEach {
                audioReadyKeys.add(it.name.substringBeforeLast('.'))
            }
        }
        loadUrlCache(ctx)
        cacheExecutor.execute {
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }?.forEach { it.delete() }
            }
        }
    }

    /** Aquece o cliente HTTP/extrator (a primeira chamada e a mais lenta). */
    fun warmUp() {
        init()
        runCatching { YoutubeParsingHelper.getClientVersion() }
    }

    fun search(query: String, limit: Int = 30): List<SearchResult> {
        init()
        val key = query.trim().lowercase(Locale.ROOT)
        val hit = searchCache.get(key)
        if (hit != null && System.currentTimeMillis() - hit.at < SEARCH_TTL_MS) {
            return hit.results.take(limit)
        }
        // Busca e acao do usuario: nao espera o espacamento anti-bot.
        val out = NxDownloader.bypassThrottle {
            try {
                fetchSearch(query, limit)
            } catch (t: Throwable) {
                if (t is ReCaptchaException) throw t
                sleepQuietly(SEARCH_RETRY_MS)
                fetchSearch(query, limit)
            }
        }
        searchCache.put(key, CachedSearch(out, System.currentTimeMillis()))
        return out
    }

    private fun fetchSearch(query: String, limit: Int): List<SearchResult> {
        val extractor = service.getSearchExtractor(query)
        extractor.fetchPage()
        val items = extractor.initialPage.items
        val out = ArrayList<SearchResult>(items.size)
        for (item in items) {
            if (item !is StreamInfoItem) continue
            val url = item.url ?: continue
            val id = videoIdFromUrl(url)
            val duration = if (item.duration >= 0) item.duration else 0L
            out.add(
                SearchResult(
                    url = url,
                    id = id,
                    title = item.name.orEmpty(),
                    uploader = item.uploaderName.orEmpty(),
                    duration = duration,
                    thumb = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    /** Resolve a URL de audio — M4A 128k (itag 140), opus so como ultimo recurso — com cache TTL e dedup. */
    fun resolveAudioUrl(videoUrl: String): String {
        init()
        val key = cacheKeyFor(videoUrl)
        if (key != null) {
            val hit = urlCache.get(key)
            if (hit != null && System.currentTimeMillis() - hit.at < URL_TTL_MS) {
                return hit.url
            }
        }
        if (key == null) return resolveAudioUrlUncached(videoUrl).url

        // Dedup: se ja existe resolucao em andamento para este video, espera por ela.
        val mine = CompletableFuture<String>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return awaitInFlight(running)

        resolveInFlight.add(key)
        try {
            val resolved = resolveAudioUrlUncached(videoUrl)
            urlCache.put(key, CachedUrl(resolved.url, System.currentTimeMillis(), resolved.format, resolved.kbps))
            schedulePersistUrlCache()
            mine.complete(resolved.url)
            return resolved.url
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            resolveInFlight.remove(key)
            inFlight.remove(key, mine)
        }
    }

    /** Espera uma resolucao em andamento preservando a excecao original. */
    private fun awaitInFlight(future: CompletableFuture<String>): String {
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Resolucao interrompida.", e)
        }
    }

    /** Resolve ignorando o cache (e o substitui) — usado para retentar URL expirada. */
    fun resolveAudioUrlFresh(videoUrl: String): String {
        init()
        val key = cacheKeyFor(videoUrl)
        if (key != null) urlCache.remove(key)
        val resolved = resolveAudioUrlUncached(videoUrl)
        if (key != null) {
            urlCache.put(key, CachedUrl(resolved.url, System.currentTimeMillis(), resolved.format, resolved.kbps))
            schedulePersistUrlCache()
        }
        return resolved.url
    }

    /**
     * Pre-resolve (somente URL, sem baixar áudio) os primeiros resultados da busca —
     * o toque fica quase instantâneo e não há tráfego pesado competindo com a reprodução.
     */
    fun warmUpSearchResults(results: List<SearchResult>) {
        if (results.isEmpty() || warmPaused()) return
        val gen = warmGeneration.incrementAndGet()
        val pending = ArrayList<Pair<String, String>>(WARMUP_LIMIT)
        for (r in results) {
            if (pending.size >= WARMUP_LIMIT) break
            val key = cacheKeyFor(r.url) ?: continue
            if (isAudioReadyKey(key)) continue
            forgottenKeys.remove(key)
            pending.add(r.url to key)
        }
        if (pending.isEmpty()) return
        for (p in pending) scheduled.add(p.second)
        for (band in 0 until WARMUP_BANDS) {
            val chunk = ArrayList<Pair<String, String>>()
            var i = band
            while (i < pending.size) {
                chunk.add(pending[i])
                i += WARMUP_BANDS
            }
            if (chunk.isNotEmpty()) {
                warmBands[band].execute { runResolveChunk(chunk, gen, warmGeneration, WARMUP_GAP_MS) }
            }
        }
    }

    /**
     * Aquece a fila resolvendo SÓ as URLs, na ordem recebida (teto de 24, [QUEUE_BANDS] bandas).
     * Nada de baixar áudio aqui: download em massa disputava banda com a reprodução e
     * era o que deixava o play lento. Rajadas são coalescidas; lotes novos supersedem os antigos.
     */
    fun warmUpQueue(urls: List<String>) {
        if (urls.isEmpty()) return
        synchronized(warmQueueLock) {
            warmQueuePending = urls
            if (warmQueueScheduled) return
            warmQueueScheduled = true
        }
        warmQueueTimer.schedule({
            val pending = synchronized(warmQueueLock) {
                warmQueueScheduled = false
                warmQueuePending.also { warmQueuePending = null }
            } ?: return@schedule
            warmUpQueueNow(pending)
        }, QUEUE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun warmUpQueueNow(urls: List<String>) {
        if (warmPaused()) return
        val gen = queueGeneration.incrementAndGet()
        val pending = ArrayList<Pair<String, String>>(QUEUE_WARMUP_LIMIT)
        val seen = HashSet<String>()
        for (u in urls) {
            if (pending.size >= QUEUE_WARMUP_LIMIT) break
            val key = cacheKeyFor(u) ?: continue
            if (!seen.add(key)) continue
            if (isAudioReadyKey(key)) continue
            forgottenKeys.remove(key)
            pending.add(u to key)
        }
        if (pending.isEmpty()) return
        for (p in pending) scheduled.add(p.second)
        for (band in 0 until QUEUE_BANDS) {
            val chunk = ArrayList<Pair<String, String>>()
            var i = band
            while (i < pending.size) {
                chunk.add(pending[i])
                i += QUEUE_BANDS
            }
            if (chunk.isNotEmpty()) {
                queueBands[band].execute { runResolveChunk(chunk, gen, queueGeneration, QUEUE_RESOLVE_GAP_MS) }
            }
        }
    }

    /** Resolve um lote de URLs sequencialmente com gap; abandona se um lote mais novo superou esta geração. */
    private fun runResolveChunk(
        items: List<Pair<String, String>>,
        gen: Long,
        genSource: AtomicLong,
        gapMs: Long,
    ) {
        try {
            for ((url, _) in items) {
                if (genSource.get() != gen) return
                if (warmPaused()) return
                var ok = false
                try {
                    resolveAudioUrl(url)
                    ok = true
                } catch (_: Throwable) {
                } finally {
                    noteWarmResult(ok)
                }
                if (genSource.get() != gen) return
                if (!sleepQuietly(gapMs)) return
            }
        } finally {
            for ((_, key) in items) scheduled.remove(key)
        }
    }

    private fun warmPaused(): Boolean =
        System.currentTimeMillis() < warmPauseUntil || NxDownloader.isBlocked()

    /** Conta falhas seguidas dos warm-ups; ao atingir o limite, pausa tudo por um tempo. */
    private fun noteWarmResult(ok: Boolean) {
        if (ok) {
            warmFails.set(0)
            return
        }
        if (warmFails.incrementAndGet() >= WARM_FAIL_PAUSE_AFTER) {
            warmFails.set(0)
            warmPauseUntil = System.currentTimeMillis() + WARM_PAUSE_MS
        }
    }

    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** True se existe URL cacheada (mesmo expirada) para o video. */
    fun isUrlCached(videoUrl: String): Boolean {
        val key = cacheKeyFor(videoUrl) ?: return false
        return urlCache.get(key) != null
    }

    /**
     * Estado de carga de um vídeo para a UI: ready = arquivo completo OU URL já resolvida
     * (toca na hora, sem esperar); loading = resolvendo/baixando; queued = agendado; none.
     */
    fun urlState(videoUrl: String): String {
        if (videoUrl.isBlank()) return "none"
        val key = cacheKeyFor(videoUrl) ?: return "none"
        if (isAudioReadyKey(key)) return "ready"
        val cached = urlCache.get(key)
        if (cached != null && System.currentTimeMillis() - cached.at < URL_TTL_MS) return "ready"
        if (resolveInFlight.contains(key) || downloadInFlight.contains(key)) return "loading"
        if (scheduled.contains(key)) return "queued"
        return "none"
    }

    // ---------- cache de áudio em disco (play instantâneo) ----------

    private fun audioDir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, "audio")
    }

    private fun audioFile(key: String): File? {
        val dir = audioDir() ?: return null
        return File(dir, "$key.bin")
    }

    /** true se há arquivo completo para a chave (remove órfãos do set). */
    private fun isAudioReadyKey(key: String): Boolean {
        if (key !in audioReadyKeys) return false
        val f = audioFile(key)
        if (f != null && f.isFile && f.length() > 0) return true
        audioReadyKeys.remove(key)
        return false
    }

    /** Público para o AudioService: tocar direto do disco quando pronto. */
    fun isAudioReady(videoUrl: String): Boolean {
        val key = cacheKeyFor(videoUrl) ?: return false
        return isAudioReadyKey(key)
    }

    /** Arquivo local pronto para tocar, ou null (só consulta; não altera estado). */
    fun audioFileFor(videoUrl: String): File? {
        val key = cacheKeyFor(videoUrl) ?: return null
        if (!isAudioReadyKey(key)) return null
        return audioFile(key)
    }

    /** Marca o arquivo como usado agora (LRU); não altera estado. */
    fun markAudioUsed(videoUrl: String) {
        val key = cacheKeyFor(videoUrl) ?: return
        val f = audioFile(key) ?: return
        if (f.isFile) runCatching { f.setLastModified(System.currentTimeMillis()) }
    }

    /** Pré-baixa o áudio em background (fila do cacheExecutor). */
    fun prefetchAudio(videoUrl: String) {
        cacheExecutor.execute { downloadAudio(videoUrl) }
    }

    /** Esquece um vídeo: bloqueia downloads, apaga arquivo local e URL cacheada. */
    fun forget(videoUrl: String) {
        val key = cacheKeyFor(videoUrl) ?: return
        forgottenKeys.add(key)
        urlCache.remove(key)
        audioReadyKeys.remove(key)
        scheduled.remove(key)
        val dir = audioDir()
        if (dir != null) {
            runCatching { File(dir, "$key.bin").delete() }
            runCatching { File(dir, "$key.part").delete() }
        }
        schedulePersistUrlCache()
    }

    /** Baixa o áudio completo (resolve + HTTP) para disco, um por vez. Silencioso. */
    private fun downloadAudio(videoUrl: String) {
        if (NxDownloader.isBlocked()) return
        val key = cacheKeyFor(videoUrl) ?: return
        if (key in forgottenKeys) return
        if (isAudioReadyKey(key)) return
        if (!downloadInFlight.add(key)) return
        try {
            if (key in forgottenKeys) return
            val url = resolveAudioUrl(videoUrl)
            if (key in forgottenKeys) return
            val dir = audioDir() ?: return
            dir.mkdirs()
            val part = File(dir, "$key.part")
            val dest = File(dir, "$key.bin")
            try {
                downloadTo(part, url)
                if (key in forgottenKeys) return
                if (!part.renameTo(dest)) throw IOException("Falha ao finalizar o arquivo de áudio.")
                dest.setLastModified(System.currentTimeMillis())
                audioReadyKeys.add(key)
                evictAudio()
            } catch (t: Throwable) {
                runCatching { part.delete() }
                throw t
            }
        } catch (_: Throwable) {
            // silencioso: pré-carga nunca incomoda o usuário
        } finally {
            downloadInFlight.remove(key)
        }
    }

    /**
     * GET completo do stream para um arquivo .part com 4 conexões Range em paralelo
     * (o YouTube limita por conexão; em paralelo o arquivo fica pronto bem mais rápido).
     * Sem suporte a Range, cai para uma conexão única.
     */
    private fun downloadTo(part: File, url: String) {
        val total = remoteSize(url)
        if (total <= 0) {
            copyStream(part, url)
            return
        }
        if (total > AUDIO_MAX_BYTES) throw IOException("Áudio grande demais.")
        val parts = RANGE_PARTS
            .coerceAtMost((total / RANGE_MIN_PART_BYTES).toInt().coerceAtLeast(1))
        if (total < RANGE_MIN_BYTES || parts <= 1) {
            copyStream(part, url)
            return
        }
        RandomAccessFile(part, "rw").use { it.setLength(total) }
        val failure = AtomicReference<Throwable?>(null)
        val chunk = total / parts
        val workers = ArrayList<Thread>(parts)
        for (i in 0 until parts) {
            val start = i * chunk
            val end = if (i == parts - 1) total - 1 else start + chunk - 1
            val t = Thread({
                try {
                    copyRange(part, url, start, end)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }, "nx-part$i").apply { isDaemon = true }
            workers.add(t)
            t.start()
        }
        for (t in workers) t.join()
        failure.get()?.let { throw it }
        if (part.length() != total) throw IOException("Download incompleto.")
    }

    /** Uma conexão única (caminho simples/quando não há Range). */
    private fun copyStream(part: File, url: String) {
        val conn = openConn(url)
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val expected = conn.contentLengthLong
            var written = 0L
            FileOutputStream(part).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Interrompido.")
                        val n = input.read(buf)
                        if (n <= 0) break
                        written += n
                        if (written > AUDIO_MAX_BYTES) throw IOException("Áudio grande demais.")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (written <= 0) throw IOException("Áudio vazio.")
            if (expected > 0 && written != expected) throw IOException("Download incompleto.")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Baixa um pedaço (Range) direto no offset do arquivo. */
    private fun copyRange(part: File, url: String, start: Long, end: Long) {
        val conn = openConn(url)
        try {
            conn.setRequestProperty("Range", "bytes=$start-$end")
            conn.connect()
            val code = conn.responseCode
            if (code != 206) throw IOException("HTTP $code (Range indisponível).")
            var pos = start
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(start)
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (pos <= end) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Interrompido.")
                        val want = minOf(buf.size.toLong(), end - pos + 1).toInt()
                        val n = input.read(buf, 0, want)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        pos += n
                    }
                }
            }
            if (pos != end + 1) throw IOException("Parte incompleta.")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Descobre o tamanho remoto com um Range de 1 byte; -1 se o servidor não suportar Range. */
    private fun remoteSize(url: String): Long {
        val conn = openConn(url)
        return try {
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.connect()
            if (conn.responseCode == 206) {
                conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
            } else {
                -1L
            }
        } catch (_: Throwable) {
            -1L
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun openConn(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = AUDIO_CONNECT_TIMEOUT_MS
            readTimeout = AUDIO_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
        }

    /** Mantém o cache de áudio no limite (LRU pelo último uso). */
    private fun evictAudio() {
        val dir = audioDir() ?: return
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.toMutableList() ?: return
            files.sortBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            var i = 0
            while (i < files.size && (files.size - i > AUDIO_MAX_FILES || total > AUDIO_MAX_BYTES)) {
                val f = files[i]
                i++
                val key = f.name.substringBeforeLast('.')
                if (key in downloadInFlight) continue
                total -= f.length()
                if (f.delete()) audioReadyKeys.remove(key)
            }
        }
    }

    // ---------- persistência do cache de URLs ----------

    private fun urlCacheFile(context: Context): File = File(context.filesDir, URL_CACHE_FILE)

    private fun loadUrlCache(context: Context) {
        runCatching {
            val f = urlCacheFile(context)
            if (!f.isFile) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val url = o.optString("url", "")
                val at = o.optLong("at", 0L)
                val fmt = o.optString("fmt", "")
                val kbps = o.optInt("kbps", 0)
                if (id.length == 11 && url.isNotBlank()) urlCache.put(id, CachedUrl(url, at, fmt, kbps))
            }
        }
    }

    /** Agenda a gravação do cache de URLs (coalescida; roda no cacheExecutor). */
    private fun schedulePersistUrlCache() {
        if (!persistDirty.compareAndSet(false, true)) return
        cacheExecutor.execute {
            while (persistDirty.get()) {
                persistDirty.set(false)
                runCatching { writeUrlCache() }
            }
        }
    }

    private fun writeUrlCache() {
        val ctx = appContext ?: return
        val arr = JSONArray()
        for ((id, cached) in urlCache.snapshot()) {
            val o = JSONObject()
            o.put("id", id)
            o.put("url", cached.url)
            o.put("at", cached.at)
            o.put("fmt", cached.format)
            o.put("kbps", cached.kbps)
            arr.put(o)
        }
        val tmp = File(ctx.filesDir, "$URL_CACHE_FILE.tmp")
        FileOutputStream(tmp).use { it.write(arr.toString().toByteArray()) }
        val f = urlCacheFile(ctx)
        if (!tmp.renameTo(f)) {
            runCatching { f.delete() }
            if (!tmp.renameTo(f)) throw IOException("Falha ao gravar o cache de URLs.")
        }
    }

    /**
     * Resolve com uma segunda tentativa curta: o YouTube às vezes devolve player vazio
     * (throttling/anti-bot) e a mesma requisição funciona logo em seguida.
     */
    private fun resolveAudioUrlUncached(videoUrl: String): ResolvedAudio {
        init()
        var last: Throwable? = null
        for (attempt in 1..RESOLVE_ATTEMPTS) {
            try {
                return fetchResolved(videoUrl)
            } catch (t: Throwable) {
                if (t is ReCaptchaException) throw t
                last = t
                if (attempt < RESOLVE_ATTEMPTS) {
                    sleepQuietly(RESOLVE_RETRY_MS + (0L..200L).random())
                }
            }
        }
        throw last ?: ExtractionException("Nenhum stream de audio disponivel.")
    }

    private fun fetchResolved(videoUrl: String): ResolvedAudio {
        val info = StreamInfo.getInfo(service, videoUrl)
        val stream = info.audioStreams
            .filter { it.isUrl }
            .minWithOrNull(
                compareBy<AudioStream>(
                    { formatRank(it.format) },
                    { bitrateDistanceKbps(it) },
                    { -it.averageBitrate },
                )
            )
            ?: throw ExtractionException("Nenhum stream de audio disponivel.")
        val url = stream.content
        if (url.isNullOrBlank()) {
            throw ExtractionException("Nenhum stream de audio disponivel.")
        }
        val format = when (stream.format) {
            MediaFormat.M4A -> "m4a"
            MediaFormat.WEBMA_OPUS -> "opus"
            else -> "audio"
        }
        val kbps = kbpsOf(stream)
        Log.d("NxService", "audio ${videoIdFromUrl(videoUrl)} -> $format ${kbps}k")
        return ResolvedAudio(url, format, kbps)
    }

    private fun cacheKeyFor(videoUrl: String): String? =
        videoIdFromUrl(videoUrl).takeIf { it.length == 11 }

    private fun formatRank(format: MediaFormat?): Int = when (format) {
        MediaFormat.M4A -> 0
        MediaFormat.WEBMA_OPUS -> 1
        else -> 2
    }

    /** Bitrate em kbps (normaliza bps; 0 quando desconhecido). */
    private fun kbpsOf(stream: AudioStream): Int {
        val raw = stream.averageBitrate
        if (raw <= 0) return 0
        return if (raw >= 1000) raw / 1000 else raw
    }

    /** Distancia do bitrate ao alvo de 128 kbps (desconhecido fica por ultimo). */
    private fun bitrateDistanceKbps(stream: AudioStream): Int {
        val kbps = kbpsOf(stream)
        return if (kbps <= 0) Int.MAX_VALUE else kotlin.math.abs(kbps - PREFERRED_AUDIO_KBPS)
    }

    /** Rotulo do formato ja resolvido (ex.: "m4a 129k") para a UI; null se ainda nao resolvido. */
    fun streamLabelById(videoId: String): String? {
        if (videoId.length != 11) return null
        val cached = urlCache.get(videoId) ?: return null
        return if (cached.kbps > 0) "${cached.format} ${cached.kbps}k" else cached.format.takeIf { it.isNotBlank() }
    }

    fun videoIdFromUrl(url: String): String {
        val match = VID_REGEX.find(url)
        if (match != null) return match.groupValues[1]
        return url.substringAfterLast('/')
            .substringBefore('?')
            .takeLast(11)
    }

    /** Converte excecoes em mensagem amigavel pt-BR (cooldown tem prioridade). */
    fun friendlyError(t: Throwable): String {
        if (NxDownloader.isBlocked()) return NxDownloader.blockedMessage()
        return when (t) {
            is ReCaptchaException -> "O YouTube pediu verificacao (captcha). Tente novamente em alguns minutos."
            is ContentNotAvailableException -> "Video indisponivel no momento."
            is ExtractionException -> "O YouTube recusou a requisicao agora. Aguarde alguns segundos e tente de novo."
            is IOException -> "Falha de rede. Verifique sua conexao."
            else -> t.message ?: "Erro inesperado."
        }
    }

    private val VID_REGEX = Regex("[?&]v=([A-Za-z0-9_-]{11})")
}
