package com.leo.nexyou

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.AudioAttributes as M3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Estados de reproducao (tambem usados pela ponte JS).
const val STATE_STOPPED = 0
const val STATE_LOADING = 1
const val STATE_PLAYING = 2
const val STATE_PAUSED = 3
const val STATE_ERROR = 4

/** Snapshot imutavel do estado de reproducao enviado para a UI. */
data class PlaybackSnapshot(
    val state: Int,
    val positionSec: Double,
    val durationSec: Double,
    val index: Int,
    val id: String?,
    val title: String?,
    val uploader: String?,
    val error: String?,
) {
    companion object {
        fun stopped() = PlaybackSnapshot(STATE_STOPPED, 0.0, 0.0, -1, null, null, null, null)
    }
}

/** Callback de estado (UI principal). */
fun interface Listener {
    fun onPlayback(snapshot: PlaybackSnapshot)
}

/**
 * Servico de reproducao em foreground:
 * resolve a URL de audio do YouTube (NewPipeExtractor) em background e
 * toca via media3/ExoPlayer (buffer de início curto), com notificacao + MediaSession + audio focus.
 */
class AudioService : Service() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var loudness: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var preamp: DynamicsProcessing? = null
    private var effectsSession = 0

    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor()
    private val prefetchExecutor = Executors.newSingleThreadExecutor()

    private var state = STATE_STOPPED
    private var currentIndex = -1
    private var currentTrack: Track? = null
    private var lastError: String? = null
    private var pauseWhenReady = false
    private var pausedByFocus = false
    private var notifActive = false
    private var transient = false
    private var freshUrlRetried = false
    private var resolveRetried = false
    @Volatile private var prefetchInFlight = false
    @Volatile private var currentUrlFromCache = false
    @Volatile private var currentFromFile = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && state == STATE_PLAYING) {
                pause()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            pushState()
            if (state == STATE_PLAYING || state == STATE_PAUSED) {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (state == STATE_PLAYING) pause()
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (state == STATE_PLAYING) {
                    pausedByFocus = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player?.volume = DUCK_VOLUME
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.volume = 1f
                if (pausedByFocus) {
                    pausedByFocus = false
                    resume()
                }
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = resume()
        override fun onPause() = pause()
        override fun onSkipToNext() = playNext()
        override fun onSkipToPrevious() = playPrevOrRestart()
        override fun onStop() = stopEverything()
        override fun onSeekTo(pos: Long) = seekTo(pos)
    }

    /**
     * Player nativo (media3) com buffer de início curto: começa a tocar com ~250 ms
     * de áudio em vez dos ~2,5 s padrão — é o que deixa o toque quase instantâneo.
     */
    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 15_000, 250, 500)
            .build()
        val attrs = M3AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(attrs, false)
            .build()
            .apply { addListener(playerListener) }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> onPlayerReady()
                Player.STATE_ENDED -> onPlayerEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            onPlayerFailed()
        }
    }

    private fun onPlayerReady() {
        applyBoost()
        if (state != STATE_LOADING) return
        if (pauseWhenReady) {
            pauseWhenReady = false
            state = STATE_PAUSED
            player?.pause()
        } else {
            ensureFocus()
            state = STATE_PLAYING
        }
        onStateChanged()
        startTicker()
        // Só pré-baixa a próxima depois que a reprodução estabilizou (não disputa o início).
        handler.postDelayed(prefetchRunnable, PREFETCH_DELAY_MS)
    }

    private fun onPlayerEnded() {
        if (state != STATE_PLAYING && state != STATE_PAUSED) return
        if (transient) {
            if (PlaylistStore.snapshot().isEmpty()) stopEverything() else playIndex(0)
        } else {
            playNext()
        }
    }

    private fun onPlayerFailed() {
        if (currentFromFile) {
            // Arquivo local falhou (corrompido): esquece e volta para streaming fresco.
            currentFromFile = false
            currentTrack?.url?.let { NxService.forget(it) }
            retryFreshUrl(generation.get())
        } else if (currentUrlFromCache && !freshUrlRetried) {
            retryFreshUrl(generation.get())
        } else {
            failPlayback("Falha ao reproduzir o áudio.")
        }
    }

    private val prefetchRunnable = Runnable { prefetchNext() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AudioManager::class.java)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_playback_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )

        session = MediaSession(this, "NEXYOU").apply {
            setCallback(sessionCallback)
            setActive(true)
        }

        // Player persistente: reutilizado entre faixas (sem custo de criação por toque).
        player = runCatching { buildPlayer() }.getOrNull()

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisyReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_PLAY_INDEX -> {
                val index = intent.getIntExtra(EXTRA_INDEX, -1)
                if (index >= 0) playIndex(index)
            }
            ACTION_PLAY_TRACK -> {
                val url = intent.getStringExtra(EXTRA_TRACK_URL).orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val id = intent.getStringExtra(EXTRA_TRACK_ID)?.takeIf { it.isNotBlank() }
                        ?: NxService.videoIdFromUrl(url)
                    playTransient(
                        Track(
                            id = id,
                            url = url,
                            title = intent.getStringExtra(EXTRA_TRACK_TITLE)?.takeIf { it.isNotBlank() } ?: "Sem título",
                            uploader = intent.getStringExtra(EXTRA_TRACK_UPLOADER).orEmpty(),
                            duration = intent.getLongExtra(EXTRA_TRACK_DURATION, 0L),
                            thumb = intent.getStringExtra(EXTRA_TRACK_THUMB)?.takeIf { it.isNotBlank() }
                                ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                        )
                    )
                }
            }
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevOrRestart()
            ACTION_SEEK -> {
                val ms = intent.getLongExtra(EXTRA_POSITION_MS, -1L)
                if (ms >= 0) seekTo(ms)
            }
            ACTION_STOP -> stopEverything()
            ACTION_PLAYLIST_UPDATED -> onPlaylistUpdated()
        }
        if (state == STATE_STOPPED) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        generation.incrementAndGet()
        stopTicker()
        releasePlayer()
        runCatching { unregisterReceiver(noisyReceiver) }
        session?.release()
        session = null
        abandonFocus()
        executor.shutdownNow()
        prefetchExecutor.shutdownNow()
        listener = null
        if (instance === this) instance = null
        notifActive = false
        super.onDestroy()
    }

    // ---------------------------------------------------------- comandos

    private fun playIndex(index: Int) {
        val snap = PlaylistStore.snapshot()
        val track = snap.getOrNull(index) ?: return
        startTrack(track, index, isTransient = false)
    }

    /** Toca um item avulso (resultado de busca) sem alterar a fila. */
    private fun playTransient(track: Track) {
        PlaylistStore.clearCurrent()
        startTrack(track, index = -1, isTransient = true)
    }

    private fun startTrack(track: Track, index: Int, isTransient: Boolean) {
        generation.incrementAndGet()
        stopPlayback()
        stopTicker()
        pausedByFocus = false
        pauseWhenReady = false
        lastError = null
        transient = isTransient
        freshUrlRetried = false
        resolveRetried = false
        currentTrack = track
        currentIndex = index
        if (index >= 0) PlaylistStore.setCurrent(index)
        currentUrlFromCache = NxService.isUrlCached(track.url)
        if (index >= 0) warmQueueAround(index)
        state = STATE_LOADING
        ensureForeground()
        onStateChanged()

        val gen = generation.get()

        // Arquivo local completo? Toca direto do disco (instantâneo, sem rede).
        val local = NxService.audioFileFor(track.url)
        if (local != null) {
            NxService.markAudioUsed(track.url)
            startPlayer(local.absolutePath, gen, fromFile = true)
            return
        }

        executor.execute {
            try {
                val url = NxDownloader.bypassThrottle { NxService.resolveAudioUrl(track.url) }
                handler.post {
                    if (gen == generation.get()) startPlayer(url, gen)
                }
            } catch (t: Throwable) {
                handler.post {
                    if (gen != generation.get()) return@post
                    if (!resolveRetried && !NxDownloader.isBlocked()) {
                        resolveRetried = true
                        retryResolve(gen)
                    } else {
                        failPlayback(NxService.friendlyError(t))
                    }
                }
            }
        }
    }

    /** Segunda tentativa silenciosa quando o extrator falha de forma transitória. */
    private fun retryResolve(gen: Int) {
        val track = currentTrack ?: return
        handler.postDelayed({
            if (gen != generation.get()) return@postDelayed
            executor.execute {
                try {
                    val url = NxDownloader.bypassThrottle { NxService.resolveAudioUrlFresh(track.url) }
                    handler.post {
                        if (gen == generation.get()) startPlayer(url, gen)
                    }
                } catch (t: Throwable) {
                    handler.post {
                        if (gen == generation.get()) failPlayback(NxService.friendlyError(t))
                    }
                }
            }
        }, RESOLVE_RETRY_DELAY_MS)
    }

    private fun startPlayer(url: String, gen: Int, fromFile: Boolean = false) {
        if (gen != generation.get()) return
        currentFromFile = fromFile
        val exo = player ?: run {
            failPlayback("Player indisponível.")
            return
        }
        try {
            val uri = if (fromFile) Uri.fromFile(File(url)) else Uri.parse(url)
            exo.stop()
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            if (pauseWhenReady) exo.pause() else exo.play()
        } catch (t: Exception) {
            failPlayback(NxService.friendlyError(t))
        }
    }

    /** URL cacheada pode ter expirado: tenta uma resolucao fresca uma unica vez. */
    private fun retryFreshUrl(gen: Int) {
        if (gen != generation.get()) return
        val track = currentTrack ?: return
        freshUrlRetried = true
        stopPlayback()
        stopTicker()
        state = STATE_LOADING
        onStateChanged()
        executor.execute {
            try {
                val url = NxDownloader.bypassThrottle { NxService.resolveAudioUrlFresh(track.url) }
                handler.post {
                    if (gen == generation.get()) {
                        currentUrlFromCache = false
                        startPlayer(url, gen)
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    if (gen == generation.get()) failPlayback(NxService.friendlyError(t))
                }
            }
        }
    }

    /** Pré-baixa o áudio da próxima faixa (silencioso, sem tocar). */
    private fun prefetchNext() {
        if (transient || prefetchInFlight) return
        val snap = PlaylistStore.snapshot()
        if (snap.size < 2 || currentIndex !in snap.indices) return
        val next = snap[(currentIndex + 1) % snap.size]
        if (next.id == currentTrack?.id) return
        if (NxService.isAudioReady(next.url)) return
        prefetchInFlight = true
        prefetchExecutor.execute {
            try {
                NxService.prefetchAudio(next.url)
            } catch (_: Throwable) {
            } finally {
                prefetchInFlight = false
            }
        }
    }

    /** Aquece a fila em ordem circular a partir da faixa atual (lotes novos supersedem). */
    private fun warmQueueAround(index: Int) {
        val snap = PlaylistStore.snapshot()
        if (snap.isEmpty()) return
        val ordered: List<String>
        if (index in snap.indices) {
            val out = ArrayList<String>(snap.size)
            for (i in snap.indices) out.add(snap[(index + i) % snap.size].url)
            ordered = out
        } else {
            ordered = snap.map { it.url }
        }
        NxService.warmUpQueue(ordered)
    }

    private fun pause() {
        if (state != STATE_PLAYING) return
        runCatching { player?.pause() }
        state = STATE_PAUSED
        onStateChanged()
    }

    private fun resume() {
        when (state) {
            STATE_PAUSED -> {
                ensureFocus()
                runCatching { player?.play() }
                state = STATE_PLAYING
                onStateChanged()
                startTicker()
            }
            STATE_STOPPED, STATE_ERROR -> {
                val snap = PlaylistStore.snapshot()
                if (snap.isEmpty()) return
                val idx = currentIndex.takeIf { it in snap.indices } ?: 0
                playIndex(idx)
            }
            else -> Unit
        }
    }

    private fun toggle() {
        when (state) {
            STATE_PLAYING -> pause()
            STATE_PAUSED -> resume()
            STATE_LOADING -> {
                pauseWhenReady = !pauseWhenReady
                if (pauseWhenReady) player?.pause() else player?.play()
            }
            else -> resume()
        }
    }

    private fun playNext() {
        val snap = PlaylistStore.snapshot()
        if (snap.isEmpty()) return
        val next = if (currentIndex in snap.indices) (currentIndex + 1) % snap.size else 0
        playIndex(next)
    }

    private fun playPrevOrRestart() {
        if (transient) {
            if (state == STATE_PLAYING || state == STATE_PAUSED) {
                runCatching { player?.seekTo(0L) }
                pushState()
            }
            return
        }
        val snap = PlaylistStore.snapshot()
        if (snap.isEmpty()) return
        val pos = safePositionMs()
        if ((state == STATE_PLAYING || state == STATE_PAUSED) && pos > RESTART_THRESHOLD_MS) {
            runCatching { player?.seekTo(0L) }
            pushState()
            return
        }
        val prev = if (currentIndex in snap.indices) (currentIndex - 1 + snap.size) % snap.size else 0
        playIndex(prev)
    }

    private fun seekTo(ms: Long) {
        if (state != STATE_PLAYING && state != STATE_PAUSED) return
        runCatching { player?.seekTo(ms) }
        pushState()
    }

    private fun clearCurrent() {
        currentTrack = null
        currentIndex = -1
    }

    private fun stopEverything() {
        generation.incrementAndGet()
        pauseWhenReady = false
        pausedByFocus = false
        lastError = null
        transient = false
        freshUrlRetried = false
        state = STATE_STOPPED
        stopPlayback()
        stopTicker()
        abandonFocus()
        onStateChanged()
        if (notifActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifActive = false
        }
        stopSelf()
    }

    private fun onPlaylistUpdated() {
        if (transient) return
        val snap = PlaylistStore.snapshot()
        val track = currentTrack
        if (snap.isEmpty()) {
            clearCurrent()
            if (state != STATE_STOPPED) stopEverything()
            return
        }
        if (track == null) {
            clearCurrent()
            return
        }
        val slot = snap.indexOfFirst { it.id == track.id }
        if (slot >= 0) {
            currentIndex = slot
            PlaylistStore.setCurrent(slot)
            return
        }
        // A faixa atual saiu da fila.
        if (state == STATE_PLAYING || state == STATE_LOADING) {
            val c = PlaylistStore.current
            val nextSlot = if (c != null) snap.indexOfFirst { it.id == c.id } else -1
            if (nextSlot >= 0) {
                playIndex(nextSlot)
            } else {
                clearCurrent()
                stopEverything()
            }
        } else {
            clearCurrent()
            stopEverything()
        }
    }

    private fun failPlayback(message: String) {
        lastError = message
        state = STATE_ERROR
        stopPlayback()
        stopTicker()
        onStateChanged()
    }

    // ---------------------------------------------------------- foco/estado

    private fun ensureFocus(): Boolean {
        val am = audioManager ?: return false
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        }
        return am.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        val req = focusRequest ?: return
        runCatching { am.abandonAudioFocusRequest(req) }
    }

    /** Aplica (ou reaplica) de forma idempotente os efeitos do booster nativo. */
    private fun applyBoost() {
        if (player == null) return
        val target = boostLevel
        val sessionId = try {
            player?.audioSessionId ?: 0
        } catch (t: Exception) {
            0
        }
        if (target <= 0 || sessionId == 0) {
            releaseEffects()
            return
        }
        if (sessionId != effectsSession) releaseEffects()
        if (loudness == null) {
            runCatching { loudness = LoudnessEnhancer(sessionId).apply { enabled = true } }
        }
        if (bassBoost == null) {
            runCatching { bassBoost = BassBoost(0, sessionId).apply { enabled = true } }
        }
        if (preamp == null) {
            preamp = createPreamp(sessionId)
        }
        effectsSession = sessionId
        runCatching { loudness?.setTargetGain(LEVEL_GAIN_MB[target]) }
        runCatching { bassBoost?.setStrength(LEVEL_BASS_STRENGTH[target].toShort()) }
        runCatching { preamp?.setInputGainAllChannelsTo(LEVEL_PREAMP_DB[target]) }
    }

    /**
     * Pre-amp digital real (ganho de volume) + limitador de pico.
     * O LoudnessEnhancer sozinho quase nao aumenta o volume em musica ja alta;
     * aqui o ganho e aplicado de verdade e o limiter evita clipping.
     * Retorna null em aparelhos sem suporte (efeito existe desde a API 28).
     */
    private fun createPreamp(sessionId: Int): DynamicsProcessing? = runCatching {
        val limiter = DynamicsProcessing.Limiter(
            true, // in use
            true, // enabled
            0,    // link group
            1f,   // attack (ms)
            60f,  // release (ms)
            10f,  // ratio
            -1f,  // threshold (dB)
            0f    // post gain (dB)
        )
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,        // canais (estereo)
            false, 0, // sem pre-EQ
            false, 0, // sem compressor multibanda
            false, 0, // sem post-EQ
            true      // limitador ativo
        ).setLimiterAllChannelsTo(limiter).build()
        DynamicsProcessing(0, sessionId, cfg).apply { enabled = true }
    }.getOrNull()

    private fun releaseEffects() {
        runCatching { loudness?.release() }
        runCatching { bassBoost?.release() }
        runCatching { preamp?.release() }
        loudness = null
        bassBoost = null
        preamp = null
        effectsSession = 0
    }

    /** Para a reprodução atual mantendo o player vivo (reutilizado na próxima faixa). */
    private fun stopPlayback() {
        handler.removeCallbacks(prefetchRunnable)
        runCatching {
            player?.stop()
            player?.clearMediaItems()
        }
    }

    private fun releasePlayer() {
        releaseEffects()
        handler.removeCallbacks(prefetchRunnable)
        val exo = player
        player = null
        runCatching { exo?.release() }
    }

    private fun safePositionMs(): Long {
        val exo = player ?: return 0L
        return try {
            exo.currentPosition
        } catch (t: Exception) {
            0L
        }
    }

    private fun safeDurationMs(): Long {
        val exo = player
        if (exo != null) {
            try {
                val d = exo.duration
                if (d > 0 && d != C.TIME_UNSET) return d
            } catch (_: Exception) {
            }
        }
        return (currentTrack?.duration ?: 0L) * 1000L
    }

    fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        state = state,
        positionSec = safePositionMs() / 1000.0,
        durationSec = safeDurationMs() / 1000.0,
        index = currentIndex,
        id = currentTrack?.id,
        title = currentTrack?.title,
        uploader = currentTrack?.uploader,
        error = lastError,
    )

    private fun pushState() {
        listener?.onPlayback(snapshot())
    }

    private fun onStateChanged() {
        updateSession()
        updateNotification()
        pushState()
    }

    private fun updateSession() {
        val s = session ?: return
        val code = when (state) {
            STATE_LOADING -> PlaybackState.STATE_BUFFERING
            STATE_PLAYING -> PlaybackState.STATE_PLAYING
            STATE_PAUSED -> PlaybackState.STATE_PAUSED
            STATE_ERROR -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_STOPPED
        }
        val pb = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO
            )
            .setState(code, safePositionMs(), if (state == STATE_PLAYING) 1f else 0f)
            .build()
        s.setPlaybackState(pb)
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTrack?.title.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentTrack?.uploader.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, safeDurationMs())
            .build()
        s.setMetadata(md)
    }

    // ---------------------------------------------------------- notificacao

    private fun ensureForeground() {
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            notifActive = true
        } catch (t: Exception) {
            notifActive = false
        }
    }

    private fun updateNotification() {
        if (!notifActive) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val playing = state == STATE_PLAYING || state == STATE_LOADING
        val toggleIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val toggleLabel = if (playing) "Pausar" else "Reproduzir"

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(currentTrack?.title?.takeIf { it.isNotBlank() } ?: "NEXYOU")
            .setContentText(lastError ?: currentTrack?.uploader.orEmpty())
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .setContentIntent(mainPendingIntent())
            .addAction(notifAction(R.drawable.ic_prev, "Anterior", ACTION_PREV))
            .addAction(notifAction(toggleIcon, toggleLabel, ACTION_TOGGLE))
            .addAction(notifAction(R.drawable.ic_next, "Próxima", ACTION_NEXT))

        session?.let {
            builder.style = Notification.MediaStyle()
                .setMediaSession(it.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        }
        return builder.build()
    }

    private fun notifAction(iconRes: Int, title: String, action: String): Notification.Action {
        val intent = Intent(this, AudioService::class.java).setAction(action)
        val pi = PendingIntent.getForegroundService(
            this,
            REQ_SERVICE_BASE + action.hashCode() and 0xFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, iconRes), title, pi).build()
    }

    private fun mainPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            REQ_MAIN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---------------------------------------------------------- ticker

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    companion object {
        const val ACTION_PLAY_INDEX = "com.leo.nexyou.action.PLAY_INDEX"
        const val ACTION_PLAY_TRACK = "com.leo.nexyou.action.PLAY_TRACK"
        const val ACTION_TOGGLE = "com.leo.nexyou.action.TOGGLE"
        const val ACTION_NEXT = "com.leo.nexyou.action.NEXT"
        const val ACTION_PREV = "com.leo.nexyou.action.PREV"
        const val ACTION_SEEK = "com.leo.nexyou.action.SEEK"
        const val ACTION_STOP = "com.leo.nexyou.action.STOP"
        const val ACTION_PLAYLIST_UPDATED = "com.leo.nexyou.action.PLAYLIST_UPDATED"

        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_TRACK_URL = "track_url"
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_TRACK_UPLOADER = "track_uploader"
        const val EXTRA_TRACK_DURATION = "track_duration"
        const val EXTRA_TRACK_THUMB = "track_thumb"

        private const val CHANNEL_ID = "nexyou_playback"
        private const val NOTIF_ID = 1001
        private const val REQ_MAIN = 10
        private const val REQ_SERVICE_BASE = 100
        private const val TICK_MS = 500L
        private const val RESTART_THRESHOLD_MS = 4000L
        private const val DUCK_VOLUME = 0.3f
        private const val PREFETCH_DELAY_MS = 4_000L
        private const val RESOLVE_RETRY_DELAY_MS = 800L

        @Volatile
        private var instance: AudioService? = null

        fun isAlive(): Boolean = instance != null

        fun pushUi() {
            instance?.pushState()
        }

        @Volatile
        var listener: Listener? = null
            set(value) {
                field = value
                val inst = instance
                value?.onPlayback(inst?.snapshot() ?: PlaybackSnapshot.stopped())
            }

        private val LEVEL_GAIN_MB = intArrayOf(0, 200, 400, 800)
        private val LEVEL_BASS_STRENGTH = intArrayOf(0, 400, 700, 1000)
        private val LEVEL_PREAMP_DB = floatArrayOf(0f, 3f, 6f, 9f)

        @Volatile
        var boostLevel: Int = 0
            private set

        /** Ajusta o nivel do booster (0=off .. 3) e aplica no player ativo. */
        fun setBoostLevel(level: Int) {
            val v = level.coerceIn(0, 3)
            boostLevel = v
            val inst = instance ?: return
            inst.handler.post { inst.applyBoost() }
        }

        fun stateName(state: Int): String = when (state) {
            STATE_LOADING -> "loading"
            STATE_PLAYING -> "playing"
            STATE_PAUSED -> "paused"
            STATE_ERROR -> "error"
            else -> "stopped"
        }
    }
}
