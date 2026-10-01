# Lista de verificación para Google Play

## Cumplimiento ya resuelto en el código
- [x] `targetSdk = 36` / `compileSdk = 36` (requisito vigente para apps nuevas y actualizaciones).
- [x] Edge-to-edge, atrás predictivo (`enableOnBackInvokedCallback`), sin bloqueo de orientación (compatible con pantallas grandes/plegables).
- [x] Permisos mínimos: `INTERNET` y `WRITE_EXTERNAL_STORAGE` (sólo `maxSdkVersion=28`). El video de entrada llega por **Photo Picker** (sin `READ_MEDIA_VIDEO`, que Play restringe).
- [x] Guardado con **MediaStore** (Android 10+ sin permisos) y compartir con **FileProvider** + permisos temporales de lectura.
- [x] `<queries>` limitado a TikTok / Instagram / Facebook (no se usa `QUERY_ALL_PACKAGES`).
- [x] Sin anuncios, SDKs de analítica ni trackers. `allowBackup=false` y `dataExtractionRules` que excluyen todo.
- [x] Tráfico sólo HTTPS (`usesCleartextTraffic=false`).
- [x] R8/minify + shrinkResources con reglas para Vosk/JNA/MediaPipe/OkHttp.
- [x] Sin descarga de videos de YouTube (política *Deceptive Behavior / Intellectual Property* y Términos de YouTube).
- [x] Aviso de propiedad del contenido (casilla obligatoria en la importación), política de privacidad y licencias de código abierto dentro de la app.
- [x] Textos en español (por defecto) e inglés.

## Antes de subir (acciones tuyas)
1. **Firma**: crea un keystore y un `keystore.properties` en la raíz (nunca lo subas a git):
   ```properties
   storeFile=../mi-release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```
   Activa **Play App Signing** al crear la app en la consola.
2. **Build**: `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
3. **Id de aplicación**: cambia `com.shortsmaker.viral` en `app/build.gradle.kts` si quieres uno propio (no se puede cambiar después de publicar).
4. **Política de privacidad**: publica `PRIVACY_POLICY.md` en una URL pública (p. ej. GitHub Pages), rellena tu correo y pega la URL en la ficha de Play.
5. **Seguridad de los datos (Data safety)**: declara *no se recopilan datos* ni se comparten. Los datos tratados (video, audio, rostros) se procesan **sólo en el dispositivo** y no salen de él. Marca que la app no recopila datos de usuario.
6. **Clasificación de contenido**: cuestionario IARC, categoría *Reproductores y editores de video*. No hay contenido generado por el servidor.
7. **Declaración de contenido generado por IA / UGC**: la app edita contenido del propio usuario; no genera medios con IA generativa.
8. **Ficha**: capturas de pantalla (teléfono y, si quieres, tablet 7"/10"), icono 512×512, gráfico destacado 1024×500. No uses logos de YouTube/TikTok/Instagram/Facebook ni sugieras afiliación.
9. **Pruebas cerradas**: las cuentas personales nuevas deben hacer una prueba cerrada con ≥12 testers durante 14 días antes de pasar a producción.

## Puntos que debes verificar en un dispositivo real
- **Páginas de 16 KB** (obligatorio para apps con código nativo que apuntan a Android 15+): ejecuta `zipalign -c -P 16 -v 4 app-release.aab`/`check_elf_alignment.sh` sobre el APK generado y comprueba que `libvosk.so`, `libjnidispatch.so` y las libs de MediaPipe estén alineadas. Si alguna no lo está, actualiza esa dependencia.
- **Heatmap de YouTube**: pega varios enlaces reales. Es una lectura de página pública (no API oficial), puede fallar si YouTube cambia su HTML o muestra una pantalla de consentimiento; en ese caso la app cae a análisis de texto.
- **Tiempos del overlay de subtítulos** en un export de 720p y 1080p (los tiempos son relativos al inicio del clip recortado).
- **TikTok / Instagram / Facebook**: sólo se garantiza abrir la app destino con el video adjunto (intent de compartir). Cada app decide qué pantalla muestra.
- **Dispositivos de gama baja**: prueba con un video de 10+ minutos (la transcripción de Vosk es en tiempo casi real ≈1× la duración del audio) y el export en 720p.

## Riesgos de política que debes conocer
- Leer datos de la página de YouTube **no es una API oficial** y podría contravenir sus Términos. Está aislado en `YouTubeClient`/`AnalysisPipeline` para poder desactivarlo sin afectar al resto.
- No añadas la descarga de videos de YouTube: Google Play rechaza apps que lo faciliten.
