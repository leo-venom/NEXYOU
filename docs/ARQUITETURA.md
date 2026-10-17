# NEXYOU — Arquitetura

Referências de linha: código da **v15 (versionCode 15 / 1.0.14)**, out/2026.

## Visão geral

```
┌──────────────────────────┐        ponte JS (window.NexusAndroid)
│  WebView (assets/app.html)│ ◄──────────────────────────────────┐
│  UI + fila + cards        │                                    │
└────────────┬─────────────┘                                    │
             │ evaluateJavascript (eventos: player/playlist/search)
             ▼                                                  │
┌──────────────────────────┐      ┌───────────────────────────┐  │
│ MainActivity.kt           │      │ AudioService.kt           │  │
│ - cria WebView            │      │ - ExoPlayer (media3)      │  │
│ - NxBridge (@Javascript)  │─────►│ - notificação + MediaSession
│ - pushEvent/pushPlayer    │      │ - AudioFocus + booster    │  │
└────────────┬─────────────┘      └────────────┬──────────────┘  │
             │                                 │                 │
             ▼                                 ▼                 │
┌──────────────────────────┐      ┌───────────────────────────┐  │
│ PlaylistStore.kt          │      │ NxService.kt (object)     │  │
│ - fila persistida         │      │ - busca (NewPipe)         │  │
│   (SharedPreferences)     │      │ - resolução de URL (m4a)  │  │
└──────────────────────────┘      │ - cache de URL + áudio    │  │
                                  │ - warm-up (busca e fila)  │  │
                                  └────────────┬──────────────┘  │
                                               │                 │
                                  NxDownloader.kt (HTTP do NPE)  │
└────────────────────────────────────────────────────────────────┘
```

Conversão de eventos para a página: `MainActivity.pushEvent()` —
`MainActivity.kt:98`. Payloads são JSON; tempos sempre em segundos.

## Componentes

### MainActivity (`MainActivity.kt`)
- `onCreate` (`:33`): `PlaylistStore.init`, `NxService.attach`, `warmUp()` em
  thread própria, cria o `WebView` e carrega `assets/app.html` com
  `loadDataWithBaseURL("https://nexyou.local/", ...)`.
- `NxBridge` (`:203`): métodos expostos ao JS — `search`, `getState`,
  `setBoost`/`getBoost`, `urlState`, `warmQueue`, `addToPlaylist`,
  `playItem`, `playIndex`, `toggle`, `next`, `prev`, `seekTo`, `stopPlayback`,
  `removeFromPlaylist`, `moveInPlaylist`, `clearPlaylist`.
- `pushPlayer` (`:132`): envia `state/pos/dur/index/id/title/uploader/fmt`
  (o `fmt` é o rótulo `m4a 129k`, vindo de `NxService.streamLabelById`).

### AudioService (`AudioService.kt`)
Serviço em primeiro plano com `MediaSession` e notificação MediaStyle.

- **Player**: `media3/ExoPlayer` (`buildPlayer`, `:160`), reutilizado entre
  faixas (não recria por toque). `DefaultLoadControl` com
  `setBufferDurationsMs(1500, 15000, 250, 500)` — começa a tocar com
  **~250 ms** de áudio em buffer.
- `onPlayerReady` (`:194`): transição `loading → playing/paused`, aplica
  booster, inicia ticker e agenda o pré-download da próxima faixa
  (`PREFETCH_DELAY_MS = 4 s`).
- `startTrack` (`:335`): incrementa a geração, para o player, procura **arquivo
  local** (`NxService.audioFileFor`) — se existir, toca do disco; senão resolve
  a URL (cache) e transmite.
- `startPlayer` (`:404`): `MediaItem` local (`file://`) ou remoto (`https://`).
- `prefetchNext` (`:449`): pré-baixa **somente a próxima** faixa.
- `warmQueueAround` (`:468`): aquece a fila em ordem circular a partir da atual.
- `retryFreshUrl` (`:423`): URL expirada → resolve de novo e retenta uma vez.
- Booster (`applyBoost`, `:645`): `LoudnessEnhancer` + `BassBoost` +
  `DynamicsProcessing` (limiter) na sessão de áudio do ExoPlayer.

### NxService (`NxService.kt`)
`object` com thread-safety via `ConcurrentHashMap`/`AtomicLong`/`@Synchronized`.

- **Busca** `search` (`:179`): `SearchExtractor.fetchPage()`, até 30 itens,
  cache de 5 min (6 entradas).
- **Resolução** `resolveAudioUrl` (`:237`): cache TTL 30 min (48 entradas),
  dedup por `CompletableFuture` (`inFlight`), marca `resolveInFlight` para a UI.
  `resolveAudioUrlFresh` (`:282`) ignora/renova o cache. A resolução sem cache
  tenta **duas vezes** (500 ms + jitter) — falhas transitórias de player vazio
  se resolvem na segunda tentativa.
- **Seleção de stream** `resolveAudioUrlUncached` (`:736`): ordena por
  `formatRank` (M4A=0, Opus=1, resto=2) e distância de bitrate até 128 kbps —
  na prática **itag 140 (AAC ~129 kbps)**. Guarda `format`/`kbps` no cache.
- **Warm-up da busca** `warmUpSearchResults` (`:298`): resolve **só URLs** dos
  primeiros 8 resultados, 2 bandas, gap de 350 ms. Não baixa áudio.
- **Warm-up da fila** `warmUpQueue` (`:329`): debounce de 250 ms
  (`warmQueueLock`/`warmQueueTimer`), depois `warmUpQueueNow` (`:345`) divide em
  até 16 itens por 2 bandas (`queueBands`) e chama `runResolveChunk` (`:374`) —
  **somente resolução de URL**, sem download em massa.
- **Backoff dos warm-ups** (`noteWarmResult`, `:403`): após 3 falhas seguidas,
  os warm-ups ficam pausados por 45 s (`warmPauseUntil`), evitando insistir
  enquanto o YouTube está limitando.
- **Cache de áudio** (`filesDir/audio/<id>.bin`): `downloadAudio` (`:505`);
  `downloadTo` (`:541`) usa 4 conexões HTTP **Range** em paralelo
  (`copyRange`, `:606`; tamanho via `remoteSize`, `:635`) e cai para conexão
  única se o servidor não suportar Range. Limite de 30 arquivos / 300 MB, LRU.
- **Estado** `urlState` (`:432`): `ready` (arquivo completo **ou** URL já
  resolvida), `loading` (resolvendo/baixando), `queued` (agendado), `none`.
  A UI não desenha mais barra (v28); a ponte segue disponível.
- `streamLabelById` (`:802`): rótulo `m4a 129k` para o subtítulo do player.

### Outros
- `NxDownloader.kt`: `Downloader` do NewPipeExtractor sobre `HttpURLConnection`
  (12 s conexão / 20 s leitura) com **proteção anti-bot**: User-Agent estável de
  Chrome Android + cookies de consentimento; espaçamento global de 450 ms entre
  requests (1,35 s por 60 s após um 429); até 3 tentativas com backoff
  exponencial em 429/503/ReCaptcha. Busca e toque do usuário usam
  `bypassThrottle` (não esperam o espaçamento, mas respeitam o cooldown).
  **Cooldown global persistente**: ao esgotar as tentativas em 429, bloqueia
  todas as requisições por 5 min e escala (10/20/40/60 min) se repetir em menos
  de 6 h; grava em `SharedPreferences` (`nx_antibot`) e recarrega em
  `attach(context)`, então reabrir o app não volta a martelar.
- `PlaylistStore.kt`: fila em `SharedPreferences`, `Track` (id, url, título,
  uploader, duração, thumb).
- `assets/app.html`: UI completa (HTML/CSS/JS), sem framework.

## Fluxos

### Busca
1. JS `doSearch` → `NexusAndroid.search(q, reqId)`.
2. `MainActivity` executa `NxService.search` em `searchExecutor` (1 thread).
3. Resultado volta por evento `search` (descartado se `reqId` já mudou).
4. `warmUpSearchResults`: 12 URLs resolvidas em background (3 bandas).

### Toque em resultado (avulso)
1. JS `api('playItem', ...)` → `ACTION_PLAY_TRACK`.
2. `startTrack` (transiente, não mexe na fila).
3. Arquivo local? toca do disco. Senão `resolveAudioUrl` (normalmente já
   cacheada pelo warm-up) → ExoPlayer em streaming (~250 ms de buffer).

### Toque em item da fila
1. JS `api('playIndex', i)` → `ACTION_PLAY_INDEX`.
2. `startTrack` → `warmQueueAround(i)` reaquece a fila (debounce).
3. Toca do arquivo local, se existir; senão da URL (já resolvida).

### Transição entre faixas
- `prefetchNext` baixa a próxima para disco 4 s após o play estabilizar
  (4 conexões Range), preservando a banda no início da faixa atual.

## Invariantes e concorrência
- Cada lote de warm-up tem uma **geração** (`warmGeneration`/`queueGeneration`);
  lote novo cancela o anterior entre itens.
- Dedup de resolução por vídeo (`inFlight`); quem chega depois espera o mesmo
  resultado.
- `cacheExecutor` (1 thread) faz downloads pontuais e persistência do cache de
  URLs; as bandas só resolvem URLs.
- O player é criado no `onCreate` do serviço e liberado no `onDestroy`.
- Efeitos de áudio são reaplicados de forma idempotente na sessão atual.

## Tratamento de erro
- Falha de resolução no toque: retry silencioso após 800 ms (`retryResolve`,
  `:385`) antes de mostrar erro.
- Erro no ExoPlayer: arquivo local → `forget` + retry com URL fresca; URL de
  cache → uma retentativa; senão `STATE_ERROR` com mensagem PT-BR.
- 429/ReCaptcha → backoff no downloader e mensagem amigável (`friendlyError`).
- `ExtractionException` → mensagem "O YouTube recusou a requisição agora…".
- Timeouts: extrator 12/20 s; áudio 15/30 s.

## Testes
Sem testes automatizados. Validação por build + inspeção estática + teste
manual no aparelho (adb indisponível nesta máquina).
