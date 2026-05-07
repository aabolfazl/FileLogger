# FileLogger architecture

This document is the contributor-facing reference for FileLogger's internals. The README is for
consumers; this is for whoever has to read or change the code.

---

## 1. Goals & non-goals

**Goals**

- One-call-and-forget logger that survives high log rates without blocking the caller.
- Pluggable output: file, logcat, HTTP, custom — composable as decorators.
- Predictable threading: producers never block; consumers run on a single ordered dispatcher.
- Auditable failure path — every internal error has exactly one place it surfaces.
- Public-API stability via Kotlin explicit-API mode.

**Non-goals**

- Cross-platform (Kotlin Multiplatform). Android only.
- Native (NDK / JNI) producers. JVM/Kotlin only.
- Durable HTTP delivery (write-ahead-log + replay). v2.x parks this.

---

## 2. Layers

```
┌──────────────────────────────────────────────────────────────────┐
│ Tier 1 — Producer surface (Logger.kt, FileLogger.kt, Mdc.kt)     │
│   FileLogger.i("Net", "request done")                            │
│   FileLogger.d { "expensive: ${heavyComputation()}" }            │
│   Mdc.with("requestId" to id) { ... }                            │
│   Runs on the caller's thread. Non-blocking. Filtered eagerly.   │
└──────────────────────────────────────────────────────────────────┘
                              ↓ Channel.trySend (non-blocking)
┌──────────────────────────────────────────────────────────────────┐
│ Tier 2 — Pipeline (LogPipeline.kt, LogEvent.kt, PipelineScope.kt)│
│   Channel<LogEvent>(1024, DROP_OLDEST, onUndeliveredElement)     │
│   Single drain coroutine on Dispatchers.IO.limitedParallelism(1) │
│   Sequential fan-out. Per-sink failure isolation. Drop counter.  │
│   SharedFlow<LogEvent> mirror for external subscribers.          │
└──────────────────────────────────────────────────────────────────┘
                              ↓ sequential sink.emit(event)
┌──────────────┬──────────────┬──────────────┬─────────────────────┐
│  FileSink    │  LogcatSink  │  HttpSink    │  RedactingSink      │
│ append+batch │ android.Log  │ batched POST │ wraps any sink      │
│ rotate+retain│ formatter-   │ retry+queue  │ regex scrub before  │
│ disk-guard   │ free         │ withContext( │ delegating          │
│              │              │   IO) for    │                     │
│              │              │   network    │                     │
└──────────────┴──────────────┴──────────────┴─────────────────────┘
                              ↑ each sink uses
                  ┌───────────────────────────────┐
                  │ LogFormatter (format/)         │
                  │  - PlainTextFormatter (default)│
                  │  - JsonFormatter (JSON-Lines)  │
                  │ Stateless, pure, non-suspending│
                  └───────────────────────────────┘
```

### Tier 1 — Producer surface

Public entry points the host app calls. Every method returns immediately.

- `FileLogger` — process-wide singleton. Implements `Logger`. Idempotent `init`, `shutdown`,
  `events` flow.
- `Logger` — interface. Eight `i/d/w/e` overloads (string + lambda) plus `isLoggable`, `isEnabled`,
  `events`.
- `LoggerImpl` — non-singleton implementation, used by `FileLogger` internally and available for
  tests / per-feature loggers.
- `Mdc` — `ThreadLocal`-backed for synchronous code, `ThreadContextElement`-backed for coroutines.
  Allocation-free when empty.

**Filter pipeline on the producer side:**

1. `isEnabled` — atomic boolean kill switch.
2. `minLevel` — global threshold.
3. `tagOverrides[tag]` — per-tag override of `minLevel`.

If filtered, the lambda variant never invokes the producer lambda — the perf win.

### Tier 2 — Pipeline

`LogPipeline` owns the channel, the drain coroutine, the events `SharedFlow`, and the lifecycle
hooks. It does not know about formatters or sink internals.

**Backpressure.**
`Channel(capacity = 1024, onBufferOverflow = DROP_OLDEST, onUndeliveredElement = { dropCounter++ })`.
When the producer outpaces the drain, the oldest event is evicted and the drop counter increments.
On the next successful drain, a synthetic `Warning` event ("dropped N events due to backpressure")
is fanned out before the next real event.

**Fan-out.** Sequential, in registration order. Each sink call is wrapped in
`try/catch CancellationException-rethrow / Exception → FileLoggerInternalLog.warnRateLimited`. A
permanently failing sink can't crash the drain or starve other sinks.

**Lifecycle.**

- `Runtime.addShutdownHook { runBlocking { shutdown(2_000) } }` — JVM exit guarantee.
- `ProcessLifecycleOwner.ON_STOP` → best-effort `flush()` (does NOT shutdown — apps may foreground
  again).

`runBlocking` is permitted on these two threads only; both call sites carry the justification
comment.

### Tier 3 — Sinks

`LogSink` interface: `emit`, `flush`, `close`. Stateful. Each sink decides its own
batching/buffering/retry. The pipeline guarantees serial calls.

| Sink            | Purpose                                                                                                         | Threading inside                                                                              |
|-----------------|-----------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `FileSink`      | append-mode file writes, batched flush, rotation, retention, disk guard                                         | pipeline thread for state, file I/O is fast enough not to need offload                        |
| `LogcatSink`    | mirrors to `android.util.Log.i/d/w/e`                                                                           | pipeline thread; logcat is a JNI call with no Looper requirement                              |
| `HttpSink`      | JSON-Lines POST, batched, exponential backoff with jitter, permanent-4xx drop, queue cap, network policy gating | pipeline thread for queue mutations, `withContext(Dispatchers.IO)` for blocking network calls |
| `RedactingSink` | decorator; regex-scrubs messages before delegating                                                              | pipeline thread; pure CPU                                                                     |

Sinks **never** call back into `FileLogger.x(...)`. Errors funnel through
`internal/FileLoggerInternalLog` (logcat-only).

---

## 3. Data flow walkthroughs

### `FileLogger.i("Net", "request done")` — happy path

```
caller thread:
  LoggerImpl.i(tag = "Net", message = "request done", throwable = null)
    isLoggable(Info, "Net") → true
    postLog(Info, "Net", "request done", null)
      LogEvent {
        level = Info, tag = "Net", lazyMessage = { "request done" },
        throwable = null, timestampMs = now,
        threadName = "main", mdc = Mdc.currentSnapshot()
      }
      pipeline.emit(event) → channel.trySend → ChannelResult.success
  → returns

pipeline thread (drain loop):
  for (event in channel)
    val pending = dropCounter.getAndSet(0L)
    if (pending > 0) fanOut(syntheticDropNotice(pending))
    fanOut(event):
      FileSink.emit(event)        → format, append, maybe flush
      LogcatSink.emit(event)      → Log.i("Net", "request done", null)
      _events.tryEmit(event)      → external SharedFlow subscribers
```

### `FileLogger.init(context, config)`

`synchronized(this) {`

1. If `delegate != null`, return (idempotent).
2. Build `TimeFormatter`.
3. `newPipelineScope()` →
   `SupervisorJob + Dispatchers.IO.limitedParallelism(1) + CoroutineName("FileLogger")`.
4. Build `LogFormatter` from `Config.formatter`.
5. Build `DiskSpaceGuard`.
6. Build `FileSink`. (Schedules its own batch-flush + retention + disk-guard loops on the scope.)
7. Compose sink list: `[FileSink]` + optional `LogcatSink`.
8. Build `LogPipeline(scope, sinks)`. (Starts drain, registers shutdown hook + lifecycle observer.)
9. Launch a relay coroutine: `pipeline.events.collect { _events.tryEmit(it) }` — so the stable
   top-level `events` flow survives shutdown/re-init.
10. Build `LoggerImpl` with the pipeline. Apply current `isEnabled` to it. Set `delegate`.
    `}`

### `FileLogger.shutdown(timeoutMs = 2000)`

1. `pipeline.shutdown(timeoutMs)`:
    1. CAS-guard against double-shutdown.
    2. `channel.close()` — drain finishes when buffer empty.
    3. `withTimeoutOrNull(timeoutMs) { drainJob.join() }`.
    4. For each sink: `flush()` then `close()` (errors funneled).
    5. `scope.cancel()` — kills the relay coroutine and all sink-scoped loops.
2. Null out `delegate`, `pipeline`, `fileSink`, `config`, `interceptor`.

The top-level `_events: MutableSharedFlow` is preserved across shutdown; subscribers reattached to
the next pipeline via the relay launched on the next `init`.

### Crash flow

```
uncaught throw on thread T:
  CrashHandlerInstaller.handle(T, exception, rethrow=true, flushTimeoutMs=2000):
    if inHandler.get() == true → forward to previousHandler, return
    inHandler.set(true)
    try {
      LogEvent {
        level = Error, tag = "CrashHandler",
        lazyMessage = { "uncaught exception on thread $T" },
        throwable = exception, ...
      }
      FileLogger.emitFatal(event)        // bypasses isLoggable
      runBlocking { FileLogger.shutdown(2000) }   // synchronous flush
      if (rethrow) previousHandler.uncaughtException(T, exception)
    } finally { inHandler.remove() }
```

The `runBlocking` is justified the same way as the JVM shutdown hook: this thread is about to
terminate the process; there is no surviving caller to suspend back to.

---

## 4. Threading model — one-page summary

| Concern                                                  | Thread                                                                              |
|----------------------------------------------------------|-------------------------------------------------------------------------------------|
| `FileLogger.i/d/w/e` (eager + lambda)                    | caller's thread                                                                     |
| `Mdc.with` (synchronous)                                 | caller's thread, restores in `finally`                                              |
| `Mdc.withSuspending`                                     | propagates via `ThreadContextElement` across dispatcher hops                        |
| Pipeline drain + most sinks                              | `Dispatchers.IO.limitedParallelism(1)` — single ordered worker                      |
| `HttpSink.uploadBatchOnce`                               | `Dispatchers.IO` (unlimited pool) — releases the limited worker during slow network |
| `FileSink` periodic flush / retention sweep / disk guard | child coroutines of pipeline scope, same limited worker                             |
| `ProcessLifecycleOwner.ON_STOP` flush                    | main thread → launches into pipeline scope                                          |
| JVM shutdown hook                                        | shutdown thread → `runBlocking { pipeline.shutdown(2000) }`                         |
| Crash handler                                            | uncaught-exception thread → `runBlocking { FileLogger.shutdown(2000) }`             |

---

## 5. Error-handling philosophy

One funnel: `internal/FileLoggerInternalLog`.

- `warn(msg, e)` / `error(msg, e)` — always-log via `android.util.Log`.
- `warnRateLimited(key, msg, e, intervalMs = 60_000)` — at most one entry per `key` per minute. Used
  wherever a sink could fail repeatedly (e.g. permanently broken HTTP endpoint, full disk).

Rules every catch follows:

1. `CancellationException` — always rethrow. Structured concurrency.
2. `IOException` / `Exception` — funnel through `FileLoggerInternalLog`.
3. **Never** `e.printStackTrace()`. **Never** swallow silently. **Never** call back into
   `FileLogger.x(...)` from inside a sink/crash/internal path.
4. The funnel is logcat-only by design: an error in the file pipeline must not try to log itself
   through the file pipeline.

---

## 6. Extension points

A library consumer extends FileLogger by:

- **Custom formatter** — implement `LogFormatter`. Pure function over `LogEvent`. Plug into a
  `LogSink` constructor.
- **Custom sink** — implement `LogSink`. Idempotent `close`. Don't pin a dispatcher. Don't recurse.
  Built-in sinks (`FileSink`, `HttpSink`, `RedactingSink`) are reference implementations.
- **Decorator sink** — wrap any `LogSink`. `RedactingSink` is the canonical example.
- **Subscribe to `FileLogger.events`** — a stable `SharedFlow<LogEvent>` for in-app viewers,
  telemetry forwarders, or custom routing.

The DSL doesn't currently expose a `sinks: List<LogSink>` knob (sinks need a `CoroutineScope` that
doesn't exist at config time). Power users wanting custom sinks construct `LogPipeline` and
`LoggerImpl` directly.

---

## 7. Module structure

```
:filelogger              — core library. Zero runtime deps beyond
                           kotlinx-coroutines, androidx-startup,
                           androidx-lifecycle-process, androidx-core-ktx.
:filelogger-okhttp       — optional. compileOnly OkHttp dep so consumers
                           bring their own OkHttp version.
:app                     — sample app demonstrating the DSL, lambda API,
                           MDC, and events flow.
```

Package layout under `:filelogger`:

```
abbasi.android.filelogger/
├── FileLogger.kt              singleton entry point
├── Logger.kt                  public interface
├── LoggerImpl.kt              non-singleton impl
├── config/                    Config + DSL-side enums + constants
├── crash/                     CrashHandlerInstaller
├── dsl/                       fileLogger { } DSL builder
├── file/                      LogLevel, RetentionPolicyChecker,
│                              DiskSpaceGuard
├── format/                    LogFormatter + PlainText + Json
├── interceptor/               LogInterceptor (producer-side rewriter)
├── internal/                  FileLoggerInternalLog (the funnel)
├── mdc/                       Mdc (ThreadLocal + ThreadContextElement)
├── pipeline/                  LogPipeline, LogEvent, PipelineScope
├── sink/                      LogSink + Console/File/Http/Redacting
├── startup/                   FileLoggerInitializer (App Startup)
├── time/                      TimeFormatter (DateTimeFormatter wrapper)
└── util/                      FileZipper, FileIntent
```

The Java desugaring path was removed at v2.0 (`minSdk` raised to 26 — `java.time` is native).

---

## 8. Key architectural decisions

| Decision                                                          | Rationale                                                                                                                |
|-------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| `Channel(1024, DROP_OLDEST)` rather than unbounded                | Bounded memory under runaway producers. Drop counter + synthetic notice make the loss visible.                           |
| `Dispatchers.IO.limitedParallelism(1)` for the drain              | Preserves event order. Avoids per-sink locking.                                                                          |
| `withContext(Dispatchers.IO)` inside `HttpSink.uploadBatchOnce`   | Slow network would otherwise hold the limited worker for seconds. Documented exception in the coroutines rule.           |
| Lambda overloads (`{ "msg" }`) over varargs                       | Lazy evaluation: lambda only invoked if `isLoggable`. Non-capturing lambdas are singleton-cached by the Kotlin compiler. |
| `LogLevel` as `enum class` (not sealed)                           | `ordinal` is the single source of truth for filtering. Was sealed in v1.x; v2.0 took the binary break.                   |
| Hand-rolled JSON in `JsonFormatter`                               | Zero runtime deps. The escape table is small and well-tested.                                                            |
| `Mdc` returns `emptyMap()` singleton when unused                  | Allocation-free common case. Per-call allocation only when the caller pushed a context.                                  |
| `events: SharedFlow` hoisted to `FileLogger` (not pipeline-owned) | Subscribers survive `shutdown` + re-`init` without manual reattach.                                                      |
| `Config.Builder` deprecated, not deleted                          | One-version migration window for v1.x callers. Removal in v2.1.                                                          |
| Explicit-API in `Strict` mode                                     | Forces every public symbol to declare visibility and return type. The compiler is the API stability test.                |

---

## 9. Where to start reading

If you want to understand the code in 30 minutes, read in this order:

1. `pipeline/LogEvent.kt` — the unit of currency.
2. `Logger.kt` — the public surface.
3. `LoggerImpl.kt` — what producers actually do.
4. `pipeline/LogPipeline.kt` — the heart.
5. `sink/LogSink.kt` — the contract.
6. `sink/FileSink.kt` — the most representative sink.
7. `internal/FileLoggerInternalLog.kt` — the error funnel.
8. `FileLogger.kt` — singleton wrapper, init/shutdown choreography.

Everything else (formatters, DSL, MDC, crash handler, App Startup, OkHttp interceptor, retention
policies) is a leaf attached to that spine.
