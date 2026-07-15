# Ocultar Fotos — App Android

App muy simple: un botón central.
- Primer toque: **oculta** todas las fotos y videos del teléfono (los mueve a una carpeta privada de la app que la Galería no puede ver).
- Segundo toque: **restaura** cada foto y video exactamente a su carpeta original.

## Cómo abrirlo y compilarlo

1. Instala [Android Studio](https://developer.android.com/studio) (gratis).
2. Abre Android Studio → **Open** → selecciona la carpeta `HideMediaApp` (esta carpeta).
3. Cuando te pida sincronizar/crear el Gradle Wrapper, acepta. Se descargarán las dependencias automáticamente (necesitas internet la primera vez).
4. Conecta tu teléfono Android por USB con la "Depuración USB" activada, o usa un emulador.
5. Pulsa **Run ▶** para instalar la app directamente en tu teléfono.

## Cómo obtener el archivo .apk (para instalar sin cable o compartirlo)

1. Con el proyecto abierto en Android Studio, ve al menú **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**.
2. Cuando termine, aparecerá una notificación abajo a la derecha con el enlace **"locate"** — te lleva directo al archivo.
3. El archivo estará en: `app/build/outputs/apk/debug/app-debug.apk`.
4. Copia ese `.apk` a tu teléfono (por cable, Drive, WhatsApp, etc.) y ábrelo para instalarlo. Puede que Android te pida activar "Instalar apps de fuentes desconocidas" la primera vez — es normal para apps fuera de la Play Store.

> Este `.apk` es de tipo "debug" (sin firma de producción), perfecto para uso personal. Si algún día quieres publicarla en la Play Store necesitarías firmarla y justificar el permiso de acceso a todos los archivos ante Google, así que está pensada para instalación directa, no para tienda.

## Primer uso en el teléfono

1. Al abrir la app, te pedirá el permiso **"Acceso a todos los archivos"**. Tócalo y actívalo en Ajustes (Android te llevará automáticamente a esa pantalla).
2. Vuelve a la app. Verás el botón azul en el centro.
3. Toca **"Ocultar fotos y videos"** → espera unos segundos (según cuántas fotos tengas) → listo, tu galería quedará vacía de fotos/videos.
4. Toca **"Mostrar fotos y videos"** → todo vuelve exactamente a donde estaba.

## Cómo funciona por dentro

Android no tiene un interruptor de "ocultar/mostrar" para fotos. Lo que hace la app es:
- Buscar, mediante `MediaStore`, todas las fotos y videos indexados por el sistema (cámara, WhatsApp, descargas, etc.) **en el instante en que tocas el botón**.
- **Mover** físicamente cada archivo a una carpeta privada de la app llamada `.ocultados` (dentro de `Android/data/com.hidemedia.app/...`), reforzada con un archivo `.nomedia` para que el sistema jamás la escanee ni la muestre en ninguna galería o explorador de archivos.
- Guardar en un pequeño archivo `manifest.json` la ruta original de cada archivo.
- Al pulsar de nuevo, mueve cada archivo de regreso a su ruta original usando ese manifiesto, y avisa al sistema para que vuelva a indexarlos.

## Fotos y videos nuevos mientras está oculto

La app **no vigila la galería de forma continua** — solo actúa en el momento exacto del toque. Esto significa que:
- Si tomas una foto nueva mientras el modo "oculto" está activo, esa foto se guarda normal en su carpeta de cámara de siempre y **aparece con normalidad en tu galería**.
- Al tocar "Mostrar fotos y videos", solo se restauran las que estaban en la lista original — las fotos nuevas no se ven afectadas en absoluto.

## Notas importantes

- **No se borra nada**: los archivos solo se mueven de carpeta; nunca se eliminan.
- El permiso "Acceso a todos los archivos" es un permiso sensible. Google Play exige justificación especial para publicar apps con este permiso, así que esta app está pensada para **uso personal** (instalación directa vía Android Studio o APK), no para subir a la Play Store tal cual.
- Si tienes muchísimas fotos (varios miles), el proceso puede tardar uno o dos minutos: mover archivos grandes (videos) lleva su tiempo.
- Si cierras la app o se reinicia el teléfono a mitad del proceso, simplemente vuelve a abrirla y pulsa el botón otra vez para completar la operación (el manifiesto no se pierde).
- Probado para minSdk 24 (Android 7) en adelante.
