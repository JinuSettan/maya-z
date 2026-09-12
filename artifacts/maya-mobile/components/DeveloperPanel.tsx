import React, { useEffect, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useColors } from '@/hooks/useColors';
import {
  DEFAULT_DEVELOPER_CONFIG,
  getDeveloperConfig,
  MayaDeveloperConfig,
  saveDeveloperConfig
} from '@/lib/developer-config';
import { getFirebaseFoundationStatus } from '@/lib/online-services';

export function DeveloperPanel({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const colors = useColors();
  const [config, setConfig] = useState<MayaDeveloperConfig>(DEFAULT_DEVELOPER_CONFIG);
  const firebase = getFirebaseFoundationStatus();

  useEffect(() => {
    if (visible) void getDeveloperConfig().then(setConfig);
  }, [visible]);

  const update = <K extends keyof MayaDeveloperConfig>(key: K, value: MayaDeveloperConfig[K]) =>
    setConfig((current) => ({ ...current, [key]: value }));

  const save = async () => {
    await saveDeveloperConfig(config);
    onClose();
  };

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <View style={[styles.root, { backgroundColor: colors.background }]}>
        <View style={[styles.header, { borderBottomColor: colors.border }]}>
          <View>
            <Text style={[styles.eyebrow, { color: colors.primary }]}>MAYA DEVELOPER</Text>
            <Text style={[styles.title, { color: colors.foreground }]}>Safe controls</Text>
          </View>
          <Pressable onPress={onClose} accessibilityLabel="Close developer panel" style={styles.close}>
            <Ionicons name="close" size={24} color={colors.foreground} />
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={styles.content}>
          <Text style={[styles.section, { color: colors.foreground }]}>Behaviour</Text>
          <TextInput
            multiline
            value={config.systemInstructions}
            onChangeText={(value) => update('systemInstructions', value)}
            placeholder="System instructions"
            placeholderTextColor={colors.mutedForeground}
            style={[styles.input, { color: colors.foreground, borderColor: colors.input, backgroundColor: colors.card }]}
          />
          <TextInput
            multiline
            value={config.personality}
            onChangeText={(value) => update('personality', value)}
            placeholder="Personality"
            placeholderTextColor={colors.mutedForeground}
            style={[styles.input, { color: colors.foreground, borderColor: colors.input, backgroundColor: colors.card }]}
          />
          <TextInput
            multiline
            value={config.responseRules}
            onChangeText={(value) => update('responseRules', value)}
            placeholder="Response rules"
            placeholderTextColor={colors.mutedForeground}
            style={[styles.input, { color: colors.foreground, borderColor: colors.input, backgroundColor: colors.card }]}
          />

          <Text style={[styles.section, { color: colors.foreground }]}>Runtime and features</Text>
          <SettingRow label="Face animation" value={config.faceEnabled} onValueChange={(value) => update('faceEnabled', value)} colors={colors} />
          <SettingRow label="Text-to-speech" value={config.ttsEnabled} onValueChange={(value) => update('ttsEnabled', value)} colors={colors} />
          <SettingRow label="Online features" value={config.onlineFeaturesEnabled} onValueChange={(value) => update('onlineFeaturesEnabled', value)} colors={colors} />
          <Text style={[styles.status, { color: colors.mutedForeground }]}>
            Local brain: configured at runtime{'\n'}
            Firebase: {firebase.message}{'\n'}
            Video, image, voice, sync: safe interfaces only; not configured
          </Text>

          <Pressable onPress={save} style={[styles.save, { backgroundColor: colors.primary }]}>
            <Text style={{ color: colors.primaryForeground, fontWeight: '700' }}>Save controls</Text>
          </Pressable>
        </ScrollView>
      </View>
    </Modal>
  );
}

function SettingRow({
  label,
  value,
  onValueChange,
  colors
}: {
  label: string;
  value: boolean;
  onValueChange: (value: boolean) => void;
  colors: ReturnType<typeof useColors>;
}) {
  return (
    <View style={[styles.settingRow, { borderBottomColor: colors.border }]}>
      <Text style={{ color: colors.foreground }}>{label}</Text>
      <Switch value={value} onValueChange={onValueChange} trackColor={{ false: colors.secondary, true: colors.primary }} />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  header: { paddingTop: 58, paddingHorizontal: 20, paddingBottom: 18, borderBottomWidth: StyleSheet.hairlineWidth, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  eyebrow: { fontSize: 10, fontWeight: '700', letterSpacing: 2 },
  title: { fontSize: 24, fontWeight: '700', marginTop: 5 },
  close: { width: 42, height: 42, alignItems: 'center', justifyContent: 'center' },
  content: { padding: 20, gap: 12 },
  section: { fontSize: 16, fontWeight: '700', marginTop: 12 },
  input: { minHeight: 58, borderWidth: 1, borderRadius: 13, padding: 12, textAlignVertical: 'top' },
  settingRow: { minHeight: 52, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth },
  status: { fontSize: 12, lineHeight: 19, marginTop: 8 },
  save: { alignItems: 'center', borderRadius: 14, paddingVertical: 14, marginTop: 14 }
});