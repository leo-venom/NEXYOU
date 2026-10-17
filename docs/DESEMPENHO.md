# NEXYOU — Desempenho: por que toca na hora

Documento da investigação de out/2026 que comparou o NEXYOU ao **NexusMusic 2**
(`APK/NexusMusic2`) e resultou nas mudanças das versões 13 e 15.

## Sintoma

Mesmo com o warm-up em bandas paralelas, o áudio demorava para começar e a fila
demorava a "encher".

## O que o NexusMusic 2 faz (referência de velocidade)

| Aspecto | NexusMusic 2 | NEXYOU até a v14 |
|---|---|---|
| Motor de reprodução | elemento `<audio>` HTML5 dentro da WebView (`audio.src = URL`) | `android.media.MediaPlayer` |
| Buffer inicial | mínimo do navegador (~0,3–0,5 s) | interno do MediaPlayer (~1–2,5 s, não ajustável) |
| Download em background da fila | **nenhum** — só resolve URLs (cache de 300 s) | baixava até 24 faixas (~96 MB) em 4 bandas |
| Resolução de URL | NewPipeExtractor (`fetchPage`) | NewPipeExtractor (`StreamInfo.getInfo`) — equivalente |

Conclusão: a diferença não estava na resolução (mesma biblioteca), e sim em
**(1) motor de áudio** e **(2) tráfego de download competindo com o play**.

## Correções aplicadas

### v13 — warm-up mais leve
- Busca passou a resolver **somente URLs** dos primeiros 12 resultados
  (antes baixava 10 áudios completos, ~40 MB por busca).
- Fila aquecida primeiro resolvendo URLs e só depois baixando (duas fases).
- Debounce de 250 ms no warm-up da fila — rajadas de add/mover/remover não
  cancelam mais o lote em andamento.
- Timeouts do extrator: 30/30 s → 10/15 s (banda travada não prende a fila).
- Rótulo `m4a 129k` no subtítulo do player (verificação do formato real).

### v15 — motor e fila definitivos
- **MediaPlayer → media3/ExoPlayer 1.3.1** com
  `DefaultLoadControl(1500, 15000, 250, 500)`:
  começa a tocar com **~250 ms** de áudio (~4 s de buffer máximo por faixa).
  Player persistente (reutilizado; sem custo de criação por toque).
- **Fila não baixa mais áudio em massa** — apenas resolve URLs (4 bandas,
  gap de 120 ms). A barra sólida passou a significar "URL pronta" (toca na hora),
  não "arquivo completo em disco".
- **Apenas a próxima faixa** é pré-baixada para disco, com **4 conexões HTTP
  Range em paralelo**, iniciada **4 s depois** de o play estabilizar — troca de
  faixa sem espera e sem atrapalhar o início.
- `urlState` reflete resolução em andamento (`loading`) e URL pronta (`ready`).

## Efeito esperado

| Etapa | Antes | Depois |
|---|---|---|
| Toque em resultado já aquecido | ~1–2,5 s (buffer do MediaPlayer) + disputa com downloads | ~0,3–0,6 s (buffer de 250 ms, sem downloads competindo) |
| Toque em item da fila | resolução na hora (1–3 s) + buffer | URL já resolvida → direto para o player |
| Tráfego após cada busca | ~40 MB | 0 (só resolução) |
| Tráfego após montar fila | ~96 MB em 4 conexões durante o play | 0 durante o play; ~4 MB da próxima faixa, 4 s depois |
| Transição para a próxima faixa | baixava na hora (lento) | arquivo pronto em disco (instantâneo) |

Resultado no aparelho (Leo, out/2026): **carregamento instantâneo** — confirmado
e registrado neste documento.

## Como verificar no aparelho

1. Toque num resultado: o som deve começar em frações de segundo.
2. Suba uma fila e toque em qualquer item: deve começar imediatamente.
3. Observe o subtítulo do player: deve mostrar **`m4a 129k`**
   (itag 140 = AAC 128 kbps; o YouTube reporta ~129 kbps de média).
   Se mostrar `opus`, o vídeo não tinha m4a disponível.
4. A fila não exibe mais barra (removida na v28); o estado `ready` continua
   disponível na ponte (`urlState`).

## Estabilidade: "o extrator não conseguiu processar" e anti-bot (v18)

Sintoma relatado: muitos erros do extrator após a v15. Causa: a v15 disparava
até **3 (busca) + 4 (fila) resoluções simultâneas** em rajada; o YouTube
responde player vazio/"comportamento robótico" e o NewPipeExtractor lança
`ExtractionException` (a mensagem exibida).

O NexusMusic 2 já se protegia disso; o NEXYOU adotou o mesmo esquema:

| Medida | NexusMusic 2 | NEXYOU v18 |
|---|---|---|
| User-Agent estável de Chrome Android | sim | sim |
| Cookies de consentimento (`SOCS`, `CONSENT`, `GPS`, `YSC`) | sim | sim |
| Espaçamento mínimo entre requests | 450 ms (1,35 s por 60 s após 429) | igual |
| Retry com backoff em 429/503/ReCaptcha | 3 tentativas (1 s/2 s; 1,5 s/3 s) | igual |
| Bypass para ações do usuário | `semEspera` (ThreadLocal) | `bypassThrottle` (busca e toque) |
| Pausa dos warm-ups após falhas | não | 45 s após 3 falhas seguidas |
| Cooldown global em bloqueio (429) | — | 5/10/20/40/60 min, **persistido** entre reinícios |
| Retry transitório da resolução | — | 2 tentativas (500 ms + jitter) |

Além disso, a concorrência caiu: busca 3→2 bandas (gap 200→350 ms, 12→8 itens)
e fila 4→2 bandas (gap 120→300 ms, 24→16 itens). O warm-up fica um pouco mais
lento, mas estável — o toque continua instantâneo porque a URL vem do cache ou
de uma resolução sob demanda (que não espera o espaçamento).

No **cooldown** (v21): ao esgotar as tentativas em 429, o app bloqueia todas as
requisições por 5 min; se repetir em menos de 6 h, escala para 10/20/40/60 min.
O prazo é gravado em `SharedPreferences` (`nx_antibot`) — reabrir o app durante o
bloqueio **não** volta a requisitar; a UI mostra "Aguarde ~N min" (toast de 6 s)
e os warm-ups e pré-downloads ficam parados até vencer.

Trade-off: não existe como "furar" bloqueio de IP no cliente; o que dá para
fazer é não provocá-lo (fingerprint + ritmo + backoff + cooldown), que é o que
foi feito. Se o IP já estiver bloqueado, o app avisa para aguardar e tenta de
novo só depois do prazo.

## Ajustes (knobs)

Em `NxService.kt`:
- `QUEUE_BANDS = 2` — bandas que resolvem a fila.
- `QUEUE_RESOLVE_GAP_MS = 300` — folga entre resoluções por banda.
- `QUEUE_WARMUP_LIMIT = 16` — itens aquecidos da fila.
- `WARMUP_BANDS = 2`, `WARMUP_GAP_MS = 350`, `WARMUP_LIMIT = 8` — busca.
- `WARM_FAIL_PAUSE_AFTER = 3`, `WARM_PAUSE_MS = 45 000` — pausa dos warm-ups.
- `RESOLVE_ATTEMPTS = 2`, `RESOLVE_RETRY_MS = 500` — retry da resolução.
- `RANGE_PARTS = 4` — conexões do pré-download da próxima faixa.
- `AUDIO_MAX_FILES = 30`, `AUDIO_MAX_BYTES = 300 MB` — cache em disco.

Em `NxDownloader.kt`:
- `MIN_INTERVAL_MS = 450`, `BACKOFF_WINDOW_MS = 60 000` — espaçamento anti-bot.
- `MAX_ATTEMPTS = 3` — tentativas em 429/503/ReCaptcha.
- `CONNECT_TIMEOUT_MS = 12 000`, `READ_TIMEOUT_MS = 20 000`.

Em `AudioService.kt`:
- `setBufferDurationsMs(min, max, paraTocar, apósRebufferar)` — buffer do player.
- `PREFETCH_DELAY_MS = 4000` — atraso do pré-download após o play estabilizar.

Ao mexer: aumentar bandas demais eleva o risco de throttling/429 do YouTube
(a prática segura é ~5–6 requisições simultâneas no total).

## Limitações

- O primeiro toque de um vídeo **nunca resolvido** ainda depende de um
  `fetchPage` (~0,5–1,5 s de rede) — o warm-up cobre busca (12 primeiros) e
  fila (24 a partir da atual).
- URLs do YouTube expiram (~30 min de TTL de cache); o player retenta uma vez
  com resolução fresca.
- O APK cresceu ~0,5 MB com o media3 (1,64 MB no total, após R8).
- Sem adb nesta máquina: validação por build, inspeção estática e teste manual.
