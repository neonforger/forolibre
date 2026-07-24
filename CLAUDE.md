# ForoPlus v2 — guía del proyecto

Cliente **Android nativo no oficial** para ForoCoches. Este worktree (rama `v2-shell`) es la
reescritura **v2**: WebView motor invisible + UI 100% nativa. **El `README.md` describe la v1
(WebView), NO este código.** El backlog/briefing histórico está en
`Desktop\foroplus_prompt_revision.md` y el diario detallado en
`~\.claude\projects\C--Users-domen\memory\progress.md` (léelo si vas a tocar algo serio).

Tamaño: 29 ficheros Kotlin. `MainActivity.kt` (~2300 líneas) y `assets/extractor.js` (~1200
líneas) son el corazón.

---

## 🔴 REGLA DE ORO (requisito del dueño, innegociable)

> **El foro web que corre por debajo NO debe verse bajo ningún concepto. Todo lo que ve el
> usuario debe ser nuestro cliente nativo.**

Única excepción: el challenge de Cloudflare (solo lo resuelve un navegador visible). Cualquier
otro `showWeb()` es un bug. Enlaces/pantallas de FC que no sabemos pintar en nativo → **navegador
EXTERNO** (`openExternal`), nunca la capa web.

## Arquitectura

- El WebView existe solo como **motor**: carga forocoches.com, se le inyecta `extractor.js`, y
  **todas** las peticiones al foro salen de ahí con `fetch` same-origin usando las cookies de
  sesión. **Cero HTTP nativo para HTML** → inmune a Cloudflare por diseño. (HTTP nativo sí se usa
  para imágenes `PostImages`/avatares y para APIs de terceros de embeds — no es HTML de FC.)
- `extractor.js` solo **extrae** y entrega JSON por el puente `AndroidShell` (`ShellBridge.kt`).
  El filtrado (ignorados/keywords) y el render viven en Kotlin.
- Escritura sin reimplementar el protocolo: se trae el **formulario REAL** de FC, se copian TODOS
  los campos ocultos (`securitytoken`, `posthash`, `wysiwyg`…) y solo se sustituye el contenido.
  Helpers en extractor.js: `formWith`, `copyFormFields`, `extractErr`.

## Ficheros clave

- `MainActivity.kt` — orquesta todos los paneles nativos (lista, hilo, respuesta/composer, login,
  MPs, perfil ajeno, opciones, citas/menciones, **reportar** con overlay de Cloudflare), navegación
  y el puente. Diálogo de reporte: `res/layout/dialog_report.xml`.
- `assets/extractor.js` — motor de datos: `fcLoadThreadList`, `fcLoadThread`, `fcSubmitReply`,
  `fcCreateThread`, `fcEditPost`, `fcDeletePost`, `fcSearch`, `fcLoadOwnThreads`,
  `fcToggleFavorite`, `fcLoadPmInbox`/`fcLoadPm`/`fcSendPm`, `fcLoadMember`, `fcLogin`, etc.
- `ShellBridge.kt` — `@JavascriptInterface` de todos los callbacks JS→Kotlin.
- `PostAdapter.kt` — render de posts (texto nativo + `EmbedView` por embed).
- `EmbedView.kt` — embeds interactivos (WebView por embed, reproductor oficial inline).
- `PmParser.kt` / `PmInboxAdapter.kt` — MPs.
- `TrustedOrigins.kt` — qué URLs son de FC de confianza.

---

## Reglas de trabajo (innegociables)

### Git
- Mensaje de commit: **siempre exactamente `cambios varios`**.
- Firma: **`--no-gpg-sign`**. **NUNCA** añadas el trailer `Co-Authored-By`.
- Push: **solo a `origin`** (`albertd987/forocoches-plus`; el repo se **movió a
  `albertdom/forocoches-plus`**, la URL vieja redirige). **NUNCA** al remoto `neonforger`
  (forolibre): lleva un token embebido → si imprimes salida de git, sanitízala
  (`sed -E 's/gh[pousr]_[A-Za-z0-9]+/***/g'`). (Excepción puntual: 2026-07-25 el dueño autorizó
  explícitamente replicar la release en `neonforger` — no es lo habitual, la regla sigue vigente.)
- **NUNCA merge a `main`.** La validación se hace repartiendo el APK/AAB a testers.
- `keystore` / `keystore.properties` / `release.keystore` están gitignorados y deben seguir así.

### Cosas que Claude NO puede hacer
- No introducir la contraseña de ForoCoches (si hace falta sesión, que entre el usuario por el
  login nativo).
- No publicar/editar/borrar/**reportar/enviar MP** reales en la cuenta del usuario para "probar"
  sin permiso explícito (acciones públicas e irreversibles).
- No saltarse el anti-flood de 15s de FC ni ningún rate limit.

### Entorno (Windows)
- Hay un hook que **bloquea comandos PowerShell con `\d+`, `/` o `*`** → usa la herramienta
  **Bash** para todo lo que lleve rutas, regex o globs.
- `adb`: `C:\Users\domen\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

---

## Compilar, instalar, depurar

```bash
cd "C:/Users/domen/Desktop/ALBERT/foroapp/foroplus-v2"
./gradlew.bat assembleDebug --console=plain 2>&1 | grep -E "BUILD|e: |FAILURE" | tail
ADB="C:/Users/domen/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s <DEVICE> install -r app/build/outputs/apk/debug/app-debug.apk
```

- El build **debug** instala `com.foroplus.app.v2` (sufijo `.v2` → convive con la app de Play).
  El **release** es `com.foroplus.app` (la app real; ver Publicación).

### ADB inalámbrico (el USB/wifi se cae a menudo)
- `ADB_MDNS_OPENSCREEN=1 adb mdns services` → da `IP:puerto` de `_adb-tls-connect`. Reconectar:
  `adb connect IP:PUERTO`. Si hace falta emparejar, el servicio `_adb-tls-pairing` SOLO se anuncia
  con el diálogo "Emparejar con código" abierto; el usuario dicta el código de 6 dígitos.
- El samsung de pruebas es `SM-S901B` (serial `R3CTA0N1P8M`).

### Depuración del motor por CDP (imprescindible para entender el HTML de FC)
```bash
PID=$(adb -s $DEV shell "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1")
adb -s $DEV forward tcp:9223 localabstract:$PID
curl -s http://localhost:9223/json   # elegir el target "Forocoches", descartar chrome-error://
```
Python con `websocket.create_connection(ws, suppress_origin=True)` + `Runtime.evaluate`
(`awaitPromise:true`, `returnByValue:true`). Gotchas: **WebView en PRIMER PLANO** (en background se
suspenden timers/fetch y `evaluate` cuelga); filtra targets `chrome-error://` (o "Failed to fetch");
`PYTHONUTF8=1` en Windows. El socket del devtools **cambia con cada reinstalación** → re-forward.

---

## Publicación en Google Play

- **Misma app que la v1 de Play**: el release usa `applicationId com.foroplus.app` (el sufijo
  `.v2` es SOLO del buildType debug). Subir el AAB de release es una **actualización** de la app
  existente → **no reinicia** el closed testing (12 testers/14 días). NO crear una app nueva ni
  subir el paquete `.v2`.
- `versionCode` debe ser **mayor** que el vivo en Play. Estado a 2026-07-25: en Play se han usado
  códigos hasta el **10** (el registro anterior de "el 8" se quedó corto); el build.gradle está en
  **11 / 1.3.2**. Al preparar otro release, sube el `versionCode` por encima del último usado en Play;
  si Play rechaza con "código de versión ya usado", vuelve a subirlo (+1) y recompila el AAB.
- `targetSdk`/`compileSdk` = **36** (Android 16, requisito de Play). AGP 8.2.2 es viejo (soporta
  hasta 34): compila con aviso silenciado por `android.suppressUnsupportedCompileSdk=36` en
  gradle.properties. Largo plazo: subir AGP a 8.9+ (arrastra Gradle 8.11.1+); no urge.
- **Firma**: la upload key real está en el repo v1 (`forocoches-plus/keystore.properties` +
  `release.keystore`, `CN=Forocoches Plus`, SHA-256 `C7:CF:5E:6B:…:A1:5D`). Ya está copiada al
  worktree v2 (gitignorada). El release firma con ella; si falta, `bundleRelease` falla a propósito.
- Generar el AAB: `./gradlew.bat bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
- `minifyEnabled false` en release → el release es el **mismo código** que el debug (sin ofuscar).
  Por eso el dogfooding diario en debug transfiere al release.
- **Release de GitHub** (v2 nativa): `v2.0.0` publicada en `albertdom/forocoches-plus` (origin) con el
  APK de release adjunto (sideload, no el AAB). Crear con `gh release create <tag> -R
  albertdom/forocoches-plus --target v2-shell --title … --notes-file … --latest <apk>`. La unicidad de
  `versionCode` solo importa en Play; para sideload da igual.

---

## GOTCHAS CRÍTICOS de ForoCoches (verificados por CDP — NO reinventar)

1. **FC enmascara TODOS los user id a `u=0`** en menú y autores de post. El UID real se saca con
   `fetch` a `member.php` sin args de una página **recién traída** (no del DOM vivo). Ver
   `ownIdentity()`. **EXCEPCIÓN**: los enlaces de **mención dentro del cuerpo** de un post SÍ traen
   el uid real (`member.php?u=NNN`) → por eso el perfil ajeno nativo funciona.
2. **Resultados de búsqueda NO usan `threadbit`**: son `<a href="showthread.php?t=N&highlight=">`.
   El discriminador fiable es el sufijo **`&highlight=`**. Ver `parseSearchDoc()`.
3. **La búsqueda EXIGE sesión** (de invitado el token es `guest` y devuelve la home).
4. **`wysiwyg` manda**: los forms vienen con `wysiwyg=1` (textarea = HTML). Enviar con `wysiwyg=0`
   y **cargar para editar con `&wysiwyg=0`** en la URL, o salen etiquetas literales. Regla: el modo
   del form del que copias campos = el modo con el que envías.
5. **FC IGNORA `goto=lastpost`/`goto=newpost`** (responde página 1). La última página = `page=<total>`.
   Por eso "primer mensaje sin leer" queda fuera (no hay señal fiable de post no leído).
6. **Sesión = solo la cookie `bbuserid`** (HttpOnly, la ve `CookieManager`, no JS). El HTML del
   menú es IDÉNTICO para invitados y logueados — nunca úsalo como señal de login.
7. **Hilos +HD de invitado**: FC no enseña login, **redirige** a `misc.php?do=page&template=Info`
   → detéctalo por la URL final.
8. **Borrar post**: `editpost.php?do=deletepost` viene VACÍO → coge `securitytoken` del form de
   EDICIÓN y monta el POST a mano.
9. **Éxito al publicar/enviar**: por la **URL final** de redirección, nunca por el HTML.
10. **VARNISH**: FC cachea páginas privadas ~1 min (`x-cache: HIT`). Tras escribir, leer para
    verificar devuelve estado rancio; `cache:'no-store'` NO sirve. Antídoto: **query param único**
    (`?_fp=Date.now()`). Aplicado en favoritos, MPs, member, etc.
11. **Separador de miles**: hilos con ≥1.000 respuestas → `"1.680 @ usuario"` (no solo dígitos);
    tolera `[\d.,]+` en `parseSearchDoc`/`parseRow` o salen sin autor/contador.
12. **`report.php` tras Cloudflare INTERACTIVO** (casilla "Verifique que es un ser humano"): un
    `fetch` del motor da 403 (no ejecuta el JS del challenge). RESUELTO en **Reportar**: WebView aparte
    a pantalla completa **tapado por una capa nativa opaca** ("Enviando reporte…"); el cover solo se
    BAJA cuando aparece el challenge para que el usuario pulse la casilla (única excepción de la regla
    de oro) y vuelve a subir al caer el formulario. A veces NO sale CF (cf_clearance válida) y el form
    llega directo → por eso el cover arranca ARRIBA y solo baja si `cf && !form`. Form real:
    `report.php?do=sendemail` (POST), textarea `reason`=comentario, radio `tipo` (+18→6, Spam→1,
    Troll→2, Flood→5, Contenido→3, Otros→4). Éxito: cerrar 2.5s tras `f.submit()` (el POST ya salió);
    NO cargar el redirect a showthread (bajo el cover relanza CF y colgaba). Ver `startReportFlow`.
13. **YouTube en FC = script, NO iframe/enlace**: un `<div id=NNN></div>` VACÍO + `<script>verVideo(
    'IDVIDEO','NNN')</script>` que monta el iframe en runtime. Como parseamos estático, el ID solo vive
    en el texto del script → `collectEmbeds` lo extrae con regex `verVideo(HD)?\('ID'`. CLAVE: quitar
    los `<script>` del mensaje **DESPUÉS** de `collectEmbeds`, no antes (línea del pipeline en `fcLoadThread`).

## GOTCHAS de Android / UI

- **Modo oscuro**: tema `DayNight` pero paneles con fondo BLANCO fijo. Todo `EditText`/`TextView`
  sobre panel necesita `textColor`/`textColorHint` explícitos o es invisible.
- **BottomSheet**: aplica el inset `systemBars` como `paddingBottom` o la última fila queda tapada.
- **Layouts**: hijo de LinearLayout vertical con alto `match_parent` se come el resto → usa `0dp`+weight.
- **Botón ATRÁS (targetSdk 35+)**: sobrescribir `onBackPressed()` **NO se llama** (Android despacha
  por `OnBackInvokedCallback`) → la app se salía. La navegación atrás va por un
  `OnBackPressedCallback` registrado en `onBackPressedDispatcher` (ver `onBackCallback` +
  `addCallback` en onCreate). **Toda navegación atrás va por ahí, nunca por `onBackPressed()`.**
- **Swipe entre subforos**: `dispatchTouchEvent` cambia de pestaña solo si el gesto **empieza sobre
  la lista** (`touchInside(threadList)`), o deslizar la barra inferior cambiaba de subforo.
- **WebView de embed en RecyclerView**: carga diferida 300ms (cancelada en `release()`), se libera
  en `onViewRecycled`. `mediaPlaybackRequiresUserGesture=true` (no autoplay; el play del propio
  reproductor reproduce inline).
- **YouTube embed exige origen de TERCEROS**: `EmbedView` carga el iframe con
  `loadDataWithBaseURL("https://forocoches.com", …)` + `&origin=…` en la src (NO youtube.com, o el
  player da **error 150/152** por ser mismo-origen). X/IG/TikTok sí usan su propio dominio como base
  (son `blockquote`+script, no un iframe directo).
- **WebView OCLUIDO por una vista opaca → Chromium THROTTLEA el render** (`onVisibilityAggregated=false`)
  y un challenge de Cloudflare se **CONGELA** (no se resuelve). Forzar `onVisibilityAggregated(true)`
  NO basta. Por eso el cover del reporte se baja mientras el CF se resuelve (gotcha 12).
- **WebView sin `WebViewClient` abre el NAVEGADOR EXTERNO** en una navegación de main-frame (el
  redirect post-reporte a showthread lanzaba Chrome). Solución: `WebViewClient` con
  `shouldOverrideUrlLoading → false` para que todo cargue dentro del WebView.

---

## Estado actual (todo HECHO y validado en dispositivo salvo lo indicado)

Lista nativa + pestañas de subforos + scroll infinito + filtros ignorados/keywords; vista de hilo
nativa (tarjetas, avatares, citas, paginación bajo demanda + salto de página + carga de página
anterior); responder/citar/multicitar; crear/editar/borrar hilos y posts; **editor BBCode** +
smilies; login nativo; hilos +HD restringidos; **favoritos** (toggle suscripción); **Mis hilos** /
**Participados**; **citas y menciones** aisladas; perfil propio + pantalla de **Opciones** (fuente,
filtros, ignorados); **buscador**; **deslizar entre subforos**; **MPs nativos** (bandeja + detalle
+ compositor — envío verificado hasta el POST, sin mandar MP real); **perfil de OTRO usuario**
nativo (mención → perfil, no Chrome); **enlaces FC no-hilo** → nativo o navegador externo (nunca la
capa web); **embeds interactivos** de X/IG/TikTok/YouTube/vídeo (reproductor oficial que se despliega
solo al aparecer el post y reproduce inline); deep links de notificaciones (hilo y MP) a vista nativa;
**reportar posts** (menú ⋮ de post ajeno → diálogo nativo con motivo + comentario contextual en
"Otros"; Cloudflare interactivo tapado por capa nativa, gotcha 12); **comunidad de Telegram**
(`t.me/foroplus`) + botón ☕ "Invítame a un café" (`paypal.me/neonforger`) en Opciones + **popup de
bienvenida** una sola vez (pref `welcome_shown`).

Sesión 2026-07-24/25: arreglado el **aviso de Play** "servicios en primer plano restringidos" (se
quitó `BootReceiver`; el WorkManager periódico ya sobrevive al reinicio sin arrancar el FGS en boot,
prohibido en Android 15/targetSdk 35+); arreglados los **embeds de YouTube** (gotcha 13 + error 150/152);
añadido **reportar** (gotcha 12); publicada **release v2.0.0** en GitHub.

### Pendiente / no verificado
- **Envío real de MP**: el POST se construye igual que `fcSubmitReply` (validado) pero no se ha
  mandado un MP real desde la app (falta que lo pruebe el dueño).
- **Instagram embed**: `embed.js` es más estricto que X/TikTok — no verificado en un hilo real.
- **Rendimiento** con MUCHOS embeds en un mismo hilo — no estresado.
- **Reportar — éxito por temporizador**: se da por enviado 2.5s tras el POST; NO detecta fallos
  (anti-flood / rate-limit de FC) → puede cantar "enviado ✓" en falso. Aceptado; endurecer si molesta.
- **"Contacto" fantasma**: el enlace del pie de FC (`showthread t=8241760`) se cuela como fila en
  las listas nativas (`parseListDoc` no filtra el footer). Bug menor conocido, sin arreglar.

### FUERA DE ALCANCE — no empezar
- **Bloque C** (chat estilo WhatsApp sobre MPs con servidor propio e identidad): **aplazado
  conscientemente** por el dueño hasta tener un producto refinado. El diseño ya está razonado en
  `progress.md`. **No montar ningún servidor ni sistema de identidad.**

## Cómo trabajar aquí
1. Antes de arreglar un bug, **causa raíz** sondeando el HTML real por CDP (este proyecto castiga
   las suposiciones). Usa `superpowers:systematic-debugging`.
2. Fases pequeñas: implementar → compilar → instalar → **verificar en el dispositivo** → commit.
3. Verifica con **evidencia** (captura/CDP/foco), no por deducción. "Compila" ≠ "funciona".
4. Actualiza `progress.md` con cada gotcha nuevo.
5. No rompas lo validado; si tocas algo compartido (`onThreadJson`, `parseListDoc`, composer,
   `showNative`/paneles), re-prueba lo que dependa.
