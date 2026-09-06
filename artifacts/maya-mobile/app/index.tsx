import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Image,
  Keyboard,
  Modal,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import { useColors } from '@/hooks/useColors';
import { Conversation, mayaApi, Message } from '@/lib/maya-api';

type MessageRowProps = {
  message: Message;
  colors: ReturnType<typeof useColors>;
};

function MessageRow({ message, colors }: MessageRowProps) {
  const isUser = message.role === 'user';
  return (
    <View style={[styles.messageRow, isUser ? styles.userRow : styles.assistantRow]}>
      {!isUser ? (
        <View style={[styles.mayaMark, { backgroundColor: colors.primary }]}>
          <Ionicons name="sparkles" size={14} color={colors.primaryForeground} />
        </View>
      ) : null}
      <View
        style={[
          styles.messageBubble,
          isUser
            ? { backgroundColor: colors.primary }
            : { backgroundColor: colors.card, borderColor: colors.border },
        ]}
      >
        <Text
          style={[
            styles.messageText,
            { color: isUser ? colors.primaryForeground : colors.foreground },
          ]}
        >
          {message.content}
        </Text>
      </View>
    </View>
  );
}

function ConversationDrawer({
  visible,
  conversations,
  activeId,
  colors,
  onClose,
  onSelect,
  onNew,
  onDelete,
}: {
  visible: boolean;
  conversations: Conversation[];
  activeId: string | null;
  colors: ReturnType<typeof useColors>;
  onClose: () => void;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (conversation: Conversation) => void;
}) {
  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={[styles.drawerBackdrop, { backgroundColor: 'rgba(0,0,0,0.64)' }]}>
        <View
          style={[
            styles.drawer,
            { backgroundColor: colors.background, borderColor: colors.border },
          ]}
        >
          <View style={styles.drawerHeader}>
            <View>
              <Text style={[styles.drawerEyebrow, { color: colors.primary }]}>MAYA</Text>
              <Text style={[styles.drawerTitle, { color: colors.foreground }]}>
                Your conversations
              </Text>
            </View>
            <Pressable
              accessibilityLabel="Close conversations"
              testID="close-conversations"
              onPress={onClose}
              style={({ pressed }) => [styles.iconButton, pressed && styles.pressed]}
            >
              <Ionicons name="close" size={23} color={colors.foreground} />
            </Pressable>
          </View>

          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Start a new conversation"
            testID="new-conversation"
            onPress={onNew}
            style={({ pressed }) => [
              styles.newConversation,
              { backgroundColor: colors.primary },
              pressed && styles.pressed,
            ]}
          >
            <Ionicons name="add" size={20} color={colors.primaryForeground} />
            <Text style={[styles.newConversationText, { color: colors.primaryForeground }]}>
              New conversation
            </Text>
          </Pressable>

          <FlatList
            data={conversations}
            keyExtractor={(item) => item.id}
            contentContainerStyle={styles.conversationList}
            renderItem={({ item }) => (
              <View
                style={[
                  styles.conversationItem,
                  {
                    backgroundColor:
                      item.id === activeId ? colors.secondary : 'transparent',
                  },
                ]}
              >
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`Open ${item.title}`}
                  onPress={() => onSelect(item.id)}
                  style={({ pressed }) => [styles.conversationSelect, pressed && styles.pressed]}
                >
                  <Ionicons
                    name="chatbubble-ellipses-outline"
                    size={18}
                    color={item.id === activeId ? colors.primary : colors.mutedForeground}
                  />
                  <View style={styles.conversationCopy}>
                    <Text
                      numberOfLines={1}
                      style={[styles.conversationTitle, { color: colors.foreground }]}
                    >
                      {item.title || 'New conversation'}
                    </Text>
                    {item.preview ? (
                      <Text
                        numberOfLines={1}
                        style={[styles.conversationPreview, { color: colors.mutedForeground }]}
                      >
                        {item.preview}
                      </Text>
                    ) : null}
                  </View>
                </Pressable>
                <Pressable
                  accessibilityLabel={`Delete ${item.title}`}
                  onPress={() => onDelete(item)}
                  style={({ pressed }) => [styles.deleteButton, pressed && styles.pressed]}
                >
                  <Ionicons name="trash-outline" size={17} color={colors.mutedForeground} />
                </Pressable>
              </View>
            )}
            ListEmptyComponent={
              <Text style={[styles.emptyDrawerText, { color: colors.mutedForeground }]}>
                No conversations yet.
              </Text>
            }
          />
        </View>
      </View>
    </Modal>
  );
}

export default function MayaChatScreen() {
  const colors = useColors();
  const insets = useSafeAreaInsets();
  const inputRef = useRef<TextInput>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [drawerVisible, setDrawerVisible] = useState(false);

  const loadConversations = useCallback(async () => {
    const next = await mayaApi.conversations.list();
    setConversations(next);
    return next;
  }, []);

  const loadMessages = useCallback(async (conversationId: string) => {
    setMessages(await mayaApi.messages.list(conversationId));
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const next = await loadConversations();
        if (cancelled) return;
        if (next.length > 0) {
          setActiveId(next[0].id);
          await loadMessages(next[0].id);
        } else {
          const created = await mayaApi.conversations.create();
          if (cancelled) return;
          setConversations([created]);
          setActiveId(created.id);
          setMessages([]);
        }
      } catch (requestError) {
        if (!cancelled) setError(requestError instanceof Error ? requestError.message : 'Unable to connect to Maya.');
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [loadConversations, loadMessages]);

  const activeConversation = useMemo(
    () => conversations.find((item) => item.id === activeId),
    [activeId, conversations],
  );

  const refresh = useCallback(async () => {
    if (!activeId) return;
    setIsRefreshing(true);
    setError(null);
    try {
      await Promise.all([loadConversations(), loadMessages(activeId)]);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Unable to refresh Maya.');
    } finally {
      setIsRefreshing(false);
    }
  }, [activeId, loadConversations, loadMessages]);

  const startNewConversation = useCallback(async () => {
    try {
      await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      const created = await mayaApi.conversations.create();
      setConversations((current) => [created, ...current]);
      setActiveId(created.id);
      setMessages([]);
      setDrawerVisible(false);
      setError(null);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Unable to start a conversation.');
    }
  }, []);

  const selectConversation = useCallback(
    async (conversationId: string) => {
      setActiveId(conversationId);
      setDrawerVisible(false);
      setError(null);
      try {
        await loadMessages(conversationId);
      } catch (requestError) {
        setError(requestError instanceof Error ? requestError.message : 'Unable to load this conversation.');
      }
    },
    [loadMessages],
  );

  const deleteConversation = useCallback(
    (conversation: Conversation) => {
      Alert.alert('Delete conversation?', 'This cannot be undone.', [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: async () => {
            try {
              await mayaApi.conversations.delete(conversation.id);
              const remaining = conversations.filter((item) => item.id !== conversation.id);
              if (remaining.length === 0) {
                await startNewConversation();
              } else {
                setConversations(remaining);
                if (activeId === conversation.id) {
                  await selectConversation(remaining[0].id);
                }
              }
            } catch (requestError) {
              setError(requestError instanceof Error ? requestError.message : 'Unable to delete conversation.');
            }
          },
        },
      ]);
    },
    [activeId, conversations, selectConversation, startNewConversation],
  );

  const sendMessage = useCallback(async () => {
    const content = draft.trim();
    if (!content || !activeId || isSending) return;
    Keyboard.dismiss();
    setDraft('');
    setIsSending(true);
    setError(null);
    try {
      await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      const response = await mayaApi.messages.create(activeId, content);
      setMessages((current) => [...current, response.user_message, response.assistant_message]);
      await loadConversations();
    } catch (requestError) {
      setDraft(content);
      setError(requestError instanceof Error ? requestError.message : 'Maya could not respond right now.');
    } finally {
      setIsSending(false);
    }
  }, [activeId, draft, isSending, loadConversations]);

  if (isLoading) {
    return (
      <View style={[styles.loadingScreen, { backgroundColor: colors.background }]}>
        <Image source={require('@/assets/images/icon.png')} style={styles.loadingLogo} />
        <ActivityIndicator color={colors.primary} size="small" />
        <Text style={[styles.loadingLabel, { color: colors.mutedForeground }]}>CONNECTING TO MAYA</Text>
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={[styles.screen, { backgroundColor: colors.background }]}
      behavior="padding"
      keyboardVerticalOffset={0}
    >
      <View style={[styles.header, { paddingTop: insets.top + 12, borderBottomColor: colors.border }]}>
        <Pressable
          accessibilityLabel="Open conversations"
          testID="open-conversations"
          onPress={() => setDrawerVisible(true)}
          style={({ pressed }) => [styles.iconButton, pressed && styles.pressed]}
        >
          <Ionicons name="menu-outline" size={26} color={colors.foreground} />
        </Pressable>
        <View style={styles.brand}>
          <Image source={require('@/assets/images/icon.png')} style={styles.headerLogo} />
          <View>
            <Text style={[styles.brandName, { color: colors.foreground }]}>MAYA</Text>
            <Text style={[styles.brandStatus, { color: colors.primary }]}>ONLINE</Text>
          </View>
        </View>
        <Pressable
          accessibilityLabel="Start a new conversation"
          testID="header-new-conversation"
          onPress={startNewConversation}
          style={({ pressed }) => [styles.iconButton, pressed && styles.pressed]}
        >
          <Ionicons name="create-outline" size={23} color={colors.foreground} />
        </Pressable>
      </View>

      <View style={styles.chatMeta}>
        <Text style={[styles.conversationHeading, { color: colors.foreground }]} numberOfLines={1}>
          {activeConversation?.title || 'New conversation'}
        </Text>
        <Text style={[styles.conversationSubheading, { color: colors.mutedForeground }]}>
          Ask anything. Maya is listening.
        </Text>
      </View>

      {error ? (
        <View style={[styles.errorBanner, { backgroundColor: colors.secondary, borderColor: colors.border }]}>
          <Ionicons name="warning-outline" size={17} color={colors.primary} />
          <Text style={[styles.errorText, { color: colors.foreground }]} numberOfLines={2}>
            {error}
          </Text>
          <Pressable onPress={refresh} accessibilityLabel="Retry" style={styles.retryButton}>
            <Text style={[styles.retryText, { color: colors.primary }]}>Retry</Text>
          </Pressable>
        </View>
      ) : null}

      <FlatList
        inverted
        data={[...messages].reverse()}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => <MessageRow message={item} colors={colors} />}
        contentContainerStyle={[
          styles.messageList,
          { paddingBottom: 18, paddingTop: isSending ? 12 : 28 },
        ]}
        keyboardDismissMode="interactive"
        keyboardShouldPersistTaps="handled"
        refreshControl={<RefreshControl refreshing={isRefreshing} onRefresh={refresh} tintColor={colors.primary} />}
        ListEmptyComponent={
          <View style={styles.welcome}>
            <View style={[styles.welcomeIcon, { backgroundColor: colors.primary }]}>
              <Ionicons name="sparkles" size={26} color={colors.primaryForeground} />
            </View>
            <Text style={[styles.welcomeTitle, { color: colors.foreground }]}>Hello, I’m Maya.</Text>
            <Text style={[styles.welcomeBody, { color: colors.mutedForeground }]}>
              A friendly tech-savvy chatbot for clear answers, curious questions, and good conversations.
            </Text>
          </View>
        }
        ListHeaderComponent={
          isSending ? (
            <View style={styles.typingRow}>
              <ActivityIndicator color={colors.primary} size="small" />
              <Text style={[styles.typingText, { color: colors.mutedForeground }]}>Maya is thinking…</Text>
            </View>
          ) : null
        }
      />

      <View
        style={[
          styles.composerWrap,
          {
            backgroundColor: colors.background,
            borderTopColor: colors.border,
            paddingBottom: Math.max(insets.bottom, 12),
          },
        ]}
      >
        <View style={[styles.composer, { backgroundColor: colors.card, borderColor: colors.input }]}>
          <TextInput
            ref={inputRef}
            value={draft}
            onChangeText={setDraft}
            onSubmitEditing={sendMessage}
            placeholder="Message Maya"
            placeholderTextColor={colors.mutedForeground}
            multiline
            maxLength={4000}
            returnKeyType="send"
            blurOnSubmit={false}
            style={[styles.input, { color: colors.foreground }]}
            accessibilityLabel="Message Maya"
            testID="message-input"
          />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Send message"
            testID="send-message"
            disabled={!draft.trim() || isSending}
            onPress={sendMessage}
            style={({ pressed }) => [
              styles.sendButton,
              { backgroundColor: draft.trim() && !isSending ? colors.primary : colors.secondary },
              pressed && styles.pressed,
            ]}
          >
            {isSending ? (
              <ActivityIndicator color={colors.mutedForeground} size="small" />
            ) : (
              <Ionicons
                name="arrow-up"
                size={21}
                color={draft.trim() ? colors.primaryForeground : colors.mutedForeground}
              />
            )}
          </Pressable>
        </View>
        <Text style={[styles.disclaimer, { color: colors.mutedForeground }]}>
          Maya can make mistakes. Check important information.
        </Text>
      </View>

      <ConversationDrawer
        visible={drawerVisible}
        conversations={conversations}
        activeId={activeId}
        colors={colors}
        onClose={() => setDrawerVisible(false)}
        onSelect={selectConversation}
        onNew={startNewConversation}
        onDelete={deleteConversation}
      />
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  loadingScreen: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16 },
  loadingLogo: { width: 82, height: 82, borderRadius: 24, marginBottom: 6 },
  loadingLabel: { fontFamily: 'Inter_600SemiBold', fontSize: 10, letterSpacing: 2.1 },
  header: {
    minHeight: 76,
    paddingHorizontal: 18,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  headerLogo: { width: 34, height: 34, borderRadius: 10 },
  brandName: { fontFamily: 'Inter_700Bold', fontSize: 17, letterSpacing: 2.2 },
  brandStatus: { fontFamily: 'Inter_600SemiBold', fontSize: 8, letterSpacing: 1.5, marginTop: 2 },
  iconButton: { width: 42, height: 42, alignItems: 'center', justifyContent: 'center' },
  pressed: { opacity: 0.62 },
  chatMeta: { paddingHorizontal: 22, paddingTop: 18, paddingBottom: 4 },
  conversationHeading: { fontFamily: 'Inter_600SemiBold', fontSize: 18 },
  conversationSubheading: { fontFamily: 'Inter_400Regular', fontSize: 13, marginTop: 4 },
  messageList: { flexGrow: 1, paddingHorizontal: 18 },
  messageRow: { flexDirection: 'row', marginBottom: 15, gap: 8, alignItems: 'flex-end' },
  userRow: { justifyContent: 'flex-end' },
  assistantRow: { justifyContent: 'flex-start' },
  mayaMark: { width: 26, height: 26, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  messageBubble: { maxWidth: '82%', borderRadius: 18, paddingHorizontal: 15, paddingVertical: 12, borderWidth: 1 },
  messageText: { fontFamily: 'Inter_400Regular', fontSize: 15, lineHeight: 22 },
  welcome: { flex: 1, justifyContent: 'center', alignItems: 'center', paddingHorizontal: 36, paddingVertical: 60 },
  welcomeIcon: { width: 58, height: 58, borderRadius: 20, alignItems: 'center', justifyContent: 'center', marginBottom: 18 },
  welcomeTitle: { fontFamily: 'Inter_700Bold', fontSize: 23, marginBottom: 10 },
  welcomeBody: { fontFamily: 'Inter_400Regular', fontSize: 14, lineHeight: 21, textAlign: 'center' },
  typingRow: { flexDirection: 'row', alignItems: 'center', gap: 9, paddingHorizontal: 8, paddingVertical: 12 },
  typingText: { fontFamily: 'Inter_400Regular', fontSize: 13 },
  composerWrap: { borderTopWidth: StyleSheet.hairlineWidth, paddingHorizontal: 16, paddingTop: 12 },
  composer: { minHeight: 52, borderRadius: 18, borderWidth: 1, flexDirection: 'row', alignItems: 'flex-end', padding: 6 },
  input: { flex: 1, maxHeight: 116, minHeight: 38, paddingHorizontal: 10, paddingTop: 9, paddingBottom: 8, fontFamily: 'Inter_400Regular', fontSize: 15, lineHeight: 21 },
  sendButton: { width: 38, height: 38, borderRadius: 13, alignItems: 'center', justifyContent: 'center' },
  disclaimer: { fontFamily: 'Inter_400Regular', fontSize: 10, textAlign: 'center', marginTop: 8 },
  errorBanner: { marginHorizontal: 16, marginTop: 8, padding: 10, borderRadius: 12, borderWidth: 1, flexDirection: 'row', alignItems: 'center', gap: 8 },
  errorText: { flex: 1, fontFamily: 'Inter_400Regular', fontSize: 12, lineHeight: 17 },
  retryButton: { padding: 5 },
  retryText: { fontFamily: 'Inter_600SemiBold', fontSize: 12 },
  drawerBackdrop: { flex: 1, justifyContent: 'flex-end' },
  drawer: { minHeight: '76%', borderTopLeftRadius: 28, borderTopRightRadius: 28, borderWidth: 1, paddingTop: 22, paddingHorizontal: 18, paddingBottom: 28 },
  drawerHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 },
  drawerEyebrow: { fontFamily: 'Inter_700Bold', fontSize: 10, letterSpacing: 2.2, marginBottom: 5 },
  drawerTitle: { fontFamily: 'Inter_700Bold', fontSize: 22 },
  newConversation: { minHeight: 48, borderRadius: 15, paddingHorizontal: 16, flexDirection: 'row', alignItems: 'center', gap: 9, marginBottom: 15 },
  newConversationText: { fontFamily: 'Inter_600SemiBold', fontSize: 14 },
  conversationList: { gap: 4, paddingBottom: 18 },
  conversationItem: { minHeight: 62, borderRadius: 14, flexDirection: 'row', alignItems: 'center', paddingLeft: 13, paddingRight: 6 },
  conversationSelect: { flex: 1, minHeight: 56, flexDirection: 'row', alignItems: 'center', gap: 11 },
  conversationCopy: { flex: 1 },
  conversationTitle: { fontFamily: 'Inter_500Medium', fontSize: 14 },
  conversationPreview: { fontFamily: 'Inter_400Regular', fontSize: 11, marginTop: 4 },
  deleteButton: { width: 38, height: 42, alignItems: 'center', justifyContent: 'center' },
  emptyDrawerText: { textAlign: 'center', fontFamily: 'Inter_400Regular', paddingVertical: 30 },
});