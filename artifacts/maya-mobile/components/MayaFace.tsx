import React, { useEffect, useRef, useState } from 'react';
import { Animated, StyleSheet, View } from 'react-native';

type FaceMode = 'idle' | 'thinking' | 'speaking';

export function MayaFace({
  mode,
  enabled,
  colors
}: {
  mode: FaceMode;
  enabled: boolean;
  colors: { primary: string; foreground: string; card: string; border: string };
}) {
  const blink = useRef(new Animated.Value(1)).current;
  const bob = useRef(new Animated.Value(0)).current;
  const [look, setLook] = useState(-1);

  useEffect(() => {
    if (!enabled) return;
    const blinkTimer = setInterval(() => {
      Animated.sequence([
        Animated.timing(blink, { toValue: 0.12, duration: 90, useNativeDriver: true }),
        Animated.timing(blink, { toValue: 1, duration: 120, useNativeDriver: true })
      ]).start();
    }, 3300);
    const lookTimer = setInterval(() => setLook((value) => (value === -1 ? 1 : -1)), 4600);
    return () => {
      clearInterval(blinkTimer);
      clearInterval(lookTimer);
    };
  }, [blink, enabled]);

  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(bob, { toValue: mode === 'thinking' ? -2 : 1, duration: mode === 'thinking' ? 420 : 1200, useNativeDriver: true }),
        Animated.timing(bob, { toValue: 0, duration: mode === 'thinking' ? 420 : 1200, useNativeDriver: true })
      ])
    );
    loop.start();
    return () => loop.stop();
  }, [bob, mode]);

  if (!enabled) return null;
  return (
    <View pointerEvents="none" style={styles.frame} accessibilityLabel="MAYA face">
      <Animated.View style={[styles.face, { backgroundColor: colors.card, borderColor: colors.border, transform: [{ translateY: bob }] }]}>
        <View style={styles.earLeft} />
        <View style={styles.earRight} />
        <View style={[styles.hair, { backgroundColor: colors.foreground }]} />
        <View style={styles.browRow}>
          <View style={[styles.brow, { backgroundColor: colors.primary }]} />
          <View style={[styles.brow, { backgroundColor: colors.primary }]} />
        </View>
        <View style={styles.eyeRow}>
          <Animated.View style={[styles.eye, { transform: [{ scaleY: blink }, { translateX: look }] }]}>
            <View style={[styles.pupil, { backgroundColor: colors.primary }]} />
          </Animated.View>
          <Animated.View style={[styles.eye, { transform: [{ scaleY: blink }, { translateX: look }] }]}>
            <View style={[styles.pupil, { backgroundColor: colors.primary }]} />
          </Animated.View>
        </View>
        <View style={[styles.nose, { backgroundColor: colors.border }]} />
        <View style={[styles.mouth, { borderColor: colors.primary, height: mode === 'speaking' ? 9 : mode === 'thinking' ? 4 : 6 }]} />
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  frame: { width: 88, height: 72, alignItems: 'center', justifyContent: 'center' },
  face: { width: 58, height: 58, borderRadius: 26, borderWidth: 1.5, alignItems: 'center', paddingTop: 16, overflow: 'visible' },
  hair: { position: 'absolute', top: -2, left: 6, right: 6, height: 18, borderBottomLeftRadius: 14, borderBottomRightRadius: 18, opacity: 0.9 },
  earLeft: { position: 'absolute', left: -5, top: 25, width: 8, height: 14, borderRadius: 5, backgroundColor: '#7b4e45' },
  earRight: { position: 'absolute', right: -5, top: 25, width: 8, height: 14, borderRadius: 5, backgroundColor: '#7b4e45' },
  browRow: { flexDirection: 'row', gap: 15, marginBottom: 4 },
  brow: { width: 10, height: 2, borderRadius: 2 },
  eyeRow: { flexDirection: 'row', gap: 12 },
  eye: { width: 9, height: 12, borderRadius: 6, backgroundColor: '#f5eee8', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  pupil: { width: 4, height: 7, borderRadius: 3 },
  nose: { width: 3, height: 5, borderRadius: 2, marginTop: 3 },
  mouth: { width: 15, borderBottomWidth: 2, borderRadius: 10, marginTop: 3 }
});