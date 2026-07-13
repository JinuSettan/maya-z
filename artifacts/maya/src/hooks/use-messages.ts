import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, Message } from '../lib/api';
import { CONVERSATIONS_KEY } from './use-conversations';

export const messagesKeys = {
  all: ['messages'] as const,
  list: (conversationId: string) => [...messagesKeys.all, conversationId] as const,
};

export function useMessages(conversationId: string | null) {
  return useQuery({
    queryKey: messagesKeys.list(conversationId!),
    queryFn: () => api.messages.list(conversationId!),
    enabled: !!conversationId,
  });
}

export function useSendMessage() {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: ({
      conversationId,
      content,
      replyToId,
    }: {
      conversationId: string;
      content: string;
      replyToId?: string | null;
    }) => api.messages.create(conversationId, { content, reply_to_id: replyToId ?? null }),
    onMutate: async ({ conversationId, content, replyToId }) => {
      await queryClient.cancelQueries({ queryKey: messagesKeys.list(conversationId) });
      
      const previousMessages = queryClient.getQueryData<Message[]>(messagesKeys.list(conversationId));
      
      // Optimistically add user message
      const optimisticMessage: Message = {
        id: `opt-${Date.now()}`,
        conversation_id: conversationId,
        role: 'user',
        content,
        created_at: new Date().toISOString(),
        pinned: false,
        reply_to_id: replyToId ?? null,
        image_url: null,
      };
      
      if (previousMessages) {
        queryClient.setQueryData<Message[]>(messagesKeys.list(conversationId), [
          ...previousMessages,
          optimisticMessage,
        ]);
      }
      
      return { previousMessages, optimisticMessage };
    },
    onSuccess: (data, variables) => {
      queryClient.setQueryData<Message[]>(messagesKeys.list(variables.conversationId), (old) => {
        if (!old) return [data.user_message, data.assistant_message];
        return [
          ...old.filter((m) => !m.id.startsWith('opt-')),
          data.user_message,
          data.assistant_message,
        ];
      });
      queryClient.invalidateQueries({ queryKey: CONVERSATIONS_KEY });
    },
    onError: (err, variables, context) => {
      if (context?.previousMessages) {
        queryClient.setQueryData(messagesKeys.list(variables.conversationId), context.previousMessages);
      }
    },
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({ queryKey: messagesKeys.list(variables.conversationId) });
    },
  });
}

export function useAnalyzeImage() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      conversationId,
      imageDataUrl,
      prompt,
    }: {
      conversationId: string;
      imageDataUrl: string;
      prompt?: string;
    }) => api.messages.analyzeImage(conversationId, { imageDataUrl, prompt }),
    onMutate: async ({ conversationId, imageDataUrl, prompt }) => {
      await queryClient.cancelQueries({ queryKey: messagesKeys.list(conversationId) });
      const previousMessages = queryClient.getQueryData<Message[]>(messagesKeys.list(conversationId));

      const optimisticMessage: Message = {
        id: `opt-${Date.now()}`,
        conversation_id: conversationId,
        role: 'user',
        content: prompt || "What's in this image?",
        created_at: new Date().toISOString(),
        pinned: false,
        reply_to_id: null,
        image_url: imageDataUrl,
      };

      if (previousMessages) {
        queryClient.setQueryData<Message[]>(messagesKeys.list(conversationId), [
          ...previousMessages,
          optimisticMessage,
        ]);
      }

      return { previousMessages };
    },
    onSuccess: (data, variables) => {
      queryClient.setQueryData<Message[]>(messagesKeys.list(variables.conversationId), (old) => {
        if (!old) return [data.user_message, data.assistant_message];
        return [
          ...old.filter((m) => !m.id.startsWith('opt-')),
          data.user_message,
          data.assistant_message,
        ];
      });
      queryClient.invalidateQueries({ queryKey: CONVERSATIONS_KEY });
    },
    onError: (err, variables, context) => {
      if (context?.previousMessages) {
        queryClient.setQueryData(messagesKeys.list(variables.conversationId), context.previousMessages);
      }
    },
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({ queryKey: messagesKeys.list(variables.conversationId) });
    },
  });
}

export function useGenerateImage() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ conversationId, prompt }: { conversationId: string; prompt: string }) =>
      api.messages.generateImage(conversationId, { prompt }),
    onMutate: async ({ conversationId, prompt }) => {
      await queryClient.cancelQueries({ queryKey: messagesKeys.list(conversationId) });
      const previousMessages = queryClient.getQueryData<Message[]>(messagesKeys.list(conversationId));

      const optimisticMessage: Message = {
        id: `opt-${Date.now()}`,
        conversation_id: conversationId,
        role: 'user',
        content: `Generate an image: ${prompt}`,
        created_at: new Date().toISOString(),
        pinned: false,
        reply_to_id: null,
        image_url: null,
      };

      if (previousMessages) {
        queryClient.setQueryData<Message[]>(messagesKeys.list(conversationId), [
          ...previousMessages,
          optimisticMessage,
        ]);
      }

      return { previousMessages };
    },
    onSuccess: (data, variables) => {
      queryClient.setQueryData<Message[]>(messagesKeys.list(variables.conversationId), (old) => {
        if (!old) return [data.user_message, data.assistant_message];
        return [
          ...old.filter((m) => !m.id.startsWith('opt-')),
          data.user_message,
          data.assistant_message,
        ];
      });
      queryClient.invalidateQueries({ queryKey: CONVERSATIONS_KEY });
    },
    onError: (err, variables, context) => {
      if (context?.previousMessages) {
        queryClient.setQueryData(messagesKeys.list(variables.conversationId), context.previousMessages);
      }
    },
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({ queryKey: messagesKeys.list(variables.conversationId) });
    },
  });
}

export function useSetMessagePinned() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      conversationId,
      messageId,
      pinned,
    }: {
      conversationId: string;
      messageId: string;
      pinned: boolean;
    }) => api.messages.setPinned(conversationId, messageId, pinned),
    onMutate: async ({ conversationId, messageId, pinned }) => {
      await queryClient.cancelQueries({ queryKey: messagesKeys.list(conversationId) });
      const previousMessages = queryClient.getQueryData<Message[]>(messagesKeys.list(conversationId));

      if (previousMessages) {
        queryClient.setQueryData<Message[]>(
          messagesKeys.list(conversationId),
          previousMessages.map((m) => (m.id === messageId ? { ...m, pinned } : m))
        );
      }

      return { previousMessages };
    },
    onError: (err, variables, context) => {
      if (context?.previousMessages) {
        queryClient.setQueryData(messagesKeys.list(variables.conversationId), context.previousMessages);
      }
    },
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({ queryKey: messagesKeys.list(variables.conversationId) });
    },
  });
}
