export type ChatHistoryMessage = {
  role: 'user' | 'assistant' | 'system';
  content: string;
};

export type LlmProvider = {
  complete(message: string, history?: ChatHistoryMessage[]): Promise<string>;
};
