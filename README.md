# Trazo

App de pintura digital para Android, del estilo de Procreate. Está optimizada para la Xiaomi Pad 5 (2560×1600 a 120 Hz,
Snapdragon 860, Xiaomi Smart Pen: presión sí, inclinación no).

- Kotlin + Jetpack Compose para la interfaz y OpenGL ES 3.0 para el lienzo (`engine/`).
- Las capas son texturas RGBA premultiplicadas del tamaño del lienzo. El compositor guarda en caché todo lo que hay
  bajo la capa activa. Hay 22 modos de fusión con las fórmulas W3C, máscara de recorte y bloqueo alfa.
- Pinceles por estampado instanciado: StreamLine, curva de presión, afinado, dispersión, variaciones, forma y grano
  procedurales. El difuminado toma pintura de la posición anterior. El trazo se pinta en su propio búfer y se funde
  con la opacidad de la herramienta al terminar.
- Deshacer por rectángulos de píxeles en RAM (hasta 600 MB / 250 pasos). Las operaciones de capa también se pueden
  deshacer.
- Archivos en `files/artworks/<id>/`: `doc.json`, `L<id>.z` (RGBA en deflate) y `thumb.jpg`. Se guarda
  automáticamente a los 15 s de inactividad, al salir y al pausar.

## Compilar e instalar

```
./gradlew assembleDebug
"C:\Program Files\SuperDisplay\adb\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

En MIUI hay que activar *Opciones de desarrollador → Instalar vía USB*.

## Hoja de ruta (paridad con Procreate)

**Fase 1, hecha:** galería (crear, renombrar, duplicar y eliminar), lienzos de hasta 4096², pintar, difuminar y
borrar con 25 pinceles en 7 categorías, ajustes de pincel (Brush Studio básico), capas (añadir, duplicar, combinar,
reordenar arrastrando, opacidad, 22 modos de fusión, bloqueo alfa, máscara de recorte, rellenar, borrar, voltear,
visibilidad), color de fondo y transparencia, color (disco, clásico, armonía, valores/hex, paletas, recientes),
cuentagotas (mantener el dedo o el botón), ColorDrop (arrastrar el color para rellenar, con umbral y referencia),
gestos (2 dedos deshacer, 3 rehacer, 4 pantalla completa, pellizco rápido para ajustar, mover/girar/zoom), curva
de presión, rechazo de la palma, 120 Hz y exportación PNG/JPEG/compartir.

**Fase 2:**
- Latencia: renderizado en front buffer (`androidx.graphics` `GLFrontBufferedRenderer`) y predicción de movimiento
  (`androidx.input` motionprediction).
- Cursor del pincel al pasar el lápiz por encima (hover).
- QuickShape: mantener al final del trazo para convertirlo en línea, elipse o polígono.
- Selección (a mano alzada, rectangular, elíptica, automática) con calado, invertir, copiar y pegar.
- Transformar (libre, uniforme, distorsionar, deformar), voltear y girar.
- Ajustes: tono/saturación/brillo, balance de color, curvas, mapa de degradado, desenfoque gaussiano, de
  movimiento y de perspectiva, enfocar, ruido, licuar, clonar, aberración cromática, bloom, falla, medios tonos.

**Fase 3:** guías de dibujo (cuadrícula, isométrica, perspectiva de 1, 2 y 3 puntos, simetría con dibujo
asistido), grupos de capas y máscaras de capa, texto, ventana de referencia, Brush Studio completo (curvas,
importar formas y granos, dual brush, wet mix), exportación PSD con capas, importación de `.procreate` y `.brush`
propios (aquí sí puede servir REA para analizar el formato), y time-lapse grabado en vídeo.

**Fase 4:** asistente de animación (onion skin, fotogramas, GIF/MP4), modo de pintura 3D y Pantalla compartida.

## Versiones y actualizaciones (OTA)

- Publicar una versión: `git tag v0.2.0 && git push origin v0.2.0`. El workflow *Release* compila el APK firmado
  y crea el Release de GitHub `trazo-0.2.0.apk`.
- **Obtainium** sigue `https://github.com/ikerlorente11/trazo`. La app también avisa en la galería
  («Trazo X disponible → Actualizar»): descarga el APK y lo instala ella misma (la primera vez pide permiso para
  instalar apps).
- La clave de firma está en `%USERPROFILE%\.android-keys\trazo-release.jks` (contraseña en `keystore.properties`,
  fuera de git). En GitHub está en los secrets `TRAZO_KEYSTORE_B64` y `TRAZO_KEYSTORE_PASSWORD`. Si se pierde,
  las actualizaciones dejan de instalarse encima: hay que guardarla.
