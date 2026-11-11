# El prompt del sistema, fuera de la APK

Esta versión de Mika lee el prompt del sistema de una carpeta del teléfono:

```
/sdcard/Mika/prompts
```

Lo que hay en esa carpeta sustituye, archivo por archivo, a lo que va dentro de la
APK. Un archivo que no esté en la carpeta sale de la APK: vaciar la carpeta o quitar
el permiso devuelve el comportamiento original sin recompilar nada.

## Dónde está la carpeta

Hay dos ubicaciones y la app elige sola:

| permiso | carpeta | quién puede editarla |
| --- | --- | --- |
| «Acceso a todos los archivos» concedido | `/sdcard/Mika/prompts` | cualquier editor o gestor de archivos |
| sin conceder | `Android/data/<paquete>/files/prompts` | sólo Mika y los gestores que entran en Android/data |

Al conceder el permiso, lo que hubiera en la carpeta privada se traslada a la pública
(archivos y manifiesto) y se sigue desde ahí; al revocarlo se usa la privada, que puede
estar desactualizada. La ruta y el estado se ven en la página de ajustes de más abajo.

Ojo: la carpeta pública está fuera del sandbox de la app. Cualquier app con «acceso a
todos los archivos» puede escribir ahí, y lo que hay ahí es el prompt del sistema. Es el
precio de poder editarlo desde el teléfono sin recompilar.

## Ajustes › Prompt del sistema

Ajustes › Avanzado y otros › Opciones de desarrollador › Prompt del sistema
(`PromptScreen`), visible en las compilaciones de depuración y `perf`:

- la ruta que se está usando y si viene del permiso o de la carpeta privada;
- cuántos archivos hay, cuántos has tocado y cuántos se ignoran;
- la lista de los que no son los de la app, con el motivo del ignorado, y un botón para
  restaurar cada uno desde la APK;
- «Restaurar todos los ficheros»: borra la carpeta y la vuelve a sembrar, con confirmación.

Los números y la lista se calculan al abrir la página (`PromptFolder.inspect()` y
`PromptFolder.items()`), no al arrancar: recorrer el árbol entero en el arranque se nota.

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
- `app/src/main/java/com/wangzan101/mika/ui/PromptScreen.kt`: la página con la lista y los
  botones de restaurar.

## Compilar

Se necesita un JDK 17 aparte del del sistema:

```
export JAVA_HOME=~/jdk17/jdk-17.0.20+8
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assemblePerf
```

La firma es la del fork: `fork.keystore` con sus claves en `fork-signing.properties`, los
dos fuera de git. `app/build.gradle.kts` los lee si están y firma `perf` y `release` con esa
clave; sin el fichero, `perf` cae a la clave de depuración como antes. Con la misma clave en
otra máquina, `adb install -r` actualiza sin desinstalar.

La firma no coincide con la del Mika oficial, así que la primera instalación de este fork
sobre el oficial exige desinstalar (se van los datos de la app; la carpeta de prompts está
fuera y se queda).
