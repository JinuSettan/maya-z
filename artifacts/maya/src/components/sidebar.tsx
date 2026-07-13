import React, { useState, useRef, useEffect } from 'react';
import { Plus, MoreVertical, Edit2, Trash2, X, Check } from 'lucide-react';
import { useConversations, useCreateConversation, useUpdateConversation, useDeleteConversation } from '../hooks/use-conversations';
import { Conversation } from '../lib/api';
import { Link, useLocation } from 'wouter';

interface SidebarProps {
  currentId: string | null;
  isOpen: boolean;
  onClose: () => void;
}

export function Sidebar({ currentId, isOpen, onClose }: SidebarProps) {
  const { data: conversations, isLoading } = useConversations();
  const createConversation = useCreateConversation();
  const updateConversation = useUpdateConversation();
  const deleteConversation = useDeleteConversation();
  const [, setLocation] = useLocation();

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);

  const handleNewChat = () => {
    createConversation.mutate({}, {
      onSuccess: (newConv) => {
        setLocation(`/${newConv.id}`);
        onClose();
      }
    });
  };

  const startEdit = (conv: Conversation) => {
    setEditingId(conv.id);
    setEditTitle(conv.title);
    setMenuOpenId(null);
  };

  const saveEdit = (id: string) => {
    if (editTitle.trim()) {
      updateConversation.mutate({ id, title: editTitle.trim() });
    }
    setEditingId(null);
  };

  const handleDelete = (id: string) => {
    deleteConversation.mutate(id, {
      onSuccess: () => {
        if (currentId === id) {
          setLocation('/');
        }
      }
    });
    setMenuOpenId(null);
  };

  // Close menu when clicking outside
  useEffect(() => {
    const handleDocClick = () => setMenuOpenId(null);
    document.addEventListener('click', handleDocClick);
    return () => document.removeEventListener('click', handleDocClick);
  }, []);

  return (
    <>
      {/* Mobile backdrop overlay: closes the drawer on outside tap */}
      {isOpen && (
        <div
          className="fixed inset-0 bg-black/60 z-40 md:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      )}

      <div
        className={`fixed md:static inset-y-0 left-0 z-50 w-[85vw] max-w-72 md:w-72 flex-shrink-0 flex flex-col h-full bg-sidebar border-r border-sidebar-border overflow-hidden transform transition-transform duration-300 ease-out md:translate-x-0 ${
          isOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
      <div className="p-4 border-b border-sidebar-border flex items-center justify-between">
        <h1 className="text-xl font-bold font-sans tracking-tight text-sidebar-primary">MAYA<span className="text-sidebar-foreground/30">.</span></h1>
        <div className="flex items-center gap-1">
          <button
            onClick={handleNewChat}
            disabled={createConversation.isPending}
            className="p-2 rounded-md bg-sidebar-primary/10 text-sidebar-primary hover:bg-sidebar-primary hover:text-sidebar-primary-foreground transition-all duration-200 disabled:opacity-50"
            title="New Chat"
          >
            <Plus size={18} />
          </button>
          <button
            onClick={onClose}
            className="p-2 rounded-md text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-foreground transition-all duration-200 md:hidden"
            title="Close menu"
            aria-label="Close menu"
          >
            <X size={18} />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-1 custom-scrollbar">
        {isLoading ? (
          <div className="space-y-3">
            {[1, 2, 3].map(i => (
              <div key={i} className="h-16 bg-sidebar-accent/50 rounded-lg animate-pulse" />
            ))}
          </div>
        ) : conversations?.length === 0 ? (
          <div className="text-center p-4 text-sm text-sidebar-foreground/50 mt-10 font-mono">
            No history.
          </div>
        ) : (
          conversations?.map((conv) => {
            const isActive = currentId === conv.id;
            const isEditing = editingId === conv.id;

            return (
              <div
                key={conv.id}
                className={`group relative rounded-lg border border-transparent transition-all duration-200 ${
                  isActive 
                    ? 'bg-sidebar-accent/80 border-sidebar-accent-border/50 shadow-sm' 
                    : 'hover:bg-sidebar-accent/40'
                }`}
              >
                {isEditing ? (
                  <div className="p-3 flex items-center gap-2">
                    <input
                      autoFocus
                      type="text"
                      className="flex-1 bg-background border border-border rounded px-2 py-1 text-sm outline-none focus:border-primary"
                      value={editTitle}
                      onChange={(e) => setEditTitle(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') saveEdit(conv.id);
                        if (e.key === 'Escape') setEditingId(null);
                      }}
                    />
                    <button onClick={() => saveEdit(conv.id)} className="text-primary hover:text-primary-foreground"><Check size={16} /></button>
                    <button onClick={() => setEditingId(null)} className="text-muted-foreground hover:text-foreground"><X size={16} /></button>
                  </div>
                ) : (
                  <Link href={`/${conv.id}`} className="block p-3" onClick={onClose}>
                    <div className="flex items-center justify-between">
                      <h3 className={`text-sm font-medium truncate pr-6 ${isActive ? 'text-sidebar-foreground' : 'text-sidebar-foreground/80'}`}>
                        {conv.title || 'New Conversation'}
                      </h3>
                    </div>
                    <p className="text-xs text-sidebar-foreground/50 truncate mt-1 h-4">
                      {conv.preview || '...'}
                    </p>
                  </Link>
                )}

                {!isEditing && (
                  <div className="absolute top-2 right-2 flex items-center">
                    <div className="relative" onClick={(e) => e.stopPropagation()}>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setMenuOpenId(menuOpenId === conv.id ? null : conv.id);
                        }}
                        className={`p-1.5 rounded-md transition-opacity ${isActive || menuOpenId === conv.id ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'} hover:bg-sidebar-border text-sidebar-foreground/60 hover:text-sidebar-foreground`}
                      >
                        <MoreVertical size={14} />
                      </button>
                      
                      {menuOpenId === conv.id && (
                        <div className="absolute right-0 top-full mt-1 w-32 bg-popover border border-popover-border rounded-md shadow-lg z-50 overflow-hidden text-sm">
                          <button
                            onClick={() => startEdit(conv)}
                            className="w-full text-left px-3 py-2 flex items-center gap-2 text-popover-foreground hover:bg-primary hover:text-primary-foreground transition-colors"
                          >
                            <Edit2 size={14} /> Rename
                          </button>
                          <button
                            onClick={() => handleDelete(conv.id)}
                            className="w-full text-left px-3 py-2 flex items-center gap-2 text-destructive hover:bg-destructive hover:text-destructive-foreground transition-colors"
                          >
                            <Trash2 size={14} /> Delete
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
      </div>
    </>
  );
}
