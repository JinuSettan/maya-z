import { Router, type IRouter } from 'express';
import { createLlmProvider } from '../lib/llm-provider';
import type { ChatHistoryMessage } from '../lib/types';

const router: IRouter = Router();
const MAX_MESSAGE_LENGTH = 8_000;
const MAX_HISTORY_ITEMS = 50;

function isHistory(value: unknown): value is ChatHistoryMessage[] {
  return Array.isArray(value) && value.length <= MAX_HISTORY_ITEMS && value.every((item) =>
    Boolean(item) && typeof item === 'object' &&
    ['user', 'assistant', 'system'].includes((item as { role?: unknown }).role as string) &&
    typeof (item as { content?: unknown }).content === 'string' &&
    (item as { content: string }).content.length <= MAX_MESSAGE_LENGTH,
  );
}

router.post('/chat', async (req, res) => {
  const body = req.body as { message?: unknown; conversationId?: unknown; history?: unknown };
  if (typeof body.message !== 'string' || !body.message.trim() || body.message.length > MAX_MESSAGE_LENGTH) {
    res.status(400).json({ error: 'message must be a non-empty string of 8000 characters or fewer' });
    return;
  }
  if (body.conversationId !== undefined && typeof body.conversationId !== 'string') {
    res.status(400).json({ error: 'conversationId must be a string' });
    return;
  }
  if (body.history !== undefined && !isHistory(body.history)) {
    res.status(400).json({ error: 'history must be an array of valid chat messages' });
    return;
  }

  try {
const message = await createLlmProvider().complete(body.message.trim(), body.history as ChatHistoryMessage[]);
    res.json({ message });
  } catch (error) {
    req.log?.error({
      error: error instanceof Error ? error.message : 'Unknown provider error',
      conversationId: typeof body.conversationId === 'string' ? body.conversationId : undefined,
    }, 'Online chat provider failed');
    res.status(502).json({ error: 'Online chat is temporarily unavailable', details: error instanceof Error ? error.message : 'Unknown provider error' });
  }
});

export default router;
