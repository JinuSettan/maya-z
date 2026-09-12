import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useColors } from '@/hooks/useColors';

export function ErrorFallback({ error }: { error: Error }) {
  const colors = useColors();
  return (
    <View style={[styles.root, { backgroundColor: colors.background }]}>
      <Text style={[styles.title, { color: colors.foreground }]}>MAYA needs a restart.</Text>
      <Text style={[styles.body, { color: colors.mutedForeground }]}>{error.message}</Text>
      <Pressable style={[styles.button, { backgroundColor: colors.primary }]}>
        <Text style={{ color: colors.primaryForeground }}>Close and reopen MAYA</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 28, gap: 12 },
  title: { fontSize: 20, fontWeight: '700' },
  body: { textAlign: 'center', lineHeight: 20 },
  button: { borderRadius: 14, paddingHorizontal: 18, paddingVertical: 12 }
});