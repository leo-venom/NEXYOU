package com.leo.nexyou

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.text.Charsets.UTF_8

/**
 * Downloader do NewPipeExtractor em cima de HttpURLConnection puro,
 * sem dependencia de OkHttp/AndroidX.
 *
 * Camada de protecao contra bloqueio/excesso de requisicoes do YouTube:
 * - fingerprint estavel de Chrome Android + cookies de consentimento;
 * - controle de frequencia global: espacamento minimo de 450 ms + jitter
 *   (1,35 s por 60 s apos 429) e teto deslizante de 72 requisicoes por minuto;
 * - limite global de extracoes simultaneas: 4 requests, sendo no maximo 3
 *   ocupadas por warm-up/pre-download — 1 vaga fica reservada para acoes do usuario;
 * - ate 3 tentativas com espera progressiva em 403 de bot, 429, 503 e ReCaptcha;
 * - deteccao de bloqueio de bot (429 e 403 com marcas de "unusual traffic");
 * - **cooldown global persistente**: ao esgotar as tentativas em bloqueio,
 *   TODAS as requisicoes param por 5/10/20/40/60 min (escalona se repetir em
 *   < 6 h) e o prazo e gravado em SharedPreferences — reabrir o app nao volta a
 *   martelar o YouTube. Ao vencer, as operacoes retomam com espacamento reduzido
 *   (3x o intervalo por 2 min), sem rajada;
 * - dedup de requisicoes identicas em andamento (mesmo metodo/URL/corpo):
 *   a segunda espera a primeira resposta em vez de repetir a chamada.
 *
 * Caminhos sensiveis a latencia (busca e toque do usuario) entram com
 * [bypassThrottle] apenas para nao esperar o espacamento; o cooldown, o limite
 * de concorrencia e o teto por minuto continuam valendo.
 */
class NxDownloader : Downloader() {

    override fun execute(request: Request): Response {
        if (isBlocked()) throw ReCaptchaException(blockedMessage(), request.url())

        // Dedup: requisicao identica ja em andamento -> espera a mesma resposta.
        val key = dedupKey(request)
        val mine = CompletableFuture<Response>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return awaitDuplicate(running)

        try {
            val response = executeProtected(request)
            mine.complete(response)
            return response
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /** Executa com limite de concorrencia, espera progressiva e cooldown. */
    private fun executeProtected(request: Request): Response {
        var networkFails = 0
        for (attempt in 1..MAX_ATTEMPTS) {
            if (isBlocked()) throw ReCaptchaException(blockedMessage(), request.url())
            waitTurn()

            val response = try {
                withSlot { executeOnce(request) }
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                networkFails++
                if (networkFails >= NETWORK_MAX_ATTEMPTS) throw e
                val wait = NETWORK_RETRY_MS * networkFails
                Log.w(TAG, "Falha de rede (${e.javaClass.simpleName}) — aguardando ${wait}ms")
                SystemClock.sleep(wait)
                continue
            }

            val code = response.responseCode()
            val botBlock = code == 429 || code == 503 || (code == 403 && looksLikeBotPage(response))
            if (botBlock) {
                last429At = System.currentTimeMillis()
                if (attempt == MAX_ATTEMPTS) {
                    startCooldown()
                    throw ReCaptchaException(blockedMessage(), request.url())
                }
                val wait = progressiveWaitMs(attempt, code)
                Log.w(TAG, "HTTP $code (bloqueio) tentativa $attempt/$MAX_ATTEMPTS — aguardando ${wait}ms")
                SystemClock.sleep(wait)
                continue
            }
            if (code == 403) {
                // 403 sem marcas de bot: restricao do proprio video; pausa curta e devolve ao extrator.
                if (attempt < MAX_ATTEMPTS) {
                    val wait = progressiveWaitMs(attempt, code)
                    Log.w(TAG, "HTTP 403 (sem marca de bot) tentativa $attempt/$MAX_ATTEMPTS — aguardando ${wait}ms")
                    SystemClock.sleep(wait)
                    continue
                }
                return response
            }
            return response
        }
        throw IOException("Falha ao acessar o YouTube")
    }

    /** Espera a resposta de uma requisicao identica ja em voo (sem repetir a chamada). */
    private fun awaitDuplicate(future: CompletableFuture<Response>): Response {
        return try {
            future.get(DEDUP_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IOException("Requisicao duplicada demorou demais.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Espera de requisicao duplicada interrompida.")
        }
    }

    /** Chave de dedup: metodo + URL + corpo (requests do NewPipe sao pequenas). */
    private fun dedupKey(request: Request): String {
        val body = request.dataToSend()
        return buildString(64) {
            append(request.httpMethod())
            append(' ')
            append(request.url())
            if (body != null && body.isNotEmpty()) {
                append(' ')
                append(String(body, UTF_8))
            }
        }
    }

    /** Limita extracoes simultaneas; acoes do usuario usam a vaga reservada. */
    private fun <T> withSlot(block: () -> T): T {
        if (bypass.get() == true) {
            acquire(slots)
            try {
                return block()
            } finally {
                slots.release()
            }
        }
        acquire(warmSlots)
        try {
            acquire(slots)
            try {
                return block()
            } finally {
                slots.release()
            }
        } finally {
            warmSlots.release()
        }
    }

    private fun acquire(semaphore: Semaphore) {
        try {
            semaphore.acquire()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Aguardando vaga de requisicao.")
        }
    }

    /** true quando a resposta 403 tem marcas de bloqueio de bot (nao de video restrito). */
    private fun looksLikeBotPage(response: Response): Boolean {
        val body = response.responseBody()
        if (body.isEmpty()) return false
        val lower = body.lowercase(Locale.ROOT)
        return BOT_MARKERS.any { lower.contains(it) }
    }

    /** Espera progressiva (1 s, 2 s, 4 s para 429/503; 1,5 s, 3 s, 6 s para 403). */
    private fun progressiveWaitMs(attempt: Int, code: Int): Long {
        val base = if (code == 403) 1_500L else 1_000L
        return (base shl (attempt - 1)) + (0L..400L).random()
    }

    /** Executa UMA request (sem retry/c spacing). */
    private fun executeOnce(request: Request): Response {
        val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
        }

        // Headers do NewPipe (Accept-Encoding e negociado manualmente abaixo).
        for ((name, values) in request.headers()) {
            if (name.equals("Accept-Encoding", ignoreCase = true)) continue
            conn.setRequestProperty(name, values.joinToString(", "))
        }
        // Fingerprint por ultimo: garante que nada do NewPipe sobrescreva.
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        conn.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        )
        conn.setRequestProperty("Cookie", COOKIES)

        val body = request.dataToSend()
        if (body != null && body.isNotEmpty()) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }

        val code = conn.responseCode
        val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.use { readBody(it, conn.contentEncoding) } ?: ""

        val headers = HashMap<String, List<String>>()
        for ((key, value) in conn.headerFields) {
            if (key != null && value != null) headers[key] = value
        }

        val message = conn.responseMessage ?: ""
        val latestUrl = conn.url.toString()
        conn.disconnect()
        return Response(code, message, headers, text, latestUrl)
    }

    /** Controle de frequencia global: teto por minuto + espacamento entre requests. */
    private fun waitTurn() {
        synchronized(throttleLock) {
            val userAction = bypass.get() == true
            enforceRateWindow()
            if (!userAction) {
                val now = System.currentTimeMillis()
                var interval = MIN_INTERVAL_MS + (0L..JITTER_MS).random()
                val recovering = (last429At > 0 && now - last429At < BACKOFF_WINDOW_MS) || now < graceUntilMs
                if (recovering) interval *= 3
                val elapsed = now - lastRequestEndMs
                if (elapsed < interval) SystemClock.sleep(interval - elapsed)
            }
            val stamp = System.currentTimeMillis()
            lastRequestEndMs = stamp
            requestTimes.addLast(stamp)
        }
    }

    /** Mantem no maximo [RATE_MAX_REQUESTS] requisicoes na janela deslizante de 60 s. */
    private fun enforceRateWindow() {
        while (true) {
            val now = System.currentTimeMillis()
            while (requestTimes.isNotEmpty() && now - requestTimes.first() >= RATE_WINDOW_MS) {
                requestTimes.removeFirst()
            }
            if (requestTimes.size < RATE_MAX_REQUESTS) return
            val wait = RATE_WINDOW_MS - (now - requestTimes.first()) + JITTER_MS
            if (wait <= 0) return
            Log.w(TAG, "Teto de $RATE_MAX_REQUESTS req/min atingido — aguardando ${wait}ms")
            SystemClock.sleep(wait)
        }
    }

    /** Dispara o cooldown global (escalonado) e persiste entre reinicios. */
    private fun startCooldown() {
        synchronized(blockLock) {
            val now = System.currentTimeMillis()
            if (now < blockedUntilMs) return
            if (now - lastBlockEndMs > BLOCK_RESET_AFTER_MS) blockLevel.set(0)
            val level = blockLevel.incrementAndGet()
            val duration = minOf(BLOCK_BASE_MS shl (level - 1), BLOCK_MAX_MS)
            blockedUntilMs = now + duration
            lastBlockEndMs = blockedUntilMs
            graceUntilMs = blockedUntilMs + POST_BLOCK_GRACE_MS
            persistBlock()
            Log.w(TAG, "cooldown anti-bot: ${duration / 60_000} min (nivel $level)")
        }
    }

    private fun persistBlock() {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong(KEY_UNTIL, blockedUntilMs)
                .putLong(KEY_LAST, lastBlockEndMs)
                .putInt(KEY_LEVEL, blockLevel.get())
                .apply()
        }
    }

    private fun readBody(raw: InputStream, contentEncoding: String?): String {
        val buffered = BufferedInputStream(raw)
        val decoded = if (contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }
        return decoded.use { it.readBytes().toString(UTF_8) }
    }

    companion object {
        private const val TAG = "NxDownloader"

        /** User-Agent estavel de Chrome Android — nunca variar entre chamadas. */
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** Cookies que marcam consentimento ja resolvido (reduz flag de bot). */
        private const val COOKIES = "SOCS=CAI; CONSENT=YES+1; GPS=1; YSC=1"

        /** Intervalo minimo entre requests consecutivas (ms) + jitter. */
        private const val MIN_INTERVAL_MS = 450L
        private const val JITTER_MS = 120L

        /** Janela em que o espacamento e triplicado apos um 429. */
        private const val BACKOFF_WINDOW_MS = 60_000L

        /** Teto deslizante de requisicoes por minuto (protecao contra rajadas). */
        private const val RATE_WINDOW_MS = 60_000L
        private const val RATE_MAX_REQUESTS = 72

        /** Limite global de extracoes simultaneas (1 vaga reservada ao usuario). */
        private const val MAX_CONCURRENT_REQUESTS = 4
        private const val MAX_WARM_REQUESTS = 3

        /** Tentativas por request em bloqueio/ReCaptcha. */
        private const val MAX_ATTEMPTS = 3

        /** Retry curto de falha de rede (timeout/queda), sem martelar. */
        private const val NETWORK_MAX_ATTEMPTS = 2
        private const val NETWORK_RETRY_MS = 1_000L

        /** Espera maxima por uma requisicao identica ja em andamento. */
        private const val DEDUP_WAIT_MS = 90_000L

        /** Marcas de bloqueio de bot no corpo de uma resposta 403. */
        private val BOT_MARKERS = listOf(
            "unusual traffic",
            "not a robot",
            "sorry/index",
            "confirm you're not a bot",
            "confirm you are not a bot",
            "consent.youtube.com",
        )

        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000

        /** Cooldown base e teto (escala 5, 10, 20, 40, 60 min). */
        private const val BLOCK_BASE_MS = 5 * 60_000L
        private const val BLOCK_MAX_MS = 60 * 60_000L

        /** Depois disso sem bloqueio, o nivel de escalonamento zera. */
        private const val BLOCK_RESET_AFTER_MS = 6 * 60 * 60_000L

        /** Retomada suave: espacamento 3x nos 2 min seguintes ao fim do cooldown. */
        private const val POST_BLOCK_GRACE_MS = 2 * 60_000L

        private const val PREF = "nx_antibot"
        private const val KEY_UNTIL = "blocked_until"
        private const val KEY_LAST = "last_block_end"
        private const val KEY_LEVEL = "block_level"

        private val throttleLock = Any()
        private val blockLock = Any()

        /** Vagas de extracao simultanea: globais e reservadas ao warm-up/pre-download. */
        private val slots = Semaphore(MAX_CONCURRENT_REQUESTS)
        private val warmSlots = Semaphore(MAX_WARM_REQUESTS)

        /** Requisicoes identicas em andamento — dedup por metodo + URL + corpo. */
        private val inFlight = ConcurrentHashMap<String, CompletableFuture<Response>>()

        /** Timestamps das requisicoes recentes (janela deslizante de 60 s). */
        private val requestTimes = ArrayDeque<Long>()

        @Volatile
        private var lastRequestEndMs = 0L

        @Volatile
        private var last429At = 0L

        @Volatile
        private var blockedUntilMs = 0L

        @Volatile
        private var lastBlockEndMs = 0L

        /** Fim do periodo de retomada suave apos um cooldown. */
        @Volatile
        private var graceUntilMs = 0L

        private val blockLevel = AtomicInteger(0)

        private val bypass = ThreadLocal<Boolean>()

        @Volatile
        private var appContext: Context? = null

        /** Carrega o cooldown persistido (chamado por NxService.attach). */
        @Synchronized
        fun attach(context: Context) {
            if (appContext != null) return
            val ctx = context.applicationContext
            appContext = ctx
            runCatching {
                val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                blockedUntilMs = sp.getLong(KEY_UNTIL, 0L)
                lastBlockEndMs = sp.getLong(KEY_LAST, 0L)
                blockLevel.set(sp.getInt(KEY_LEVEL, 0))
                if (blockedUntilMs > System.currentTimeMillis()) {
                    graceUntilMs = blockedUntilMs + POST_BLOCK_GRACE_MS
                }
            }
        }

        fun isBlocked(): Boolean = System.currentTimeMillis() < blockedUntilMs

        fun blockedRemainingMs(): Long =
            (blockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

        /** Mensagem pronta para a UI (minutos arredondados para cima). */
        fun blockedMessage(): String {
            val minutes = ((blockedRemainingMs() + 59_999L) / 60_000L).coerceAtLeast(1L)
            return "YouTube bloqueou temporariamente. Aguarde ~$minutes min."
        }

        /** Desativa o espacamento nesta thread (acoes do usuario). */
        fun <T> bypassThrottle(block: () -> T): T {
            bypass.set(true)
            try {
                return block()
            } finally {
                bypass.remove()
            }
        }
    }
}
