# NEXYOU — Changelog

Registro a partir da v10 (out/2026). Formato: versão de entrega, código
(`versionCode`/`versionName`) e mudanças.

## v34 — `versionCode 34` / `1.0.33`

- Rodapé adicionado: **"OPENCODE BY LEO"** em 9 px, caixa alta, `letter-spacing`
  1,4 px, cor discreta (classe `.foot`).

APK: `app-v34-1.0.33-20261017-103529.apk`
SHA-256: `438475b85e85744548a1874231552de3cdc9429c9ff89621314e4a3f3bae5847`

## v33 — `versionCode 33` / `1.0.32`

**Ordem da fila.**

- Novas músicas adicionadas entram no **topo** (posição 1); o índice da faixa
  atual é ajustado (+1) para continuar apontando para a mesma música.
- Popup: **Subir** agora leva o item para o **topo**; **Descer** leva para a
  **última posição** da fila.
- Reordenação continua via `PlaylistStore.move`, com persistência e evento para
  o player.

APK: `app-v33-1.0.32-20261017-103225.apk`
SHA-256: `7f89d7aaf491351e7747ec1b50c5ef7f40db694bcaacdc299949db11167dca32`

## v32 — `versionCode 32` / `1.0.31`

**Fluidez: 14 → 60 FPS (paridade com o NEXUS77).**

- Removido **todo** o `backdrop-filter` (player, busca, 36 cards, botões, popup)
  — era o principal gargalo; fundos ficaram mais opacos.
- Fundo virou camada fixa composta (`body::before`, `body` transparente) e não
  repinta ao rolar.
- Cards com `content-visibility:auto` + `contain-intrinsic-size` (itens fora da
  tela não renderizam).
- Sombras reduzidas; capas com `loading="lazy"` e `decoding="async"`.
- Medição (Chromium 412x915, 24 fila + 12 resultados, scroll 4 s):
  antes 14,1 FPS / p95 116,7 ms / 53 frames longos; depois **60,0 FPS** /
  p95 16,7 ms / **0 frames longos**; NEXUS77: 60,1 FPS.
- Detalhes e método em `docs/FPS.md`.

APK: `app-v32-1.0.31-20261017-102404.apk`
SHA-256: `dd720fcea2e17005ef5da77feb1f60ebfe41abad34ec4e87ee1314c2cdbe5558`

## v31 — `versionCode 31` / `1.0.30`

- **Removido o efeito de ondas** do cabeçalho (SVG `.rays`, brilhos e keyframes
  `dashFlow`/`neonPulse`/`focusPulse`), junto com o halo do LED.
- **LED maior e realista**: 14 px, corpo colorido por estado, brilho interno
  (`inset` claro/escuro), anel de 1 px, reflexo especular (`.dot::after`) e halo
  externo suave; `playing` verde, `loading` âmbar pulsando, `error` vermelho.

APK: `app-v31-1.0.30-20261017-101653.apk`
SHA-256: `c37904b835efe0e9a0b42419d4fca48a46e550a903977fb67bf101235f3091a8`

## v30 — `versionCode 30` / `1.0.29`

- Título e artista agora **sobre a própria foto** (overlay com gradiente no
  rodapé do card), não mais abaixo dela.
- Relógio (duração) movido para o **canto superior direito da foto**; o número
  do item segue no canto superior esquerdo.
- Card = imagem quadrada, sem padding; cantos arredondados pelo próprio card.
- Sem botões/barra (mantido da v28).

APK: `app-v30-1.0.29-20261017-101408.apk`
SHA-256: `bc0ab60de36f59863a7f9b2e120442c4ec24c5cf72b00280e9a8d20fe2e72b78`

## v29 — `versionCode 29` / `1.0.28`

- **Relógio dentro do card**: chip com ícone de relógio + duração no canto
  inferior direito da capa (`makeCover`/`.dur`).
- Título e subtítulo (artista) dentro do card; a duração saiu do subtítulo do
  card e continua no popup.
- Fonte mantida compacta (11 px / 9,5 px); cards seguem retangulares e sem
  botões/barra.

APK: `app-v29-1.0.28-20261017-100856.apk`
SHA-256: `48d41fd2ac57e1c135c8877f0e72dc688dbd7b2ac3621b5d7ad9ceaee9809241`

## v28 — `versionCode 28` / `1.0.27`

**Cards limpos e retangulares.**

- Removidos os botões dos cards (as ações já estão no popup do card).
- Removida a barra de 3 px e todo o polling (`applyBar`/`pollBars`/`barStates`/
  `miniBtn`); a ponte `urlState` continua existindo, sem uso na UI.
- Informações resumidas: título em 1 linha (11 px) e artista/duração em 9,5 px;
  cards uniformes (capa quadrada + 2 linhas), sem espaço para botões.
- Número do item permanece como selo sobre a capa; item atual com destaque.

APK: `app-v28-1.0.27-20261017-100431.apk`
SHA-256: `41de264ba6860d3ab91ec37c98a61ecf9c7b9169d7a3e349572bff9d8fb387f1`

## v27 — `versionCode 27` / `1.0.26`

**Grade de 3 colunas + popup do card.**

- Resultados e fila agora em **grade de 3 colunas** (`repeat(3,minmax(0,1fr))`),
  com fluxo automático: novos itens entram no grid.
- Cards retangulares com capa quadrada, título em até 2 linhas, uploader/duração
  e **todos os botões dentro do card** (▶/+ nos resultados; ↑/↓/✕ na fila).
- Número do item vira selo sobre a capa; barra de 3 px mantida no topo do card.
- **Popup (bottom sheet)** ao tocar no card: tocar, adicionar à fila (resultado)
  ou tocar agora, subir, descer, remover (fila); fecha no X ou no fundo.
- Ajustado para a v26 (sem título "Fila"/botão "Limpar").

APK: `app-v27-1.0.26-20261017-095911.apk`
SHA-256: `1ed41ff82046726119f96d141a405f555490f35e66cebdb1c78d12389c925b4c`

## v26 — `versionCode 26` / `1.0.25`

**Cabeçalho neon e limpeza da fila.**

- Cabeçalho: **5 linhas de 1px** neon ondulatórias que convergem no LED
  (`vector-effect:non-scaling-stroke`, gradiente do acento + brilho duplo).
  Removido o efeito experimental de chamas.
- Fila: removidos o título "Fila" (e a contagem de itens) e o botão "Limpar"
  com sua confirmação em dois toques.
- Mantida a aparência de vidro da v24.

APK: `app-v26-1.0.25-20261017-094702.apk`
SHA-256: `b364931822dffd4627d6433ee72b4c5d8db5e0b5643a1c4acee34e4b246d7391`

## v24 — `versionCode 24` / `1.0.23`

**Aparência de vidro (glassmorphism) na UI.**

- Player (`.pcard`), campo de busca (`#q`), cards de resultado (`.card`) e linhas
  da fila (`.prow`): fundo translúcido com brilho diagonal, `backdrop-filter`
  (blur + saturação), borda clara e realce superior.
- Controles do player, botões das listas e "Limpar" ganharam vidro sutil.
- Item atual da fila mantém o gradiente do acento em versão translúcida.
- Somente CSS em `assets/app.html` — nenhuma mudança de layout, HTML ou lógica.

APK: `app-v24-1.0.23-20261017-093758.apk`
SHA-256: `87c22d6105a6830772b717ad09c1585ea56f2a6172eacdf909adc05a260dcfa0`

## v23 — `versionCode 23` / `1.0.22`

**Camada de proteção anti-bloqueio do YouTube (somente `NxDownloader`).**

- Controle de frequência: espaçamento de 450 ms + jitter mantido e novo teto
  deslizante de **72 requisições/minuto**.
- Limite global de **4 extrações simultâneas**, com no máximo 3 ocupadas por
  warm-up/pré-download — 1 vaga fica reservada para busca e toque do usuário.
- Detecção de **403 de bot** (marcas de "unusual traffic"/"not a robot") além de
  429/503; 403 sem essas marcas é tratado como vídeo restrito (pausa curta, sem
  cooldown).
- Espera progressiva (1/2/4 s em 429/503 e 1,5/3/6 s em 403 de bot) e retry
  curto de falha de rede.
- Cooldown global persistente mantido (5→60 min), com retomada suave: espaçamento
  3x nos 2 min seguintes ao fim do bloqueio.
- Dedup de requisições idênticas em andamento (método + URL + corpo): a segunda
  espera a primeira resposta, sem repetir a chamada.
- Sem mudanças em UI, extração, seleção M4A 128 kbps, downloads ou dependências.

APK: `app-v23-1.0.22-20261017-093210.apk`
SHA-256: `8cab34bdd84528c66b823ced9abe23032dcb7b3424d0acf62908d41c302b8e27`

## v22 — `versionCode 22` / `1.0.21`

- Fundo: camada **preta a 90%** (`rgba(0,0,0,.90)`) sobre a imagem, deixando-a
  bem escura mas ainda perceptível. Validado em render headless (412x915).

APK: `app-v22-1.0.21-20261017-025309.apk`
SHA-256: `e43b8690679785f4649c28181d8ee1ccd5834003d7b7d3e2bcda11788e999f19`

## v21 — `versionCode 21` / `1.0.20`

**Reforço anti-bot para não repetir o bloqueio.**

- **Cooldown global persistente**: ao esgotar as tentativas em 429, todas as
  requisições param por 5 min; se repetir em menos de 6 h, escala para
  10/20/40/60 min. Gravado em `SharedPreferences`, sobrevive a reinício.
- `bypassThrottle` não fura o cooldown; warm-ups e pré-downloads também param.
- Jitter de até 120 ms no espaçamento entre requests.
- UI: mensagem "YouTube bloqueou temporariamente. Aguarde ~N min." com toast de
  6 s; sem retry automático enquanto bloqueado.

APK: `app-v21-1.0.20-20261017-025124.apk`
SHA-256: `b0f0f0871666f6dd52ef2b723c4269e3fcb93454ddc8f232d40a868d92a81648`

## v20 — `versionCode 20` / `1.0.19`

- **Correção do fundo**: `body::before` com `z-index:-1` era pintado atrás do
  fundo do `body` e a imagem não aparecia. A imagem passou para o próprio
  `body` (`background-image` + `cover` + `fixed`); `html` mantém só a cor de
  fallback.
- Validado por render headless (412x915): imagem visível atrás da UI.

APK: `app-v20-1.0.19-20261017-024430.apk`
SHA-256: `2aea2922950bdf601f292987ce3963dc375b9b58037f866c33de0f6ab384e723`

## v19 — `versionCode 19` / `1.0.18`

- Imagem `pjk.jpg` (736x1308) aplicada como fundo do app, embutida em base64
  no CSS (`body::before`, fixa, `cover`) com véu escuro para legibilidade.
  Não depende de arquivo externo nem do `baseURL`.

APK: `app-v19-1.0.18-20261017-024042.apk`
SHA-256: `f2d559f46d3a3e6cb66d76c410693f7ef58126525ac84b403f7cdcf086f38d5f`

## v18 — `versionCode 18` / `1.0.17`

**Estabilidade: erros do extrator e proteção anti-bot.**

- Causa dos erros: rajadas de resolução (3 bandas na busca + 4 na fila)
  faziam o YouTube devolver player vazio → `ExtractionException`.
- Concorrência reduzida: busca 2 bandas (8 itens, gap 350 ms) e fila 2 bandas
  (16 itens, gap 300 ms).
- `NxDownloader` com proteção no estilo NexusMusic 2: User-Agent estável de
  Chrome Android, cookies de consentimento, espaçamento de 450 ms (1,35 s por
  60 s após 429) e 3 tentativas com backoff exponencial.
- `bypassThrottle` para busca e toque do usuário (não esperam o espaçamento).
- Retry transitório: resolução tenta 2× (500 ms + jitter); toque tenta de novo
  após 800 ms antes de mostrar erro.
- Warm-ups pausam 45 s após 3 falhas seguidas.
- Mensagem de `ExtractionException` mais clara ("O YouTube recusou a requisição
  agora. Aguarde alguns segundos e tente de novo.").

APK: `app-v18-1.0.17-20261017-022947.apk`
SHA-256: `8e8dc4ce89a27a7f229eff65b94f4fc02b6824693c22b00bb041c3cb94a7231f`

## v17 — `versionCode 17` / `1.0.16`

- Efeito do cabeçalho trocado por **5 linhas ondulatórias vibrantes** (SVG)
  que atravessam do título até o LED, com fluxo animado (`dashFlow`),
  vibração defasada (`lineVibe`) e foco de luz pulsante no LED.

APK: `app-v17-1.0.16-20261017-022114.apk`
SHA-256: `428933fd3db8184d6a394f0948218a0ce0e02aab51d619149b7e20d71e723b7e`

## v16 — `versionCode 16` / `1.0.15`

**Cabeçalho.**

- Removido o texto ao lado do LED; o status agora fica só na bolinha à direita
  (estado também no `aria-label`).
- Novo efeito decorativo de ondas/raios suaves, na cor do tema, do título até a
  bolinha (CSS puro; respeita `prefers-reduced-motion`).

APK: `app-v16-1.0.15-20261017-021544.apk`
SHA-256: `6942a054605b032ffd5fd21d6ea742d7c345f4607761ecb57dda760b3a88afc3`

## v15 — `versionCode 15` / `1.0.14`

**Reprodução instantânea.**

- Player trocado de `MediaPlayer` para **media3/ExoPlayer 1.3.1**, persistente
  e com buffer de início de **250 ms** (`DefaultLoadControl`).
- Fila passou a **só resolver URLs** — removido o download em massa (24 faixas,
  ~96 MB) que disputava banda com a reprodução.
- `urlState`: `ready` agora inclui **URL já resolvida** (barra sólida = toca na
  hora); `loading` cobre resolução em andamento.
- Pré-download **somente da próxima faixa**, com **4 conexões Range** em
  paralelo, iniciado 4 s após o play estabilizar.
- Retentativa de URL expirada e tratamento de arquivo local corrompido mantidos.

APK: `app-v15-1.0.14-20261017-020647.apk`
SHA-256: `9833aaaf2829527fd69f5d09b135d0b8bd0f162b7cabd8fd43de845d5832d0cf`

## v14 — `versionCode 14` / `1.0.13`

- Removido o texto "Busque uma música ou cole um link do YouTube." do estado
  inicial e ao limpar a busca (a área de resultados fica vazia).

APK: `app-v14-1.0.13-20261017-015826.apk`
SHA-256: `a53917b83a442bbe7431b593285d782abaf6b259255bae0a9bce17b9ecfb46f6`

## v13 — `versionCode 13` / `1.0.12`

**Diagnóstico de lentidão e primeiras otimizações.**

- Warm-up da busca passou a resolver **somente URLs** (12 resultados, 3 bandas)
  — antes baixava 10 áudios (~40 MB) a cada busca.
- Warm-up da fila em duas fases (resolver tudo, depois baixar) e **debounce de
  250 ms** para não cancelar lotes em rajadas.
- Timeouts do extrator reduzidos para 10 s (conexão) / 15 s (leitura).
- Subtítulo do player exibe o formato real resolvido (ex.: `m4a 129k`).

APK: `app-v13-1.0.12-20261017-015546.apk`
SHA-256: `80f29402d1bb525cc2a10ed39d05fb66d69ede69cd2f9f9316fc78f3029336ef`

## v12 — `versionCode 12` / `1.0.11`

- Fila com **4 bandas** paralelas de resolução/download (`QUEUE_BANDS = 4`).

APK: `app-v12-1.0.11-20261017-014654.apk`
SHA-256: `a990f2a525aa3025abbf761ff62a15e9fdf6d2ac66156cb954d9763f96ef60b4`

## v11 — `versionCode 11` / `1.0.10`

- Removido o **botão de parar** (quarto botão do player). Player fica com
  Anterior / Reproduzir-Pausar / Próxima, centralizados.

APK: `app-v11-1.0.10-20261017-014302.apk`
SHA-256: `9740f6819f140cc06dd5c246b36dc27ba1b45f2fbcb4181a8ff58c5e2fed4451`

## v10 — `versionCode 10` / `1.0.9`

- Fila aquecida com **3 bandas** em round-robin a partir da faixa atual
  (até 24 itens), com gap de 200 ms e geração para descartar lotes antigos.

APK: `app-v10-1.0.9-20261017-013627.apk`

## Histórico anterior

As versões 1–9 (até `1.0.8`) são anteriores a este registro. O app já contava
com: busca NewPipe, seleção M4A 128 kbps, fila persistida, notificação com
MediaSession, booster de áudio em 3 níveis e cache de áudio em disco.
