const basePrefix = import.meta.env.BASE_URL.replace(/\/$/, '');
const API_BASE = `${basePrefix}/api`;

// Each browser gets its own opaque owner token, persisted locally, so the
// backend can scope conversations to this visitor without full accounts.
const OWNER_TOKEN_KEY = 'maya-owner-token';
function getOwnerToken(): string {
  let token = localStorage.getItem(OWNER_TOKEN_KEY);
  if (!token) {
    token = crypto.randomUUID();
    localStorage.setItem(OWNER_TOKEN_KEY, token);
  }
  return token;
}

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  return { 'X-Owner-Token': getOwnerToken(), ...extra };
}

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

export const api = {
  conversations: {
    list: async (): Promise<Conversation[]> => {
      const res = await fetch(`${API_BASE}/conversations`, { headers: authHeaders() });
      if (!res.ok) throw new Error('Failed to fetch conversations');
      return res.json();
    },
    create: async (data: { title?: string } = {}): Promise<Conversation> => {
      const res = await fetch(`${API_BASE}/conversations`, {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(data),
      });
      if (!res.ok) throw new Error('Failed to create conversation');
      return res.json();
    },
    update: async (id: string, data: { title: string }): Promise<Conversation> => {
      const res = await fetch(`${API_BASE}/conversations/${id}`, {
        method: 'PATCH',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(data),
      });
      if (!res.ok) throw new Error('Failed to update conversation');
      return res.json();
    },
    delete: async (id: string): Promise<void> => {
      const res = await fetch(`${API_BASE}/conversations/${id}`, {
        method: 'DELETE',
        headers: authHeaders(),
      });
      if (!res.ok) throw new Error('Failed to delete conversation');
    },
  },
  messages: {
    list: async (conversationId: string): Promise<Message[]> => {
      const res = await fetch(`${API_BASE}/conversations/${conversationId}/messages`, {
        headers: authHeaders(),
      });
      if (!res.ok) throw new Error('Failed to fetch messages');
      return res.json();
    },
    create: async (
      conversationId: string,
      data: { content: string; reply_to_id?: string | null }
    ): Promise<{ user_message: Message; assistant_message: Message }> => {
      const res = await fetch(`${API_BASE}/conversations/${conversationId}/messages`, {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(data),
      });
      if (!res.ok) throw new Error('Failed to send message');
      return res.json();
    },
    setPinned: async (
      conversationId: string,
      messageId: string,
      pinned: boolean
    ): Promise<Message> => {
      const res = await fetch(
        `${API_BASE}/conversations/${conversationId}/messages/${messageId}`,
        {
          method: 'PATCH',
          headers: authHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ pinned }),
        }
      );
      if (!res.ok) throw new Error('Failed to update message');
      return res.json();
    },
    analyzeImage: async (
      conversationId: string,
      data: { imageDataUrl: string; prompt?: string }
    ): Promise<{ user_message: Message; assistant_message: Message }> => {
      const res = await fetch(`${API_BASE}/conversations/${conversationId}/vision-messages`, {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ image_data_url: data.imageDataUrl, prompt: data.prompt }),
      });
      if (!res.ok) throw new Error('Failed to analyze image');
      return res.json();
    },
    generateImage: async (
      conversationId: string,
      data: { prompt: string }
    ): Promise<{ user_message: Message; assistant_message: Message }> => {
      const res = await fetch(`${API_BASE}/conversations/${conversationId}/generate-image`, {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(data),
      });
      if (!res.ok) throw new Error('Failed to generate image');
      return res.json();
    },
  },
};
