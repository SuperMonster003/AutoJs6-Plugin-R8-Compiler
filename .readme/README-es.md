<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <p><img src="https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/res/mipmap/ic_launcher.png?raw=true" alt="R8 Compiler icon" width="128" /></p>
  <p>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases"><img alt="Release" src="https://img.shields.io/github/v/release/SuperMonster003/AutoJs6-Plugin-R8-Compiler?label=Release" /></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/issues"><img alt="Issues" src="https://img.shields.io/github/issues/SuperMonster003/AutoJs6-Plugin-R8-Compiler?label=Issues" /></a>
    <a href="https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/LICENSE"><img alt="License" src="https://img.shields.io/github/license/SuperMonster003/AutoJs6-Plugin-R8-Compiler?label=License" /></a>
  </p>

  <p>Plugin compilador R8 independiente para AutoJs6. Compila los JAR de script a DEX con el perfil release completo (shrink + optimize + obfuscate) en un proceso aislado</p>

  <p><sub>Etapa actual: prelanzamiento privado (el código fuente y los instaladores aún no son públicos)</sub></p>
</div>

******

### Idiomas

******

El archivo README.md está disponible actualmente en los siguientes idiomas:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-fr.md)
- Español [es] # actual
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ar.md)

******

### Introducción

******

Los scripts de AutoJs6 pueden cargar un JAR con `runtime.loadJar()` y llamar a las clases Java que contiene. Esa vía predeterminada usa D8 para una compilación simple de JAR a DEX, sin reducción ni ofuscación. Cuando quieres que el resultado pase por un tratamiento release completo -- eliminación de código muerto (shrinking), optimización de bytecode y ofuscación de identificadores -- necesitas R8.

Este plugin es una aplicación instalada por separado que ejecuta una versión fijada del compilador Google R8 en su propio proceso aislado, ofreciendo a AutoJs6 un servicio explícito de compilación release completa. Los scripts inician la compilación mediante la entrada dedicada `runtime.loadJarWithR8()` y deben aportar reglas keep; AutoJs6 recupera cinco artefactos (DEX, mapping y más), verifica cada uno, los almacena en caché y solo carga el DEX verificado.

La mayor diferencia con la vía integrada: la entrada R8 es explícita y sin respaldo. Una compilación fallida nunca cambia silenciosamente a D8/dx; el error se devuelve al script tal cual. Esto garantiza un invariante simple: si la carga tiene éxito, el artefacto pasó por el procesamiento R8 completo.

Buenas razones para instalar este plugin: necesitas reducir u ofuscar un artefacto JAR; necesitas archivos mapping para desofuscar trazas de pila más adelante; o quieres una semántica de compilación totalmente determinista (o R8 completo, o un fallo claro).

******

### Cómo funciona

******

Con el plugin habilitado, una llamada a `runtime.loadJarWithR8()` pasa aproximadamente por los siguientes pasos:

```text
1. script     calls runtime.loadJarWithR8(program, keepRules[, classpath[, consumerRules, ordinals]])
2. AutoJs6    snapshots program, classpath and rule files into one canonical path-free input bundle
3. plugin     re-verifies the bundle, then runs pinned R8 (full release profile) in its private ":r8" process
4. plugin     streams back one bundle with five artifacts: DEX ZIP, mapping, seeds, usage, retrace metadata
5. AutoJs6    re-validates every artifact, commits an R8-only cache generation, and loads the verified DEX ZIP
*  no fallback: any failure terminates the call as an R8 error; D8/dx is never used silently
```

El plugin solo es responsable de los pasos 3 y 4 -- la compilación en sí; la captura y congelación de la entrada, la validación de artefactos, el almacenamiento en caché y la carga final de clases siempre los realiza AutoJs6. Las dos partes solo intercambian descriptores de archivo por Binder, ninguna ruta de archivo cruza jamás el canal, y el plugin no puede leer tu directorio de scripts. Los resultados se almacenan en caché por contenido de entrada y parámetros de compilación en un dominio de caché R8 dedicado; las cargas repetidas de la misma entrada aciertan directamente en la caché, los datos de caché corruptos se desalojan y recompilan automáticamente, y cada artefacto abierto desde la caché se vuelve a comprobar con hash primero.

******

### Funciones

******

- Compilación release completa: shrinking (eliminación de código muerto), optimización y ofuscación siempre están todas habilitadas, realizadas por R8 8.13.17 fijado.
- Semántica explícita sin respaldo silencioso: solo `runtime.loadJarWithR8()` usa este plugin; cada fallo termina como error R8 y nunca cambia silenciosamente a D8/dx. `runtime.loadJar()` y `runtime.loadJarWithClasspath()` permanecen totalmente sin cambios.
- Cinco artefactos en un solo viaje: DEX ZIP, mapping (mapa de ofuscación), seeds (elementos conservados), usage (elementos eliminados) y metadatos de retrace, cada uno vinculado a un SHA-256 y verificado de nuevo de forma independiente por el host.
- La compilación se ejecuta en el proceso `:r8` propio del plugin y en un espacio de trabajo privado, aislados de AutoJs6; la entrada se revalida por completo antes de ejecutar R8.
- Admite JAR de classpath ordenados en tiempo de compilación y reglas consumer vinculadas a su JAR de classpath propietario; las reglas keep deben proporcionarse explícitamente, y las reglas nunca se descubren implícitamente dentro de los archivos.
- Incluye una biblioteca de plataforma Android 36 verificada byte a byte como biblioteca del compilador, en lugar de depender de los JAR del boot classpath del dispositivo, posiblemente recortados; `minApi` 24 a 36 está totalmente cubierto por un corpus R8 real.
- Compatible con Android 7.0 (API 24) y superior; verificado en dispositivos físicos y emuladores API 25/28/37 (incluido un dispositivo con páginas de 16 KiB) con ejecución ART real, llamadas JNI y restauración con Retrace.
- Solo se comunica con un AutoJs6 de la misma firma (protegido por el permiso `org.autojs.permission.PLUGIN`) y no solicita permisos de red ni de almacenamiento.

******

### Relación con el plugin DEX Compiler

******

El ecosistema AutoJs6 tiene dos plugins compiladores independientes. Se complementan, no se superponen y pueden instalarse a la vez:

- [AutoJs6-Plugin-DEX-Compiler](https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler) sirve la vía predeterminada `runtime.loadJar()` / `runtime.loadJarWithClasspath()`: compilación simple con un D8 más reciente, sin reducción ni ofuscación; cuando el plugin falla, el host recurre automáticamente a su compilador integrado.
- Este plugin (R8) sirve solo la vía explícita `runtime.loadJarWithR8()`: compilación release completa con reglas keep obligatorias; un fallo es un fallo, sin respaldo.

Ambos usan protocolos totalmente independientes (`dex-compiler-api` frente a `r8-compiler-api`), acciones de servicio, entradas de opciones de desarrollador y dominios de caché; ninguno depende del otro ni lo conoce. El modo `RELEASE` del protocolo DEX es solo el modo de compilación release de D8 y no tiene nada que ver con R8. Instalar o desinstalar cualquiera de los dos nunca afecta al otro.

******

### Instalación y uso

******

Habilitar el plugin lleva tres pasos: instalar un AutoJs6 emparejado que contenga la integración R8, instalar el APK de este plugin y, después, seleccionar manualmente el plugin en las opciones de desarrollador de AutoJs6. Dos cosas que conviene saber de antemano:

- El plugin está inerte por defecto. Instalarlo sin más no cambia nada en AutoJs6; mientras no esté habilitado, `runtime.loadJarWithR8()` simplemente falla de forma segura (fail-closed) en lugar de usar otro compilador.
- Siempre es reversible. Deshabilita la entrada en las opciones de desarrollador para restaurar el estado anterior; no hace falta desinstalar nada.

#### Requisitos previos

- Un AutoJs6 que contenga la integración de `runtime.loadJarWithR8()` (build representativo verificado: AutoJs6 6.8.0 (build 5276)); los hosts más antiguos no tienen ni la entrada ni el elemento correspondiente de opciones de desarrollador.
- El host y el plugin deben provenir de la misma fuente de confianza y llevar la misma firma; con firmas distintas el plugin no puede seleccionarse -- usa paquetes de instalación publicados (o compilados) en pareja.
- El plugin está actualmente en fase de prelanzamiento privado; los instaladores provienen del prelanzamiento privado de GitHub o de una compilación local. Consíguelos junto con el host emparejado.
- Si compilas por tu cuenta, mantén sin cambios el paquete y el componente de servicio fijos indicados abajo.

Los identificadores relevantes son:

```text
host package: org.autojs.autojs6
plugin package: io.github.supermonster003.autojs6.plugin.r8compiler
paired host: AutoJs6 6.8.0 (build 5276)
exact component: io.github.supermonster003.autojs6.plugin.r8compiler/io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService
```

#### Instalar y habilitar

1. Instala o actualiza a un AutoJs6 emparejado que contenga la integración R8.
2. Instala el APK de este plugin.
3. Abre AutoJs6, ve a Ajustes > Acerca de la aplicación y el desarrollador, y mantén pulsado el icono de la aplicación para entrar en las opciones de desarrollador.
4. Entra en R8 compiler > Explicit full-release R8 provider.
5. Selecciona el componente de servicio de este plugin (el exact component mostrado arriba) y confirma.

Insistimos: instalar por sí solo nunca habilita el plugin, y AutoJs6 nunca selecciona automáticamente ningún provider que descubra; hasta que se haga la selección, `runtime.loadJarWithR8()` siempre termina en fallo.

#### Confirmar que está activo

De vuelta en la página de opciones de desarrollador, la configuración tuvo éxito cuando el resumen de Explicit full-release R8 provider dice que `runtime.loadJarWithR8` usa el componente de este plugin; un resumen que contenga "disabled" o "fails closed" significa que la entrada sigue apagada.

Si el plugin no aparece en la lista, comprueba en orden: que el host sea un build emparejado con la integración R8; que los nombres de paquete del host y del plugin coincidan con los identificadores de arriba; que la aplicación del plugin no esté deshabilitada por el sistema; que ambas firmas coincidan.

A diferencia del plugin DEX, esta entrada no tiene la ambigüedad de "quién compiló realmente": siempre que `runtime.loadJarWithR8()` retorna con éxito, el artefacto pasó necesariamente por el procesamiento R8 completo (en esta llamada o en una generación de caché ya verificada).

#### Ejemplo de script

Coloca un JAR con archivos `.class` de JVM y un archivo de reglas keep en tu directorio de scripts, y llama a cualquier sobrecarga de la familia de entradas. Las reglas keep no son opcionales: R8 elimina y ofusca cada símbolo no conservado por una regla, así que una compilación sin reglas casi con seguridad produce clases a las que ya no se puede acceder por su nombre original.

```javascript
"use strict";

const program = files.path("./lib/example.jar");
const keepRules = files.path("./lib/keep-rules.pro");

// keep-rules.pro (UTF-8), e.g.:
//   -keep class com.example.autojs6.R8PluginExample { public *; }

runtime.loadJarWithR8(program, [keepRules]);

// Replace this with a public class that actually exists in example.jar
// and is kept by your keep rules.
const Example = Packages.com.example.autojs6.R8PluginExample;
console.log("R8 compiler example: " + Example.answer());
```

Cuando el JAR del programa referencia clases de tiempo de compilación que no están dentro de él (stubs de API, por ejemplo), usa la sobrecarga de tres argumentos con un classpath ordenado:

```javascript
runtime.loadJarWithR8(
    files.path("./lib/program.jar"),
    [files.path("./lib/keep-rules.pro")],
    [files.path("./lib/compile-api-stubs.jar")],
);
```

La sobrecarga de cinco argumentos acepta además archivos de reglas consumer y sus ordinales propietarios; cada archivo de reglas consumer se vincula por ordinal al JAR de classpath correspondiente:

```javascript
runtime.loadJarWithR8(program, keepRuleFiles, classpathJars, consumerRuleFiles, ownerOrdinals);
```

Puntos clave:

- Los JAR de classpath solo se usan para resolver referencias en tiempo de compilación; ni se empaquetan en la salida ni se cargan automáticamente. Su orden importa y forma parte de la identidad de caché.
- Las reglas keep y consumer deben ser texto UTF-8 estricto; las reglas que contengan acceso al sistema de archivos, include, redirección de entrada/salida, diccionario y directivas peligrosas similares se rechazan de plano (fail-closed).
- El mapping y los demás artefactos se verifican y almacenan actualmente en la caché privada del host; todavía no hay una entrada de exportación para scripts (ver ROADMAP).
- Compilar no es una revisión de seguridad; carga solo JAR en los que confíes.

#### Guía de reglas keep

Para recetas mínimas sobre acceso con `Packages`, reflexión, JNI, serialización y una API pública, consulta la [guía práctica de reglas keep](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/keep-rules-guide.md).

#### Qué ocurre cuando falla

La semántica de fallo de esta entrada es deliberadamente simple: o consigues los artefactos R8 completos o un error -- nada intermedio.

- Sin provider seleccionado, argumentos inválidos, provider no disponible u ocupado (BUSY; solo una sesión de compilación a la vez), fallo de compilación, tiempo agotado (120 s por defecto, 300 s como máximo), fallo de verificación de artefactos, fallo de caché o de carga -- todos terminan la llamada del script como error R8, nunca cambiando a D8/dx.
- Tu propia cancelación (por ejemplo, detener el script) termina la llamada de inmediato e igualmente no activa ningún respaldo.
- Un acierto de caché no cambia la semántica: los artefactos en caché se verificaron por completo al escribirse y se vuelven a comprobar con hash en cada lectura.

Si de verdad quieres "recurrir a una compilación simple en caso de fallo", captura el error en tu script y llama a `runtime.loadJar()` explícitamente; el host no tomará esa decisión por ti.

#### Diagnóstico y reporte

Las causas más comunes, en orden: ningún provider seleccionado en las opciones de desarrollador; reglas rechazadas (directivas prohibidas o codificación no UTF-8); entrada que supera un límite de recursos; un nombre de clase mal escrito o no cubierto por las reglas keep (R8 ha ofuscado o eliminado los símbolos no conservados). Al informar de un problema, adjunta en lo posible:

- Versión y build de AutoJs6, versión del plugin y el nombre completo del componente del resumen de opciones de desarrollador.
- Modelo del dispositivo, versión de Android (API) y arquitectura de CPU (ABI).
- El JAR del programa que lo desencadena y todos los archivos de reglas (o sus tamaños en bytes y resúmenes SHA-256), la excepción completa del script y los pasos de reproducción.

Si sabes usar ADB, los siguientes comandos recogen los registros relevantes (sustituye `<serial>` por el número de serie de tu dispositivo; elimina rutas privadas y contenido sensible antes de compartir):

```powershell
adb -s <serial> shell dumpsys package org.autojs.autojs6
adb -s <serial> shell dumpsys package io.github.supermonster003.autojs6.plugin.r8compiler
adb -s <serial> logcat -d -v threadtime AndroidClassLoader:D AndroidRuntime:E *:S
```

#### Deshabilitar, revertir y desinstalar

- Deshabilitar temporalmente: apaga la entrada en Explicit full-release R8 provider dentro de las opciones de desarrollador y confirma. `runtime.loadJarWithR8()` vuelve a fallar de forma segura, nada más cambia, y la selección de componente conservada puede reactivarse en cualquier momento.
- Desinstalar el plugin: deshabilita primero la entrada, luego detén AutoJs6 y desinstala el APK del plugin. Desinstalar elimina todos los datos y archivos temporales propios del plugin.
- Tras una reinstalación o actualización, el host revalida la identidad del componente (incluidos UID y firma), por lo que la selección debe confirmarse de nuevo en las opciones de desarrollador.

******

### Preguntas frecuentes

******

**P: ¿Por qué son obligatorias las reglas keep?**

R: El perfil full-release de R8 elimina y ofusca cada símbolo que no se conserve explícitamente. Los scripts acceden a las clases por reflexión mediante `Packages.xxx`, así que R8 no puede inferir qué símbolos deben sobrevivir; por eso el protocolo hace las reglas keep explícitamente obligatorias, evitando la trampa silenciosa de "compiló bien, clase no encontrada".

**P: ¿Es más rápido que el compilador integrado o que el plugin DEX?**

R: No -- normalmente más lento. R8 realiza un análisis de programa completo (shrink/optimize/obfuscate), inherentemente más costoso que una compilación D8 simple; a cambio obtienes un artefacto más pequeño y difícil de revertir, más un archivo mapping. Los resultados se almacenan en caché, así que las cargas posteriores de la misma entrada son rápidas.

**P: ¿Puede sustituir al plugin DEX Compiler (o viceversa)?**

R: No. Sirven entradas de script distintas con protocolos y cachés independientes; ver "Relación con el plugin DEX Compiler".

**P: ¿Por qué no hay respaldo automático a D8 cuando falla?**

R: Es intencionado. Llamar a la entrada R8 declara "necesito un artefacto release completo"; un respaldo silencioso te entregaría un artefacto sin ofuscar sin que lo supieras. Si quieres semántica de respaldo, captura el error en tu script y llama tú mismo a `runtime.loadJar()`.

**P: ¿Cómo desofusco una traza de pila?**

R: Cada compilación produce mapping y metadatos de retrace, que el host verifica y almacena en caché; la versión actual aún no tiene una entrada de script o de interfaz para obtener el mapping, y un RPC de retrace está en la hoja de ruta. Cuando compilas el artefacto tú mismo, usa la herramienta retrace de R8 con el mapping que guardaste para restaurar las pilas.

**P: ¿El plugin accede a la red o lee mis archivos?**

R: No. No tiene permisos de red ni de almacenamiento, lee la entrada de compilación solo de los descriptores de archivo que le entrega AutoJs6, nunca ve rutas de archivo en el canal y mantiene los archivos temporales estrictamente dentro de su propio directorio privado.

**P: ¿Qué hace la interfaz del lanzador?**

R: La pantalla de solo lectura del plugin muestra su versión, la versión fijada de R8, la disponibilidad del componente de servicio y el registro de cambios incluido. No activa el proveedor; la selección y activación siguen estando exclusivamente en las opciones de desarrollador de AutoJs6.

******

### Límites del alcance

******

Para evitar malentendidos, lo siguiente queda explícitamente fuera del alcance de este plugin:

- Sirve solo a `runtime.loadJarWithR8()`; nunca cambia `runtime.loadJar()` ni `runtime.loadJarWithClasspath()`, y esas entradas tampoco pueden seleccionarlo implícitamente.
- Sin modo D8, sin compilación debug y sin interruptores individuales para shrink/optimize/obfuscate: el perfil está fijado en FULL_RELEASE.
- Sin descubrimiento implícito de reglas dentro de los JAR (como archivos proguard bajo META-INF); las reglas keep y consumer deben proporcionarse explícitamente con la petición.
- Las reglas con acceso al sistema de archivos, include, redirección de entrada/salida, importación de mapping, print, diccionario o directivas de control global de perfil se rechazan (fuera del protocolo 1.0, fail-closed).
- Sin RPC de retrace; los metadatos de retrace son solo procedencia del mapping, y el mapping aún no tiene exportación para scripts (ambos en la ROADMAP).
- Sin descarga ni resolución de dependencias (sin integración Maven/Gradle), sin compilación por red.
- Sin manejo de `.aar`, `.dex` precompilados ni bytecode dinámico de `defineClass()`; esos siempre van por las vías integradas de AutoJs6.
- Por ahora solo distribución privada: el código fuente y los instaladores viven en un repositorio privado de GitHub; la publicación pública es un elemento aparte de la hoja de ruta.

******

### Referencia técnica

******

Lo siguiente está dirigido a desarrolladores e integradores que necesitan límites exactos; los usuarios normales del plugin pueden saltárselo.

#### Entrada y salida

El protocolo 1.0 recibe un bundle de entrada canónico a través de un descriptor de solo lectura y devuelve un bundle de artefactos canónico a través de un descriptor de solo escritura; ninguna ruta de archivo cruza jamás el canal, y toda la entrada más cada artefacto quedan vinculados a un SHA-256:

```text
input: 1 program JAR + ordered classpath JARs + explicit keep rules + optional consumer rules
output: DEX_ZIP + MAPPING_TEXT + SEEDS_TEXT + USAGE_TEXT + RETRACE_METADATA
compiler: R8 8.13.17
profile: FULL_RELEASE (shrink + optimize + obfuscate)
```

#### Identificadores de descubrimiento del plugin

El host descubre y llama al plugin mediante los siguientes identificadores:

```text
service action: org.autojs.plugin.R8_COMPILER
plugin id: r8-compiler
protocol provider id: autojs6-r8
engine: r8-compiler
variant: r8
protocol: 1.0–1.1
api namespace: org.autojs.plugin.r8compiler.api
distribution: org.autojs.plugin.r8compiler:r8-compiler-api:0.2.0
cache domain: autojs6:r8-compiler:v1
```

El plugin declara R8 8.13.17, el protocolo 1.0, el perfil fijo FULL_RELEASE, `minApi` 24 a 36, salida multi-dex y el conjunto de capacidades de cinco artefactos. La biblioteca del compilador es una biblioteca de plataforma Android 36 incluida y verificada byte a byte; la huella de la runtime-library sigue vinculada a los archivos del boot classpath observados en el dispositivo.

El plugin no contiene bibliotecas nativas y cubre todas las ABI de dispositivos con un único APK universal de JVM pura; las llamadas JNI verificadas apuntan a métodos nativos dentro de los JAR compilados, no al plugin en sí.

#### Modelo de seguridad

El plugin no solicita permisos de red ni de almacenamiento. El servicio de compilación está protegido por el permiso `org.autojs.permission.PLUGIN` y se ejecuta en el proceso dedicado `:r8`; cada llamada verifica el nombre de paquete, el UID del llamante y ambas firmas en ambos sentidos, aceptando solo el host AutoJs6 de la misma firma. La entrada y la salida viajan exclusivamente como descriptores de archivo; se rechazan los descriptores con modos de acceso incorrectos o cuya entrada/salida apunte al mismo extremo. Los archivos temporales permanecen dentro del espacio de trabajo privado del plugin, y los espacios obsoletos se recuperan automáticamente. El host también vuelve a verificar cada artefacto de forma independiente, y el cargador de clases solo acepta una copia DEX de solo lectura verificada de nuevo con hash.

#### Límites de recursos

Para defenderse de entradas maliciosas o anómalas, el protocolo fija límites estrictos en cada etapa; las peticiones que los superan se rechazan de plano:

- JAR del programa: hasta 128 MiB; classpath: hasta 32 JAR, 64 MiB cada uno, 128 MiB en total.
- Archivos de reglas: hasta 16 keep y 32 consumer; 256 KiB por archivo, 2 MiB de reglas en total, 16 KiB por línea.
- Bundle de entrada completo hasta 260 MiB; expansión de archivos: hasta 20000 entradas por JAR y 60000 en total, 512 MiB descomprimidos; datos de clases hasta 8 MiB por clase y 256 MiB en total.
- Bundle de salida hasta 256 MiB: DEX ZIP hasta 192 MiB, mapping hasta 32 MiB, seeds y usage hasta 16 MiB cada uno, metadatos de retrace hasta 256 KiB.
- Concurrencia: exactamente una sesión de compilación a la vez; las demás peticiones reciben un BUSY reintentable. Tiempo límite por defecto de 120 s con techo de 300 s.
- Los diagnósticos están limitados a 64 KiB y con las rutas censuradas.

#### Advertencias

- `minApi` es un parámetro del compilador, no una declaración de ejecución del dispositivo; un artefacto no puede cargarse en dispositivos por debajo de su `minApi`.
- La versión de R8 está fijada (actualmente R8 8.13.17); la identidad de caché incluye las huellas del compilador y del runtime, así que una actualización del compilador nunca reutiliza resultados obsoletos.
- La cancelación impide de inmediato la publicación del resultado, pero el trabajo interno de CPU de R8 puede continuar dentro del proceso aislado hasta que la compilación retorne; la ranura de sesión permanece BUSY hasta que termina la limpieza.
- La misma entrada se reproduce byte a byte bajo la misma versión del compilador (los gates de release verificaron la reproducibilidad en el mismo entorno), pero no se promete identidad a nivel de byte entre versiones de R8.
- La versión de R8 incluida por AGP que muestra el banner de compilación del host pertenece a la cadena de empaquetado de APK y no guarda relación con la versión del compilador de este plugin.

******

### Hoja de ruta de desarrollo

******

El desarrollo avanza mediante gates verificables: G1 congelación del contrato, G2 implementación del provider, G3 integración con el host, G4 corpus de compatibilidad, G5 release local firmada, G6 aceptación en dispositivo, G7 cierre de ART/JNI/Retrace y G8 release remota privada están todos completos, cada uno con evidencia revisable vinculada a SHA-256. Los planes futuros (publicación pública, disponibilidad de retrace, documentación y UX, mantenimiento de actualizaciones del motor) y la definición de terminado de cada elemento están en:

- [Abrir el ROADMAP.md verificable](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/ROADMAP.md)

******

### Historial de versiones

******

# v0.2.2

###### 2026/09/15

* `Mejora` compileSdk y targetSdk suben a 37 (Android 17); el comportamiento del plugin no depende del nuevo objetivo

# v0.2.1

###### 2026/09/13

* `Mejora` Recursos traducidos coherentes, activación explícita del complemento y validación de los paquetes de publicación

# v0.2.0

###### 2026/09/13

* `Mejora` La verificación de compilación rechaza dependencias nativas accidentales y genera un informe JSON
* `Mejora` Recursos traducidos coherentes, activación explícita del complemento y validación de los paquetes de publicación

##### Más versiones

* [CHANGELOG-es.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/assets/doc/CHANGELOG-es.md)

******

### Compilación

******

```powershell
.\gradlew.bat :app:assembleDebug
```

Compilación de versión:

```powershell
.\gradlew.bat :app:assembleRelease
```

Compilar requiere JDK 17 o posterior (se recomienda 21) y Android SDK Platform 36: el script de compilación verifica byte a byte `platforms/android-36/android.jar` y lo incluye como recurso de biblioteca del compilador, abortando ante cualquier discrepancia. El minSdk actual es 24 y el targetSdk es 37.

La ABI del protocolo proviene de los AAR de contrato 0.1.0 congelados dentro del repositorio (bajo `plugin-api/r8-compiler-api/releases/0.1.0/`); la aplicación consume esos bytes de AAR en lugar de sus proyectos fuente:

```text
protocol-wire-api-0.1.0.aar
r8-compiler-api-0.2.0.aar
```

El compilador se obtiene de Maven como R8 8.13.17 fijado. Las releases oficiales usan los scripts de publicación y verificación bajo `scripts/` (directorio de release local append-only, compilaciones reproducibles de dos instantáneas y verificación por gate); para la depuración diaria bastan los comandos de Gradle de arriba.

******

### Licencia

******

El código fuente del proyecto está bajo licencia MPL-2.0. R8 y los demás componentes de terceros permanecen bajo sus propias licencias.

******

### Estructura de recursos

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
```

`.python/generate_markdown.py` genera el README y el CHANGELOG en los 10 idiomas (incluidos el README.md y el CHANGELOG.md de la raíz del repositorio) a partir de fuentes JSON; para cambiar la documentación, edita las fuentes JSON en lugar del Markdown generado.

Para comprobar que cada Markdown generado coincide con su fuente sin modificar el área de trabajo, ejecuta:

```powershell
python .python/generate_markdown.py --check
```

******

### Enlaces

******

- Documentación de AutoJs6: https://docs.autojs6.com
- Proyecto R8: https://r8.googlesource.com/r8
- Plugin DEX Compiler (D8): https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler
- Página de release privada (requiere acceso): https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases/tag/v0.1.0-provider-dev-private.1


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/16kb.md)
