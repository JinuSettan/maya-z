import type { ChatHistoryMessage, LlmProvider } from './types';

const DEFAULT_TIMEOUT_MS = 20_000;

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is not configured`);
  return value;
}

export function createLlmProvider(): LlmProvider {
  const apiUrl = required('MAYA_LLM_API_URL').replace(/\/$/, '');
  const apiKey = required('MAYA_LLM_API_KEY');
  const model = required('MAYA_LLM_MODEL');
  const timeoutMs = Number(process.env.MAYA_LLM_TIMEOUT_MS ?? DEFAULT_TIMEOUT_MS);

  return {
    async complete(message: string, history: ChatHistoryMessage[] = []): Promise<string> {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), Number.isFinite(timeoutMs) ? timeoutMs : DEFAULT_TIMEOUT_MS);
      try {
        const response = await fetch(`${apiUrl}/chat/completions`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${apiKey}`,
          },
          body: JSON.stringify({
            model,
            messages: [...history, { role: 'user', content: message }],
          }),
          signal: controller.signal,
        });
        if (!response.ok) throw new Error(`LLM provider returned HTTP ${response.status}`);
        const data: unknown = await response.json();
        const content = (data as { choices?: Array<{ message?: { content?: unknown } }> })
          .choices?.[0]?.message?.content;
        if (typeof content !== 'string' || !content.trim()) throw new Error('LLM provider returned an empty response');
        return content.trim();
      } finally {
        clearTimeout(timeout);
      }
    },
  };
}
