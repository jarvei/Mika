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
enseña la ruta, cuántos archivos se están usando y cuántos has tocado tú. Si falta el
permiso, al tocarla se abre la pantalla del sistema para concederlo; si ya está, vuelve
a alinear la carpeta (útil después de actualizar).

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

`PromptLibrary` compara la versión del manifiesto con la del APK (número de versión y
hash del commit, el mismo que sale en «Acerca de»). Cuando cambian:

- los archivos que no has tocado se sustituyen por la versión nueva;
- los que has tocado se quedan como están, marcados como editados, y ya no se
  vuelven a sobrescribir en futuras actualizaciones;
- los que hayas borrado no se vuelven a poner (borrar = «usa el de la APK»).

Si cambia tanto el prompt que un archivo editado se queda inservible, se borra y se
deja que la app lo vuelva a poner, o se toca «Prompt del sistema» en los ajustes.

## Dónde está el código

- `core/src/main/kotlin/com/wangzan101/mika/core/prompt/Prompts.kt`: lectura de cada
  prompt, caché por archivo y rechazo de los que no valen.
- `core/src/main/kotlin/com/wangzan101/mika/core/prompt/PromptLibrary.kt`:
  sincronización de la carpeta, manifiesto y LEEME.txt.
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
