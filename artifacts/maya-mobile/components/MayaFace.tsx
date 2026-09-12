import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  StyleSheet,
  View,
} from 'react-native';

type FaceMode = 'idle' | 'thinking' | 'speaking';

type MayaFaceProps = {
  mode: FaceMode;
  enabled: boolean;
  colors: {
    primary: string;
    foreground: string;
    card: string;
    border: string;
  };
};

export function MayaFace({
  mode,
  enabled,
  colors,
}: MayaFaceProps) {
  const blink = useRef(new Animated.Value(1)).current;
  const faceFloat = useRef(new Animated.Value(0)).current;
  const eyeLook = useRef(new Animated.Value(0)).current;
  const mouthOpen = useRef(new Animated.Value(0)).current;
  const browLeft = useRef(new Animated.Value(0)).current;
  const browRight = useRef(new Animated.Value(0)).current;
  const hairMove = useRef(new Animated.Value(0)).current;

  const [blinkBusy, setBlinkBusy] = useState(false);

  /*
   * Natural blinking
   */
  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;

    const blinkLoop = () => {
      if (cancelled) return;

      const delay = 2200 + Math.random() * 2800;

      const timer = setTimeout(() => {
        if (cancelled || blinkBusy) {
          blinkLoop();
          return;
        }

        setBlinkBusy(true);

        Animated.sequence([
          Animated.timing(blink, {
            toValue: 0.08,
            duration: 75,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(blink, {
            toValue: 1,
            duration: 115,
            easing: Easing.out(Easing.ease),
            useNativeDriver: true,
          }),
        ]).start(() => {
          setBlinkBusy(false);
          blinkLoop();
        });
      }, delay);

      return () => clearTimeout(timer);
    };

    const cleanup = blinkLoop();

    return () => {
      cancelled = true;
      if (cleanup) cleanup();
    };
  }, [enabled, blink, blinkBusy]);

  /*
   * Gentle floating movement
   */
  useEffect(() => {
    if (!enabled) return;

    const duration = mode === 'thinking' ? 520 : 1500;

    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(faceFloat, {
          toValue: mode === 'thinking' ? -2 : 1.5,
          duration,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(faceFloat, {
          toValue: 0,
          duration,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ])
    );

    animation.start();

    return () => animation.stop();
  }, [enabled, mode, faceFloat]);

  /*
   * Eye movement
   */
  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;

    const lookAround = () => {
      if (cancelled) return;

      const delay = mode === 'thinking' ? 900 : 2600 + Math.random() * 2200;

      const timer = setTimeout(() => {
        if (cancelled) return;

        const direction =
          mode === 'thinking'
            ? Math.random() > 0.5
              ? -4
              : 4
            : Math.random() > 0.5
              ? -3
              : 3;

        Animated.sequence([
          Animated.timing(eyeLook, {
            toValue: direction,
            duration: 300,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.delay(mode === 'thinking' ? 500 : 700),
          Animated.timing(eyeLook, {
            toValue: 0,
            duration: 350,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ]).start(() => lookAround());
      }, delay);

      return () => clearTimeout(timer);
    };

    const cleanup = lookAround();

    return () => {
      cancelled = true;
      if (cleanup) cleanup();
    };
  }, [enabled, mode, eyeLook]);

  /*
   * Mouth animation
   */
  useEffect(() => {
    if (!enabled) return;

    if (mode === 'speaking') {
      const animation = Animated.loop(
        Animated.sequence([
          Animated.timing(mouthOpen, {
            toValue: 1,
            duration: 130,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: false,
          }),
          Animated.timing(mouthOpen, {
            toValue: 0.25,
            duration: 110,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: false,
          }),
          Animated.timing(mouthOpen, {
            toValue: 0.7,
            duration: 120,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: false,
          }),
          Animated.timing(mouthOpen, {
            toValue: 0,
            duration: 150,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: false,
          }),
        ])
      );

      animation.start();

      return () => animation.stop();
    }

    Animated.timing(mouthOpen, {
      toValue: mode === 'thinking' ? 0.15 : 0,
      duration: 180,
      useNativeDriver: false,
    }).start();
  }, [enabled, mode, mouthOpen]);

  /*
   * Eyebrow expression
   */
  useEffect(() => {
    if (!enabled) return;

    const leftTarget = mode === 'thinking' ? -2 : 0;
    const rightTarget = mode === 'thinking' ? 2 : 0;

    Animated.parallel([
      Animated.timing(browLeft, {
        toValue: leftTarget,
        duration: 250,
        useNativeDriver: true,
      }),
      Animated.timing(browRight, {
        toValue: rightTarget,
        duration: 250,
        useNativeDriver: true,
      }),
    ]).start();
  }, [enabled, mode, browLeft, browRight]);

  /*
   * Small hair movement
   */
  useEffect(() => {
    if (!enabled) return;

    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(hairMove, {
          toValue: 1,
          duration: 1800,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(hairMove, {
          toValue: -1,
          duration: 1800,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ])
    );

    animation.start();

    return () => animation.stop();
  }, [enabled, hairMove]);

  if (!enabled) {
    return null;
  }

  const mouthHeight = mouthOpen.interpolate({
    inputRange: [0, 1],
    outputRange: [5, 12],
  });

  const mouthRadius = mouthOpen.interpolate({
    inputRange: [0, 1],
    outputRange: [8, 14],
  });

  const hairRotation = hairMove.interpolate({
    inputRange: [-1, 1],
    outputRange: ['-2deg', '2deg'],
  });

  return (
    <View
      pointerEvents="none"
      style={styles.frame}
      accessibilityLabel="MAYA animated face"
    >
      <Animated.View
        style={[
          styles.face,
          {
            backgroundColor: colors.card,
            borderColor: colors.border,
            transform: [{ translateY: faceFloat }],
          },
        ]}
      >
        {/* Back hair */}
        <View
          style={[
            styles.backHair,
            {
              backgroundColor: colors.foreground,
            },
          ]}
        />

        {/* Left ear */}
        <View
          style={[
            styles.ear,
            styles.leftEar,
            {
              backgroundColor: colors.card,
              borderColor: colors.border,
            },
          ]}
        />

        {/* Right ear */}
        <View
          style={[
            styles.ear,
            styles.rightEar,
            {
              backgroundColor: colors.card,
              borderColor: colors.border,
            },
          ]}
        />

        {/* Hair */}
        <Animated.View
          style={[
            styles.hair,
            {
              backgroundColor: colors.foreground,
              transform: [{ rotate: hairRotation }],
            },
          ]}
        >
          <View style={styles.hairPointOne} />
          <View style={styles.hairPointTwo} />
          <View style={styles.hairPointThree} />
        </Animated.View>

        {/* Face highlight */}
        <View
          style={[
            styles.faceHighlight,
            {
              backgroundColor: colors.primary,
            },
          ]}
        />

        {/* Eyebrows */}
        <View style={styles.browRow}>
          <Animated.View
            style={[
              styles.brow,
              {
                backgroundColor: colors.primary,
                transform: [{ translateY: browLeft }],
              },
            ]}
          />

          <Animated.View
            style={[
              styles.brow,
              {
                backgroundColor: colors.primary,
                transform: [{ translateY: browRight }],
              },
            ]}
          />
        </View>

        {/* Eyes */}
        <View style={styles.eyeRow}>
          <Animated.View
            style={[
              styles.eye,
              {
                transform: [
                  { scaleY: blink },
                  { translateX: eyeLook },
                ],
              },
            ]}
          >
            <View
              style={[
                styles.iris,
                {
                  backgroundColor: colors.primary,
                },
              ]}
            >
              <View style={styles.eyeSparkle} />
              <View
                style={[
                  styles.pupil,
                  {
                    backgroundColor: colors.foreground,
                  },
                ]}
              />
            </View>
          </Animated.View>

          <Animated.View
            style={[
              styles.eye,
              {
                transform: [
                  { scaleY: blink },
                  { translateX: eyeLook },
                ],
              },
            ]}
          >
            <View
              style={[
                styles.iris,
                {
                  backgroundColor: colors.primary,
                },
              ]}
            >
              <View style={styles.eyeSparkle} />
              <View
                style={[
                  styles.pupil,
                  {
                    backgroundColor: colors.foreground,
                  },
                ]}
              />
            </View>
          </Animated.View>
        </View>

        {/* Nose */}
        <View
          style={[
            styles.nose,
            {
              backgroundColor: colors.border,
            },
          ]}
        />

        {/* Blush */}
        <View
          style={[
            styles.blush,
            styles.leftBlush,
            {
              backgroundColor: colors.primary,
            },
          ]}
        />

        <View
          style={[
            styles.blush,
            styles.rightBlush,
            {
              backgroundColor: colors.primary,
            },
          ]}
        />

        {/* Animated mouth */}
        <Animated.View
          style={[
            styles.mouth,
            {
              height: mouthHeight,
              borderColor: colors.primary,
              borderRadius: mouthRadius,
              backgroundColor:
                mode === 'speaking'
                  ? `${colors.primary}18`
                  : 'transparent',
            },
          ]}
        />

        {/* Chin / jaw highlight */}
        <View
          style={[
            styles.jawHighlight,
            {
              backgroundColor: colors.border,
            },
          ]}
        />
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  frame: {
    width: 96,
    height: 82,
    alignItems: 'center',
    justifyContent: 'center',
  },

  face: {
    width: 62,
    height: 64,
    borderRadius: 30,
    borderWidth: 1.5,
    alignItems: 'center',
    paddingTop: 18,
    overflow: 'visible',
  },

  backHair: {
    position: 'absolute',
    width: 54,
    height: 48,
    top: 10,
    borderRadius: 26,
    opacity: 0.22,
  },

  ear: {
    position: 'absolute',
    width: 9,
    height: 17,
    borderRadius: 6,
    borderWidth: 1,
    top: 27,
  },

  leftEar: {
    left: -7,
  },

  rightEar: {
    right: -7,
  },

  hair: {
    position: 'absolute',
    top: -6,
    left: 4,
    right: 4,
    height: 25,
    borderTopLeftRadius: 25,
    borderTopRightRadius: 27,
    borderBottomLeftRadius: 13,
    borderBottomRightRadius: 15,
    opacity: 0.95,
  },

  hairPointOne: {
    position: 'absolute',
    left: 5,
    bottom: -7,
    width: 13,
    height: 15,
    borderBottomLeftRadius: 12,
    transform: [{ rotate: '-18deg' }],
  },

  hairPointTwo: {
    position: 'absolute',
    left: 23,
    bottom: -8,
    width: 12,
    height: 17,
    borderBottomLeftRadius: 10,
    transform: [{ rotate: '12deg' }],
  },

  hairPointThree: {
    position: 'absolute',
    right: 5,
    bottom: -6,
    width: 12,
    height: 14,
    borderBottomRightRadius: 10,
    transform: [{ rotate: '18deg' }],
  },

  faceHighlight: {
    position: 'absolute',
    top: 13,
    right: 14,
    width: 4,
    height: 4,
    borderRadius: 4,
    opacity: 0.65,
  },

  browRow: {
    flexDirection: 'row',
    gap: 18,
    marginBottom: 4,
  },

  brow: {
    width: 12,
    height: 2.5,
    borderRadius: 3,
  },

  eyeRow: {
    flexDirection: 'row',
    gap: 12,
  },

  eye: {
    width: 13,
    height: 17,
    borderRadius: 9,
    backgroundColor: '#f5eee8',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },

  iris: {
    width: 9,
    height: 12,
    borderRadius: 7,
    alignItems: 'center',
    justifyContent: 'center',
  },

  pupil: {
    width: 4,
    height: 7,
    borderRadius: 4,
  },

  eyeSparkle: {
    position: 'absolute',
    top: 1,
    left: 2,
    width: 3,
    height: 3,
    borderRadius: 3,
    backgroundColor: '#ffffff',
    opacity: 0.9,
  },

  nose: {
    width: 3,
    height: 5,
    borderRadius: 3,
    marginTop: 3,
  },

  blush: {
    position: 'absolute',
    width: 8,
    height: 3,
    borderRadius: 5,
    opacity: 0.18,
    top: 40,
  },

  leftBlush: {
    left: 7,
  },

  rightBlush: {
    right: 7,
  },

  mouth: {
    width: 17,
    borderWidth: 1.5,
    marginTop: 3,
    alignItems: 'center',
    justifyContent: 'center',
  },

  jawHighlight: {
    position: 'absolute',
    bottom: 7,
    width: 12,
    height: 1,
    borderRadius: 2,
    opacity: 0.35,
  },
});
