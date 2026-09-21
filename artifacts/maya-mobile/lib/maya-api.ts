import AsyncStorage from '@react-native-async-storage/async-storage';
import { generateLocalReply } from '@/lib/local-llm';
import { translateForDisplay } from '@/lib/translation';
import { requestOnlineReply, type ChatHistoryMessage } from '@/lib/online-chat';

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

const CONVERSATIONS_KEY = 'maya-conversations';
const MESSAGES_KEY = 'maya-messages';

async function getConversations(): Promise<Conversation[]> {
  const raw = await AsyncStorage.getItem(CONVERSATIONS_KEY);
  return raw ? JSON.parse(raw) as Conversation[] : [];
}
async function saveConversations(value: Conversation[]) { await AsyncStorage.setItem(CONVERSATIONS_KEY, JSON.stringify(value)); }
async function getMessages(): Promise<Message[]> {
  const raw = await AsyncStorage.getItem(MESSAGES_KEY);
  return raw ? JSON.parse(raw) as Message[] : [];
}
async function saveMessages(value: Message[]) { await AsyncStorage.setItem(MESSAGES_KEY, JSON.stringify(value)); }
function makeId(prefix: string) { return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`; }
async function appendMessages(messages: Message[]) { await saveMessages([...(await getMessages()), ...messages]); }
async function updateConversation(conversationId: string, preview: string, updatedAt: string) {
  const conversations = await getConversations();
  const index = conversations.findIndex((item) => item.id === conversationId);
  if (index < 0) return;
  conversations[index] = { ...conversations[index], preview: preview.slice(0, 120), updated_at: updatedAt };
  await saveConversations(conversations);
}

async function createResponse(conversationId: string, content: string, imageUrl: string | null) {
  const now = new Date().toISOString();
  const userMessage: Message = { id: makeId('user'), conversation_id: conversationId, role: 'user', content, created_at: now, pinned: false, reply_to_id: null, image_url: imageUrl };
  let reply: string;
  if (imageUrl) {
    reply = 'I received your photo. Offline MAYA can keep it in this conversation, but this local brain cannot inspect image pixels yet. An online vision service is not configured.';
  } else {
    const history: ChatHistoryMessage[] = (await getMessages())
      .filter((item) => item.conversation_id === conversationId)
      .slice(-50)
      .map((item) => ({ role: item.role, content: item.content }));
    try {
      reply = await requestOnlineReply(content, conversationId, history);
    } catch {
      reply = await generateLocalReply(content);
    }
  }
  const assistantMessage: Message = { id: makeId('assistant'), conversation_id: conversationId, role: 'assistant', content: translateForDisplay(reply), created_at: new Date().toISOString(), pinned: false, reply_to_id: userMessage.id, image_url: null };
  await appendMessages([userMessage, assistantMessage]);
  await updateConversation(conversationId, assistantMessage.content, assistantMessage.created_at);
  return { user_message: userMessage, assistant_message: assistantMessage };
}

export const mayaApi = {
  conversations: {
    list: getConversations,
    create: async (title = '') => {
      const now = new Date().toISOString();
      const conversation: Conversation = { id: makeId('conversation'), title: title || 'New conversation', created_at: now, updated_at: now, preview: null };
      await saveConversations([conversation, ...(await getConversations())]);
      return conversation;
    },
    delete: async (id: string) => {
      await saveConversations((await getConversations()).filter((item) => item.id !== id));
      await saveMessages((await getMessages()).filter((item) => item.conversation_id !== id));
    }
  },
  messages: {
    list: async (conversationId: string) => (await getMessages()).filter((item) => item.conversation_id === conversationId),
    create: (conversationId: string, content: string) => createResponse(conversationId, content, null),
    analyzeImage: (conversationId: string, imageDataUrl: string, prompt = 'What is in this image?') => createResponse(conversationId, prompt, imageDataUrl)
  }
};
