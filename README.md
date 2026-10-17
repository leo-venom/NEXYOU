# NEXYOU

Player Android nativo que reproduz **apenas o áudio** (M4A 128 kbps) de vídeos do YouTube, com busca integrada, fila de reprodução e reprodução em segundo plano.

- **Kotlin nativo** — UI é uma WebView servindo `assets/app.html` (HTML/JS puro); único componente externo de mídia é o **media3/ExoPlayer**.
- **NewPipeExtractor v0.26.5** resolve busca e streams de áudio — seleção sempre **M4A 128 kbps** (itag 140); Opus apenas como último recurso (requer `INTERNET`).
- **Carregamento instantâneo**: a busca pré-resolve as URLs dos 8 primeiros resultados (2 bandas, 350 ms) e a fila resolve até 16 URLs a partir da faixa atual (2 bandas, 300 ms) — **sem baixar áudio em massa**, com proteção anti-bot. Toques simultâneos no mesmo vídeo compartilham a mesma resolução (dedup); cache de URLs de 48 itens (TTL 30 min). Na fila, cada item mostra a capa com o número como selo; o estado de carga fica só na lógica (`urlState`), sem barra na UI.
- **Reprodução instantânea**: URL já resolvida (streaming começa na hora) ou M4A completo em `filesDir/audio` (toca do disco). Apenas a **próxima faixa** é pré-baixada para disco, com 4 conexões Range em paralelo, 4 s após o play estabilizar. O cache de áudio sobrevive a reinícios (máx. 30 faixas / 300 MB, LRU por último uso); **Remover** no popup apaga o áudio salvo.
- **media3/ExoPlayer** com buffer de início de **~250 ms**; `AudioService` em primeiro plano (notificação MediaStyle) e AudioFocus.
- **Booster de áudio nativo** (`LoudnessEnhancer` + `BassBoost` + preamp `DynamicsProcessing` com limitador de pico) com 3 níveis — toque no título "NEXYOU" para alternar (o tema muda de cor em cada nível).
- Interface 100% em **PT-BR**, com imagem de fundo embutida (véu escuro para legibilidade; não depende de arquivo externo).
- **Anti-bot** no acesso ao YouTube (fingerprint estável, ritmo e backoff) para evitar bloqueio por "comportamento robótico".
- **Camada de proteção (v23)**: teto de 72 req/min, no máximo 4 extrações simultâneas (1 vaga reservada às ações do usuário), detecção de 403 de bot além de 429/503, espera progressiva, cooldown persistente e dedup de requisições idênticas.

## Identificação

| Item | Valor |
|---|---|
| Package | `com.leo.nexyou` |
| Versão | `versionCode 34` / `versionName 1.0.33` |
| minSdk | 26 (Android 8.0) |
| targetSdk / compileSdk | 34 (Android 14) |
| Java / Kotlin | 17 / 1.9.24 |
| AGP / Gradle | 8.5.0 / 8.8 |

## Estrutura

```
NEXYOU/
├── android/                 # Projeto Gradle/Kotlin
│   ├── app/
│   │   └── src/main/
│   │       ├── java/com/leo/nexyou/
│   │       │   ├── MainActivity.kt    # WebView + ponte JS (NexusAndroid)
│   │       │   ├── AudioService.kt    # player (ExoPlayer), notificação, AudioFocus
│   │       │   ├── NxService.kt       # busca, resolução de URLs, fila e cache de áudio
│   │       │   ├── NxDownloader.kt    # HTTP para o NewPipeExtractor
│   │       │   └── PlaylistStore.kt   # fila persistida (SharedPreferences)
│   │       ├── assets/app.html        # UI completa (HTML/CSS/JS)
│   │       └── res/                   # strings, cores, tema, ícones
│   ├── keystore.properties  # aponta para keystore/nexyou-release.jks
│   └── local.properties     # sdk.dir
├── docs/                    # ARQUITETURA, DESEMPENHO, MAPA_UI, CHANGELOG, ASSINATURA
└── README.md
```

## Documentação

- [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md) — componentes, fluxos, concorrência e tratamento de erro.
- [`docs/DESEMPENHO.md`](docs/DESEMPENHO.md) — análise de velocidade (comparação com o NexusMusic 2) e ajustes.
- [`docs/FPS.md`](docs/FPS.md) — fluidez/FPS do WebView, método de medição e comparação com o NEXUS77 (14 → 60 FPS).
- [`docs/MAPA_UI.md`](docs/MAPA_UI.md) — elementos da UI, handlers e ponte JS.
- [`docs/CHANGELOG.md`](docs/CHANGELOG.md) — histórico de versões.
- [`docs/ASSINATURA.md`](docs/ASSINATURA.md) — assinatura v2/v3 e regra de entrega.

## Build

Requisitos: JDK 17, Android SDK em `~/Android/Sdk`, Gradle wrapper incluso.

```bash
cd android

# Iteração rápida (sem R8)
./gradlew :app:assembleDebug     # ou ./gradlew-fast

# Entrega (R8 + shrink + assinatura v2/v3)
./gradlew :app:assembleRelease
```

Saída release: `app/build/outputs/apk/release/app-release.apk`

> Regra da máquina: **nunca** usar `--no-daemon` nem `clean` por hábito. Daemon + `workers.max=1` já configurados em `gradle.properties`.

### Assinatura

`keystore.properties` define `storeFile=keystore/nexyou-release.jks`. O release usa **v1 desabilitado, v2 + v3 habilitados** (fallback: debug keystore, caso o release keystore esteja ausente).

Verificação:

```bash
~/Android/Sdk/build-tools/35.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

## Instalação

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Ou copie o APK para o dispositivo e instale manualmente (fontes desconhecidas habilitadas).

## Uso

1. Abra o app e digite a busca (música/vídeo) — os resultados aparecem em **grade de 3 colunas**; cada card tem capa com **título, artista e relógio (duração) sobre a própria foto**; tocar no card abre o popup com Tocar / Adicionar à fila (novas músicas entram no **topo** da fila).
2. Toque em ▶ para tocar o áudio em segundo plano; a notificação mostra controles play/pause/next. O subtítulo do player exibe o formato real do stream resolvido (ex.: `m4a 129k`).
3. Fila: também em **grade de 3 colunas**; toque no card para abrir o popup (tocar agora, subir para o topo, descer para o fim, remover).
4. "Limpar" usa confirmação em dois toques (o app não usa `confirm()` nativo) e preserva os áudios já salvos em disco.
5. Na primeira reprodução no Android 13+, conceda a permissão de notificações.

## Notas

- **Sem WebChromeClient**: diálogos nativos do JS (`alert`/`confirm`) não funcionam por design — a UI usa toasts e confirmação inline.
- Tempos na ponte JS↔Kotlin são sempre em segundos (`Double`).
- Captcha do YouTube → erro amigável; o NewPipeExtractor pode exigir atualização futura quando o YouTube mudar.
- **Anti-bot**: User-Agent estável de Chrome Android + cookies de consentimento, 450 ms + jitter entre requisições (1,35 s por 60 s após um 429), 3 tentativas com backoff exponencial, pausa de 45 s nos warm-ups após 3 falhas e **cooldown global persistente de 5→60 min** quando o YouTube bloqueia (sobrevive a reinício do app). Busca e toque do usuário não esperam o espaçamento, mas respeitam o cooldown.
- Fila só resolve URLs (sem baixar áudio em massa): o toque em qualquer item começa na hora; apenas a próxima faixa é pré-baixada (4 conexões Range) e só 4 s depois da reprodução estabilizar.
- Player nativo **media3/ExoPlayer** com buffer de início de ~250 ms (antes: MediaPlayer, que segurava ~2 s antes de tocar).
- `usesCleartextTraffic=false`; `allowBackup=false`.
- Teste em device físico não executado nesta máquina (adb indisponível) — validado por build + inspeção estática.
