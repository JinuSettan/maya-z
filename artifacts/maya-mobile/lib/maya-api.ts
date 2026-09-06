import AsyncStorage from '@react-native-async-storage/async-storage';

export type Conversation = {
  id: string;
  title: string;
  created_at: string;
  updated_at: string;
  preview: string | null;
};

export type Message = {
  id: string;
  conversation_id: string;
  role: 'user' | 'assistant';
  content: string;
  created_at: string;
  pinned: boolean;
  reply_to_id: string | null;
  image_url: string | null;
};

const OWNER_TOKEN_KEY = 'maya-owner-token';
const apiBase = process.env.EXPO_PUBLIC_DOMAIN
  ? `https://${process.env.EXPO_PUBLIC_DOMAIN}/api`
  : '/api';

function makeToken(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

async function getOwnerToken(): Promise<string> {
  const existing = await AsyncStorage.getItem(OWNER_TOKEN_KEY);
  if (existing) return existing;
  const token = makeToken();
  await AsyncStorage.setItem(OWNER_TOKEN_KEY, token);
  return token;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const ownerToken = await getOwnerToken();
  const response = await fetch(`${apiBase}${path}`, {
    ...init,
    headers: {
      'X-Owner-Token': ownerToken,
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    let detail = '';
    try {
      const payload = (await response.json()) as { detail?: string };
      detail = payload.detail ? `: ${payload.detail}` : '';
    } catch {
      // Keep a useful generic error when the server response is not JSON.
    }
    throw new Error(`Maya request failed${detail}`);
  }

  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const mayaApi = {
  conversations: {
    list: () => request<Conversation[]>('/conversations'),
    create: (title = '') =>
      request<Conversation>('/conversations', {
        method: 'POST',
        body: JSON.stringify(title ? { title } : {}),
      }),
    delete: (id: string) =>
      request<void>(`/conversations/${id}`, { method: 'DELETE' }),
  },
  messages: {
    list: (conversationId: string) =>
      request<Message[]>(`/conversations/${conversationId}/messages`),
    create: (conversationId: string, content: string) =>
      request<{ user_message: Message; assistant_message: Message }>(
        `/conversations/${conversationId}/messages`,
        {
          method: 'POST',
          body: JSON.stringify({ content }),
        },
      ),
  },
};