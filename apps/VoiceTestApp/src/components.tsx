import React from 'react';
import { Pressable, Text, View, ViewStyle } from 'react-native';
import { colors, styles } from './theme';
import type { Availability, DetectedLanguage, Tier, TierStatus, TranscriptResult } from '../modules/indic-voice';

export function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>{title}</Text>
      {children}
    </View>
  );
}

export function Chip({ label, on, onPress, style }: { label: string; on?: boolean; onPress?: () => void; style?: ViewStyle }) {
  return (
    <Pressable onPress={onPress} style={[styles.chip, on && styles.chipOn, style]}>
      <Text style={styles.chipText}>{label}</Text>
    </Pressable>
  );
}

export function Button({ label, onPress, kind, disabled }: { label: string; onPress: () => void; kind?: 'primary' | 'secondary' | 'danger'; disabled?: boolean }) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={[styles.btn, kind === 'secondary' && styles.btnSecondary, kind === 'danger' && styles.btnDanger, disabled && { opacity: 0.45 }]}>
      <Text style={styles.btnText}>{label}</Text>
    </Pressable>
  );
}

export function Badge({ text, color }: { text: string; color?: string }) {
  return (
    <View style={[styles.badge, color ? { borderColor: color } : null]}>
      <Text style={[styles.badgeText, color ? { color } : null]}>{text}</Text>
    </View>
  );
}

export function TierSelector({ value, onChange, allowAuto = true }: { value: Tier | 0; onChange: (t: Tier | 0) => void; allowAuto?: boolean }) {
  const options: Array<{ v: Tier | 0; l: string }> = [
    ...(allowAuto ? [{ v: 0 as const, l: 'Auto (policy)' }] : []),
    { v: 1, l: 'Tier 1 · OS' },
    { v: 2, l: 'Tier 2 · On-device' },
    { v: 3, l: 'Tier 3 · Backend' },
  ];
  return (
    <View style={styles.row}>
      {options.map((o) => (
        <Chip key={o.v} label={o.l} on={value === o.v} onPress={() => onChange(o.v)} />
      ))}
    </View>
  );
}

export function availabilityColor(a?: Availability | null): string {
  if (!a) return colors.muted;
  if (a.available && !a.reason) return colors.ok;
  if (a.available) return colors.warn;
  return colors.bad;
}

export function TierStatusRow({ status, extra }: { status?: TierStatus; extra?: React.ReactNode }) {
  if (!status) return null;
  const a = status.availability;
  const details = a?.details ? Object.entries(a.details).filter(([k]) => ['packId', 'engine', 'url', 'onDeviceRecognizer', 'installedOnDevice', 'voices', 'offlineVoices', 'health', 'loadMs', 'sizeMb'].includes(k)) : [];
  return (
    <View style={styles.tierRow}>
      <View style={[styles.dot, { backgroundColor: availabilityColor(a) }]} />
      <View style={{ flex: 1 }}>
        <Text style={{ color: colors.text, fontWeight: '700' }}>
          Tier {status.tier} · {status.label.replace(/^tier\d-/, '')} {status.engine ? <Text style={{ color: colors.muted, fontWeight: '400' }}>({status.engine})</Text> : null}
        </Text>
        <Text style={{ color: a?.available ? colors.muted : colors.bad, fontSize: 12 }}>
          {a?.available ? (a.reason ? `available – ${a.reason}` : 'available') : a?.reason ?? 'unavailable'}
        </Text>
        {details.length > 0 && (
          <Text style={{ color: colors.muted, fontSize: 11, marginTop: 2 }} numberOfLines={3}>
            {details.map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`).join('  ')}
          </Text>
        )}
        {extra}
      </View>
    </View>
  );
}

export function LanguageBadge({ lang }: { lang?: DetectedLanguage | null }) {
  if (!lang) return null;
  return (
    <View style={[styles.row, { marginTop: 6 }]}>
      <Badge text={`${lang.name} · ${lang.code}`} color={colors.accent} />
      <Badge text={`script ${lang.script}`} />
      <Badge text={`${Math.round(lang.confidence * 100)}% via ${lang.source}`} />
    </View>
  );
}

export function TranscriptView({ r }: { r?: TranscriptResult | null }) {
  if (!r) return <Text style={styles.sub}>No result yet.</Text>;
  return (
    <View>
      <LanguageBadge lang={r.language} />
      <Text style={[styles.big, { marginTop: 8 }]}>{r.text || '(empty)'}</Text>
      <Text style={[styles.label, { marginTop: 10 }]}>Words ({r.wordCount})</Text>
      <View style={[styles.row, { gap: 0 }]}>
        {r.words.map((w, i) => (
          <View key={i} style={styles.wordChip}>
            <Text style={styles.chipText}>
              {w.text}
              {w.startMs != null ? <Text style={{ color: colors.muted, fontSize: 10 }}> {(w.startMs / 1000).toFixed(2)}s</Text> : null}
            </Text>
          </View>
        ))}
      </View>
      <Text style={[styles.mono, { marginTop: 8 }]}>
        tier {r.tier} · {r.engine} · {r.latencyMs} ms for {r.audioMs} ms audio · RTF {r.rtf}
        {r.confidence != null ? ` · conf ${r.confidence}` : ''}
      </Text>
    </View>
  );
}
