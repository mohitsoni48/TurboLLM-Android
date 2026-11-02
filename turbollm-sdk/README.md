# TurboLLM SDK

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
