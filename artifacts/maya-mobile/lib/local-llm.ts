import { initLlama, LlamaContext } from 'llama.rn';
import * as FileSystem from 'expo-file-system/legacy';
import { getDeveloperConfig } from '@/lib/developer-config';
import { detectMayaLanguage, languageInstruction } from '@/lib/translation';

let context: LlamaContext | null = null;

export const MODEL_URL =
  'https://github.com/JinuSettan/maya-z/releases/download/maya-model-v1/qwen2.5-0.5b-instruct-q4_0.gguf';
const MODEL_FILENAME = 'maya-brain-v1.gguf';
const MIN_VALID_MODEL_BYTES = 100_000_000;

export type ModelProgress = {
  percent: number;
  status: 'checking' | 'downloading' | 'initializing' | 'ready';
};

export function modelPath(): string {
  return `${FileSystem.documentDirectory}${MODEL_FILENAME}`;
}

export async function hasValidLocalModel(): Promise<boolean> {
  const info = await FileSystem.getInfoAsync(modelPath());
  return Boolean(info.exists && info.size && info.size > MIN_VALID_MODEL_BYTES);
}

export async function prepareLocalModel(onProgress?: (progress: ModelProgress) => void): Promise<string> {
  onProgress?.({ percent: 0, status: 'checking' });
  const destination = modelPath();
  if (await hasValidLocalModel()) {
    onProgress?.({ percent: 1, status: 'ready' });
    return destination;
  }

  await FileSystem.deleteAsync(destination, { idempotent: true });
  const download = FileSystem.createDownloadResumable(
    MODEL_URL,
    destination,
    {},
    ({ totalBytesExpectedToWrite, totalBytesWritten }) => {
      const percent = totalBytesExpectedToWrite > 0
        ? Math.min(0.99, totalBytesWritten / totalBytesExpectedToWrite)
        : 0;
      onProgress?.({ percent, status: 'downloading' });
    }
  );
  const result = await download.downloadAsync();
  if (!result?.uri || result.status !== 200 || !(await hasValidLocalModel())) {
    await FileSystem.deleteAsync(destination, { idempotent: true });
    throw new Error('MAYA could not finish preparing her brain.');
  }
  onProgress?.({ percent: 0.99, status: 'initializing' });
  return result.uri;
}

export async function initializeLocalModel(onProgress?: (progress: ModelProgress) => void): Promise<void> {
  if (context) {
    onProgress?.({ percent: 1, status: 'ready' });
    return;
  }
  const path = await prepareLocalModel(onProgress);
  context = await initLlama({ model: path, n_ctx: 2048, n_gpu_layers: 0 });
  onProgress?.({ percent: 1, status: 'ready' });
}

export function resetLocalModel(): void {
  context = null;
}

export async function generateLocalReply(userMessage: string): Promise<string> {
  if (!context) await initializeLocalModel();
  if (!context) throw new Error('MAYA is still preparing.');
  const config = await getDeveloperConfig();
  const language = detectMayaLanguage();
  const system = [
    config.systemInstructions,
    `Personality: ${config.personality}`,
    `Response rules: ${config.responseRules}`,
    languageInstruction(language),
    'Do not claim to see an attached photo. If a photo is attached, say that offline MAYA cannot inspect pixels yet.'
  ].join('\n');
  const result = await context.completion({
    messages: [
      { role: 'system', content: system },
      { role: 'user', content: userMessage }
    ],
    n_predict: 256,
    temperature: 0.7
  });
  return result.text.trim();
}