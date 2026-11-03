# Trazo

App Android para **escanear una firma y quedarse solo con el trazo**, eliminando el papel.
El resultado es un **PNG con transparencia** (más PDF opcional) listo para pegar en un
contrato, un correo o una imagen.

## Qué hace

1. **Captura** con la cámara integrada (CameraX) o elige una foto de la galería.
2. **Corrige la perspectiva**: arrastra las 4 esquinas hasta los bordes del papel y la foto
   se endereza como si la hubieras escaneado.
3. **Quita el fondo** automáticamente y te deja afinar con tres controles:
   - *Umbral*: cuánta oscuridad cuenta como tinta.
   - *Suavizado*: dureza del borde del trazo.
   - *Grosor*: adelgaza o engorda el trazo.
   - Color: tinta negra (recomendado) o el color real del bolígrafo.
4. **Borrador**: pinta con el dedo para quitar manchas, sombras o trozos de papel.
   Con deshacer y restaurar.
5. **Exporta**:
   - PNG transparente → `Pictures/Trazo`.
   - PDF A4 con fondo blanco → `Download/Trazo`.
   - Compartir directamente (WhatsApp, correo, Drive…).

## Cómo se quita el fondo (la parte interesante)

No hace falta un modelo de IA para esto. La clave es separar **tinta** de **papel** por
luminancia relativa, no por un umbral fijo:

1. La imagen se reduce a 1600 px de lado mayor (de sobra para una firma) y se pasa a gris.
2. Se estima la **iluminación del papel** con un desenfoque de caja muy grande
   (radio ≈ 1/20 del lado menor, 3 pasadas). Ese desenfoque "borra" la firma y deja solo
   el gradiente de luz y las sombras.
3. Para cada píxel se mide cuánto se oscurece respecto a ese fondo:
   `tinta = (fondo − píxel) / fondo`.
   Al ser **relativo**, las sombras y la luz desigual dejan de importar.
4. Ese valor se convierte en **alfa** con una curva `smoothstep` (umbral + suavizado) y un
   ajuste de gamma (grosor). Se usa una LUT de 1024 entradas para no llamar a `pow()` por píxel.
5. Para conservar el color del bolígrafo se despeja el color original de la mezcla con el
   papel: `P = C·α + 255·(1−α)  ⇒  C = (P − 255·(1−α)) / α`.
6. El PNG final es `ARGB` con alfa proporcional a la tinta. El borrador multiplica ese alfa.

Con una firma sintética (papel con gradiente de luz, ruido y tinta azul) el método da
**IoU ≈ 0,96** contra la máscara real y deja el papel con alfa ≈ 0.

## Estructura

```
app/src/main/java/com/trazo/scanner/
├── MainActivity.kt              # entry point + tema
├── TrazoApp.kt                  # navegación entre pantallas y overlay de progreso
├── TrazoViewModel.kt            # estado, recorte, render con debounce, exportar
├── image/
│   ├── SignatureProcessor.kt    # núcleo: iluminación → tinta → alfa (Otsu + LUT)
│   ├── PerspectiveWarp.kt       # homografía de 4 puntos (Heckbert) + muestreo bilineal
│   ├── EraserMask.kt            # máscara de borrado con pincel circular
│   └── ImageStore.kt            # decodificar/EXIF, guardar PNG y PDF, compartir
└── ui/
    ├── HomeScreen.kt
    ├── CameraScreen.kt          # CameraX + permisos
    ├── CropScreen.kt            # 4 esquinas arrastrables
    ├── EditorScreen.kt          # vista previa, ajustes, borrador, exportar
    ├── components/SignatureSurface.kt
    └── theme/
```

## Compilar

Requisitos: **JDK 17** y **Android SDK 35** (`platforms;android-35`, `build-tools;35.0.0`).

```bash
# Android Studio: abrir la carpeta del proyecto y pulsar Run.
# O por línea de comandos:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` debe apuntar al SDK (`sdk.dir=/ruta/al/Android/Sdk`).

### APK ya generado

```bash
# Instalar en un teléfono conectado por USB (depuración USB activada)
adb install -r dist/trazo-debug.apk
```

- `dist/trazo-debug.apk` — instalable directamente, firmado con la clave de depuración.
- `dist/trazo-release.apk` — misma app sin la marca de depuración.
- Paquete `com.trazo.scanner`, `minSdk 24` (Android 7.0+), `targetSdk 35`.
- Permisos: cámara y, **solo en Android 9 o inferior**, escritura en almacenamiento.
  En Android 10+ no se pide ningún permiso de archivos: la galería se abre con el
  selector del sistema (Photo Picker) y el PNG/PDF se guardan con `MediaStore`, que no
  requiere permiso. Por eso en Ajustes solo verás "Cámara"; es lo correcto, no un fallo.

También puedes compilar sin Gradle global:

```bash
bash scripts/build_apk.sh
```

> Si `dl.google.com` no responde en tu red, el proyecto ya incluye mirrors de Aliyun en
> `settings.gradle.kts` antes de `google()`.

## Detalles técnicos

- Kotlin 2.0.21, Jetpack Compose (Material 3), AGP 8.7.3, Gradle 8.9.
- `minSdk 24`, `targetSdk 35`. Sin dependencias de servicios de Google ni red: todo offline.
- El render de la vista previa se hace a media resolución en un `Channel` *conflated* para
  no saturar la CPU mientras se mueven los sliders; la exportación siempre es a resolución
  completa (1600 px).
- Procesado 100 % en el dispositivo, sin subir fotos a ningún sitio.

## Limitaciones conocidas

- Fondos muy texturados (madera, tela) o firmas sobre papel de color pueden necesitar
  ajustar el umbral o usar el borrador.
- Si la firma es más clara que el papel (por ejemplo, tinta blanca sobre fondo oscuro),
  el algoritmo actual no la detecta: está pensado para tinta oscura sobre papel claro.
- El PDF se genera en A4 con la firma centrada; no incluye campos de formulario.
