import React, { useEffect, useRef } from 'react';
import {
  Animated,
  Easing,
  StyleSheet,
  View,
} from 'react-native';

type FaceMode = 'idle' | 'thinking' | 'speaking';

type RockyFaceProps = {
  mode: FaceMode;
  enabled: boolean;
  colors: {
    primary: string;
    foreground: string;
    card: string;
    border: string;
  };
};

export function RockyFace({
  mode,
  enabled,
  colors,
}: RockyFaceProps) {
  const blink = useRef(new Animated.Value(1)).current;

  // X/Y eye movement
  const eyeX = useRef(new Animated.Value(0)).current;
  const eyeY = useRef(new Animated.Value(0)).current;

  // Face movement
  const headX = useRef(new Animated.Value(0)).current;
  const headY = useRef(new Animated.Value(0)).current;

  // Speaking animation
  const mouth = useRef(new Animated.Value(0)).current;

  // Hair movement
  const hair = useRef(new Animated.Value(0)).current;

  /*
   * BLINK
   */
  useEffect(() => {
    if (!enabled) return;

    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;

    const blinkLoop = () => {
      if (stopped) return;

      timer = setTimeout(() => {
        if (stopped) return;

        Animated.sequence([
          Animated.timing(blink, {
            toValue: 0.08,
            duration: 75,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(blink, {
            toValue: 1,
            duration: 120,
            easing: Easing.out(Easing.ease),
            useNativeDriver: true,
          }),
        ]).start(() => blinkLoop());
      }, 2200 + Math.random() * 2800);
    };

    blinkLoop();

    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [enabled, blink]);

  /*
   * X/Y EYE LOOP
   */
  useEffect(() => {
    if (!enabled) return;

    let stopped = false;

    const moveEyes = () => {
      if (stopped) return;

      const x =
        mode === 'thinking'
          ? Math.random() > 0.5
            ? -5
            : 5
          : Math.random() > 0.5
            ? -3
            : 3;

      const y =
        mode === 'thinking'
          ? Math.random() > 0.5
            ? -3
            : 3
          : Math.random() > 0.5
            ? -1.5
            : 1.5;

      Animated.parallel([
        Animated.timing(eyeX, {
          toValue: x,
          duration: 450,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(eyeY, {
          toValue: y,
          duration: 450,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ]).start(() => {
        if (stopped) return;

        setTimeout(() => {
          Animated.parallel([
            Animated.timing(eyeX, {
              toValue: 0,
              duration: 400,
              useNativeDriver: true,
            }),
            Animated.timing(eyeY, {
              toValue: 0,
              duration: 400,
              useNativeDriver: true,
            }),
          ]).start(() => moveEyes());
        }, mode === 'thinking' ? 450 : 900);
      });
    };

    moveEyes();

    return () => {
      stopped = true;
    };
  }, [enabled, mode, eyeX, eyeY]);

  /*
   * SUBTLE HEAD X/Y MOVEMENT
   */
  useEffect(() => {
    if (!enabled) return;

    const animation = Animated.loop(
      Animated.sequence([
        Animated.parallel([
          Animated.timing(headX, {
            toValue: mode === 'thinking' ? -1.5 : 1,
            duration: mode === 'thinking' ? 650 : 1500,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(headY, {
            toValue: mode === 'thinking' ? -2 : 1,
            duration: mode === 'thinking' ? 650 : 1500,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ]),

        Animated.parallel([
          Animated.timing(headX, {
            toValue: 0,
            duration: mode === 'thinking' ? 650 : 1500,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(headY, {
            toValue: 0,
            duration: mode === 'thinking' ? 650 : 1500,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ]),
      ])
    );

    animation.start();

    return () => animation.stop();
  }, [enabled, mode, headX, headY]);

  /*
   * SPEAKING MOUTH
   */
  useEffect(() => {
    if (!enabled) return;

    if (mode === 'speaking') {
      const animation = Animated.loop(
        Animated.sequence([
          Animated.timing(mouth, {
            toValue: 1,
            duration: 130,
            useNativeDriver: false,
          }),
          Animated.timing(mouth, {
            toValue: 0.25,
            duration: 110,
            useNativeDriver: false,
          }),
          Animated.timing(mouth, {
            toValue: 0.75,
            duration: 120,
            useNativeDriver: false,
          }),
          Animated.timing(mouth, {
            toValue: 0,
            duration: 140,
            useNativeDriver: false,
          }),
        ])
      );

      animation.start();

      return () => animation.stop();
    }

    Animated.timing(mouth, {
      toValue: mode === 'thinking' ? 0.15 : 0,
      duration: 180,
      useNativeDriver: false,
    }).start();
  }, [enabled, mode, mouth]);

  /*
   * HAIR MOVEMENT
   */
  useEffect(() => {
    if (!enabled) return;

    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(hair, {
          toValue: 1,
          duration: 1800,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(hair, {
          toValue: -1,
          duration: 1800,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ])
    );

    animation.start();

    return () => animation.stop();
  }, [enabled, hair]);

  if (!enabled) return null;

  const mouthHeight = mouth.interpolate({
    inputRange: [0, 1],
    outputRange: [3, 11],
  });

  const mouthWidth = mouth.interpolate({
    inputRange: [0, 1],
    outputRange: [16, 19],
  });

  const hairRotation = hair.interpolate({
    inputRange: [-1, 1],
    outputRange: ['-2deg', '2deg'],
  });

  return (
    <View
      pointerEvents="none"
      style={styles.frame}
      accessibilityLabel="ROCKY animated face"
    >
      <Animated.View
        style={[
          styles.face,
          {
            backgroundColor: colors.card,
            borderColor: colors.primary,
            transform: [
              { translateX: headX },
              { translateY: headY },
            ],
          },
        ]}
      >
        {/* Back hair */}
        <View
          style={[
            styles.backHair,
            { backgroundColor: colors.foreground },
          ]}
        />

        {/* Ears */}
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

        {/* Main hair */}
        <Animated.View
          style={[
            styles.hair,
            {
              backgroundColor: colors.foreground,
              transform: [{ rotate: hairRotation }],
            },
          ]}
        >
          <View style={styles.hairLockOne} />
          <View style={styles.hairLockTwo} />
          <View style={styles.hairLockThree} />
          <View style={styles.hairLockFour} />
        </Animated.View>

        {/* Blue hair glow */}
        <View
          style={[
            styles.hairGlow,
            { backgroundColor: colors.primary },
          ]}
        />

        {/* Eyebrows */}
        <View style={styles.browRow}>
          <View
            style={[
              styles.brow,
              { backgroundColor: colors.primary },
            ]}
          />
          <View
            style={[
              styles.brow,
              { backgroundColor: colors.primary },
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
                  { translateX: eyeX },
                  { translateY: eyeY },
                ],
              },
            ]}
          >
            <View
              style={[
                styles.iris,
                { backgroundColor: colors.primary },
              ]}
            >
              <View style={styles.bigSparkle} />
              <View style={styles.smallSparkle} />

              <View
                style={[
                  styles.pupil,
                  { backgroundColor: colors.foreground },
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
                  { translateX: eyeX },
                  { translateY: eyeY },
                ],
              },
            ]}
          >
            <View
              style={[
                styles.iris,
                { backgroundColor: colors.primary },
              ]}
            >
              <View style={styles.bigSparkle} />
              <View style={styles.smallSparkle} />

              <View
                style={[
                  styles.pupil,
                  { backgroundColor: colors.foreground },
                ]}
              />
            </View>
          </Animated.View>
        </View>

        {/* Nose */}
        <View
          style={[
            styles.nose,
            { backgroundColor: colors.border },
          ]}
        />

        {/* Small cheek highlights */}
        <View
          style={[
            styles.cheek,
            styles.leftCheek,
            { backgroundColor: colors.primary },
          ]}
        />

        <View
          style={[
            styles.cheek,
            styles.rightCheek,
            { backgroundColor: colors.primary },
          ]}
        />

        {/* Speaking mouth */}
        <Animated.View
          style={[
            styles.mouth,
            {
              width: mouthWidth,
              height: mouthHeight,
              borderColor: colors.primary,
              backgroundColor:
                mode === 'speaking'
                  ? `${colors.primary}20`
                  : 'transparent',
            },
          ]}
        />

        {/* Chin */}
        <View
          style={[
            styles.chin,
            { backgroundColor: colors.border },
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
    borderRadius: 29,
    borderWidth: 1.5,
    alignItems: 'center',
    paddingTop: 18,
    overflow: 'visible',
  },

  backHair: {
    position: 'absolute',
    width: 58,
    height: 50,
    top: 8,
    borderRadius: 27,
    opacity: 0.28,
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
    top: -8,
    left: 2,
    right: 2,
    height: 28,
    borderTopLeftRadius: 25,
    borderTopRightRadius: 28,
    borderBottomLeftRadius: 15,
    borderBottomRightRadius: 17,
  },

  hairLockOne: {
    position: 'absolute',
    left: 4,
    bottom: -11,
    width: 15,
    height: 20,
    borderBottomLeftRadius: 14,
    transform: [{ rotate: '-22deg' }],
  },

  hairLockTwo: {
    position: 'absolute',
    left: 17,
    bottom: -12,
    width: 13,
    height: 22,
    borderBottomLeftRadius: 12,
    transform: [{ rotate: '-5deg' }],
  },

  hairLockThree: {
    position: 'absolute',
    left: 31,
    bottom: -10,
    width: 13,
    height: 19,
    borderBottomRightRadius: 12,
    transform: [{ rotate: '7deg' }],
  },

  hairLockFour: {
    position: 'absolute',
    right: 3,
    bottom: -9,
    width: 14,
    height: 18,
    borderBottomRightRadius: 13,
    transform: [{ rotate: '20deg' }],
  },

  hairGlow: {
    position: 'absolute',
    top: 1,
    left: 11,
    width: 4,
    height: 22,
    borderRadius: 4,
    opacity: 0.5,
    transform: [{ rotate: '-18deg' }],
  },

  browRow: {
    flexDirection: 'row',
    gap: 18,
    marginBottom: 4,
  },

  brow: {
    width: 13,
    height: 2.5,
    borderRadius: 3,
  },

  eyeRow: {
    flexDirection: 'row',
    gap: 12,
  },

  eye: {
    width: 14,
    height: 17,
    borderRadius: 9,
    backgroundColor: '#eef5ff',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },

  iris: {
    width: 10,
    height: 13,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },

  pupil: {
    width: 4,
    height: 8,
    borderRadius: 5,
  },

  bigSparkle: {
    position: 'absolute',
    top: 1,
    left: 2,
    width: 3,
    height: 3,
    borderRadius: 3,
    backgroundColor: '#ffffff',
  },

  smallSparkle: {
    position: 'absolute',
    bottom: 2,
    right: 2,
    width: 2,
    height: 2,
    borderRadius: 2,
    backgroundColor: '#ffffff',
    opacity: 0.8,
  },

  nose: {
    width: 3,
    height: 5,
    borderRadius: 3,
    marginTop: 3,
  },

  cheek: {
    position: 'absolute',
    width: 7,
    height: 3,
    borderRadius: 5,
    opacity: 0.12,
    top: 41,
  },

  leftCheek: {
    left: 7,
  },

  rightCheek: {
    right: 7,
  },

  mouth: {
    borderWidth: 1.5,
    borderRadius: 12,
    marginTop: 3,
  },

  chin: {
    position: 'absolute',
    bottom: 7,
    width: 11,
    height: 1,
    borderRadius: 2,
    opacity: 0.3,
  },
});
