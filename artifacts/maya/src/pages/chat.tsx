import React, { useEffect, useState } from 'react';
import { useRoute, useLocation } from 'wouter';
import { Sidebar } from '../components/sidebar';
import { ChatArea } from '../components/chat-area';
import { useConversations, useCreateConversation } from '../hooks/use-conversations';
import { Sparkles, Menu } from 'lucide-react';

export default function ChatPage({ params }: { params?: { id?: string } }) {
  const [, setLocation] = useLocation();
  const { data: conversations, isLoading } = useConversations();
  const createConversation = useCreateConversation();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const id = params?.id || null;

  // Handle first load empty state
  useEffect(() => {
    if (!isLoading && conversations) {
      if (conversations.length === 0 && !createConversation.isPending) {
        // Create initial conversation
        createConversation.mutate({}, {
          onSuccess: (conv) => {
            setLocation(`/${conv.id}`);
          }
        });
      } else if (conversations.length > 0 && !id) {
        // Redirect to most recent conversation if no ID in URL
        setLocation(`/${conversations[0].id}`);
      }
    }
  }, [isLoading, conversations, id, setLocation]); // Intentionally excluding createConversation.isPending to avoid loops

  // While creating the first conversation or checking initial state
  if (isLoading || (!id && conversations?.length === 0)) {
    return (
      <div className="flex h-[100dvh] w-full items-center justify-center bg-background text-foreground">
        <div className="flex flex-col items-center gap-4">
          <Sparkles className="w-8 h-8 text-primary animate-pulse-slow" />
          <p className="font-mono text-sm tracking-widest text-muted-foreground">INITIALIZING</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-[100dvh] w-full overflow-hidden bg-background text-foreground">
      <Sidebar currentId={id} isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      {id ? (
        <ChatArea conversationId={id} onOpenSidebar={() => setSidebarOpen(true)} />
      ) : (
        <div className="flex-1 flex flex-col bg-background">
          <div className="md:hidden flex items-center gap-3 p-4 border-b border-border/50">
            <button
              onClick={() => setSidebarOpen(true)}
              className="p-2 rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
              title="Open menu"
              aria-label="Open menu"
            >
              <Menu size={20} />
            </button>
            <h1 className="text-lg font-bold font-sans tracking-tight text-primary">MAYA<span className="text-foreground/30">.</span></h1>
          </div>
          <div className="flex-1 flex items-center justify-center">
            <p className="text-muted-foreground font-mono">Select a conversation</p>
          </div>
        </div>
      )}
    </div>
  );
}
