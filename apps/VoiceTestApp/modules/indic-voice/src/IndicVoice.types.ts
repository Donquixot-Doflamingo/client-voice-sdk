/**
 * Public types of the `indic-voice` native module. These mirror the Kotlin SDK's
 * JSON output; nothing here is vendor specific.
 */

export type Tier = 1 | 2 | 3;

export type LanguageCode =
  | 'as' | 'bn' | 'brx' | 'doi' | 'en' | 'gu' | 'hi' | 'kn' | 'ks' | 'kok' | 'mai' | 'ml'
  | 'mni' | 'mr' | 'ne' | 'or' | 'pa' | 'sa' | 'sat' | 'sd' | 'ta' | 'te' | 'ur';

/** What was spoken. `source` says how sure to be: engine > script > stopwords > hint > default. */
export interface DetectedLanguage {
  code: LanguageCode | string;
  bcp47: string;
  name: string;
  nativeName: string;
  script: string;          // ISO 15924, e.g. Deva, Taml, Latn
  confidence: number;      // 0..1
  source: 'engine' | 'script' | 'stopwords' | 'hint' | 'default' | string;
}

export interface Word {
  text: string;
  startMs?: number;
  endMs?: number;
  confidence?: number;
}

/** The STT output format: the language is always explicit, words are listed. */
export interface TranscriptResult {
  language: DetectedLanguage;
  text: string;
  words: Word[];
  wordCount: number;
  confidence?: number | null;
  tier: Tier;
  tierLabel: string;
  engine: string;
  latencyMs: number;
  audioMs: number;
  rtf: number;
  isFinal: boolean;
}

export interface TtsResult {
  language: string;
  voiceId: string;
  tier: Tier;
  tierLabel: string;
  engine: string;
  latencyMs: number;
  audioMs: number;
  sampleRate: number;
  /** WAV file written by the native side (useful for sharing / replay). */
  path?: string;
}

export interface Availability {
  available: boolean;
  reason?: string | null;
  details?: Record<string, unknown>;
}

export interface TierStatus {
  tier: Tier;
  label: string;
  engine?: string | null;
  availability: Availability;
}

export interface PackSpec {
  id: string;
  kind: 'stt' | 'tts' | 'vad' | string;
  engine: string;
  name: string;
  languages: string[];
  sizeBytes: number;
  sizeMb: number;
  license: string;
  commercialUse: boolean;
  version: string;
  notes: string;
}

export interface PackStatus {
  id: string;
  known: boolean;
  installed: boolean;
  downloading: boolean;
  dir: string;
  spec?: PackSpec | null;
}

export interface Capabilities {
  platform: string;
  device: Record<string, unknown>;
  language: string;
  features: { STT: boolean; TTS: boolean };
  backendUrl: string;
  stt: { policy: string; order: Tier[]; tiers: { tier1: TierStatus; tier2: TierStatus; tier3: TierStatus } };
  tts: { policy: string; order: Tier[]; tiers: { tier1: TierStatus; tier2: TierStatus; tier3: TierStatus } };
  packs: PackStatus[];
}

export interface VoiceInfo {
  id: string;
  language: string;
  tier: Tier;
  name: string;
  gender?: string | null;
  offline: boolean;
  quality?: string | null;
  installed: boolean;
}

export interface BenchmarkRow {
  tier: Tier;
  label: string;
  available: boolean;
  reason?: string | null;
  engine?: string | null;
  ok: boolean;
  result?: TranscriptResult;
  wallMs?: number;
  wer?: number;
  cer?: number;
  error?: { code: string; message: string };
}

export interface BenchmarkReport {
  audioMs: number;
  sampleRate: number;
  rmsDb: number;
  language: string;
  reference?: string | null;
  device: Record<string, unknown>;
  tiers: BenchmarkRow[];
}

export interface TtsBenchmarkRow {
  tier: Tier;
  label: string;
  available: boolean;
  reason?: string | null;
  engine?: string | null;
  ok: boolean;
  result?: TtsResult;
  error?: { code?: string; message: string };
}

export interface ConfigureOptions {
  /** Tier-3 backend base URL (e.g. http://192.168.1.10:8080). Empty = tier 3 off. */
  backendUrl?: string;
  backendToken?: string;
  /** Remote catalog.json; defaults to the catalog bundled with the app. */
  catalogUrl?: string;
  /** Replace the bundled voice-config.json entirely (JSON string). */
  configJson?: string;
}

export interface ListenOptions {
  /** Language hint ('hi', 'ta', ... or 'auto'). */
  language?: string;
  /** Force a tier; omit to follow the policy from voice-config.json. */
  tier?: Tier;
  packId?: string;
  keyterms?: string[];
}

export interface BenchmarkOptions extends ListenOptions {
  tiers?: Tier[];
  /** What was actually said, to compute WER/CER per tier. */
  reference?: string;
}

export interface SpeakOptions {
  text: string;
  language: string;
  tier?: Tier;
  /** 'os:<name>', 'pack:<id>[#voice]', 'hosted:<id>' or a plain voice name. */
  voiceId?: string;
  speed?: number;
  /** Synthesize only (write WAV), don't play. */
  silent?: boolean;
}

export type IndicVoiceEvents = {
  onReady: () => void;
  onSpeechStart: () => void;
  onSpeechEnd: () => void;
  onLevel: (e: { rmsDb: number }) => void;
  onPartial: (e: { text: string; language?: DetectedLanguage | null }) => void;
  onFinal: (e: TranscriptResult) => void;
  onTierChanged: (e: { tier: Tier; reason: string }) => void;
  onError: (e: { code: string; message: string; tier?: Tier | null }) => void;
  onStopped: () => void;
  onPackProgress: (e: { packId: string; phase: string; bytes: number; total: number; percent: number }) => void;
  onTtsStarted: (e: { voiceId: string; tier: Tier; engine: string }) => void;
  onTtsCompleted: (e: { voiceId: string }) => void;
};
