# ShortsMaker Viral

App Android (Kotlin + Jetpack Compose) para convertir videos largos en clips verticales 9:16 listos para TikTok, Instagram Reels y Facebook.
**Todo se procesa en el teléfono: sin servidores propios y sin APIs de pago.** Se monetiza con un banner de Google AdMob (IDs de prueba por ahora; ver `PLAY_STORE.md`).

## Flujo (6 pantallas)

| Pantalla | Archivo | Qué hace |
|---|---|---|
| 1. Inicio | `ui/home/HomeScreen.kt` | Botón “Crear nuevo Clip IA”, búsqueda, cuadrícula de proyectos guardados localmente, ajustes |
| 2. Importación | `ui/importer/ImportScreen.kt` | **Video de la galería (único requisito, Photo Picker sin permisos)** + enlace OPCIONAL de YouTube, Twitch o Kick (validado), idioma del video |
| 3. Procesamiento | `ui/processing/ProcessingScreen.kt` + `data/AnalysisPipeline.kt` | Barra de progreso real por pasos: importar → YouTube → modelo de voz → transcribir → analizar |
| 4. Sugerencias | `ui/suggestions/SuggestionsScreen.kt` | Botón fijo “Crear clip manualmente” + tarjetas con título, puntaje viral, primeras palabras, vista previa rápida y “Editar clip” |
| 5. Editor vertical | `ui/editor/EditorScreen.kt` | Lienzo 9:16, línea de tiempo con recorte, subtítulos editables, estilos, formato y encuadre |
| 6. Exportar | `ui/export/ExportScreen.kt` | Render con el hardware del móvil, guardar en galería y atajos a TikTok / Instagram / Facebook |

## Tecnología on-device

| Función | Solución | Dónde |
|---|---|---|
| Voz → texto con tiempos por palabra | **Vosk** (Kaldi, Apache 2.0). El modelo pequeño (~40-50 MB) se descarga una vez por idioma | `data/VoskModelManager.kt`, `data/AudioDecoder.kt`, `data/VoskTranscriber.kt` |
| Auto-encuadre de rostro | **MediaPipe Face Detector** (BlazeFace, incluido en `assets/`, 100 % offline) + suavizado de cámara | `data/FaceTracker.kt`, `domain/Framing.kt` |
| “Heatmap” de momentos más vistos | Lectura de la página pública del video desde el cliente (HTTP + WebView invisible como respaldo) | `data/YouTubeClient.kt`, `domain/YouTube.kt` |
| Algoritmo de clips y puntaje viral | Lógica propia (frases, heatmap, palabras gancho, ritmo, duración) | `domain/ClipAnalyzer.kt` |
| Títulos sugeridos | Palabras clave más repetidas + plantillas por idioma | `domain/TextAnalysis.kt`, `domain/TitleGenerator.kt` |
| Render 9:16 + subtítulos quemados | **Media3 Transformer** (MediaCodec por hardware) | `data/VideoExporter.kt`, `data/SubtitleRenderer.kt` |

La **misma** transformación de encuadre (`FramingMath`) y el **mismo** renderizador de subtítulos (`SubtitleRenderer`) se usan en la vista previa y en la exportación, así que lo que ves es lo que se exporta.

### Puntaje viral
`score = 0.42·heatmap + 0.24·ganchos + 0.14·gancho inicial + 0.12·ritmo + 0.08·duración` (si no hay heatmap, los pesos se redistribuyen sobre el texto). Se generan ventanas de 15-60 s alineadas a frases y se eligen las mejores sin solaparse (máx. 25 %).

## Flujos de importación

| Entrada | Metadatos | Sugerencias de clips |
|---|---|---|
| Sólo video local (sin enlace) | — | `LocalTextClipGenerator` (sólo transcripción de Vosk) |
| + enlace de **YouTube** | título, duración y *heatmap* (scraping) | heatmap + texto (`ClipAnalyzer`) |
| + enlace de **Twitch / Kick** (VOD, clip o canal) | título y duración si aparecen en el HTML/DOM. **No exponen heatmap**, así que no hay picos | `LocalTextClipGenerator` |
| Enlace cuyo scraping falla | — | fallback automático a `LocalTextClipGenerator` (el flujo nunca se detiene) |

En ningún caso se descarga el video ni el directo: el usuario selecciona siempre el archivo local (p. ej. el VOD que ya bajó).

### `LocalTextClipGenerator` (`domain/LocalTextClipGenerator.kt`)
Ventanas de 15-60 s alineadas a frases, puntuadas (0-1) con: densidad de habla relativa al video y poco silencio (30 %), preguntas/exclamaciones (20 %; Vosk no emite puntuación, así que se infiere por palabras interrogativas y énfasis), palabras clave repetidas de todo el video (25 %), ganchos (15 %) y duración (10 %). Se eligen los mejores sin solaparse (máx. 25 %).

### Modo manual
El botón fijo de Sugerencias navega a `manual/{id}` (`ui/manual/ManualClipEntryScreen.kt`), que crea un clip con el **video completo** (hasta 10 min) y salta a `editor/{id}/{clip}` sacando `manual/{id}` de la pila (Atrás vuelve a Sugerencias). En el editor se recorta con la línea de tiempo (más saltos de ±1 s / ±10 s para videos largos). La transcripción de Vosk ya cubre todo el video, y el auto-encuadre de MediaPipe se calcula **sobre el tramo recortado** (se reanaliza al soltar el control, sólo para tramos ≤ 2 min; el resultado se guarda por clip).

## Fase 2: análisis multimedia, videos largos y render avanzado

### Análisis multimedia (`domain/`)
`MultimediaClipGenerator` amplía `LocalTextClipGenerator` (que sigue siendo el componente de **texto**) con:

| Señal | Peso | Cómo se mide |
|---|---|---|
| **Audio Radar** (`AudioRadar.kt`) | **40 %** | Energía RMS por trozos de 100 ms (1 byte cada uno, guardada en `energy.bin`). Picos = por encima de la mediana +10 dB y del percentil 95; *contrastes* = ≥ 0.6 s de silencio (≤ mediana −8 dB) seguido de un salto ≥ 18 dB. Densidad de picos 40 % + contrastes 35 % + pico máximo 25 % |
| Texto | 30 % | densidad de habla, preguntas/exclamaciones, temas repetidos, ganchos |
| **Movimiento del rostro** (`MotionAnalysis.kt`) | 15 % | velocidad de desplazamiento del rostro + cambio de tamaño (acercarse/alejarse) entre muestras de MediaPipe |
| **Chat** (`ChatAnalysis.kt`) | 15 % | ráfagas de mensajes por ventanas de 5 s, con más peso para LUL/KEKW/jajaja/emotes… |

Lo que no esté disponible se ignora y su peso pasa al texto (el audio conserva su 40 %). Para ahorrar batería es en **dos etapas**: (A) se puntúan todas las ventanas con texto + audio + chat (barato) y (B) sólo en las 10 mejores se muestrea el rostro con MediaPipe (1 fotograma / 1.5 s) y se calcula el puntaje final. Sin voz (p. ej. gameplay) se recorre el video con ventanas uniformes puntuadas por audio/chat.

**Chat de Twitch/Kick:** si `LinkMetadataClient` obtiene el VOD, `ChatDomParser` busca en el DOM mensajes con marca de tiempo. Es *best effort* (las webs virtualizan el chat y sus selectores cambian): si no aparecen ≥ 30 mensajes, se ignora en silencio.

### Videos largos (`AnalysisPipeline`, `VideoTrimmer`, `FastMode.kt`)
- ≤ **15 min**: flujo normal. > 15 min: diálogo con tres salidas: **recortar** (UI con `RangeSlider` + saltos ±10 s/±1 min; el recorte es un *remux* sin recodificar, casi instantáneo), **forzar procesamiento completo** (muestra un Toast avisando del modo rápido) o cancelar.
- **Modo rápido**: sólo se decodifica la energía del audio (sin remuestrear), se toman los **5 picos** más fuertes (separados ≥ 2 min) y Vosk + MediaPipe corren **únicamente en ventanas de 2 min** alrededor de ellos. En el editor, si recortas a mano un tramo no analizado (≤ 5 min), se transcribe bajo demanda.
- **Foreground Service + WorkManager** (`work/AnalysisWorker.kt`, `AnalysisNotifications.kt`): el análisis se ejecuta como `CoroutineWorker` con `setForeground` y una notificación persistente con barra de progreso y botón Cancelar. Tipo de servicio: `mediaProcessing` en Android 15+, `dataSync` en Android 10-14. La petición se guarda en disco (`PendingRequestStore`) para poder reanudar, el progreso llega a la UI con `setProgress`, y la pantalla de inicio muestra los análisis en curso. Al terminar, una notificación abre las sugerencias.

### Editor y render (`data/VideoExporter.kt`)
- **Diseño**: Normal 9:16, **dividida rostro + gameplay** (mismo video, mitad inferior original) o **rostro + B-roll** (segundo video local en bucle). Se implementa con una `Composition` de dos `EditedMediaItemSequence` y un `VideoCompositorSettings` que coloca cada textura de 1080×960 en la mitad superior/inferior; el audio sale sólo del video principal. Los subtítulos y la barra se dibujan una sola vez con `Composition.setEffects`.
- **Auto-zoom (punch-in)**: +12 % durante 1.6 s (rampa 180 ms / 420 ms) en picos de audio y palabras clave, aplicado en el `MatrixTransformation` (`FramingMath` con `extraZoom`), así el rostro sigue centrado.
- **Subtítulos Hormozi**: el color cambia en el fotograma en que Vosk marca el inicio de cada palabra (la precisión es la del fotograma, ~33 ms a 30 fps) y cada palabra/bloque entra con *pop-in* (`PopAnimation`: escala 0.6→1.18→1.0 + fade) vía `OverlaySettings` por fotograma.
- **Énfasis automático** (`WordEmphasis`): diccionario local en memoria (dinero/fuego/error/peligro/gratis en varios idiomas…): rojo o verde y un emoji **encima** del texto.
- **Retención**: barra de progreso fina en el borde (preview en Compose y quemada en el render) y **mezcla de audio** con efectos locales (`res/raw/sfx_pop.wav`, `sfx_whoosh.wav`) en una secuencia de audio con silencios exactos (`addGap`) que Media3 mezcla con el audio original.
- Sin SFX ni split, el render usa el mismo `EditedMediaItem` de la Fase 1 (sin `Composition`).

## Idiomas

La interfaz se puede cambiar **en caliente** (Ajustes → Idioma de la app, sin reiniciar) entre: español, inglés, francés, alemán, portugués, chino, japonés, ruso e hindi (`ui/Localization.kt`).
Los mismos 9 idiomas están disponibles como **idioma del video** al importar (el último elegido se recuerda como predeterminado), porque Vosk tiene un modelo pequeño con licencia Apache 2.0 para cada uno:

| Idioma | Modelo Vosk |
|---|---|
| Español | `vosk-model-small-es-0.42` |
| English | `vosk-model-small-en-us-0.15` |
| Français | `vosk-model-small-fr-0.22` |
| Deutsch | `vosk-model-small-de-0.15` |
| Português | `vosk-model-small-pt-0.3` |
| 中文 | `vosk-model-small-cn-0.22` |
| 日本語 | `vosk-model-small-ja-0.22` |
| Русский | `vosk-model-small-ru-0.22` |
| हिन्दी | `vosk-model-small-hi-0.22` |

Los textos viven en `tools/i18n/<idioma>.txt`; tras editarlos ejecuta `python3 tools/i18n/generate.py` (valida claves y marcadores y regenera los `strings.xml`).
La precisión de los modelos *small* varía mucho (el portugués y el hindi son los más débiles); el usuario puede corregir el texto en el editor.

## Compilar

Requisitos: Android Studio Ladybug o superior (o JDK 17 + Android SDK 36).

```bash
./gradlew :app:testDebugUnitTest   # tests de la lógica de dominio (JVM puro)
./gradlew :app:assembleDebug       # APK de depuración
./gradlew :app:bundleRelease       # AAB para Play Store (ver PLAY_STORE.md)
```

## Decisiones importantes (léelas antes de publicar)

1. **No se descarga el video de YouTube.** Descargar contenido de YouTube viola sus Términos y la política de Google Play (la app sería rechazada). Por eso, con un enlace de YouTube la app usa el *heatmap*, el título y la duración, y pide que el creador **seleccione el archivo original de su propio video**. El paso “Descargando audio/video” del boceto original pasa a ser “Importando video”.
2. **El heatmap se obtiene leyendo la página pública** (como pediste). No es una API oficial: YouTube puede cambiar el formato o considerarlo contrario a sus Términos. Si falla, la app sigue funcionando con análisis de texto (lo cubre `YouTubeClient` devolviendo `null`). Si quieres reducir el riesgo regulatorio, elimina el paso `YOUTUBE` de `AnalysisPipeline`.
3. **Transcripción:** se eligió Vosk en lugar de Whisper.cpp porque se integra con un AAR de Maven Central (sin NDK/CMake) y entrega tiempos por palabra. La precisión de los modelos *small* es menor que la de Whisper; el usuario puede corregir el texto en el editor.
4. **Almacenamiento:** el video importado se copia al almacenamiento privado de la app para poder reeditarlo. Se borra al eliminar el proyecto.

Consulta **[PLAY_STORE.md](PLAY_STORE.md)** para la lista de verificación de publicación y **[PRIVACY_POLICY.md](PRIVACY_POLICY.md)** para la política de privacidad.

## Estructura

```
app/src/main/java/com/shortsmaker/viral/
├── domain/   Lógica pura (testeada en JVM): modelos, análisis viral, subtítulos, YouTube, encuadre
├── data/     Android: repositorios, Vosk, MediaPipe, Media3, MediaStore/compartir
└── ui/       Compose: tema, navegación y las 6 pantallas
```
