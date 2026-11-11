# El prompt del sistema, fuera de la APK

Esta versión de Mika lee el prompt del sistema de una carpeta del teléfono:

```
/sdcard/Mika/prompts
```

Lo que hay en esa carpeta sustituye, archivo por archivo, a lo que va dentro de la
APK. Un archivo que no esté en la carpeta sale de la APK: vaciar la carpeta o quitar
el permiso devuelve el comportamiento original sin recompilar nada.

## Permiso

La carpeta está fuera del espacio privado de la app, así que hace falta «Acceso a todos
los archivos». Mika lo declara en el manifiesto y no hace nada mientras no se conceda:
sin permiso, todos los prompts salen de la APK.

Ajustes › Avanzado y otros › Opciones de desarrollador: la fila «Prompt del sistema»
enseña la ruta, cuántos archivos hay, cuántos has tocado tú y cuántos se ignoran por no
valer. Los tres números se cuentan al abrir esa página (`PromptFolder.inspect()`), no al
arrancar: leer el árbol entero en el arranque se nota en el tiempo de apertura. Si falta el permiso, al tocar la
fila se abre la pantalla del sistema para concederlo; si ya está, vuelve a alinear la
carpeta (útil después de actualizar).

Ojo: la carpeta está fuera del sandbox de la app. Cualquier app con «acceso a todos los
archivos» puede escribir ahí, y lo que hay ahí es el prompt del sistema. Es el precio de
poder editarlo desde el teléfono sin recompilar.

## Qué hay en la carpeta

El árbol es una copia del que va en la APK (`core/src/main/resources/prompts/`):

| carpeta | qué es |
| --- | --- |
| `system/` | identidad de Mika y principios generales |
| `capabilities/` | una sección por grupo de herramientas |
| `tools/` | la descripción de cada herramienta |
| `entries/` | la descripción corta de las herramientas de entrada |
| `feedback/` | lo que el modelo recibe como resultado o aviso |
| `roles/` | los roles internos (verificador, memoria, título…) |

Junto a ellas, LEEME.txt explica lo mismo en el propio teléfono y `.seed` es el
manifiesto que usa la app para saber qué has cambiado. No hace falta tocar `.seed`.

## Qué se lee y cuándo

- `Prompts` mira primero el archivo de la carpeta; si no está, si está vacío, si trae
  un `{{placeholder}}` que la app no rellena o si es un `feedback/` al que le falta una
  clave, usa el de la APK. Los archivos descartados se pueden consultar desde
  `Prompts.rejections()`.
- `system/` y `capabilities/` se leen en cada tarea: guardar el archivo basta.
- `tools/`, `entries/`, `feedback/` y `roles/` se leen una vez por proceso: hay que
  reiniciar Mika para ver esos cambios.

## Actualizaciones de la app

`PromptLibrary` compara la versión del manifiesto con la del APK: número de versión, hash
del commit y una marca del APK instalado (tamaño y fecha del archivo). Lo último hace
falta porque al recompilar sin commitear el hash no cambia y la carpeta se quedaría con
los prompts viejos. Cuando la versión cambia:

- los archivos que no has tocado se sustituyen por la versión nueva;
- los que has tocado se quedan como están, marcados como editados, y ya no se
  vuelven a sobrescribir en futuras actualizaciones (si los devuelves al texto de la
  app, vuelven a ser «no tocados» y siguen actualizándose solos);
- los que hayas borrado no se vuelven a poner (borrar = «usa el de la APK»).

`PromptLibrary.scan()` es lo que cuenta la fila de ajustes: recorre el manifiesto, mira
qué archivos siguen ahí, cuáles ya no coinciden con lo sembrado y pasa esos por
`Prompts` para quedarse con los motivos de los que se ignoran.

Si cambia tanto el prompt que un archivo editado se queda inservible, se borra y se
deja que la app lo vuelva a poner, o se toca «Prompt del sistema» en los ajustes.

## Dónde está el código

- `core/src/main/kotlin/com/wangzan101/mika/core/prompt/Prompts.kt`: lectura de cada
  prompt (siempre desde disco cuando hay carpeta) y rechazo de los que no valen.
- `core/src/main/kotlin/com/wangzan101/mika/core/prompt/PromptLibrary.kt`:
  sincronización de la carpeta, manifiesto, LEEME.txt y `scan`.
- `app/src/main/java/com/wangzan101/mika/prompt/PromptFolder.kt`: ruta, permiso,
  lectura de los prompts que lleva la APK y arranque (lo llama `MikaApp` al crear el
  proceso).
- `app/src/main/java/com/wangzan101/mika/ui/DeveloperOptionsScreen.kt`: la fila de los
  ajustes.

## Compilar

Se necesita un JDK 17 aparte del del sistema:

```
export JAVA_HOME=~/jdk17/jdk-17.0.20+8
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assemblePerf
```

Sale firmado con la clave de depuración de esta máquina, así que la firma no coincide
con la del Mika oficial: hay que desinstalar el oficial antes de instalar este (se
pierden los datos de la app, no los de la carpeta de prompts, que está fuera).
