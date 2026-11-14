#!/usr/bin/env node
// Checks that EngineService hosts the SDK core as a TurboLLMService subclass without owning the core's lifetime, and
// that only the app, never the SDK, declares a foreground service (the SDK run's architecture §5.2-5.3):
//   node scripts/check-engine-service.mjs
// Prints PASS, or FAIL and each problem (exit 1).
import { readFileSync, realpathSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const ENGINE_SERVICE = 'composeApp/src/androidMain/kotlin/com/turbollm/app/EngineService.kt'
const APP_MANIFEST = 'composeApp/src/androidMain/AndroidManifest.xml'
const SDK_MANIFEST = 'turbollm-sdk/src/main/AndroidManifest.xml'

export function readSources(root = ROOT) {
  const read = (relative) => readFileSync(join(root, relative), 'utf8')
  return { engineService: read(ENGINE_SERVICE), appManifest: read(APP_MANIFEST), sdkManifest: read(SDK_MANIFEST) }
}

// Each rule is a problem and the test that it holds; an empty list means the sources follow the contract.
export function engineServiceProblems({ engineService, appManifest, sdkManifest }) {
  return [
    ...serviceProblems(engineService),
    ...manifestProblems(appManifest, sdkManifest),
  ]
}

function serviceProblems(service) {
  return problemsOf([
    ['EngineService must extend TurboLLMService()', /class EngineService\s*:\s*TurboLLMService\(\)/.test(service)],
    ['provideConfig() must return TurboLLMBootstrap.config(this)',
      /override fun provideConfig\(\)\s*=\s*TurboLLMBootstrap\.config\(this\)/.test(service)],
    ['notificationId must be overridden with NOTIFICATION_ID',
      /override val notificationId\s*=\s*NOTIFICATION_ID\b/.test(service)],
    ['onCreate() must call super.onCreate() first',
      /override fun onCreate\(\)\s*\{\s*super\.onCreate\(\)/.test(service)],
    ['onStartCommand must call startInForeground()', service.includes('startInForeground()')],
    ['ServiceCompat.startForeground must not be called; startInForeground() posts the notification',
      !service.includes('ServiceCompat.startForeground(')],
    ['buildNotification must override buildNotification(state: EngineState)',
      /override fun buildNotification\(state: EngineState\): Notification/.test(service)],
    ['EngineService must not close, unload or stop the core: a Quit mid-load is reported by the next start ' +
      'as an interrupted load only if the load is never cleaned up (G-9)',
      !/\bcore\.(close|unload|stopServer)\(/.test(service)],
    ['Quit must still end the process with Process.killProcess(Process.myPid())',
      service.includes('Process.killProcess(Process.myPid())')],
  ])
}

function manifestProblems(appManifest, sdkManifest) {
  return problemsOf([
    ['the app manifest must declare .EngineService as specialUse',
      /<service\s[^>]*android:name="\.EngineService"[^>]*android:foregroundServiceType="specialUse"/.test(appManifest)],
    ['the SDK manifest must declare no <service>', !/<service\b/.test(sdkManifest)],
    ['the SDK manifest must request no FOREGROUND_SERVICE permission', !sdkManifest.includes('FOREGROUND_SERVICE')],
  ])
}

function problemsOf(rules) {
  return rules.filter(([, holds]) => !holds).map(([problem]) => problem)
}

function main() {
  const problems = engineServiceProblems(readSources())
  if (problems.length === 0) {
    console.log('PASS EngineService contract')
    return
  }
  console.error(`FAIL EngineService contract\n${problems.join('\n')}`)
  process.exitCode = 1
}

// Run as a script; the test imports the functions above without reading the checkout unless it asks to.
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  try {
    main()
  } catch (e) {
    console.error(`check-engine-service: ${e instanceof Error ? e.message : String(e)}`)
    process.exitCode = 1
  }
}
