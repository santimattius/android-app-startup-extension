# Performance Analysis — `core/`

Análisis de problemas de performance, memory leaks, riesgo de ANR y buenas prácticas sobre el código de `core/`.

---

## Índice

1. [Crítico — Riesgo de ANR](#1-crítico--riesgo-de-anr)
2. [Memory Leaks](#2-memory-leaks)
3. [Bugs y Problemas de Correctness](#3-bugs-y-problemas-de-correctness)
4. [Best Practices](#4-best-practices)
5. [Resumen Priorizado](#5-resumen-priorizado)

---

## 1. Crítico — Riesgo de ANR

### 1.1 Sync initializers corren en Main Thread sin safeguard

**Archivo:** `InitializationProvider.kt:16` → `AppStartupInitializer.kt:387-404`

`InitializationProvider.onCreate()` corre en el Main Thread (es un `ContentProvider`). Llama directamente a `syncDiscoverAndInitialize()`, que ejecuta el chain completo de `doInitialize` de manera síncrona. Si cualquier `StartupSyncInitializer.create()` realiza I/O, accede a disco, o tarda más de 5 segundos → **ANR garantizado**.

La interface tiene `@MainThread` en `create()`, pero es una anotación documental, no un enforcement. No hay ningún timeout ni detección de trabajo pesado.

```kotlin
// InitializationProvider.kt:16
AppStartupInitializer.getInstance(context).discoverAndInitialize(javaClass)
// corre enteramente en el main thread
```

**Recomendación:** Añadir un `StrictMode.ThreadPolicy` check en debug builds para detectar I/O accidental dentro de sync initializers. Considerar documentar explícitamente que `create()` no debe realizar operaciones bloqueantes.

---

### 1.2 `getProviderInfo()` es una IPC call bloqueante en Main Thread

**Archivo:** `AppStartupInitializer.kt:199-201`

```kotlin
val providerInfo = applicationContext.packageManager
    .getProviderInfo(provider, GET_META_DATA)
```

`getProviderInfo()` realiza una binder call al `PackageManagerService`. En dispositivos con alto tráfico IPC al startup (muchas apps corriendo, baja memoria), esta llamada puede bloquearse por decenas o cientos de milisegundos. Es un punto de latencia invisible para quien usa la librería.

---

## 2. Memory Leaks

### 2.1 `metricsListener` puede capturar Activity en un singleton

**Archivo:** `AppStartupInitializer.kt:57`

```kotlin
@Volatile
var metricsListener: StartupMetricsListener? = null
```

`AppStartupInitializer` es un singleton (`sInstance` estático). Si el caller setea este listener con una lambda que captura un `Activity`, `Fragment`, o `ViewModel`, ese objeto nunca puede ser GC'd:

```kotlin
// Uso incorrecto — Activity queda retenida por el singleton
AppStartupInitializer.getInstance(this).metricsListener = { metric ->
    this.updateUI(metric) // `this` = Activity capturada
}
```

La documentación actual solo menciona "Set this before startup begins", sin advertir sobre retención de contexto.

**Recomendación:** Documentar explícitamente que el listener no debe capturar objetos con ciclo de vida corto, o bien tipar internamente como `WeakReference<StartupMetricsListener>`.

---

### 2.2 `initialized` map retiene todos los objetos del startup indefinidamente

**Archivo:** `AppStartupInitializer.kt:59`

```kotlin
private val initialized = ConcurrentHashMap<Class<*>, Any>()
```

Este mapa retiene las instancias de todos los objetos creados durante el startup por toda la vida de la app. Para resultados de `create()` que se necesitan en runtime, tiene sentido. Pero si un initializer produce una factory o helper que solo se usa durante la inicialización, queda retenido innecesariamente.

No es un bug, pero es un trade-off que el usuario de la librería necesita conocer y tener en cuenta al diseñar sus initializers.

---

## 3. Bugs y Problemas de Correctness

### 3.1 `CancellationException` reportada como `success = false` en métricas

**Archivo:** `AppStartupInitializer.kt:329-346`

```kotlin
var success = false
try {
    val result = withContext(initializerDispatcher) {
        asyncInitializer.create(applicationContext)
    }
    success = true
    result
} finally {
    metricsListener?.onInitializerCompleted(
        StartupMetric(..., success = success) // success = false si fue cancelado
    )
}
```

Si el scope se cancela, `success` queda en `false` y se reporta al listener como un fallo de inicialización. Una `CancellationException` no es un fallo, es cancelación estructurada. Esto contamina cualquier sistema de observabilidad o alerting que use el campo `success`.

**Recomendación:** Añadir un campo `wasCancelled: Boolean` a `StartupMetric`, o bien no invocar al listener cuando la causa es una `CancellationException`.

---

### 3.2 Coroutines huérfanas tras timeout en `awaitAllStartJobs(timeoutMs)`

**Archivo:** `AppStartupCoroutinesEngine.kt:95-110`

```kotlin
suspend fun awaitAllStartJobs(timeoutMs: Long) {
    try {
        withTimeout(timeoutMs) { _startJobs.awaitAll() }
        _startupState.value = StartupState.COMPLETED
    } catch (e: CancellationException) {
        throw e // TimeoutCancellationException toma este path
    } finally {
        _startJobs.clear() // borra la lista pero NO cancela los Deferred
    }
}
```

`TimeoutCancellationException` es subclase de `CancellationException`. Se re-lanza, el `finally` limpia la lista, pero los `Deferred` que estaban corriendo continúan ejecutándose en el `SupervisorJob`. Se pierde la referencia a ellos y no hay manera de cancelarlos. Son coroutines zombies consumiendo CPU e I/O sin control.

El método `cancel()` en `AppStartupCoroutinesEngine` existe pero no es invocado desde ningún lugar del código.

**Recomendación:** En el bloque `finally` de la versión con timeout, cancelar explícitamente los jobs que no completaron, o llamar a `supervisorJob.cancel()` y luego recrearlo si se requiere reutilización.

---

### 3.3 `Class.forName()` sin ClassLoader explícito

**Archivo:** `AppStartupInitializer.kt:395, 445`

```kotlin
Class.forName(key)
```

`Class.forName(String)` usa el classloader del caller. En Dynamic Feature Modules, Play Asset Delivery, o entornos de instrumentación de tests, el classloader puede no tener visibilidad sobre las clases del feature module, resultando en `ClassNotFoundException` en runtime.

**Recomendación:**

```kotlin
Class.forName(key, false, applicationContext.classLoader)
```

---

### 3.4 `System.currentTimeMillis()` para medir duración de inicialización

**Archivo:** `AppStartupInitializer.kt:257, ~330`

```kotlin
val startMs = System.currentTimeMillis()
// ...
durationMs = System.currentTimeMillis() - startMs,
```

`System.currentTimeMillis()` mide tiempo de reloj de pared (wall-clock) y puede saltar hacia atrás si el sistema sincroniza la hora vía NTP. Para medir duración de operaciones, la práctica correcta en Android es usar tiempo monotónico:

```kotlin
val startMs = SystemClock.elapsedRealtime()
// o para mayor precisión:
val startNs = System.nanoTime()
```

---

## 4. Best Practices

### 4.1 `CopyOnWriteArrayList` en hot path de escritura

**Archivo:** `AppStartupCoroutinesEngine.kt:56`

```kotlin
private val _startJobs = CopyOnWriteArrayList<Deferred<*>>()
```

`CopyOnWriteArrayList` está optimizado para lecturas frecuentes y escrituras raras. Durante el startup, `launchStartJob` se llama N veces (una por async initializer), y cada `add()` copia el array entero. Esto genera allocations innecesarias en el momento de mayor presión de memoria.

**Recomendación:** Usar `Collections.synchronizedList(ArrayList())` o un `ArrayList` protegido con `synchronized`, que es más eficiente para este patrón de uso.

---

### 4.2 `metricsListener` invocado desde threads arbitrarios — sin documentar

**Archivo:** `AppStartupInitializer.kt:264, 339`

```kotlin
metricsListener?.onInitializerCompleted(...)
```

- Para sync initializers: el callback corre en **Main Thread**.
- Para async initializers: corre en el dispatcher del initializer (`IO`, `Default`, o uno custom).

El listener no tiene garantía de en qué thread corre. Esto no está documentado. Un implementador que toca UI o un objeto no thread-safe desde el listener puede crashear silenciosamente o producir comportamiento undefined.

**Recomendación:** Documentar explícitamente que `onInitializerCompleted` puede ser invocado desde cualquier thread, y que el implementador es responsable de hacer el dispatch al thread correcto si es necesario.

---

### 4.3 Double import `withTimeout`

**Archivo:** `AppStartupCoroutinesEngine.kt:17-18`

```kotlin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeout // duplicado exacto
```

Import duplicado. Cosmético, pero indica que no se corrió un unused/duplicate imports pass. Puede confundir en code reviews.

---

### 4.4 `applicationContext` null check es código muerto

**Archivo:** `InitializationProvider.kt:15`

```kotlin
val applicationContext = context.applicationContext
if (applicationContext != null) { ... }
```

`context.applicationContext` devuelve una instancia de `Application`. En el lifecycle normal de Android, si `ContentProvider.onCreate()` está siendo invocado, `applicationContext` nunca es null. Esta guarda es código defensivo sin valor real que agrega ruido de lectura.

---

## 5. Resumen Priorizado

| Prioridad | Categoría | Problema | Archivo |
|-----------|-----------|----------|---------|
| 🔴 Crítico | ANR | Sync initializers en Main Thread sin safeguard | `InitializationProvider.kt:16` |
| 🔴 Crítico | ANR | `getProviderInfo()` es IPC bloqueante en Main Thread | `AppStartupInitializer.kt:199` |
| 🟠 Leak | Memory | `metricsListener` puede retener Activity en singleton | `AppStartupInitializer.kt:57` |
| 🟡 Bug | Correctness | Coroutines huérfanas después de timeout | `AppStartupCoroutinesEngine.kt:95` |
| 🟡 Bug | Correctness | `CancellationException` reportada como fallo en métricas | `AppStartupInitializer.kt:329` |
| 🟡 Bug | Correctness | `Class.forName()` sin ClassLoader explícito | `AppStartupInitializer.kt:395` |
| 🟡 Bug | Correctness | `System.currentTimeMillis()` para medir duración | `AppStartupInitializer.kt:257` |
| 🔵 BP | Performance | `CopyOnWriteArrayList` en hot path de escritura | `AppStartupCoroutinesEngine.kt:56` |
| 🔵 BP | API | Thread del `metricsListener` callback no documentado | `AppStartupInitializer.kt:264` |
| 🔵 BP | Cleanup | Double import `withTimeout` | `AppStartupCoroutinesEngine.kt:17` |
| 🔵 BP | Cleanup | Null check muerto sobre `applicationContext` | `InitializationProvider.kt:15` |
