# Review notes — independent code review before hand-off

A second reviewer (no part in writing the code) read `sdk-core`, `sdk-sherpa`, `sdk-android` and the Expo
module looking for concurrency, lifecycle and fallback bugs. Twelve findings; all fixed and re-tested
(`scripts/run-tests.sh` → `docs/TEST-REPORT.md`, 38/38). Listed here so the team knows what was changed and why.

| # | Where | Finding | Fix |
|---|---|---|---|
| 1 | `IndicVoice.startLive` | When a tier-1 live session failed and the SDK fell back to tier 2, the caller still held the *old* session object — `stop()` stopped the dead one, the fallback kept running. | `SwitchableSession` wrapper: the caller's handle always points at the current inner session; fallback swaps `inner`. |
| 2 | `IndicVoice.startLive` | Fallback ran on the engine callback thread and could run twice (two errors in a row). | `FallbackListener` with an atomic `fellBack` flag; fallback is executed once, on the SDK worker (`safeExecute`). |
| 3 | `IndicVoice.startLive` | Fallback also triggered on `BAD_AUDIO`, `PERMISSION_DENIED`, `CANCELLED` — errors a different tier cannot fix. | No fallback for those codes; they are surfaced to the listener. |
| 4 | `IndicVoice.evictIfNeeded` | Engine eviction (memory cap) could evict the engine a live session was using → crash mid-utterance. | Engines used by live sessions are pinned (`pinned` set) and skipped by eviction; generic made `<T : Any>`. |
| 5 | `IndicVoice.stopSpeaking` | Was a no-op when `speak()` had used a per-call sink. | `play()` always uses the single platform `AudioSink`; `stopSpeaking()` stops it. |
| 6 | `IndicVoice.close` | Engines closed while worker tasks could still run. | Order: shut worker → close engines → `platform.close()` (new `Platform.close()` hook). |
| 7 | `AndroidOsSttEngine` | `SpeechRecognizer` errors raised in `begin()` were reported but the session never finished → listener never got `onStopped`. | `finish()` on begin() errors; callbacks guarded after `done`. |
| 8 | `AndroidOsSttEngine` | Pipe thread for the API-33 batch path was non-daemon and leaked on cancel. | Daemon thread, `close()` shuts the executor. |
| 9 | `AndroidPlatform` | `AudioTrack.Builder` could throw outside the try (`IllegalArgumentException` for odd sample rates); `AudioRecord` leaked on `startRecording` failure. | Build inside try; release on failure; OS engines closed from `Platform.close()`. |
| 10 | `AndroidPlatform.audioSource` | Missing RECORD_AUDIO surfaced as a generic `NO_ENGINE`. | `checkSelfPermission` → `PERMISSION_DENIED`. |
| 11 | Expo `IndicVoiceModule.kt` | `sdk()` lazy init not synchronised; `live`/`voice` not volatile; `startListening` ran on the module's single HandlerThread (blocking other calls); a stale `onStopped` from a previous session could clear the new one; `configure()` replaced the SDK while a live session was running; pack-progress events flooded the JS bridge. | `synchronized sdk()`, `@Volatile` fields, `stateLock`, `runOnQueue(backgroundCoroutineScope)` for long calls, identity-checked `onStopped`, `configure` cancels live first, progress throttled to ~4/s. |
| 12 | `App.tsx` | Voices list did not refresh after a pack install; `removePack` without try/catch; level meter re-rendered at frame rate. | `installedKey` dependency; try/catch; throttled level updates. |

Things the reviewer flagged as *design limits*, not bugs (unchanged, documented in the approval doc):

* Tier-3 live mode is utterance-based (VAD segment → one HTTP request); partial results in tier 3 need a WebSocket path.
* Android tier-1 *batch* transcription (benchmark button) needs Android 13+ (`EXTRA_AUDIO_SOURCE`).
* Language ID between script-sharing languages relies on function-word tables; pass a hint when the app knows the language.
