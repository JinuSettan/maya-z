export type ChatHistoryMessage = {
  role: 'user' | 'assistant';
  content: string;
};

const API_URL = process.env.EXPO_PUBLIC_MAYA_API_URL?.replace(/\/$/, '');
const REQUEST_TIMEOUT_MS = 15_000;

export async function requestOnlineReply(message: string, conversationId: string, history: ChatHistoryMessage[]): Promise<string> {
  if (!API_URL) throw new Error('Online MAYA API is not configured');
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${API_URL}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, conversationId, history }),
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`Online MAYA API returned HTTP ${response.status}`);
    const data: unknown = await response.json();
    const reply = (data as { message?: unknown }).message;
    if (typeof reply !== 'string' || !reply.trim()) throw new Error('Online MAYA API returned an empty response');
    return reply.trim();
  } finally {
    clearTimeout(timeout);
  }
}
