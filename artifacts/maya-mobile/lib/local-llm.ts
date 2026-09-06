import { initLlama, LlamaContext } from 'llama.rn';
import * as FileSystem from 'expo-file-system';
import { Asset } from 'expo-asset';

let context: LlamaContext | null = null;

async function getModelPath(): Promise<string> {
  const destPath = `${FileSystem.documentDirectory}qwen2.5-0.5b-instruct-q4_0.gguf`;
  const info = await FileSystem.getInfoAsync(destPath);
  if (info.exists) return destPath;

  const asset = Asset.fromModule(require('../../attached_assets/models/qwen2.5-0.5b-instruct-q4_0.gguf'));
  await asset.downloadAsync();
  await FileSystem.copyAsync({ from: asset.localUri!, to: destPath });
  return destPath;
}

async function getContext(): Promise<LlamaContext> {
  if (context) return context;
  const modelPath = await getModelPath();
  context = await initLlama({
    model: modelPath,
    n_ctx: 2048,
    n_gpu_layers: 0,
  });
  return context;
}

export async function generateLocalReply(userMessage: string): Promise<string> {
  const ctx = await getContext();
  const result = await ctx.completion({
    messages: [
      { role: 'system', content: 'You are Maya, a helpful assistant.' },
      { role: 'user', content: userMessage },
    ],
    n_predict: 256,
    temperature: 0.7,
  });
  return result.text.trim();
}
