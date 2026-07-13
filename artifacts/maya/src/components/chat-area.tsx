import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { motion, AnimatePresence, PanInfo } from 'framer-motion';
import {
  Send,
  Bot,
  User,
  Sparkles,
  Menu,
  Reply,
  Copy,
  Pin,
  Check,
  X,
  CornerUpLeft,
} from 'lucide-react';
import { useMessages, useSendMessage, useSetMessagePinned } from '../hooks/use-messages';
import { Message } from '../lib/api';

interface ChatAreaProps {
  conversationId: string;
  onOpenSidebar: () => void;
}

// Swipe gesture thresholds, tuned so a deliberate flick registers but small
// scroll jitter on touch devices doesn't accidentally trigger an action.
const SWIPE_DISTANCE_THRESHOLD = 64;
const SWIPE_VELOCITY_THRESHOLD = 350;

type SwipeDirection = 'right' | 'up' | 'down' | null;

function detectSwipe(info: PanInfo): SwipeDirection {
  const { offset, velocity } = info;
  const absX = Math.abs(offset.x);
  const absY = Math.abs(offset.y);

  const passesX =
    offset.x > SWIPE_DISTANCE_THRESHOLD || velocity.x > SWIPE_VELOCITY_THRESHOLD;
  const passesUp =
    offset.y < -SWIPE_DISTANCE_THRESHOLD || velocity.y < -SWIPE_VELOCITY_THRESHOLD;
  const passesDown =
    offset.y > SWIPE_DISTANCE_THRESHOLD || velocity.y > SWIPE_VELOCITY_THRESHOLD;

  if (absX > absY && passesX) return 'right';
  if (absY >= absX && passesUp) return 'up';
  if (absY >= absX && passesDown) return 'down';
  return null;
}

function truncate(text: string, max: number) {
  const oneLine = text.replace(/\s+/g, ' ').trim();
  return oneLine.length > max ? `${oneLine.slice(0, max)}…` : oneLine;
}

interface MessageBubbleProps {
  msg: Message;
  index: number;
  isPending: boolean;
  replySource: Message | null;
  isCopied: boolean;
  registerRef: (id: string, el: HTMLDivElement | null) => void;
  onReply: (msg: Message) => void;
  onCopy: (msg: Message) => void;
  onTogglePin: (msg: Message) => void;
  onJumpToReplySource: (id: string) => void;
}

function MessageBubble({
  msg,
  index,
  replySource,
  isCopied,
  registerRef,
  onReply,
  onCopy,
  onTogglePin,
  onJumpToReplySource,
}: MessageBubbleProps) {
  const isUser = msg.role === 'user';
  const [swipeHint, setSwipeHint] = useState<SwipeDirection>(null);

  const handleDrag = (_e: unknown, info: PanInfo) => {
    setSwipeHint(detectSwipe(info));
  };

  const handleDragEnd = (_e: unknown, info: PanInfo) => {
    const direction = detectSwipe(info);
    setSwipeHint(null);
    if (direction === 'right') onReply(msg);
    else if (direction === 'up') onCopy(msg);
    else if (direction === 'down') onTogglePin(msg);
  };

  return (
    <motion.div
      ref={(el) => registerRef(msg.id, el)}
      layout
      initial={{ opacity: 0, y: 18, scale: 0.97 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{
        type: 'spring',
        stiffness: 420,
        damping: 34,
        delay: Math.min(index * 0.03, 0.3),
      }}
      className={`group flex flex-col gap-1 ${isUser ? 'items-end' : 'items-start'}`}
    >
      <div className={`flex gap-2.5 md:gap-4 w-full ${isUser ? 'justify-end' : 'justify-start'}`}>
        {!isUser && (
          <div className="w-7 h-7 md:w-8 md:h-8 rounded-lg bg-primary/10 border border-primary/20 flex items-center justify-center flex-shrink-0 mt-1">
            <Bot size={16} className="text-primary" />
          </div>
        )}

        <div className={`relative max-w-[88%] md:max-w-[85%] ${isUser ? 'items-end' : 'items-start'} flex flex-col`}>
          {/* Swipe action hints revealed behind the bubble while dragging */}
          <div
            className={`pointer-events-none absolute inset-0 flex items-center rounded-2xl transition-opacity duration-150 ${
              swipeHint ? 'opacity-100' : 'opacity-0'
            } ${swipeHint === 'right' ? 'justify-start pl-4 bg-primary/15' : 'justify-center'} ${
              swipeHint === 'up' ? 'bg-sky-500/15' : ''
            } ${swipeHint === 'down' ? 'bg-amber-500/15' : ''}`}
          >
            {swipeHint === 'right' && <Reply size={20} className="text-primary" />}
            {swipeHint === 'up' && <Copy size={20} className="text-sky-400" />}
            {swipeHint === 'down' && <Pin size={20} className="text-amber-400" />}
          </div>

          <motion.div
            drag
            dragElastic={0.5}
            dragSnapToOrigin
            dragConstraints={{ left: 0, right: 0, top: 0, bottom: 0 }}
            onDrag={handleDrag}
            onDragEnd={handleDragEnd}
            whileDrag={{ scale: 1.02, cursor: 'grabbing' }}
            transition={{ type: 'spring', stiffness: 500, damping: 30 }}
            style={{ touchAction: 'none' }}
            className={`relative rounded-2xl px-4 py-3 md:px-5 md:py-3.5 text-sm md:text-base leading-relaxed cursor-grab active:cursor-grabbing ${
              isUser
                ? 'bg-primary text-primary-foreground rounded-tr-sm shadow-md'
                : 'bg-card border border-card-border text-card-foreground rounded-tl-sm shadow-sm'
            } ${msg.pinned ? 'ring-2 ring-amber-400/60' : ''}`}
          >
            {msg.pinned && (
              <div
                className={`absolute -top-2 flex items-center gap-1 text-[10px] font-mono uppercase tracking-wide text-amber-400 ${
                  isUser ? '-left-1' : '-right-1'
                }`}
              >
                <Pin size={10} className="fill-amber-400" />
              </div>
            )}

            {replySource && (
              <button
                onClick={() => onJumpToReplySource(replySource.id)}
                className={`mb-2 flex w-full items-start gap-1.5 rounded-lg border-l-2 px-2 py-1.5 text-left text-xs md:text-sm opacity-80 hover:opacity-100 transition-opacity ${
                  isUser
                    ? 'border-primary-foreground/40 bg-primary-foreground/10'
                    : 'border-primary/50 bg-primary/5'
                }`}
              >
                <CornerUpLeft size={12} className="mt-0.5 flex-shrink-0" />
                <span className="truncate">{truncate(replySource.content, 80)}</span>
              </button>
            )}

            <p className="whitespace-pre-wrap break-words select-text">{msg.content}</p>
          </motion.div>

          {/* Tap-friendly action buttons for users who prefer not to swipe */}
          <div
            className={`flex items-center gap-1 mt-1 opacity-100 md:opacity-0 md:group-hover:opacity-100 md:focus-within:opacity-100 transition-opacity duration-150 ${
              isUser ? 'flex-row-reverse' : 'flex-row'
            }`}
          >
            <ActionButton label="Reply" onClick={() => onReply(msg)}>
              <Reply size={13} />
            </ActionButton>
            <ActionButton label={isCopied ? 'Copied' : 'Copy'} onClick={() => onCopy(msg)}>
              <AnimatePresence mode="wait" initial={false}>
                {isCopied ? (
                  <motion.span
                    key="check"
                    initial={{ scale: 0.5, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    exit={{ scale: 0.5, opacity: 0 }}
                    transition={{ duration: 0.15 }}
                    className="flex text-emerald-400"
                  >
                    <Check size={13} />
                  </motion.span>
                ) : (
                  <motion.span
                    key="copy"
                    initial={{ scale: 0.5, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    exit={{ scale: 0.5, opacity: 0 }}
                    transition={{ duration: 0.15 }}
                    className="flex"
                  >
                    <Copy size={13} />
                  </motion.span>
                )}
              </AnimatePresence>
            </ActionButton>
            <ActionButton
              label={msg.pinned ? 'Unpin' : 'Pin'}
              onClick={() => onTogglePin(msg)}
              active={msg.pinned}
            >
              <Pin size={13} className={msg.pinned ? 'fill-amber-400' : ''} />
            </ActionButton>
          </div>
        </div>

        {isUser && (
          <div className="w-7 h-7 md:w-8 md:h-8 rounded-lg bg-secondary flex items-center justify-center flex-shrink-0 mt-1 border border-border">
            <User size={16} className="text-secondary-foreground" />
          </div>
        )}
      </div>
    </motion.div>
  );
}

function ActionButton({
  label,
  onClick,
  active,
  children,
}: {
  label: string;
  onClick: () => void;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <motion.button
      type="button"
      onClick={onClick}
      title={label}
      aria-label={label}
      whileTap={{ scale: 0.8 }}
      whileHover={{ scale: 1.08 }}
      transition={{ type: 'spring', stiffness: 500, damping: 20 }}
      className={`flex items-center justify-center w-7 h-7 rounded-full border transition-colors duration-150 ${
        active
          ? 'bg-amber-400/15 border-amber-400/40 text-amber-400'
          : 'bg-secondary/60 border-border/60 text-muted-foreground hover:text-foreground hover:bg-secondary'
      }`}
    >
      {children}
    </motion.button>
  );
}

export function ChatArea({ conversationId, onOpenSidebar }: ChatAreaProps) {
  const { data: messages, isLoading } = useMessages(conversationId);
  const sendMessage = useSendMessage();
  const setPinned = useSetMessagePinned();
  const [inputValue, setInputValue] = useState('');
  const [replyingTo, setReplyingTo] = useState<Message | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const messageRefs = useRef<Map<string, HTMLDivElement>>(new Map());
  const copyTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const messagesById = useMemo(() => {
    const map = new Map<string, Message>();
    messages?.forEach((m) => map.set(m.id, m));
    return map;
  }, [messages]);

  const pinnedMessages = useMemo(() => messages?.filter((m) => m.pinned) ?? [], [messages]);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, sendMessage.isPending]);

  // Focus input on load, reset reply state when switching conversations
  useEffect(() => {
    inputRef.current?.focus();
    setReplyingTo(null);
  }, [conversationId]);

  useEffect(() => {
    return () => {
      if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current);
    };
  }, []);

  const registerMessageRef = useCallback((id: string, el: HTMLDivElement | null) => {
    if (el) messageRefs.current.set(id, el);
    else messageRefs.current.delete(id);
  }, []);

  const scrollToMessage = useCallback((id: string) => {
    const el = messageRefs.current.get(id);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      el.animate(
        [
          { boxShadow: '0 0 0 0 rgba(251,191,36,0.5)' },
          { boxShadow: '0 0 0 8px rgba(251,191,36,0)' },
        ],
        { duration: 700, easing: 'ease-out' }
      );
    }
  }, []);

  const handleCopy = useCallback((msg: Message) => {
    navigator.clipboard?.writeText(msg.content).catch(() => {
      /* clipboard API unavailable or denied — silently ignore */
    });
    setCopiedId(msg.id);
    if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current);
    copyTimeoutRef.current = setTimeout(() => setCopiedId(null), 1500);
  }, []);

  const handleTogglePin = useCallback(
    (msg: Message) => {
      setPinned.mutate({ conversationId, messageId: msg.id, pinned: !msg.pinned });
    },
    [conversationId, setPinned]
  );

  const handleReply = useCallback((msg: Message) => {
    setReplyingTo(msg);
    inputRef.current?.focus();
  }, []);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputValue.trim() || sendMessage.isPending) return;

    sendMessage.mutate({
      conversationId,
      content: inputValue.trim(),
      replyToId: replyingTo?.id ?? null,
    });
    setInputValue('');
    setReplyingTo(null);

    // Reset textarea height
    if (inputRef.current) {
      inputRef.current.style.height = 'auto';
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
    if (e.key === 'Escape' && replyingTo) {
      setReplyingTo(null);
    }
  };

  const handleInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInputValue(e.target.value);
    // Auto-resize
    e.target.style.height = 'auto';
    e.target.style.height = `${Math.min(e.target.scrollHeight, 200)}px`;
  };

  if (isLoading) {
    return (
      <div className="flex-1 flex flex-col h-full bg-background items-center justify-center">
        <div className="flex items-center gap-3 text-primary animate-pulse-slow">
          <Sparkles size={24} />
          <span className="font-mono text-sm tracking-widest">CONNECTING</span>
        </div>
      </div>
    );
  }

  const isEmpty = !messages || messages.length === 0;

  return (
    <div className="flex-1 flex flex-col h-full bg-background relative min-w-0">
      {/* Mobile header: menu toggle since the sidebar is off-canvas on small screens */}
      <div className="md:hidden flex items-center gap-3 p-4 border-b border-border/50 relative z-20 bg-background">
        <button
          onClick={onOpenSidebar}
          className="p-2 rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors -ml-2"
          title="Open menu"
          aria-label="Open menu"
        >
          <Menu size={20} />
        </button>
        <h1 className="text-lg font-bold font-sans tracking-tight text-primary">MAYA<span className="text-foreground/30">.</span></h1>
      </div>

      {/* Decorative top gradient */}
      <div className="absolute top-0 left-0 right-0 h-24 bg-gradient-to-b from-background to-transparent z-10 pointer-events-none hidden md:block" />

      {/* Pinned messages strip */}
      {pinnedMessages.length > 0 && (
        <div className="relative z-20 border-b border-border/50 bg-background/95 backdrop-blur-sm">
          <div className="max-w-3xl mx-auto px-4 md:px-8 py-2 flex items-center gap-2 overflow-x-auto custom-scrollbar">
            <span className="flex items-center gap-1 text-[10px] font-mono uppercase tracking-wide text-amber-400 flex-shrink-0">
              <Pin size={11} className="fill-amber-400" /> Pinned
            </span>
            {pinnedMessages.map((m) => (
              <button
                key={m.id}
                onClick={() => scrollToMessage(m.id)}
                className="flex-shrink-0 text-xs px-3 py-1 rounded-full bg-amber-400/10 border border-amber-400/30 text-amber-200 hover:bg-amber-400/20 transition-colors max-w-[220px] truncate"
              >
                {truncate(m.content, 40)}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Messages */}
      <div className="flex-1 overflow-y-auto px-4 md:px-8 pt-6 md:pt-10 pb-6 custom-scrollbar relative z-0">
        <div className="max-w-3xl mx-auto flex flex-col gap-6 md:gap-8 min-h-full justify-end">
          {isEmpty ? (
            <motion.div
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.6, ease: 'easeOut' }}
              className="flex-1 flex flex-col items-center justify-center text-center"
            >
              <div className="w-16 h-16 rounded-2xl bg-card border border-card-border flex items-center justify-center mb-6 shadow-xl">
                <Bot size={32} className="text-primary" />
              </div>
              <h2 className="text-2xl font-bold mb-2">How can I assist you today?</h2>
              <p className="text-muted-foreground max-w-md">
                I'm Maya. Give me a task, ask a question, or let's start solving a problem together.
              </p>
            </motion.div>
          ) : (
            <AnimatePresence initial={false}>
              {messages.map((msg, idx) => (
                <MessageBubble
                  key={msg.id}
                  msg={msg}
                  index={idx}
                  isPending={sendMessage.isPending}
                  replySource={msg.reply_to_id ? messagesById.get(msg.reply_to_id) ?? null : null}
                  isCopied={copiedId === msg.id}
                  registerRef={registerMessageRef}
                  onReply={handleReply}
                  onCopy={handleCopy}
                  onTogglePin={handleTogglePin}
                  onJumpToReplySource={scrollToMessage}
                />
              ))}
            </AnimatePresence>
          )}
          
          {sendMessage.isPending && (
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              className="flex gap-4 justify-start"
            >
              <div className="w-8 h-8 rounded-lg bg-primary/10 border border-primary/20 flex items-center justify-center flex-shrink-0 mt-1">
                <Bot size={16} className="text-primary" />
              </div>
              <div className="bg-card border border-card-border rounded-2xl rounded-tl-sm px-5 py-4 flex items-center gap-1.5 shadow-sm">
                <div className="w-1.5 h-1.5 rounded-full bg-primary/60 animate-bounce" style={{ animationDelay: '0ms' }} />
                <div className="w-1.5 h-1.5 rounded-full bg-primary/60 animate-bounce" style={{ animationDelay: '150ms' }} />
                <div className="w-1.5 h-1.5 rounded-full bg-primary/60 animate-bounce" style={{ animationDelay: '300ms' }} />
              </div>
            </motion.div>
          )}
          <div ref={bottomRef} className="h-4" />
        </div>
      </div>

      {/* Input area */}
      <div className="p-4 md:p-6 bg-background border-t border-border/50 relative z-20">
        <div className="max-w-3xl mx-auto">
          <AnimatePresence>
            {replyingTo && (
              <motion.div
                initial={{ opacity: 0, height: 0, marginBottom: 0 }}
                animate={{ opacity: 1, height: 'auto', marginBottom: 8 }}
                exit={{ opacity: 0, height: 0, marginBottom: 0 }}
                transition={{ duration: 0.2, ease: 'easeOut' }}
                className="flex items-center gap-2 rounded-lg border border-primary/30 bg-primary/5 px-3 py-2 overflow-hidden"
              >
                <CornerUpLeft size={14} className="text-primary flex-shrink-0" />
                <div className="flex-1 min-w-0">
                  <p className="text-[10px] font-mono uppercase tracking-wide text-primary">
                    Replying to {replyingTo.role === 'user' ? 'yourself' : 'Maya'}
                  </p>
                  <p className="text-xs text-muted-foreground truncate">{truncate(replyingTo.content, 100)}</p>
                </div>
                <button
                  type="button"
                  onClick={() => setReplyingTo(null)}
                  className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors flex-shrink-0"
                  aria-label="Cancel reply"
                >
                  <X size={14} />
                </button>
              </motion.div>
            )}
          </AnimatePresence>

          <form
            onSubmit={handleSubmit}
            className="relative glass-panel rounded-xl overflow-hidden focus-within:ring-1 focus-within:ring-primary transition-all duration-300"
          >
            <textarea
              ref={inputRef}
              value={inputValue}
              onChange={handleInput}
              onKeyDown={handleKeyDown}
              placeholder="Message Maya..."
              className="w-full bg-transparent border-none resize-none px-4 py-4 pr-14 text-sm md:text-base outline-none max-h-[200px] text-foreground placeholder:text-muted-foreground/70"
              rows={1}
            />
            <div className="absolute right-2 bottom-2">
              <motion.button
                type="submit"
                disabled={!inputValue.trim() || sendMessage.isPending}
                whileTap={{ scale: 0.85 }}
                className="p-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 disabled:hover:bg-primary transition-all flex items-center justify-center shadow-md shadow-primary/20"
              >
                <Send size={18} className="translate-x-[1px] translate-y-[-1px]" />
              </motion.button>
            </div>
          </form>
          <div className="text-center mt-3">
            <span className="text-[10px] text-muted-foreground/50 font-mono tracking-wide uppercase">
              MAYA — Secure Workspace Environment
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
