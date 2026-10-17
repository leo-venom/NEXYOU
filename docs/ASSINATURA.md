# NEXYOU — Assinatura e entrega

## Configuração

`android/keystore.properties` aponta para `keystore/nexyou-release.jks`
(mesmo keystore do NEXUS77, renomeado). Se o arquivo não existir, o build cai
no debug keystore.

Em `app/build.gradle.kts`, `signingConfigs.release`:

- `enableV1Signing = false`
- `enableV2Signing = true`
- `enableV3Signing = true`

## Verificação

```bash
APK=android/app/build/outputs/apk/release/app-release.apk
~/Android/Sdk/build-tools/35.0.0/apksigner verify --verbose --print-certs "$APK"
~/Android/Sdk/build-tools/35.0.0/aapt2 dump badging "$APK" | head -1
~/Android/Sdk/build-tools/35.0.0/zipalign -c -v 4 "$APK" | tail -1
sha256sum "$APK"
```

Esperado: v1 `false`, v2 `true`, v3 `true`, `Verification succesful`
(zipalign), `versionCode`/`versionName` corretos.
Certificado: `CN=NEXUS77, OU=Apps, O=Leo, L=Brasil, ST=BR, C=BR`
(SHA-256 `3e871ebe7c278c9d6ab605c1818af0bfcd55bf346e4e6accf9e5f82cf9bb2bfb`).

## Regra de entrega

- Pasta única: `/home/leo/apk22/` — **somente a última build**.
  Antes de copiar: `find /home/leo/apk22 -maxdepth 1 -name '*.apk' -delete`
  (preserva `122.jpg`).
- Nome: `app-v{versionCode}-{versionName}-{YYYYMMDD-HHMMSS}.apk`.
- Build de entrega: `./gradlew :app:assembleRelease` (R8 + shrink), sem
  `clean` e sem `--no-daemon`.

## Entregas registradas

| Versão | Código | Arquivo | SHA-256 |
|---|---|---|---|
| 1.0.33 | 34 | `app-v34-1.0.33-20261017-103529.apk` | `438475b85e85744548a1874231552de3cdc9429c9ff89621314e4a3f3bae5847` |
| 1.0.32 | 33 | `app-v33-1.0.32-20261017-103225.apk` | `7f89d7aaf491351e7747ec1b50c5ef7f40db694bcaacdc299949db11167dca32` |
| 1.0.31 | 32 | `app-v32-1.0.31-20261017-102404.apk` | `dd720fcea2e17005ef5da77feb1f60ebfe41abad34ec4e87ee1314c2cdbe5558` |
| 1.0.30 | 31 | `app-v31-1.0.30-20261017-101653.apk` | `c37904b835efe0e9a0b42419d4fca48a46e550a903977fb67bf101235f3091a8` |
| 1.0.29 | 30 | `app-v30-1.0.29-20261017-101408.apk` | `bc0ab60de36f59863a7f9b2e120442c4ec24c5cf72b00280e9a8d20fe2e72b78` |
| 1.0.28 | 29 | `app-v29-1.0.28-20261017-100856.apk` | `48d41fd2ac57e1c135c8877f0e72dc688dbd7b2ac3621b5d7ad9ceaee9809241` |
| 1.0.27 | 28 | `app-v28-1.0.27-20261017-100431.apk` | `41de264ba6860d3ab91ec37c98a61ecf9c7b9169d7a3e349572bff9d8fb387f1` |
| 1.0.26 | 27 | `app-v27-1.0.26-20261017-095911.apk` | `1ed41ff82046726119f96d141a405f555490f35e66cebdb1c78d12389c925b4c` |
| 1.0.25 | 26 | `app-v26-1.0.25-20261017-094702.apk` | `b364931822dffd4627d6433ee72b4c5d8db5e0b5643a1c4acee34e4b246d7391` |
| 1.0.23 | 24 | `app-v24-1.0.23-20261017-093758.apk` | `87c22d6105a6830772b717ad09c1585ea56f2a6172eacdf909adc05a260dcfa0` |
| 1.0.22 | 23 | `app-v23-1.0.22-20261017-093210.apk` | `8cab34bdd84528c66b823ced9abe23032dcb7b3424d0acf62908d41c302b8e27` |
| 1.0.21 | 22 | `app-v22-1.0.21-20261017-025309.apk` | `e43b8690679785f4649c28181d8ee1ccd5834003d7b7d3e2bcda11788e999f19` |
| 1.0.20 | 21 | `app-v21-1.0.20-20261017-025124.apk` | `b0f0f0871666f6dd52ef2b723c4269e3fcb93454ddc8f232d40a868d92a81648` |
| 1.0.19 | 20 | `app-v20-1.0.19-20261017-024430.apk` | `2aea2922950bdf601f292987ce3963dc375b9b58037f866c33de0f6ab384e723` |
| 1.0.18 | 19 | `app-v19-1.0.18-20261017-024042.apk` | `f2d559f46d3a3e6cb66d76c410693f7ef58126525ac84b403f7cdcf086f38d5f` |
| 1.0.17 | 18 | `app-v18-1.0.17-20261017-022947.apk` | `8e8dc4ce89a27a7f229eff65b94f4fc02b6824693c22b00bb041c3cb94a7231f` |
| 1.0.16 | 17 | `app-v17-1.0.16-20261017-022114.apk` | `428933fd3db8184d6a394f0948218a0ce0e02aab51d619149b7e20d71e723b7e` |
| 1.0.15 | 16 | `app-v16-1.0.15-20261017-021544.apk` | `6942a054605b032ffd5fd21d6ea742d7c345f4607761ecb57dda760b3a88afc3` |
| 1.0.14 | 15 | `app-v15-1.0.14-20261017-020647.apk` | `9833aaaf2829527fd69f5d09b135d0b8bd0f162b7cabd8fd43de845d5832d0cf` |
| 1.0.13 | 14 | `app-v14-1.0.13-20261017-015826.apk` | `a53917b83a442bbe7431b593285d782abaf6b259255bae0a9bce17b9ecfb46f6` |
| 1.0.12 | 13 | `app-v13-1.0.12-20261017-015546.apk` | `80f29402d1bb525cc2a10ed39d05fb66d69ede69cd2f9f9316fc78f3029336ef` |
| 1.0.11 | 12 | `app-v12-1.0.11-20261017-014654.apk` | `a990f2a525aa3025abbf761ff62a15e9fdf6d2ac66156cb954d9763f96ef60b4` |
| 1.0.10 | 11 | `app-v11-1.0.10-20261017-014302.apk` | `9740f6819f140cc06dd5c246b36dc27ba1b45f2fbcb4181a8ff58c5e2fed4451` |

> Nota: a pasta guarda apenas a última build; os nomes/hashes acima são o
> registro histórico das entregas.
