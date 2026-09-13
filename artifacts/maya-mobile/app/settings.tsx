
import React, { useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useColors } from '@/hooks/useColors';

const CHARACTER_KEY = 'maya-selected-character';

type Character = 'MAYA' | 'ROCKY';

export default function SettingsScreen() {
  const router = useRouter();
  const colors = useColors();

  const [character, setCharacter] = useState<Character>('MAYA');

  useEffect(() => {
    void AsyncStorage.getItem(CHARACTER_KEY).then((saved) => {
      if (saved === 'MAYA' || saved === 'ROCKY') {
        setCharacter(saved);
      }
    });
  }, []);

  const selectCharacter = async (next: Character) => {
    setCharacter(next);
    await AsyncStorage.setItem(CHARACTER_KEY, next);
  };

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <View style={[styles.header, { borderBottomColor: colors.border }]}>
        <Pressable
          accessibilityLabel="Go back"
          onPress={() => router.back()}
          style={({ pressed }) => [
            styles.iconButton,
            pressed && styles.pressed,
          ]}
        >
          <Ionicons
            name="arrow-back"
            size={24}
            color={colors.foreground}
          />
        </Pressable>

        <Text style={[styles.title, { color: colors.foreground }]}>
          Settings
        </Text>

        <View style={styles.headerSpacer} />
      </View>

      <View style={styles.content}>
        <Text style={[styles.sectionTitle, { color: colors.foreground }]}>
          Character
        </Text>

        <Text
          style={[
            styles.sectionDescription,
            { color: colors.mutedForeground },
          ]}
        >
          Choose who appears in your MAYA experience.
        </Text>

        <View style={styles.characterList}>
          <Pressable
            onPress={() => void selectCharacter('MAYA')}
            style={({ pressed }) => [
              styles.characterCard,
              {
                backgroundColor:
                  character === 'MAYA'
                    ? colors.secondary
                    : colors.card,
                borderColor:
                  character === 'MAYA'
                    ? colors.primary
                    : colors.border,
              },
              pressed && styles.pressed,
            ]}
          >
            <View
              style={[
                styles.characterIcon,
                { backgroundColor: colors.primary },
              ]}
            >
              <Ionicons
                name="sparkles"
                size={24}
                color={colors.primaryForeground}
              />
            </View>

            <View style={styles.characterCopy}>
              <Text
                style={[
                  styles.characterName,
                  { color: colors.foreground },
                ]}
              >
                MAYA
              </Text>

              <Text
                style={[
                  styles.characterDescription,
                  { color: colors.mutedForeground },
                ]}
              >
                Friendly AI assistant
              </Text>
            </View>

            {character === 'MAYA' ? (
              <Ionicons
                name="checkmark-circle"
                size={25}
                color={colors.primary}
              />
            ) : null}
          </Pressable>

          <Pressable
            onPress={() => void selectCharacter('ROCKY')}
            style={({ pressed }) => [
              styles.characterCard,
              {
                backgroundColor:
                  character === 'ROCKY'
                    ? colors.secondary
                    : colors.card,
                borderColor:
                  character === 'ROCKY'
                    ? colors.primary
                    : colors.border,
              },
              pressed && styles.pressed,
            ]}
          >
            <View
              style={[
                styles.characterIcon,
                { backgroundColor: colors.primary },
              ]}
            >
              <Ionicons
                name="person"
                size={24}
                color={colors.primaryForeground}
              />
            </View>

            <View style={styles.characterCopy}>
              <Text
                style={[
                  styles.characterName,
                  { color: colors.foreground },
                ]}
              >
                ROCKY
              </Text>

              <Text
                style={[
                  styles.characterDescription,
                  { color: colors.mutedForeground },
                ]}
              >
                Alternate AI character
              </Text>
            </View>

            {character === 'ROCKY' ? (
              <Ionicons
                name="checkmark-circle"
                size={25}
                color={colors.primary}
              />
            ) : null}
          </Pressable>
        </View>

        <Text style={[styles.savedText, { color: colors.mutedForeground }]}>
          Your character choice is saved on this device.
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
  },
  header: {
    minHeight: 76,
    paddingHorizontal: 18,
    flexDirection: 'row',
    alignItems: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  iconButton: {
    width: 42,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerSpacer: {
    width: 42,
  },
  title: {
    flex: 1,
    textAlign: 'center',
    fontFamily: 'Inter_700Bold',
    fontSize: 18,
  },
  content: {
    paddingHorizontal: 20,
    paddingTop: 28,
  },
  sectionTitle: {
    fontFamily: 'Inter_700Bold',
    fontSize: 23,
  },
  sectionDescription: {
    fontFamily: 'Inter_400Regular',
    fontSize: 13,
    lineHeight: 20,
    marginTop: 6,
  },
  characterList: {
    gap: 12,
    marginTop: 22,
  },
  characterCard: {
    minHeight: 82,
    borderRadius: 18,
    borderWidth: 1,
    paddingHorizontal: 14,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 13,
  },
  characterIcon: {
    width: 48,
    height: 48,
    borderRadius: 15,
    alignItems: 'center',
    justifyContent: 'center',
  },
  characterCopy: {
    flex: 1,
  },
  characterName: {
    fontFamily: 'Inter_700Bold',
    fontSize: 16,
    letterSpacing: 1,
  },
  characterDescription: {
    fontFamily: 'Inter_400Regular',
    fontSize: 12,
    marginTop: 3,
  },
  savedText: {
    fontFamily: 'Inter_400Regular',
    fontSize: 11,
    textAlign: 'center',
    marginTop: 22,
  },
  pressed: {
    opacity: 0.65,
  },
});
