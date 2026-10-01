# ShortsMaker Viral

App Android (Kotlin + Jetpack Compose) para convertir videos largos en clips verticales 9:16 listos para TikTok, Instagram Reels y Facebook.
**Todo se procesa en el teléfono: sin servidores propios y sin APIs de pago.** Se monetiza con un banner de Google AdMob (IDs de prueba por ahora; ver `PLAY_STORE.md`).

## Flujo (6 pantallas)

| Pantalla | Archivo | Qué hace |
|---|---|---|
| 1. Inicio | `ui/home/HomeScreen.kt` | Botón “Crear nuevo Clip IA”, búsqueda, cuadrícula de proyectos guardados localmente, ajustes |
| 2. Importación | `ui/importer/ImportScreen.kt` | Enlace de YouTube (validado) o video de la galería (Photo Picker, sin permisos), idioma del video |
| 3. Procesamiento | `ui/processing/ProcessingScreen.kt` + `data/AnalysisPipeline.kt` | Barra de progreso real por pasos: importar → YouTube → modelo de voz → transcribir → analizar |
| 4. Sugerencias | `ui/suggestions/SuggestionsScreen.kt` | Tarjetas con título, puntaje viral, primeras palabras, vista previa rápida y “Editar clip” |
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
