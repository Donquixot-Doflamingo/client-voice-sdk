import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Platform, Pressable, SafeAreaView, ScrollView, Text, TextInput, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import IndicVoice, {
  BenchmarkReport, Capabilities, PackStatus, Tier, TranscriptResult, TtsBenchmarkRow, TtsResult, VoiceInfo,
} from './modules/indic-voice';
import { Badge, Button, Card, Chip, TierSelector, TierStatusRow, TranscriptView } from './src/components';
import { colors, styles } from './src/theme';

/**
 * Everything the wrapper needs from the outside comes from .env (EXPO_PUBLIC_*)
 * or from this screen. With no .env the app still runs: tiers 1 and 2 work
 * without a backend, and the backend URL can be typed below.
 */
const ENV = {
  backendUrl: process.env.EXPO_PUBLIC_VOICE_BACKEND_URL ?? '',
  backendToken: process.env.EXPO_PUBLIC_VOICE_BACKEND_TOKEN ?? '',
  catalogUrl: process.env.EXPO_PUBLIC_VOICE_CATALOG_URL ?? '',
  defaultLanguage: process.env.EXPO_PUBLIC_VOICE_DEFAULT_LANGUAGE ?? 'hi',
};

const LANGUAGES: Array<{ code: string; label: string }> = [
  { code: 'auto', label: 'Auto-detect' },
  { code: 'hi', label: 'हिन्दी' }, { code: 'en', label: 'English' }, { code: 'bn', label: 'বাংলা' }, { code: 'ta', label: 'தமிழ்' },
  { code: 'te', label: 'తెలుగు' }, { code: 'mr', label: 'मराठी' }, { code: 'gu', label: 'ગુજરાતી' }, { code: 'kn', label: 'ಕನ್ನಡ' },
  { code: 'ml', label: 'മലയാളം' }, { code: 'pa', label: 'ਪੰਜਾਬੀ' }, { code: 'or', label: 'ଓଡ଼ିଆ' }, { code: 'as', label: 'অসমীয়া' },
  { code: 'ur', label: 'اردو' }, { code: 'ne', label: 'नेपाली' }, { code: 'sa', label: 'संस्कृतम्' }, { code: 'mai', label: 'मैथिली' },
  { code: 'kok', label: 'कोंकणी' }, { code: 'sat', label: 'ᱥᱟᱱᱛᱟᱲᱤ' }, { code: 'ks', label: 'کٲشُر' }, { code: 'sd', label: 'سنڌي' },
  { code: 'doi', label: 'डोगरी' }, { code: 'brx', label: 'बड़ो' }, { code: 'mni', label: 'ꯃꯤꯇꯩꯂꯣꯟ' },
];

const SAMPLE_TEXT: Record<string, string> = {
  hi: 'नमस्ते, मेरा नाम मयंक है और मैं दिल्ली में रहता हूँ।',
  en: 'Hello, my name is Mayank and I live in Delhi.',
  ta: 'வணக்கம், என் பெயர் மயங்க், நான் டெல்லியில் வசிக்கிறேன்.',
  bn: 'নমস্কার, আমার নাম মায়াঙ্ক এবং আমি দিল্লিতে থাকি।',
  te: 'నమస్కారం, నా పేరు మయంక్, నేను ఢిల్లీలో ఉంటాను.',
  mr: 'नमस्कार, माझे नाव मयंक आहे आणि मी दिल्लीत राहतो.',
  ml: 'നമസ്കാരം, എന്റെ പേര് മയങ്ക്, ഞാൻ ഡൽഹിയിൽ താമസിക്കുന്നു.',
};

type LogLine = { t: string; m: string };

export default function App() {
  const [caps, setCaps] = useState<Capabilities | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [backendUrl, setBackendUrl] = useState(ENV.backendUrl);
  const [backendToken, setBackendToken] = useState(ENV.backendToken);
  const [language, setLanguage] = useState(ENV.defaultLanguage || 'hi');
  const [sttTier, setSttTier] = useState<Tier | 0>(0);
  const [ttsTier, setTtsTier] = useState<Tier | 0>(0);
  const [listening, setListening] = useState(false);
  const [level, setLevel] = useState(-60);
  const [partial, setPartial] = useState('');
  const [result, setResult] = useState<TranscriptResult | null>(null);
  const [lastRecording, setLastRecording] = useState<{ path: string; durationMs: number } | null>(null);
  const [reference, setReference] = useState('');
  const [bench, setBench] = useState<BenchmarkReport | null>(null);
  const [ttsText, setTtsText] = useState(SAMPLE_TEXT.hi);
  const [ttsResult, setTtsResult] = useState<TtsResult | null>(null);
  const [ttsBench, setTtsBench] = useState<TtsBenchmarkRow[] | null>(null);
  const [voices, setVoices] = useState<VoiceInfo[]>([]);
  const [voiceId, setVoiceId] = useState<string>('');
  const [packProgress, setPackProgress] = useState<Record<string, string>>({});
  const [log, setLog] = useState<LogLine[]>([]);
  const [sttOn, setSttOn] = useState(true);
  const [ttsOn, setTtsOn] = useState(true);
  const listeningRef = useRef(false);
  const [ready, setReady] = useState(false);   // true once configure() resolved; gates the other native calls

  const addLog = useCallback((m: string) => {
    setLog((l) => [{ t: new Date().toLocaleTimeString(), m }, ...l].slice(0, 40));
  }, []);

  const langArg = language === 'auto' ? undefined : language;

  const refreshCaps = useCallback(async () => {
    try {
      const c = await IndicVoice.capabilities(langArg ?? null);
      setCaps(c);
    } catch (e: any) { addLog(`capabilities failed: ${e.message ?? e}`); }
  }, [langArg, addLog]);

  // ---------------------------------------------------------------- init
  useEffect(() => {
    (async () => {
      setBusy('Loading SDK…');
      try {
        const c = await IndicVoice.configure({ backendUrl: ENV.backendUrl, backendToken: ENV.backendToken, catalogUrl: ENV.catalogUrl });
        setCaps(c);
        setReady(true);
        addLog(`SDK ready on ${c.platform} · policy STT ${c.stt.policy} / TTS ${c.tts.policy}`);
        const p = await IndicVoice.requestPermissions();
        if (!p.microphone) addLog('Microphone permission denied – STT will not work');
      } catch (e: any) { addLog(`configure failed: ${e.message ?? e}`); Alert.alert('SDK failed to start', String(e.message ?? e)); }
      finally { setBusy(null); }
    })();
    const subs = [
      IndicVoice.addListener('onReady', () => addLog('listening…')),
      IndicVoice.addListener('onSpeechStart', () => addLog('speech start')),
      IndicVoice.addListener('onSpeechEnd', () => addLog('speech end → transcribing')),
      IndicVoice.addListener('onLevel', (e) => setLevel((prev) => (Math.abs(prev - e.rmsDb) >= 1 ? Math.round(e.rmsDb) : prev))),
      IndicVoice.addListener('onPartial', (e) => setPartial(e.text)),
      IndicVoice.addListener('onFinal', (r) => { setResult(r); setPartial(''); addLog(`final (tier ${r.tier}, ${r.latencyMs} ms): ${r.language.code} "${r.text}"`); }),
      IndicVoice.addListener('onTierChanged', (e) => addLog(`tier → ${e.tier}: ${e.reason}`)),
      IndicVoice.addListener('onError', (e) => addLog(`error ${e.code}${e.tier ? ` (tier ${e.tier})` : ''}: ${e.message}`)),
      IndicVoice.addListener('onStopped', () => { listeningRef.current = false; setListening(false); addLog('stopped'); }),
      IndicVoice.addListener('onPackProgress', (e) => setPackProgress((p) => ({ ...p, [e.packId]: e.phase === 'done' ? 'done' : `${e.phase} ${e.percent >= 0 ? e.percent + '%' : Math.round(e.bytes / 1048576) + ' MB'}` }))),
      IndicVoice.addListener('onTtsStarted', (e) => addLog(`speaking via tier ${e.tier} (${e.engine})`)),
      IndicVoice.addListener('onTtsCompleted', () => addLog('playback finished')),
    ];
    return () => subs.forEach((s) => s.remove());
  }, []);

  useEffect(() => { if (ready) refreshCaps(); }, [language, ready]);
  useEffect(() => { if (language !== 'auto' && SAMPLE_TEXT[language]) setTtsText(SAMPLE_TEXT[language]); }, [language]);
  const installedKey = useMemo(() => (caps?.packs ?? []).filter((p) => p.installed).map((p) => p.id).join(','), [caps]);
  useEffect(() => {
    if (!ready) return;
    let alive = true;
    IndicVoice.voices(langArg ?? null)
      .then((v) => { if (!alive) return; setVoices(v); setVoiceId((cur) => (v.some((x) => x.id === cur) ? cur : '')); })
      .catch(() => {});
    return () => { alive = false; };
  }, [language, caps?.backendUrl, installedKey, ready]);

  // ---------------------------------------------------------------- actions
  const applyBackend = async () => {
    setBusy('Applying backend…');
    try {
      await IndicVoice.setBackend(backendUrl.trim(), backendToken.trim());
      addLog(backendUrl.trim() ? `backend set: ${backendUrl.trim()}` : 'backend cleared (tier 3 off)');
      await refreshCaps();
    } catch (e: any) { addLog(`setBackend failed: ${e.message ?? e}`); }
    finally { setBusy(null); }
  };

  const toggleListen = async () => {
    if (listeningRef.current) {
      await IndicVoice.stopListening();
      return;
    }
    setResult(null); setPartial('');
    try {
      listeningRef.current = true; setListening(true);
      await IndicVoice.startListening({ language: langArg, tier: sttTier || undefined });
    } catch (e: any) {
      listeningRef.current = false; setListening(false);
      addLog(`startListening failed: ${e.message ?? e}`);
      Alert.alert('Cannot listen', String(e.message ?? e));
    }
  };

  const recordAndBenchmark = async () => {
    setBench(null); setResult(null);
    setBusy('Recording… speak now (stops on silence)');
    try {
      const rec = await IndicVoice.recordUtterance({ maxSeconds: 12 });
      setLastRecording(rec);
      addLog(`recorded ${rec.durationMs} ms (${rec.rmsDb.toFixed(1)} dBFS)`);
      setBusy('Benchmarking all tiers…');
      const report = await IndicVoice.benchmark(rec.path, { language: langArg, reference: reference.trim() || undefined });
      setBench(report);
      const best = report.tiers.find((t) => t.ok);
      if (best?.result) setResult(best.result);
      addLog(`benchmark: ${report.tiers.map((t) => `t${t.tier}=${t.ok ? t.result?.latencyMs + 'ms' : 'n/a'}`).join(' ')}`);
    } catch (e: any) { addLog(`benchmark failed: ${e.message ?? e}`); Alert.alert('Benchmark failed', String(e.message ?? e)); }
    finally { setBusy(null); }
  };

  const rerunBenchmark = async () => {
    if (!lastRecording) return;
    setBusy('Benchmarking…');
    try {
      setBench(await IndicVoice.benchmark(lastRecording.path, { language: langArg, reference: reference.trim() || undefined }));
    } catch (e: any) { addLog(`benchmark failed: ${e.message ?? e}`); }
    finally { setBusy(null); }
  };

  const speak = async () => {
    setTtsResult(null);
    setBusy('Synthesizing…');
    try {
      const r = await IndicVoice.speak({ text: ttsText, language: language === 'auto' ? 'hi' : language, tier: ttsTier || undefined, voiceId: voiceId || undefined });
      setTtsResult(r);
      addLog(`tts tier ${r.tier} ${r.engine} ${r.voiceId}: ${r.audioMs} ms audio in ${r.latencyMs} ms`);
    } catch (e: any) { addLog(`speak failed: ${e.message ?? e}`); Alert.alert('TTS failed', String(e.message ?? e)); }
    finally { setBusy(null); }
  };

  const benchmarkTts = async () => {
    setBusy('Benchmarking TTS tiers…');
    try {
      const r = await IndicVoice.benchmarkTts({ text: ttsText, language: language === 'auto' ? 'hi' : language });
      setTtsBench(r.tiers);
    } catch (e: any) { addLog(`tts benchmark failed: ${e.message ?? e}`); }
    finally { setBusy(null); }
  };

  const installPack = async (id: string) => {
    setPackProgress((p) => ({ ...p, [id]: 'starting' }));
    try {
      await IndicVoice.installPack(id);
      addLog(`pack ${id} installed`);
      await refreshCaps();
    } catch (e: any) { addLog(`pack ${id} failed: ${e.message ?? e}`); setPackProgress((p) => ({ ...p, [id]: `failed: ${e.message ?? e}` })); }
  };

  const removePack = async (id: string) => {
    try {
      await IndicVoice.removePack(id);
      setPackProgress((p) => ({ ...p, [id]: '' }));
      addLog(`pack ${id} removed`);
      await refreshCaps();
    } catch (e: any) { addLog(`remove ${id} failed: ${e.message ?? e}`); }
  };

  const toggleFeature = async (f: 'STT' | 'TTS', on: boolean) => {
    await IndicVoice.setFeatureEnabled(f, on);
    if (f === 'STT') setSttOn(on); else setTtsOn(on);
    addLog(`${f} ${on ? 'enabled' : 'disabled'}`);
    await refreshCaps();
  };

  const packs: PackStatus[] = useMemo(() => caps?.packs ?? [], [caps]);
  const sttPacks = packs.filter((p) => p.spec?.kind === 'stt' || p.spec?.kind === 'vad');
  const ttsPacks = packs.filter((p) => p.spec?.kind === 'tts');

  // ---------------------------------------------------------------- render
  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar style="light" />
      <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
        <Text style={styles.h1}>Indic Voice · test console</Text>
        <Text style={styles.sub}>
          {caps ? `${caps.platform} · ${String(caps.device?.model ?? Platform.OS)} · policy STT ${caps.stt.policy} / TTS ${caps.tts.policy}` : 'starting…'}
        </Text>
        {busy && (
          <View style={[styles.row, { marginTop: 10 }]}>
            <ActivityIndicator color={colors.accent} />
            <Text style={{ color: colors.warn }}>{busy}</Text>
          </View>
        )}

        {/* ---------------------------------------------------------- settings */}
        <Card title="Settings">
          <Text style={styles.label}>Language</Text>
          <View style={styles.row}>
            {LANGUAGES.map((l) => <Chip key={l.code} label={l.label} on={language === l.code} onPress={() => setLanguage(l.code)} />)}
          </View>
          <Text style={styles.label}>Backend API URL (tier 3) — optional; from .env EXPO_PUBLIC_VOICE_BACKEND_URL or type here</Text>
          <TextInput style={styles.input} value={backendUrl} onChangeText={setBackendUrl} placeholder="http://192.168.1.10:8080 (empty = tier 3 off)" placeholderTextColor={colors.muted} autoCapitalize="none" autoCorrect={false} keyboardType="url" />
          <Text style={styles.label}>Backend token (optional)</Text>
          <TextInput style={styles.input} value={backendToken} onChangeText={setBackendToken} placeholder="Bearer token if PROXY_TOKEN is set" placeholderTextColor={colors.muted} autoCapitalize="none" autoCorrect={false} secureTextEntry />
          <View style={[styles.row, { marginTop: 10 }]}>
            <Button label="Apply backend" onPress={applyBackend} />
            <Button label="Refresh status" kind="secondary" onPress={refreshCaps} />
          </View>
          <View style={[styles.row, { marginTop: 10 }]}>
            <Chip label={`STT ${sttOn ? 'on' : 'off'}`} on={sttOn} onPress={() => toggleFeature('STT', !sttOn)} />
            <Chip label={`TTS ${ttsOn ? 'on' : 'off'}`} on={ttsOn} onPress={() => toggleFeature('TTS', !ttsOn)} />
            <Text style={styles.sub}>per-user feature switches</Text>
          </View>
        </Card>

        {/* ---------------------------------------------------------- tiers */}
        <Card title="Speech → text tiers">
          <TierStatusRow status={caps?.stt.tiers.tier1} />
          <TierStatusRow
            status={caps?.stt.tiers.tier2}
            extra={
              <View style={{ marginTop: 6 }}>
                {sttPacks.map((p) => (
                  <View key={p.id} style={[styles.row, { marginTop: 4 }]}>
                    <Text style={{ color: p.installed ? colors.ok : colors.muted, fontSize: 12, flex: 1 }}>
                      {p.installed ? '●' : '○'} {p.id} · {p.spec?.sizeMb} MB · {p.spec?.languages.length} langs
                      {packProgress[p.id] ? `  (${packProgress[p.id]})` : ''}
                    </Text>
                    {p.installed
                      ? <Pressable onPress={() => removePack(p.id)}><Text style={{ color: colors.bad, fontSize: 12 }}>remove</Text></Pressable>
                      : (p.spec?.sizeBytes ?? 0) > 0 && <Pressable onPress={() => installPack(p.id)}><Text style={{ color: colors.accent, fontSize: 12 }}>download</Text></Pressable>}
                  </View>
                ))}
              </View>
            }
          />
          <TierStatusRow status={caps?.stt.tiers.tier3} />
        </Card>

        {/* ---------------------------------------------------------- STT */}
        <Card title="Speech → text">
          <Text style={styles.label}>Which tier to use</Text>
          <TierSelector value={sttTier} onChange={setSttTier} />
          <Pressable onPress={toggleListen} style={[styles.micBtn, { backgroundColor: listening ? colors.bad : colors.accent, borderColor: listening ? `rgba(255,107,107,${Math.min(1, Math.max(0, (level + 60) / 60))})` : colors.border }]}>
            <Text style={styles.micText}>{listening ? 'STOP' : 'SPEAK'}</Text>
          </Pressable>
          <Text style={[styles.sub, { textAlign: 'center' }]}>{listening ? `listening (${level.toFixed(0)} dBFS)… tap to stop` : 'tap, speak, tap again (or wait for silence)'}</Text>
          {partial ? <Text style={{ color: colors.muted, fontStyle: 'italic', marginTop: 8 }}>{partial}</Text> : null}
          <View style={{ marginTop: 10 }}>
            <TranscriptView r={result} />
          </View>
        </Card>

        {/* ---------------------------------------------------------- benchmark */}
        <Card title="Benchmark: same recording through all three tiers">
          <Text style={styles.label}>What you are going to say (optional, for WER/CER)</Text>
          <TextInput style={styles.input} value={reference} onChangeText={setReference} placeholder="reference transcript" placeholderTextColor={colors.muted} />
          <View style={[styles.row, { marginTop: 10 }]}>
            <Button label="Record & benchmark" onPress={recordAndBenchmark} disabled={!!busy || listening} />
            <Button label="Re-run on last recording" kind="secondary" onPress={rerunBenchmark} disabled={!lastRecording || !!busy} />
          </View>
          {bench && (
            <View style={{ marginTop: 10 }}>
              <Text style={styles.mono}>audio {bench.audioMs} ms · {bench.rmsDb} dBFS · lang {bench.language}</Text>
              {bench.tiers.map((t) => (
                <View key={t.tier} style={styles.tierRow}>
                  <View style={[styles.dot, { backgroundColor: t.ok ? colors.ok : t.available ? colors.warn : colors.bad }]} />
                  <View style={{ flex: 1 }}>
                    <Text style={{ color: colors.text, fontWeight: '700' }}>Tier {t.tier} {t.engine ? `· ${t.engine}` : ''}</Text>
                    {t.ok && t.result ? (
                      <>
                        <Text style={styles.mono}>
                          {t.result.latencyMs} ms · RTF {t.result.rtf} · {t.result.language.code}/{t.result.language.script} ({t.result.language.source})
                          {t.wer != null ? ` · WER ${t.wer} · CER ${t.cer}` : ''}
                        </Text>
                        <Text style={{ color: colors.text, marginTop: 2 }}>{t.result.text}</Text>
                      </>
                    ) : (
                      <Text style={{ color: colors.bad, fontSize: 12 }}>{t.reason ?? t.error?.message ?? 'n/a'}</Text>
                    )}
                  </View>
                </View>
              ))}
            </View>
          )}
        </Card>

        {/* ---------------------------------------------------------- TTS */}
        <Card title="Text → speech">
          <TierStatusRow status={caps?.tts.tiers.tier1} />
          <TierStatusRow
            status={caps?.tts.tiers.tier2}
            extra={
              <View style={{ marginTop: 6 }}>
                {ttsPacks.map((p) => (
                  <View key={p.id} style={[styles.row, { marginTop: 4 }]}>
                    <Text style={{ color: p.installed ? colors.ok : colors.muted, fontSize: 12, flex: 1 }}>
                      {p.installed ? '●' : '○'} {p.id} · {p.spec?.sizeMb} MB · {p.spec?.languages.join(',')}{packProgress[p.id] ? `  (${packProgress[p.id]})` : ''}
                    </Text>
                    {p.installed
                      ? <Pressable onPress={() => removePack(p.id)}><Text style={{ color: colors.bad, fontSize: 12 }}>remove</Text></Pressable>
                      : <Pressable onPress={() => installPack(p.id)}><Text style={{ color: colors.accent, fontSize: 12 }}>download</Text></Pressable>}
                  </View>
                ))}
              </View>
            }
          />
          <TierStatusRow status={caps?.tts.tiers.tier3} />
          <Text style={styles.label}>Text to speak</Text>
          <TextInput style={[styles.input, { minHeight: 70 }]} value={ttsText} onChangeText={setTtsText} multiline />
          <Text style={styles.label}>Tier</Text>
          <TierSelector value={ttsTier} onChange={setTtsTier} />
          {voices.length > 0 && (
            <>
              <Text style={styles.label}>Voice (optional)</Text>
              <View style={styles.row}>
                <Chip label="default" on={!voiceId} onPress={() => setVoiceId('')} />
                {voices.slice(0, 16).map((v) => (
                  <Chip key={v.id} label={`${v.name}${v.installed ? '' : ' ↓'}`} on={voiceId === v.id} onPress={() => setVoiceId(v.id)} />
                ))}
              </View>
            </>
          )}
          <View style={[styles.row, { marginTop: 10 }]}>
            <Button label="Speak" onPress={speak} disabled={!!busy} />
            <Button label="Stop" kind="secondary" onPress={() => IndicVoice.stopSpeaking()} />
            <Button label="Benchmark TTS tiers" kind="secondary" onPress={benchmarkTts} disabled={!!busy} />
          </View>
          {ttsResult && (
            <Text style={[styles.mono, { marginTop: 8 }]}>
              tier {ttsResult.tier} · {ttsResult.engine} · voice {ttsResult.voiceId} · {ttsResult.audioMs} ms audio in {ttsResult.latencyMs} ms · {ttsResult.sampleRate} Hz
            </Text>
          )}
          {ttsBench && ttsBench.map((t) => (
            <View key={t.tier} style={styles.tierRow}>
              <View style={[styles.dot, { backgroundColor: t.ok ? colors.ok : t.available ? colors.warn : colors.bad }]} />
              <View style={{ flex: 1 }}>
                <Text style={{ color: colors.text, fontWeight: '700' }}>Tier {t.tier} {t.engine ? `· ${t.engine}` : ''}</Text>
                <Text style={t.ok ? styles.mono : { color: colors.bad, fontSize: 12 }}>
                  {t.ok && t.result ? `${t.result.latencyMs} ms for ${t.result.audioMs} ms of audio · ${t.result.voiceId}` : t.reason ?? t.error?.message ?? 'n/a'}
                </Text>
              </View>
            </View>
          ))}
        </Card>

        {/* ---------------------------------------------------------- log */}
        <Card title="Log">
          {log.length === 0 ? <Text style={styles.sub}>—</Text> : log.map((l, i) => <Text key={i} style={styles.logLine}>{l.t} {l.m}</Text>)}
        </Card>
      </ScrollView>
    </SafeAreaView>
  );
}
