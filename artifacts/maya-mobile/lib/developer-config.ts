import AsyncStorage from '@react-native-async-storage/async-storage';

export const DEVELOPER_COMMAND = '/developerceo/maya/unlockcode/trainer';
const CONFIG_KEY = 'maya-developer-config-v1';

export type MayaDeveloperConfig = {
  systemInstructions: string;
  personality: string;
  responseRules: string;
  ttsEnabled: boolean;
  ttsRate: number;
  ttsPitch: number;
  faceEnabled: boolean;
  onlineFeaturesEnabled: boolean;
};

export const DEFAULT_DEVELOPER_CONFIG: MayaDeveloperConfig = {
  systemInstructions: 'You are MAYA, a warm and helpful assistant.',
  personality: 'Friendly, calm, curious, and concise.',
  responseRules: 'Be honest about limitations. Never claim to have used a service that is not configured.',
  ttsEnabled: true,
  ttsRate: 0.96,
  ttsPitch: 1.08,
  faceEnabled: true,
  onlineFeaturesEnabled: false
};

export function developerModeEnabled(): boolean {
  return __DEV__ || process.env.EXPO_PUBLIC_MAYA_DEVELOPER_MODE === 'true';
}

export async function getDeveloperConfig(): Promise<MayaDeveloperConfig> {
  const raw = await AsyncStorage.getItem(CONFIG_KEY);
  if (!raw) return DEFAULT_DEVELOPER_CONFIG;
  try {
    return { ...DEFAULT_DEVELOPER_CONFIG, ...JSON.parse(raw) };
  } catch {
    return DEFAULT_DEVELOPER_CONFIG;
  }
}

export async function saveDeveloperConfig(config: MayaDeveloperConfig): Promise<void> {
  await AsyncStorage.setItem(CONFIG_KEY, JSON.stringify(config));
}