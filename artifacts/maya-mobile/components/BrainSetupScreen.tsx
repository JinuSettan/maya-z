import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useColors } from '@/hooks/useColors';

export function BrainSetupScreen({
  progress,
  error,
  onRetry
}: {
  progress: number;
  error: string | null;
  onRetry: () => void;
}) {
  const colors = useColors();
  const ready = !error && progress >= 1;
  return (
    <View style={[styles.root, { backgroundColor: colors.background }]}>
      <View style={[styles.logo, { backgroundColor: colors.primary }]}>
        <Ionicons name="sparkles" size={34} color={colors.primaryForeground} />
      </View>
      <Text style={[styles.title, { color: colors.foreground }]}>
        {ready ? 'MAYA is ready. ✨' : 'Welcome to MAYA 👋'}
      </Text>
      <Text style={[styles.subtitle, { color: colors.mutedForeground }]}>
        {ready ? "Let's talk." : error ? 'We need one more try.' : 'Preparing your brain...'}
      </Text>
      {!ready && !error ? (
        <>
          <View style={[styles.track, { backgroundColor: colors.secondary }]}>
            <View style={[styles.fill, { backgroundColor: colors.primary, width: `${Math.round(progress * 100)}%` }]} />
          </View>
          <Text style={[styles.percent, { color: colors.primary }]}>{Math.round(progress * 100)}%</Text>
          <Text style={[styles.hint, { color: colors.mutedForeground }]}>Almost ready…</Text>
          <ActivityIndicator color={colors.primary} size="small" />
        </>
      ) : null}
      {error ? (
        <>
          <Text style={[styles.error, { color: colors.mutedForeground }]}>
            Check your connection and try again. Your conversations stay on this device.
          </Text>
          <Pressable testID="retry-brain-setup" onPress={onRetry} style={[styles.retry, { backgroundColor: colors.primary }]}>
            <Text style={{ color: colors.primaryForeground, fontWeight: '700' }}>Try again</Text>
          </Pressable>
        </>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: 'center', alignItems: 'center', paddingHorizontal: 34 },
  logo: { width: 78, height: 78, borderRadius: 26, alignItems: 'center', justifyContent: 'center', marginBottom: 28 },
  title: { fontFamily: 'Inter_700Bold', fontSize: 25, textAlign: 'center' },
  subtitle: { fontFamily: 'Inter_400Regular', fontSize: 16, marginTop: 10, textAlign: 'center' },
  track: { width: '100%', height: 8, borderRadius: 8, overflow: 'hidden', marginTop: 36 },
  fill: { height: '100%', borderRadius: 8 },
  percent: { fontFamily: 'Inter_700Bold', fontSize: 22, marginTop: 15 },
  hint: { fontFamily: 'Inter_400Regular', fontSize: 13, marginTop: 7, marginBottom: 18 },
  error: { textAlign: 'center', lineHeight: 21, marginTop: 22, maxWidth: 300 },
  retry: { paddingHorizontal: 20, paddingVertical: 13, borderRadius: 14, marginTop: 18 }
});