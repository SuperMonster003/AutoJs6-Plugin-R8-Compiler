******

### Historial de versiones

******

# v0.2.0

###### 2026/09/11

* `Mejora` La verificación de compilación rechaza dependencias nativas accidentales y genera un informe JSON

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `Consejo` Versión actual. Publicada como prelanzamiento privado de GitHub (5 recursos: APK firmado, dos AAR de contrato congelados, manifiesto de release y SHA256SUMS, todos vueltos a descargar y verificados byte a byte); aún sin publicación pública
* `Consejo` Inerte por defecto tras la instalación; debe habilitarse manualmente en las opciones de desarrollador de AutoJs6 -- ver la sección "Instalación y uso" del README
* `Función` Incluir una biblioteca de plataforma Android 36 verificada byte a byte como biblioteca del compilador R8, en lugar de depender del boot classpath del dispositivo; corrige fallos de compilación en dispositivos cuyos JAR de boot son solo cascarones de recursos
* `Función` Añadir recolección de diagnósticos R8 acotada y con rutas censuradas, cubriendo el arranque del provider y los fallos de importación del motor
* `Mejora` Verificar la salida optimizada en ART en API 25/28/37 (incluido un emulador con páginas de 16 KiB): reflexión, nombres de clase compuestos en tiempo de ejecución, serialización, la entrada para scripts, comprobaciones del señuelo eliminado, llamadas JNI arm64/x86/x86_64, y restauración de pilas con R8 Retrace tras verificar el hash del mapping

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `Corrección` Sustituir la inspección del modo de acceso por `/proc/self/fdinfo` por sondas de kernel públicas `Os.read`/`Os.write` de cero bytes, resolviendo las restricciones de procfs de algunos dispositivos (como Sony API 28) y conservando el rechazo de alias con `Os.fstat`
* `Mejora` Completar la aceptación de dispositivo Binder/PFD entre APK en 1 dispositivo físico y 2 emuladores (API 25/28): camino feliz, ciclo de vida, entrada hostil y muerte de proceso -- 9/9 pruebas superadas

# v0.1.0-provider-dev (local.3)

###### 2026/08/25

* `Corrección` Fijar el desugaring core-library/NIO (desugar_jdk_libs_nio 2.1.5) para API 24 a 28, corrigiendo fallos de ejecución por capacidades ausentes de la biblioteca central de Java 11 en dispositivos antiguos

# v0.1.0-provider-dev (local.2)

###### 2026/08/25

* `Corrección` Eliminar la dependencia del script de publicación en el hash de autocarga de módulos de PowerShell, garantizando un flujo de firma reproducible independiente del entorno

# v0.1.0-provider-dev (local.1)

###### 2026/08/25

* `Consejo` Primera release local firmada (generación bootstrap); compilación reproducible con dos instantáneas sin conexión idénticas byte a byte, estableciendo el directorio de release local append-only
* `Función` Plugin compilador R8 explícito para AutoJs6: los scripts solicitan una compilación release completa (shrinking + optimization + obfuscation) mediante `runtime.loadJarWithR8()`
* `Función` Una compilación devuelve cinco artefactos: DEX ZIP, mapping, seeds, usage y metadatos de retrace, cada uno vinculado a un SHA-256 y verificado de nuevo de forma independiente por el host
* `Función` Semántica sin respaldo: cada fallo termina como error R8 y nunca cambia silenciosamente a D8/dx; el host usa el dominio de caché R8 dedicado `autojs6:r8-compiler:v1`
* `Función` La compilación se ejecuta en el proceso `:r8` propio del plugin dentro de un sandbox privado, acepta solo llamantes AutoJs6 de la misma firma (protegidos por el permiso `org.autojs.permission.PLUGIN`) y no solicita permisos de red ni de almacenamiento
* `Función` Validación estricta de la entrada: bundle de entrada canónico sin rutas, reglas UTF-8 estrictas con directivas peligrosas en fail-closed, y límites estrictos en archivos, datos de clases y salidas
* `Función` Cobertura completa de `minApi` 24-36: un corpus de compatibilidad R8 real de 26 celdas Java/Kotlin con reflexión, nombres compuestos en tiempo de ejecución, JNI, serialización y comprobaciones del señuelo eliminado
* `Función` Implementación de JVM pura; un único APK universal cubre todas las arquitecturas de dispositivos
* `Dependencia` Incluir Google R8 8.13.17 (Maven `com.android.tools:r8`)

# v0.1.0 (contract)

###### 2026/08/14

* `Consejo` Congelación del contrato de protocolo sin aplicación ni comportamiento de ejecución; esta entrada registra el establecimiento de la frontera de la interfaz
* `Función` Congelar el protocolo independiente de compilación R8 1.0: espacio de nombres de API `org.autojs.plugin.r8compiler.api`, acción de descubrimiento `org.autojs.plugin.R8_COMPILER`, identidad de motor `r8-compiler`
* `Función` Congelar los formatos canónicos de bundles de flujo de entrada/artefactos, los tres descriptores AIDL y la ABI de JVM visible desde Java; publicar los AAR de contrato 0.1.0 (protocol-wire-api y r8-compiler-api) en modo append-only
