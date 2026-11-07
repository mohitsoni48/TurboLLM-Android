# TurboLLM SDK

## Engines

`TurboLLM.engines()` (and `GET /sdk/v1/engines` on the local server) lists the engines compiled into this SDK
build, in preference order:

| Id | Name | Reads | Runs on |
|---|---|---|---|
| `llama.cpp-cpu` | llama.cpp (CPU) | GGUF | CPU |
| `litert-lm` | LiteRT-LM | `.litertlm` | GPU, CPU |

Listed means compiled in; `available` means it runs on this device. An engine that cannot run here is listed with
`available: false` and a `reason`.

A load runs on the first available engine that reads the model's format. To pin one, pass
`LoadOptions(engine = EngineId.LITERT_LM)` (or `"engine": "litert-lm"` in `POST /sdk/v1/load`). A pinned engine
never falls back to another. The load is refused before it starts:

- `unknown_engine` (400): this SDK has no engine with that id.
- `engine_format_mismatch` (400): the engine can't read the model's format.
- `engine_unavailable` (501): the engine can't run on this device.

`/v1/models` lists every complete model on disk. Whether an engine can run it here is `/sdk/v1/engines`'
`available`; a chat on a model whose engine is unavailable fails with `engine_unavailable` (501).

## Keeping the model alive in the background (opt-in)

The SDK's manifest declares no service and no foreground-service permission. To use `TurboLLMService`, set
`TurboLLMService.defaultConfig` (or subclass it and override `provideConfig()`), then declare the service and the
permissions in your app's manifest:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application>
    <service
        android:name="dev.turbollm.sdk.service.TurboLLMService"
        android:exported="false"
        android:foregroundServiceType="specialUse" />
</application>
```

A subclass is declared by its own class name instead, and started and stopped by that class:
`TurboLLMService.start(context, MyService::class.java)` and `TurboLLMService.stop(context, MyService::class.java)`.
A `specialUse` service needs a Play Console
foreground-service declaration for your app.
