# NEXYOU — Mapa da UI

Arquivo único: `android/app/src/main/assets/app.html` (664 linhas, v34).
Linhas de referência em out/2026; ao editar, confirme pelos `id`s.

## Estrutura da página

| Bloco | Linhas | Elementos |
|---|---|---|
| `<head>` + CSS | 1–176 | tema `--nx-*`/`--glass-*`, fundo, grades, cards, popup, LED, rodapé, toasts |
| Header | 179–182 | `.brand` (180) e `#dot` (181) — sem texto de status e sem ondas |
| Player (`section.pcard`) | 184–203 | `#pThumbWrap`, `#pTitle`, `#pSub`, `#seek` (194), `#btnPrev` (198), `#btnToggle` (199), `#btnNext` (200) |
| Busca | 205–213 | `#q` (207), `#btnClearQ` (208), `#btnSearch` (210), `#results` (212) |
| Fila | 215–217 | `#playlist` (216) — sem título/botão Limpar desde a v26 |
| Rodapé | 219 | `<footer class="foot">OPENCODE BY LEO</footer>` (`.foot`, :170) |
| Popup | 221–234 | `#pop`, `#popClose` (223), `#popThumb`, `#popTitle`, `#popSub`, `#popActs` |
| Toasts | 235 | `#toasts` |
| Script | 237–662 | toda a lógica JS (IIFE, sem framework) |

## Fundo

- `html{...}` (31): cor de fallback `--nx-bg`.
- `body{...}` (33–39): `background: transparent` (o fundo não fica no body para
  não repintar durante o scroll).
- `body::before` (41): camada **fixa e composta** (`position:fixed; inset:0;
  z-index:-1`) com `linear-gradient(180deg, rgba(0,0,0,.90), rgba(0,0,0,.90))`
  + imagem **embutida em base64** (JPEG 736x1308), `cover`/`center`/`no-repeat`.
- Por que assim: `body::before` com `z-index:-1` fica escondido se o `body`
  tiver fundo opaco (bug da v19) — com `body` transparente, aparece. E, sendo
  camada fixa própria, não é repintada a cada frame do scroll (v32).
- Fonte original em `docs/assets/pjk.jpg`.

## Cabeçalho e LED

- Sem efeito de ondas/raios (v31); o header tem só marca e LED.
- `#dot` (181): **LED realista de 14 px** (`.dot`, :58) — corpo por `--led`,
  brilho interno, anel de 1 px, reflexo especular (`.dot::after`) e halo
  (`--glow`). Cores: `playing` verde, `loading` âmbar pulsando, `paused` âmbar,
  `error` vermelho; `aria-label` em `renderPlayer` (:376).

## Grade de cards (resultados e fila)

- `.list` (126): `display:grid; grid-template-columns:repeat(3,minmax(0,1fr))`
  — novos itens entram automaticamente. `.empty` ocupa as 3 colunas.
- Card = **a própria foto**: `.card`/`.prow` (135) sem padding, com a capa
  quadrada `.th` (129) preenchendo o card.
- **Sobre a foto**: título `.tt` (148, 11 px, 1 linha) e artista `.ss` (149,
  9,5 px) em `.info` (147) absoluto no rodapé, com gradiente; relógio `.dur`
  (130) no canto superior direito (via `makeCover`, :331); número do item no
  canto superior esquerdo.
- **Performance**: cards com `content-visibility:auto; contain-intrinsic-size:
  auto 150px` e sem `backdrop-filter` desde a v32. Ver `docs/FPS.md`.
- Sem botões e sem barra de progresso desde a v28 (ações no popup).

## Popup do card

- Abre ao tocar no card (`playlistRow` :505 / `resultCard` :518).
- `openPopup` (:449) monta título/uploader/duração e os botões via `popBtn` (:432):
  resultado → Tocar / Adicionar à fila; fila → Tocar agora / **Subir (vai para o
  topo)** / **Descer (vai para o fim)** / Remover.
- `closePopup` (:500) fecha pelo X (`#popClose`) ou toque no fundo (`.pop`).
- Layout: `.pop` (152, véu `rgba(0,0,0,.72)` sem blur), `.pop-card` (154),
  `.pop-acts` (163); `.pop-btn.wide` ocupa a linha inteira.

## Controles do player

| Elemento | Linha | Ação JS | Ponte |
|---|---|---|---|
| `#btnPrev` | 198 | `api('prev')` (em `initControls`, :580) | `NexusAndroid.prev()` → `ACTION_PREV` |
| `#btnToggle` | 199 | `api('toggle')` | `NexusAndroid.toggle()` → `ACTION_TOGGLE` |
| `#btnNext` | 200 | `api('next')` | `NexusAndroid.next()` → `ACTION_NEXT` |
| `#seek` | 194 | `input`/`change` → `api('seekTo', s)` | `NexusAndroid.seekTo(segundos)` → `ACTION_SEEK` |

## Busca

- `#q` (207): Enter ou `#btnSearch` (210) chamam `doSearch` (:571) →
  `api('search', q, reqSeq)`.
- `#btnClearQ` (208): limpa o campo, incrementa `reqSeq` e chama
  `clearResults()` (sem texto).
- `#results` (212): `renderResults` (:542) com `resultCard` (:518).

## Fila

- `#playlist` (216): `renderPlaylist` (:402) → `playlistRow` (:505).
- Cada card: selo do número, capa com relógio, título e artista sobre a foto.
- Ações somente no popup: tocar agora, subir (topo), descer (fim), remover.
- Novas músicas entram no **topo** (v33).

## Rodapé

- `<footer class="foot">` (219): "OPENCODE BY LEO", 9 px, caixa alta,
  `letter-spacing` 1,4 px, cor discreta; não interfere no layout.

## Eventos vindos do Kotlin

`window.__nexus.handle(nome, json)` (:622):

| Evento | Campos | Efeito |
|---|---|---|
| `player` | `state, pos, dur, index, id, title, uploader, fmt, error` | atualiza `P` e `renderPlayer` (:376) |
| `playlist` | `current, items[]` | atualiza `L`, `renderPlaylist` e chama `api('warmQueue')` |
| `search` | `reqId, ok, items[] \| error` | ignora resposta velha; renderiza ou mostra erro |

O subtítulo `#pSub` mostra `uploader · fmt` (ex.: `Fulano · m4a 129k`).

## Tema e booster

- Toque no `.brand` (180) → `cycleBoost` (:281): 0–3, aplica `lv1/lv2/lv3`.
- Níveis: Booster I/II/III (+200/+400/+800 mB; preamp +3/+6/+9 dB).

## Estados no cabeçalho

`STATE_LABEL` (:253): `stopped` → "parado", `loading` → "carregando…",
`playing` → "reproduzindo", `paused` → "pausado", `error` → "erro".
O texto não é exibido; vai só no `aria-label` do `#dot`.
