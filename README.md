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

## Funciones

**Pintar:** 28 pinceles en 7 categorías, sin contar los tuyos. Cada pincel se ajusta en el Estudio de pinceles:
- trazo: StreamLine, espaciado, afinado y sellos por paso;
- forma y grano: procedurales o importados desde una imagen;
- dinámica: dispersión y variación de tamaño, giro, opacidad y tono;
- mezcla húmeda: arrastra la pintura que hay debajo;
- presión: respuesta del pincel y curva de presión global.

Además puedes duplicar, crear e importar pinceles `.brush` y `.brushset`.

**Herramientas:**
- difuminar y borrar;
- clonar, con un origen que se puede mover;
- QuickShape: al mantener el lápiz al final del trazo, se convierte en línea, elipse, círculo, polígono o polilínea, y puedes ajustarla sin levantarlo;
- predicción del trazo, para reducir la latencia percibida;
- cursor del pincel al pasar el lápiz por encima.

**Capas:**
- grupos de un nivel, que se pueden plegar, combinar y desagrupar;
- máscaras de capa: pintar, desactivar, invertir y aplicar;
- máscara de recorte, bloqueo alfa y capa de referencia para el relleno;
- 22 modos de fusión;
- reordenar arrastrando, duplicar, combinar hacia abajo y aplanar.

**Selección:** automática, a mano alzada (lazo o polígono), rectangular y elíptica. Se puede añadir, restar,
invertir y calar. Pintar, rellenar, ajustar y transformar respetan la selección. También hay copiar, cortar
y pegar, con el gesto de deslizar tres dedos hacia abajo.

**Transformar:** libre, uniforme, distorsionar (perspectiva) y deformar (malla Bézier de 4×4). Además: voltear,
girar 45°, ajustar al lienzo y píxeles nítidos (vecino más cercano).

**Ajustes:**
- color: tono, saturación y brillo; balance de color; curvas por canal; mapa de degradado; brillo y contraste;
  invertir;
- desenfoques: gaussiano, de movimiento y de perspectiva;
- efectos: enfocar, ruido, resplandor, falla, medios tonos y aberración cromática;
- licuar: empujar, girar, pellizcar, expandir, cristales y reconstruir.

**Guías:**
- cuadrícula, isométrica y perspectiva de 1, 2 o 3 puntos de fuga, con dibujo asistido;
- simetría vertical, horizontal, por cuadrantes o radial (de 2 a 16 segmentos), en espejo o rotacional.

**Lienzo:**
- recortar y cambiar tamaño;
- voltear el lienzo o solo la vista;
- fondo transparente;
- ventana de referencia (con el lienzo o una imagen, y cuentagotas sobre ella);
- texto (8 tipos de letra) e insertar fotos;
- pantalla externa (Presentation).

**Animación:** fotogramas (capas o grupos de primer nivel), papel cebolla, primer fotograma como fondo y último
como primer plano. Reproducción en bucle, ida y vuelta o una vez. Exporta a GIF y MP4.

**3D:** esfera, cubo, cilindro, toro, plano u OBJ con UV. Se pinta sobre el modelo (cámara orbital con dos dedos)
o sobre la textura.

**Archivos:** PNG, JPEG, PSD (capas, grupos y máscaras), GIF y MP4. Time-lapse grabado automáticamente, que se
puede reproducir y exportar en MP4. Se pueden importar imágenes como lienzo nuevo y archivos `.procreate`
(experimental).

## Limitaciones conocidas

- **Latencia:** se usa predicción del trazo, no renderizado en el *front buffer* (`GLFrontBufferedRenderer`).
- **Importar `.procreate` y `.brush`:** el formato está deducido de la documentación de la comunidad y no se ha
  probado con archivos reales. El descompresor LZO solo se ha probado con secuencias hechas a mano. Si algo
  sale mal, aquí sí tendría sentido usar REA (`morluto/rea`) con un archivo tuyo.
- **Grupos:** sin anidar. Un fotograma de animación no tiene duración propia.
- **3D:** el pincel trabaja en el espacio de la textura, así que el tamaño varía con la densidad de UV, y en las
  costuras el trazo salta.

## Versiones y actualizaciones (OTA)

- Publicar una versión: `git tag v0.2.0 && git push origin v0.2.0`. El workflow *Release* compila el APK firmado
  y crea el Release de GitHub `trazo-0.2.0.apk`.
- **Obtainium** sigue `https://github.com/ikerlorente11/trazo`. La app también avisa en la galería
  («Trazo X disponible → Actualizar»): descarga el APK y lo instala ella misma (la primera vez pide permiso para
  instalar apps).
- La clave de firma está en `%USERPROFILE%\.android-keys\trazo-release.jks` (contraseña en `keystore.properties`,
  fuera de git). En GitHub está en los secrets `TRAZO_KEYSTORE_B64` y `TRAZO_KEYSTORE_PASSWORD`. Si se pierde,
  las actualizaciones dejan de instalarse encima: hay que guardarla.
