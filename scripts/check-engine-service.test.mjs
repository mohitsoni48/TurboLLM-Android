// Tests for scripts/check-engine-service.mjs, the check that EngineService hosts the SDK core as a TurboLLMService
// subclass without owning the core's lifetime, and that only the app declares a foreground service:
//   node --test scripts/check-engine-service.test.mjs
// The sources are small strings built here, except the last test, which checks this checkout's own files.
import assert from 'node:assert/strict'
import { test } from 'node:test'

import { engineServiceProblems, readSources } from './check-engine-service.mjs'

const ENGINE_SERVICE = `
class EngineService : TurboLLMService() {
    override val notificationId = NOTIFICATION_ID

    override fun provideConfig() = TurboLLMBootstrap.config(this)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_NOT_STICKY
    }

    private fun exit() {
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    override fun buildNotification(state: EngineState): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID).build()
    }
}
`
const APP_MANIFEST = `
<manifest>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <application>
        <service
            android:name=".EngineService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
        </service>
    </application>
</manifest>
`
const SDK_MANIFEST = `
<manifest>
    <uses-permission android:name="android.permission.INTERNET" />
    <application>
        <uses-native-library android:name="libOpenCL.so" android:required="false" />
    </application>
</manifest>
`
const SOURCES = { engineService: ENGINE_SERVICE, appManifest: APP_MANIFEST, sdkManifest: SDK_MANIFEST }

function problemsWith(changes) {
  return engineServiceProblems({ ...SOURCES, ...changes })
}

function serviceWith(anchor, replacement) {
  assert.ok(ENGINE_SERVICE.includes(anchor), anchor)
  return { engineService: ENGINE_SERVICE.replace(anchor, replacement) }
}

test('a service that follows the contract has no problems', () => {
  assert.deepEqual(engineServiceProblems(SOURCES), [])
})

test('a plain Service is refused: the SDK core would have no host', () => {
  assert.deepEqual(problemsWith(serviceWith(': TurboLLMService()', ': Service()')),
    ['EngineService must extend TurboLLMService()'])
})

test('a config other than the bootstrap one is refused: the shared core would reject the service', () => {
  const problems = problemsWith(serviceWith('TurboLLMBootstrap.config(this)', 'TurboLLMConfig(filesDir)'))
  assert.deepEqual(problems, ['provideConfig() must return TurboLLMBootstrap.config(this)'])
})

test('the notification keeps the app\'s own id', () => {
  assert.deepEqual(problemsWith(serviceWith('override val notificationId = NOTIFICATION_ID\n', '')),
    ['notificationId must be overridden with NOTIFICATION_ID'])
})

test('onCreate must let TurboLLMService create the core before anything else', () => {
  const manager = '        val manager = getSystemService(NotificationManager::class.java)'
  const late = serviceWith('        super.onCreate()\n' + manager, manager + '\n        super.onCreate()')
  assert.deepEqual(problemsWith(late), ['onCreate() must call super.onCreate() first'])
})

test('the foreground start goes through startInForeground, not ServiceCompat', () => {
  const compat = serviceWith('startInForeground()', 'ServiceCompat.startForeground(this, NOTIFICATION_ID, n, 0)')
  assert.deepEqual(problemsWith(compat), [
    'onStartCommand must call startInForeground()',
    'ServiceCompat.startForeground must not be called; startInForeground() posts the notification',
  ])
})

test('the notification is the TurboLLMService override', () => {
  const own = serviceWith('override fun buildNotification(state: EngineState)', 'private fun buildNotification()')
  assert.deepEqual(problemsWith(own), ['buildNotification must override buildNotification(state: EngineState)'])
})

for (const call of ['core.close()', 'core.unload()', 'core.stopServer()']) {
  test(`G-9: ${call} is refused, so a Quit mid-load is reported as an interrupted load`, () => {
    const closing = serviceWith('        stopSelf()\n', `        ${call}\n        stopSelf()\n`)
    assert.deepEqual(problemsWith(closing), [
      'EngineService must not close, unload or stop the core: a Quit mid-load is reported by the next start ' +
        'as an interrupted load only if the load is never cleaned up (G-9)',
    ])
  })
}

test('Quit still ends the process', () => {
  assert.deepEqual(problemsWith(serviceWith('Process.killProcess(Process.myPid())', 'stopSelf()')),
    ['Quit must still end the process with Process.killProcess(Process.myPid())'])
})

test('the app declares EngineService as its special-use foreground service', () => {
  const dataSync = { appManifest: APP_MANIFEST.replace('"specialUse"', '"dataSync"') }
  assert.deepEqual(problemsWith(dataSync), ['the app manifest must declare .EngineService as specialUse'])
})

test('the SDK manifest declares no service and no foreground-service permission', () => {
  const sdkManifest = SDK_MANIFEST.replace('<application>', '<application>\n<service android:name=".X" />')
    .replace('INTERNET', 'FOREGROUND_SERVICE')
  assert.deepEqual(problemsWith({ sdkManifest }), [
    'the SDK manifest must declare no <service>',
    'the SDK manifest must request no FOREGROUND_SERVICE permission',
  ])
})

test('this checkout\'s EngineService and manifests follow the contract', () => {
  assert.deepEqual(engineServiceProblems(readSources()), [])
})
