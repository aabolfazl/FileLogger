[![](https://jitpack.io/v/aabolfazl/FileLogger.svg)](https://jitpack.io/#aabolfazl/FileLogger)

# FileLogger

Coroutine-driven, sink-fan-out logging for Android. Lazy lambdas, MDC, JSON output, HTTP upload, redaction, and a crash handler — all behind a small `Logger` interface and a one-block `fileLogger { }` DSL.

## What's new in v2.0

- New `fileLogger(path) { ... }` DSL replaces `Config.Builder` (the old builder is still there but deprecated).
- Coroutine pipeline (`LogPipeline`) replaces v1.x's `ThreadQueue`. Backpressure-aware channel, ordered drain, per-sink failure isolation.
- Lazy logging: `FileLogger.d { "expensive: $value" }` — the lambda is invoked only when `isLoggable` returns true.
- Mapped Diagnostic Context: `Mdc.with("k" to "v") { ... }` and `Mdc.withSuspending { ... }`.
- Pluggable sinks: `FileSink`, `LogcatSink`, `HttpSink` (JSON-Lines POST batches), `RedactingSink`.
- JSON output via `FormatterChoice.Json` — JSON-Lines, hand-rolled (zero runtime deps).
- HTTP upload sink with batching, exponential backoff + jitter, permanent-4xx fast-drop, and a `NetworkPolicy` gate.
- PII redaction with default patterns (`EMAIL_REGEX`, `BEARER_TOKEN_REGEX`, `CREDIT_CARD_REGEX`, `IPV4_REGEX`).
- AndroidX App Startup auto-init via manifest `<meta-data>` (opt-in).
- `CrashHandlerInstaller` chains into the platform default handler and flushes before delegating.
- Optional `:filelogger-okhttp` artifact: an `Interceptor` that routes OkHttp diagnostics through `FileLogger`.
- `events: SharedFlow<LogEvent>` — subscribe and build your own viewer / mirror.

## Installation

JitPack at the project level:

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Module-level dependencies:

```gradle
dependencies {
    implementation 'com.github.aabolfazl:filelogger:2.0.0'
    // optional OkHttp interceptor — pulls no extra runtime dep into :filelogger
    implementation 'com.github.aabolfazl:filelogger-okhttp:2.0.0'
}
```

## Quick start

```kotlin
val path = applicationContext.getExternalFilesDir(null)?.path ?: return
val config = fileLogger(path) {
    defaultTag = "MyApp"
    minLevel = LogLevel.Debug
    formatter = FormatterChoice.PlainText
    rotation = FileRotationStrategy.TimeBased(intervalInMillis = 24 * 60 * 60 * 1000L)
    retention = RetentionPolicy.FileCountLimit(count = 7)
}
FileLogger.init(this, config)

FileLogger.i(tag = "Boot", message = "started")
FileLogger.w(message = "low battery")
FileLogger.e(message = "request failed", throwable = e)
```

## The DSL surface

`fileLogger(directory: String) { ... }` returns a `Config`. Properties:

| Property | Type | Default | Notes |
|---|---|---|---|
| `defaultTag` | `String` | `"FileLogger"` | Substituted when a call site passes `tag = null`. |
| `logcatEnabled` | `Boolean` | `true` | Attach a `LogcatSink` so events also reach `android.util.Log`. |
| `dateFormatPattern` | `String` | `"dd-MM-yyyy-HH:mm:ss"` | `DateTimeFormatter` pattern; used for both timestamps and file names. |
| `minLevel` | `LogLevel` | `Debug` | Events strictly below this severity are filtered. |
| `tagOverrides` | `Map<String, LogLevel>` | `emptyMap()` | Per-tag override; e.g. `mapOf("Network" to LogLevel.Warning)`. |
| `retention` | `RetentionPolicy?` | `null` | `FileCountLimit`, `FileSizeLimit`, or `TimeToLive`. |
| `rotation` | `FileRotationStrategy` | `None` | `None` or `TimeBased(intervalInMillis)`. |
| `interceptor` | `LogInterceptor?` | `null` | Producer-side message rewriter (runs before the pipeline). |
| `startupData` | `Map<String, String>?` | `null` | Free-form key-value lines appended to the startup banner. |
| `formatter` | `FormatterChoice` | `PlainText` | `PlainText` (human-readable) or `Json` (JSON-Lines). |

Custom-sink wiring at the DSL level is intentionally not exposed in v2.0 — sinks need the pipeline coroutine scope, which is created during `FileLogger.init`. Construct sinks manually and inject via your own pipeline if you need a custom topology; first-class DSL support is on the v2.x roadmap.

## Lambda logging + filtering

```kotlin
FileLogger.d { "rendered: ${expensive()}" }     // lambda not invoked when filtered
FileLogger.i(tag = "Net") { "url=$url status=$code" }
FileLogger.e(throwable = e) { "request to $url failed" }
```

The lambda overload short-circuits via `isLoggable(level, tag)` before invoking the body — combine with `minLevel` and `tagOverrides` to drop expensive renderings cheaply:

```kotlin
fileLogger(path) {
    minLevel = LogLevel.Info
    tagOverrides = mapOf(
        "Network" to LogLevel.Warning, // raise threshold for Network
        "Boot" to LogLevel.Debug,      // keep Boot verbose
    )
}
```

## MDC

Synchronous:

```kotlin
Mdc.with("requestId" to UUID.randomUUID().toString(), "userId" to user.id) {
    FileLogger.i { "doing work" } // every event in this block carries the MDC
}
```

Suspending — survives dispatcher hops:

```kotlin
suspend fun handleRequest(req: Request) = Mdc.withSuspending("requestId" to req.id) {
    val data = withContext(Dispatchers.IO) { fetch(req) } // MDC still active here
    FileLogger.i { "fetched ${data.size} items" }
}
```

`Mdc.currentSnapshot()` returns the singleton `emptyMap()` when no scope is active; logging without MDC pays nothing.

## Sink composition

The DSL wires `FileSink` (and optionally `LogcatSink`) automatically. To compose additional sinks (HTTP upload, redaction), construct them and hand them to a `LogPipeline` of your own:

```kotlin
val scope = pipeline.scope // exposed by LogPipeline
val redactedHttp = RedactingSink(
    delegate = HttpSink(
        endpoint = "https://logs.example.com/ingest",
        context = applicationContext,
        scope = scope,
        formatter = JsonFormatter(timeFormatter, defaultTag = "MyApp"),
        networkPolicy = NetworkPolicy.UNMETERED_ONLY,
    ),
    patterns = DEFAULT_PATTERNS,
)
```

Custom-sink wiring at the DSL level is v2.x.

## JSON output

```kotlin
fileLogger(path) {
    formatter = FormatterChoice.Json
}
```

Each line is a complete JSON object suitable for ingestion by `jq`, `vector`, `fluent-bit`, or a centralised log aggregator:

```json
{"ts":"2025-04-29-17:00:00","level":"I","tag":"Boot","msg":"started","thread":"main","throwable":null}
{"ts":"2025-04-29-17:00:01","level":"W","tag":"Net","msg":"slow request","thread":"DefaultDispatcher-worker-1","throwable":null,"mdc":{"requestId":"abc"}}
```

`mdc` is omitted when empty so common-case lines stay compact. Throwables serialise as the JSON literal `null` when absent and as an escaped string otherwise.

## HTTP upload

```kotlin
val http = HttpSink(
    endpoint = "https://logs.example.com/ingest",
    context = applicationContext,
    scope = pipeline.scope,
    formatter = JsonFormatter(timeFormatter, defaultTag = "MyApp"),
    headers = mapOf("X-App" to "MyApp"),
    batchSize = 50,
    flushInterval = 30.seconds,
    maxRetries = 5,
    networkPolicy = NetworkPolicy.ANY,
    maxQueuedBatches = 500,
)
```

Behaviour:

- Pre-formats every event on `emit` so retries replay byte-identical text.
- Batches on `batchSize` events or `flushInterval`, whichever fires first.
- 2xx → success, batch dropped.
- Permanent 4xx (`400/401/403/404/410`) → drop immediately, no retry.
- Transient 5xx / IOException → exponential backoff with jitter, capped at 60 s, up to `maxRetries`.
- `NetworkPolicy.UNMETERED_ONLY` / `WIFI_ONLY` gate uploads via `ConnectivityManager`.
- In-memory cap `maxQueuedBatches`; the **oldest** batch is dropped on overflow and a synthetic warning surfaces in the next batch.

Durable upload (write-ahead log + replay) is intentionally out of scope for v2.0.

## PII redaction

`RedactingSink` decorates any sink and replaces matches of regex patterns with `"[REDACTED]"` before delegation:

```kotlin
val sink = RedactingSink(
    delegate = fileSink,
    patterns = DEFAULT_PATTERNS + Regex("""sk_live_[A-Za-z0-9]+"""),
)
```

`DEFAULT_PATTERNS` covers email addresses, bearer tokens, 13–19 digit numbers, and IPv4 addresses. Patterns are applied to the rendered message body only — `Throwable.stackTraceToString()` output is **not** scanned. Wrap the producer-side message if you need stack-trace-level redaction. Messages whose UTF-16 length exceeds `maxMessageBytes/2` are forwarded unchanged to keep regex cost bounded.

## Crash handler

```kotlin
CrashHandlerInstaller.install(rethrow = true, flushTimeoutMs = 2_000)
```

The installer wraps whatever default handler was active before, synthesises a fatal event, drains the pipeline (bounded by `flushTimeoutMs`), then forwards to the previous handler when `rethrow = true`. A `ThreadLocal` re-entry guard prevents recursion if the handler itself throws. `uninstall()` restores the previous handler; both are idempotent.

## App Startup auto-init

`FileLoggerInitializer` is registered with AndroidX App Startup but is **opt-in**. Without `filelogger.autoInit = true` it returns immediately.

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="abbasi.android.filelogger.startup.FileLoggerInitializer"
        android:value="androidx.startup" />
</provider>

<meta-data android:name="filelogger.autoInit"   android:value="true" />
<meta-data android:name="filelogger.tag"        android:value="MyApp" />
<meta-data android:name="filelogger.minLevel"   android:value="Info" />
<meta-data android:name="filelogger.logcat"     android:value="true" />
<meta-data android:name="filelogger.formatter"  android:value="PlainText" />
```

| Key | Type | Default | Notes |
|---|---|---|---|
| `filelogger.autoInit` | boolean | _required_ | Without this set to `true`, the initializer is a no-op. |
| `filelogger.tag` | string | `FileLogger` | Default tag. |
| `filelogger.minLevel` | string | `Debug` | One of `Debug`/`Info`/`Warning`/`Error`. |
| `filelogger.logcat` | boolean | `true` | Whether to attach a `LogcatSink`. |
| `filelogger.formatter` | string | `PlainText` | One of `PlainText`/`Json`. |

When auto-init is on, `FileLogger` uses `context.filesDir` as its root. Need a different directory? Opt out and call `FileLogger.init` yourself.

## OkHttp interceptor

A separate artifact, `:filelogger-okhttp`, exposes `FileLoggerOkHttpInterceptor`. The artifact uses `compileOnly` for OkHttp so the core `:filelogger` module pulls **zero** OkHttp at runtime — only consumers who already have OkHttp on the classpath see the interceptor.

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(FileLoggerOkHttpInterceptor(level = FileLoggerOkHttpInterceptor.Level.HEADERS))
    .build()
```

`Level` enum: `NONE`, `BASIC`, `HEADERS`, `BODY`. Header redaction defaults cover `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`. Body capture is bounded by `maxBodyBytes` (default 1 MiB).

## Build your own viewer

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        FileLogger.events.collect { event ->
            // event.level, event.tag, event.lazyMessage(), event.throwable, event.mdc, ...
        }
    }
}
```

`events` is a non-replaying `SharedFlow<LogEvent>` — subscribers see only emissions that occur after they begin collecting. The buffer is `DROP_OLDEST(64)` so a slow collector cannot stall the pipeline.

## Migration guide v1.x → v2.0

| v1.x | v2.0 |
|---|---|
| `Config.Builder(path)` | `fileLogger(path) { ... }` (top-level DSL) |
| `.setDefaultTag("X")` | `defaultTag = "X"` |
| `.setLogcatEnable(true)` | `logcatEnabled = true` |
| `.setRetentionPolicy(p)` | `retention = p` |
| `.setNewFileStrategy(s)` | `rotation = s` |
| `.setLogInterceptor(i)` | `interceptor = i` |
| `.setStartupData(m)` | `startupData = m` |
| `.setDataFormatterPattern("p")` | `dateFormatPattern = "p"` |
| `.setMinLevel(l)` | `minLevel = l` |
| `.setTagOverrides(m)` | `tagOverrides = m` |
| `.setFormatter(f)` | `formatter = f` |
| `.build()` | (none — DSL returns `Config` directly) |
| `FileLogger.isEnable` | `FileLogger.isEnabled` |
| `FileLogger.i(msg = "x")` | `FileLogger.i(message = "x")` |
| `FileLogger.e(throwable = e)` | `FileLogger.e(message = "...", throwable = e)` |
| named `msg = ` argument | named `message = ` argument |

The v1.x `Config.Builder` and every setter remain on the classpath with `@Deprecated(level = WARNING)` — existing code keeps compiling, but each call site prints a deprecation warning until migrated.

## Versioning + deprecation policy

- Semantic versioning. Major bumps may break source / binary; minor bumps may not.
- Within a major series, deprecations get **at least one minor cycle** (`@Deprecated(level = WARNING)` with `ReplaceWith`) before removal.
- `:filelogger` and `:filelogger-okhttp` versions move in lockstep.

## Roadmap

Direction we are looking at for v3.0+:

- Kotlin Multiplatform — JVM + iOS targets sharing the pipeline core.
- Native (NDK) bridge so C/C++ code can publish events.
- On-disk encryption for `FileSink` output.
- ANR watchdog hook that emits a fatal-equivalent event before the platform terminates.

License
=======
    MIT License
    Copyright(c) 2022 Abolfazl Abbasi

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
