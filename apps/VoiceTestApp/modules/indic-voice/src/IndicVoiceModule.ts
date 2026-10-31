import { NativeModule, requireNativeModule } from 'expo';
import type {
  BenchmarkOptions, BenchmarkReport, Capabilities, ConfigureOptions, IndicVoiceEvents, ListenOptions,
  PackStatus, SpeakOptions, Tier, TranscriptResult, TtsBenchmarkRow, TtsResult, VoiceInfo,
} from './IndicVoice.types';

/**
 * Native interface implemented by the Kotlin (Android) and Swift (iOS) modules.
 * Every method returns plain JSON-compatible objects; audio never crosses the bridge.
 */
declare class IndicVoiceNativeModule extends NativeModule<IndicVoiceEvents> {
  /** Loads the SDK with the bundled voice-config.json; injects backend URL/token from outside. */
  configure(options: ConfigureOptions): Promise<Capabilities>;
  capabilities(language?: string | null): Promise<Capabilities>;
  setBackend(url?: string | null, token?: string | null): Promise<void>;
  setFeatureEnabled(feature: 'STT' | 'TTS', enabled: boolean): Promise<void>;
  requestPermissions(): Promise<{ microphone: boolean; speech: boolean }>;

  // Speech -> text
  startListening(options: ListenOptions): Promise<void>;
  stopListening(): Promise<void>;
  cancelListening(): Promise<void>;
  /** Records one utterance (VAD endpointed) to a WAV file for benchmarking. */
  recordUtterance(options: { maxSeconds?: number }): Promise<{ path: string; durationMs: number; rmsDb: number }>;
  transcribeFile(path: string, options: ListenOptions): Promise<TranscriptResult>;
  benchmark(path: string, options: BenchmarkOptions): Promise<BenchmarkReport>;

  // Text -> speech
  speak(options: SpeakOptions): Promise<TtsResult>;
  stopSpeaking(): Promise<void>;
  benchmarkTts(options: SpeakOptions): Promise<{ text: string; language: string; tiers: TtsBenchmarkRow[] }>;
  voices(language?: string | null): Promise<VoiceInfo[]>;

  // Tier-2 packs
  packs(): Promise<PackStatus[]>;
  installPack(id: string): Promise<PackStatus>;
  removePack(id: string): Promise<boolean>;
}

export default requireNativeModule<IndicVoiceNativeModule>('IndicVoice');
export type { Tier };
