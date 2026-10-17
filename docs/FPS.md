# NEXYOU — Fluidez e FPS

Comparação de fluidez do WebView do NEXYOU com o **NEXUS77** (`docs` do projeto
de referência), com medição antes/depois das otimizações da v32 (out/2026).

## Método

- Chromium 152 headless (`--headless=new`), janela 412x915 (celular).
- Renderização por software (SwiftShader), igual para todos os casos — mede o
  pior caso e serve para comparar.
- Página de teste: `app.html` real com **24 itens na fila** e **12 resultados**
  (grade de 3 colunas, altura ~2322 px).
- Script injetado: amostragem em `requestAnimationFrame` por 4 s enquanto a
  página rola para baixo e para cima (26 px por frame).
- Métricas: FPS médio (1000 / frame médio), p95 do frame e frames longos
  (> 33 ms). Quanto menos frames longos, mais fluido.

## Resultados

| Caso | FPS médio | Frame médio | p95 | Frames longos |
|---|---|---|---|---|
| NEXYOU antes (v31) | **14,1** | 71,05 ms | 116,7 ms | 53 de 57 |
| NEXYOU depois (v32) | **60,0** | 16,67 ms | 16,7 ms | **0 de 241** |
| NEXUS77 (página de referência, cubo animado) | 60,1 | 16,64 ms | 16,8 ms | 0 de 241 |

O NEXYOU passou de ~14 FPS (travado, com frames de até 117 ms) para **60 FPS
estáveis**, empatando com o NEXUS77.

## Causas da lentidão (v31)

1. **`backdrop-filter: blur(...)` em muitos elementos** — player, campo de busca,
   os **36 cards** da grade, botões redondos e o popup. Cada camada com blur
   força o compositor a reamostrar o fundo; com a página rolando e o fundo fixo,
   o custo explode. É o principal motivo dos frames de 70–117 ms.
2. **Sombras grandes por card** (`0 10px 26px`) multiplicadas por 36.
3. **Fundo preso ao `body`** com `background-attachment: fixed`: repintura
   constante durante o scroll.
4. Imagens sem `loading="lazy"`/`decoding="async"` (12 resultados + 24 capas).

O NEXUS77 não usa `backdrop-filter` na UI (só CSS simples + uma animação
`transform`), por isso mantém 60 FPS.

## Otimizações aplicadas (v32)

- **Removido 100% do `backdrop-filter`** (0 ocorrências): fundos mais opacos
  fazem o mesmo papel visual sem custo de reamostragem.
- **Fundo vira camada fixa composta** (`body::before`, `position:fixed`,
  `z-index:-1`, `body` transparente) — não repinta ao rolar. O `html` guarda a
  cor de fallback.
- **Cards com `content-visibility:auto` + `contain-intrinsic-size:auto 150px`**:
  itens fora da tela não são renderizados até aparecerem (grande ganho em filas
  longas).
- **Sombras reduzidas** (player, cards, popup, toasts) e sombra por card
  praticamente eliminada (só o realce interno de 1 px).
- **Imagens**: `loading="lazy"` nas capas e `decoding="async"` (capas e popup).
- Popup usa véu escuro sólido (`rgba(0,0,0,.72)`) em vez de blur de tela cheia.
- Nenhuma mudança de layout, conteúdo ou lógica — só custo de renderização.

## Como reproduzir

1. Gerar uma cópia do `app.html` com dados injetados (24 fila + 12 resultados):
   `window.__nexus.handle('playlist', ...)` e `('search', ...)` antes de `</body>`.
2. Abrir no Chromium com `--remote-debugging-port=9333` e `--window-size=412,915`.
3. Via CDP (`Runtime.evaluate`, `awaitPromise`), rodar um loop de 4 s com
   `requestAnimationFrame`, `scrollBy(0, ±26)` e calcular FPS/p95/frames longos.
4. Comparar NEXUS77 (`web/welcome.html`) no mesmo ambiente.

## Limitações

- Medição em software rendering: em celular com GPU o custo de blur é menor,
  mas o padrão (14 → 60 FPS) mostra a direção e a ordem de grandeza.
- A fluidez também depende de rede (thumbnails) e do aparelho; o teste isola o
  custo de composição da UI.
- Sem adb nesta máquina: a validação em device é manual.
