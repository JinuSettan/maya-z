import "dotenv/config";
import type { ChatHistoryMessage, LlmProvider } from './types';

const DEFAULT_TIMEOUT_MS = 20_000;
const DEFAULT_MAX_TOKENS = 512;
const CHAT_COMPLETIONS_PATH = '/chat/completions';

type ProviderMessage = {
  content?: unknown;
  reasoning?: unknown;
};

type ProviderResponse = {
  choices?: Array<{
    message?: ProviderMessage;
    text?: unknown;
  }>;
};

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is not configured`);
  return value;
}

function providerEndpoint(configuredUrl: string): string {
  const url = configuredUrl.replace(/\/$/, '');
  return url.endsWith(CHAT_COMPLETIONS_PATH) ? url : `${url}${CHAT_COMPLETIONS_PATH}`;
}

function usableText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function extractAssistantText(data: ProviderResponse): string | undefined {
  const choice = data.choices?.[0];
  const message = choice?.message;
  return usableText(message?.content) ?? usableText(message?.reasoning) ?? usableText(choice?.text);
}

export function createLlmProvider(): LlmProvider {
  const configuredUrl = required('MAYA_LLM_API_URL');
  const apiUrl = providerEndpoint(configuredUrl);
  const apiKey = required('MAYA_LLM_API_KEY');
  const model = required('MAYA_LLM_MODEL');
  const timeoutMs = Number(process.env.MAYA_LLM_TIMEOUT_MS ?? DEFAULT_TIMEOUT_MS);
  const maxTokens = Number(process.env.MAYA_LLM_MAX_TOKENS ?? DEFAULT_MAX_TOKENS);

  return {
    async complete(message: string, history: ChatHistoryMessage[] = []): Promise<string> {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), Number.isFinite(timeoutMs) ? timeoutMs : DEFAULT_TIMEOUT_MS);
      try {
        const response = await fetch(apiUrl, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${apiKey}`,
          },
          body: JSON.stringify({
            model,
            messages: [...history, { role: 'user', content: message }],
            max_tokens: Number.isFinite(maxTokens) && maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS,
          }),
          signal: controller.signal,
        });
        if (!response.ok) {
          throw new Error(`LLM provider returned HTTP ${response.status} from ${new URL(apiUrl).origin}`);
        }
        const data = await response.json() as ProviderResponse;
        const content = extractAssistantText(data);
        if (!content) throw new Error('LLM provider returned no usable assistant text');
        return content;
      } finally {
        clearTimeout(timeout);
      }
    },
  };
}
