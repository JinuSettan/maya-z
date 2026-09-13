import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Image,
  Keyboard,
  Linking,
  Modal,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  TextInput,
  View
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import * as ImagePicker from 'expo-image-picker';
import * as Speech from 'expo-speech';

import { useColors } from '@/hooks/useColors';
import { BrainSetupScreen } from '@/components/BrainSetupScreen';
import { DeveloperPanel } from '@/components/DeveloperPanel';
import { MayaFace } from '@/components/MayaFace';
import { RockyFace } from '@/components/RockyFace';

import {
  DEVELOPER_COMMAND,
  developerModeEnabled,
  getDeveloperConfig
} from '@/lib/developer-config';

import {
  initializeLocalModel,
  ModelProgress,
  resetLocalModel
} from '@/lib/local-llm';

import {
  Conversation,
  mayaApi,
  Message
} from '@/lib/maya-api';

type SelectedImage = {
  uri: string;
  dataUrl: string;
};

type Character = 'MAYA' | 'ROCKY';

const CHARACTER_STORAGE_KEY = 'maya-selected-character';

function BrandMark({
  colors,
  size = 34
}: {
  colors: ReturnType<typeof useColors>;
  size?: number;
}) {
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.3,
        backgroundColor: colors.primary,
        alignItems: 'center',
        justifyContent: 'center'
      }}
    >
      <Ionicons
        name="sparkles"
        size={size * 0.48}
        color={colors.primaryForeground}
      />
    </View>
  );
}

function MessageRow({
  message,
  colors,
  onSpeak,
  speaking,
  character
}: {
  message: Message;
  colors: ReturnType<typeof useColors>;
  onSpeak: (message: Message) => void;
  speaking: boolean;
  character: Character;
}) {
  const isUser = message.role === 'user';

  return (
    <View
      style={[
        styles.messageRow,
        isUser ? styles.userRow : styles.assistantRow
      ]}
    >
      {!isUser ? (
        <View
          style={[
            styles.mayaMark,
            { backgroundColor: colors.primary }
          ]}
        >
          <Ionicons
            name={character === 'ROCKY' ? 'person-outline' : 'sparkles'}
            size={14}
            color={colors.primaryForeground}
          />
        </View>
      ) : null}

      <View
        style={[
          styles.messageBubble,
          isUser
            ? { backgroundColor: colors.primary }
            : {
                backgroundColor: colors.card,
                borderColor: colors.border
              }
        ]}
      >
        {message.image_url ? (
          <Image
            source={{ uri: message.image_url }}
            style={styles.messageImage}
            resizeMode="cover"
            accessibilityLabel="Attached photo"
          />
        ) : null}

        {message.content ? (
          <Text
            style={[
              styles.messageText,
              {
                color: isUser
                  ? colors.primaryForeground
                  : colors.foreground
              }
            ]}
          >
            {message.content}
          </Text>
        ) : null}

        {!isUser ? (
          <Pressable
            testID={`speak-${message.id}`}
            accessibilityLabel={
              speaking ? 'Stop speaking' : 'Play response'
            }
            onPress={() => onSpeak(message)}
            style={({ pressed }) => [
              styles.speakButton,
              pressed && styles.pressed
            ]}
          >
            <Ionicons
              name={
                speaking
                  ? 'stop-circle-outline'
                  : 'volume-medium-outline'
              }
              size={16}
              color={colors.mutedForeground}
            />
          </Pressable>
        ) : null}
      </View>
    </View>
  );
}

function CharacterSelector({
  character,
  colors,
  onChange
}: {
  character: Character;
  colors: ReturnType<typeof useColors>;
  onChange: (character: Character) => void;
}) {
  return (
    <View
      style={[
        styles.characterBox,
        {
          backgroundColor: colors.card,
          borderColor: colors.border
        }
      ]}
    >
      <View style={styles.characterHeader}>
        <View style={styles.characterHeaderIcon}>
          <Ionicons
            name="person-circle-outline"
            size={20}
            color={colors.primary}
          />
        </View>

        <View style={styles.characterHeaderCopy}>
          <Text
            style={[
              styles.characterTitle,
              { color: colors.foreground }
            ]}
          >
            Character
          </Text>

          <Text
            style={[
              styles.characterSubtitle,
              { color: colors.mutedForeground }
            ]}
          >
            Choose who appears in chat
          </Text>
        </View>
      </View>

      <View style={styles.characterOptions}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Select Maya"
          testID="select-maya"
          onPress={() => onChange('MAYA')}
          style={({ pressed }) => [
            styles.characterOption,
            {
              backgroundColor:
                character === 'MAYA'
                  ? colors.primary
                  : colors.secondary,
              borderColor:
                character === 'MAYA'
                  ? colors.primary
                  : colors.border
            },
            pressed && styles.pressed
          ]}
        >
          <View
            style={[
              styles.characterOptionIcon,
              {
                backgroundColor:
                  character === 'MAYA'
                    ? colors.primaryForeground
                    : colors.card
              }
            ]}
          >
            <Ionicons
              name="sparkles"
              size={17}
              color={
                character === 'MAYA'
                  ? colors.primary
                  : colors.primary
              }
            />
          </View>

          <View style={styles.characterOptionCopy}>
            <Text
              style={[
                styles.characterOptionTitle,
                {
                  color:
                    character === 'MAYA'
                      ? colors.primaryForeground
                      : colors.foreground
                }
              ]}
            >
              MAYA
            </Text>

            <Text
              style={[
                styles.characterOptionText,
                {
                  color:
                    character === 'MAYA'
                      ? colors.primaryForeground
                      : colors.mutedForeground
                }
              ]}
            >
              AI assistant
            </Text>
          </View>

          {character === 'MAYA' ? (
            <Ionicons
              name="checkmark-circle"
              size={20}
              color={colors.primaryForeground}
            />
          ) : null}
        </Pressable>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Select Rocky"
          testID="select-rocky"
          onPress={() => onChange('ROCKY')}
          style={({ pressed }) => [
            styles.characterOption,
            {
              backgroundColor:
                character === 'ROCKY'
                  ? colors.primary
                  : colors.secondary,
              borderColor:
                character === 'ROCKY'
                  ? colors.primary
                  : colors.border
            },
            pressed && styles.pressed
          ]}
        >
          <View
            style={[
              styles.characterOptionIcon,
              {
                backgroundColor:
                  character === 'ROCKY'
                    ? colors.primaryForeground
                    : colors.card
              }
            ]}
          >
            <Ionicons
              name="person-outline"
              size={17}
              color={
                character === 'ROCKY'
                  ? colors.primary
                  : colors.primary
              }
            />
          </View>

          <View style={styles.characterOptionCopy}>
            <Text
              style={[
                styles.characterOptionTitle,
                {
                  color:
                    character === 'ROCKY'
                      ? colors.primaryForeground
                      : colors.foreground
                }
              ]}
            >
              ROCKY
            </Text>

            <Text
              style={[
                styles.characterOptionText,
                {
                  color:
                    character === 'ROCKY'
                      ? colors.primaryForeground
                      : colors.mutedForeground
                }
              ]}
            >
              AI assistant
            </Text>
          </View>

          {character === 'ROCKY' ? (
            <Ionicons
              name="checkmark-circle"
              size={20}
              color={colors.primaryForeground}
            />
          ) : null}
        </Pressable>
      </View>
    </View>
  );
}

function ConversationDrawer({
  visible,
  conversations,
  activeId,
  character,
  colors,
  onClose,
  onSelect,
  onNew,
  onDelete,
  onCharacterChange
}: {
  visible: boolean;
  conversations: Conversation[];
  activeId: string | null;
  character: Character;
  colors: ReturnType<typeof useColors>;
  onClose: () => void;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (conversation: Conversation) => void;
  onCharacterChange: (character: Character) => void;
}) {
  return (
    <Modal
      visible={visible}
      animationType="slide"
      transparent
      onRequestClose={onClose}
    >
      <View
        style={[
          styles.drawerBackdrop,
          { backgroundColor: 'rgba(0,0,0,0.64)' }
        ]}
      >
        <View
          style={[
            styles.drawer,
            {
              backgroundColor: colors.background,
              borderColor: colors.border
            }
          ]}
        >
          <View style={styles.drawerHeader}>
            <View>
              <Text
                style={[
                  styles.drawerEyebrow,
                  { color: colors.primary }
                ]}
              >
                MAYA
              </Text>

              <Text
                style={[
                  styles.drawerTitle,
                  { color: colors.foreground }
                ]}
              >
                Your conversations
              </Text>
            </View>

            <Pressable
              accessibilityLabel="Close conversations"
              testID="close-conversations"
              onPress={onClose}
              style={({ pressed }) => [
                styles.iconButton,
                pressed && styles.pressed
              ]}
            >
              <Ionicons
                name="close"
                size={23}
                color={colors.foreground}
              />
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
              pressed && styles.pressed
            ]}
          >
            <Ionicons
              name="add"
              size={20}
              color={colors.primaryForeground}
            />

            <Text
              style={[
                styles.newConversationText,
                { color: colors.primaryForeground }
              ]}
            >
              New conversation
            </Text>
          </Pressable>

          <CharacterSelector
            character={character}
            colors={colors}
            onChange={onCharacterChange}
          />

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
                      item.id === activeId
                        ? colors.secondary
                        : 'transparent'
                  }
                ]}
              >
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`Open ${item.title}`}
                  onPress={() => onSelect(item.id)}
                  style={({ pressed }) => [
                    styles.conversationSelect,
                    pressed && styles.pressed
                  ]}
                >
                  <Ionicons
                    name="chatbubble-ellipses-outline"
                    size={18}
                    color={
                      item.id === activeId
                        ? colors.primary
                        : colors.mutedForeground
                    }
                  />

                  <View style={styles.conversationCopy}>
                    <Text
                      numberOfLines={1}
                      style={[
                        styles.conversationTitle,
                        { color: colors.foreground }
                      ]}
                    >
                      {item.title || 'New conversation'}
                    </Text>

                    {item.preview ? (
                      <Text
                        numberOfLines={1}
                        style={[
                          styles.conversationPreview,
                          { color: colors.mutedForeground }
                        ]}
                      >
                        {item.preview}
                      </Text>
                    ) : null}
                  </View>
                </Pressable>

                <Pressable
                  accessibilityLabel={`Delete ${item.title}`}
                  onPress={() => onDelete(item)}
                  style={({ pressed }) => [
                    styles.deleteButton,
                    pressed && styles.pressed
                  ]}
                >
                  <Ionicons
                    name="trash-outline"
                    size={17}
                    color={colors.mutedForeground}
                  />
                </Pressable>
              </View>
            )}
            ListEmptyComponent={
              <Text
                style={[
                  styles.emptyDrawerText,
                  { color: colors.mutedForeground }
                ]}
              >
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

  const [brainProgress, setBrainProgress] = useState(0);
  const [brainError, setBrainError] = useState<string | null>(null);
  const [brainReady, setBrainReady] = useState(false);

  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);

  const [draft, setDraft] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isSending, setIsSending] = useState(false);

  const [error, setError] = useState<string | null>(null);

  const [selectedImage, setSelectedImage] =
    useState<SelectedImage | null>(null);

  const [permissionSettingsAvailable, setPermissionSettingsAvailable] =
    useState(false);

  const [drawerVisible, setDrawerVisible] = useState(false);
  const [developerVisible, setDeveloperVisible] = useState(false);

  const [speakingId, setSpeakingId] = useState<string | null>(null);

  const [faceMode, setFaceMode] =
    useState<'idle' | 'thinking' | 'speaking'>('idle');

  const [faceEnabled, setFaceEnabled] = useState(true);

  const [character, setCharacter] =
    useState<Character>('MAYA');

  const prepareBrain = useCallback(async () => {
    setBrainError(null);
    setBrainProgress(0);
    resetLocalModel();

    try {
      await initializeLocalModel(
        (progress: ModelProgress) =>
          setBrainProgress(progress.percent)
      );

      setBrainProgress(1);
      setBrainReady(true);
    } catch {
      setBrainError(
        'MAYA could not finish preparing her brain.'
      );
    }
  }, []);

  useEffect(() => {
    void prepareBrain();
  }, [prepareBrain]);

  useEffect(() => {
    void getDeveloperConfig().then((config) =>
      setFaceEnabled(config.faceEnabled)
    );
  }, []);

  /*
   * Load saved character.
   * First install = MAYA.
   */
  useEffect(() => {
    let cancelled = false;

    AsyncStorage.getItem(CHARACTER_STORAGE_KEY)
      .then((saved) => {
        if (cancelled) return;

        if (saved === 'ROCKY' || saved === 'MAYA') {
          setCharacter(saved);
        }
      })
      .catch(() => {
        // Keep MAYA as safe default.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const changeCharacter = useCallback(
    async (nextCharacter: Character) => {
      try {
        await AsyncStorage.setItem(
          CHARACTER_STORAGE_KEY,
          nextCharacter
        );

        setCharacter(nextCharacter);

        await Haptics.impactAsync(
          Haptics.ImpactFeedbackStyle.Light
        );
      } catch {
        setError('Could not save the character selection.');
      }
    },
    []
  );

  const loadConversations = useCallback(async () => {
    const next = await mayaApi.conversations.list();
    setConversations(next);
    return next;
  }, []);

  const loadMessages = useCallback(
    async (conversationId: string) => {
      setMessages(
        await mayaApi.messages.list(conversationId)
      );
    },
    []
  );

  useEffect(() => {
    if (!brainReady) return;

    let cancelled = false;

    (async () => {
      try {
        const next = await loadConversations();

        if (cancelled) return;

        if (next.length > 0) {
          setActiveId(next[0].id);
          await loadMessages(next[0].id);
        } else {
          const created =
            await mayaApi.conversations.create();

          if (cancelled) return;

          setConversations([created]);
          setActiveId(created.id);
          setMessages([]);
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(
            loadError instanceof Error
              ? loadError.message
              : 'Unable to load MAYA.'
          );
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [brainReady, loadConversations, loadMessages]);

  const activeConversation = useMemo(
    () =>
      conversations.find(
        (item) => item.id === activeId
      ),
    [activeId, conversations]
  );

  const refresh = useCallback(async () => {
    if (!activeId) return;

    setIsRefreshing(true);
    setError(null);

    try {
      await Promise.all([
        loadConversations(),
        loadMessages(activeId)
      ]);
    } catch (refreshError) {
      setError(
        refreshError instanceof Error
          ? refreshError.message
          : 'Unable to refresh MAYA.'
      );
    } finally {
      setIsRefreshing(false);
    }
  }, [activeId, loadConversations, loadMessages]);

  const startNewConversation = useCallback(async () => {
    try {
      await Haptics.impactAsync(
        Haptics.ImpactFeedbackStyle.Light
      );

      const created =
        await mayaApi.conversations.create();

      setConversations((current) => [
        created,
        ...current
      ]);

      setActiveId(created.id);
      setMessages([]);
      setDrawerVisible(false);
      setError(null);
    } catch (newError) {
      setError(
        newError instanceof Error
          ? newError.message
          : 'Unable to start a conversation.'
      );
    }
  }, []);

  const selectConversation = useCallback(
    async (conversationId: string) => {
      setActiveId(conversationId);
      setDrawerVisible(false);
      setError(null);

      try {
        await loadMessages(conversationId);
      } catch (selectError) {
        setError(
          selectError instanceof Error
            ? selectError.message
            : 'Unable to load this conversation.'
        );
      }
    },
    [loadMessages]
  );

  const deleteConversation = useCallback(
    (conversation: Conversation) => {
      Alert.alert(
        'Delete conversation?',
        'This cannot be undone.',
        [
          {
            text: 'Cancel',
            style: 'cancel'
          },
          {
            text: 'Delete',
            style: 'destructive',
            onPress: async () => {
              try {
                await mayaApi.conversations.delete(
                  conversation.id
                );

                const remaining =
                  conversations.filter(
                    (item) =>
                      item.id !== conversation.id
                  );

                if (remaining.length === 0) {
                  await startNewConversation();
                } else {
                  setConversations(remaining);

                  if (
                    activeId === conversation.id
                  ) {
                    await selectConversation(
                      remaining[0].id
                    );
                  }
                }
              } catch (deleteError) {
                setError(
                  deleteError instanceof Error
                    ? deleteError.message
                    : 'Unable to delete conversation.'
                );
              }
            }
          }
        ]
      );
    },
    [
      activeId,
      conversations,
      selectConversation,
      startNewConversation
    ]
  );

  const choosePhoto = useCallback(
    async (source: 'library' | 'camera') => {
      setError(null);
      setPermissionSettingsAvailable(false);

      try {
        const permission =
          source === 'camera'
            ? await ImagePicker.requestCameraPermissionsAsync()
            : await ImagePicker.requestMediaLibraryPermissionsAsync();

        if (!permission.granted) {
          setPermissionSettingsAvailable(
            !permission.canAskAgain
          );

          setError(
            source === 'camera'
              ? permission.canAskAgain
                ? 'Camera access is needed to take a photo.'
                : 'Camera access is off. Enable it in Android Settings to take a photo.'
              : permission.canAskAgain
                ? 'Photo library access is needed to choose a photo.'
                : 'Photo library access is off. Enable it in Android Settings to choose a photo.'
          );

          return;
        }

        const result =
          source === 'camera'
            ? await ImagePicker.launchCameraAsync({
                mediaTypes: ['images'],
                quality: 0.8,
                base64: true,
                exif: false
              })
            : await ImagePicker.launchImageLibraryAsync({
                mediaTypes: ['images'],
                quality: 0.8,
                base64: true,
                exif: false,
                selectionLimit: 1
              });

        if (result.canceled) return;

        const asset = result.assets?.[0];

        if (!asset?.uri || !asset.base64) {
          setError(
            'MAYA could not read that photo. Please try another image.'
          );
          return;
        }

        const extension =
          asset.fileName
            ?.split('.')
            .pop()
            ?.toLowerCase();

        const mimeType =
          asset.mimeType?.toLowerCase() ||
          (extension === 'png'
            ? 'image/png'
            : extension === 'webp'
              ? 'image/webp'
              : 'image/jpeg');

        if (
          ![
            'image/jpeg',
            'image/jpg',
            'image/png',
            'image/webp'
          ].includes(mimeType)
        ) {
          setError(
            'Please choose a JPEG, PNG, or WEBP photo.'
          );
          return;
        }

        const dataUrl =
          `data:${mimeType === 'image/jpg' ? 'image/jpeg' : mimeType};base64,${asset.base64}`;

        if (dataUrl.length > 7_500_000) {
          setError(
            'That photo is too large. Please choose a smaller image.'
          );
          return;
        }

        setSelectedImage({
          uri: asset.uri,
          dataUrl
        });
      } catch (pickerError) {
        setError(
          pickerError instanceof Error
            ? `Could not open ${source === 'camera' ? 'camera' : 'photos'}: ${pickerError.message}`
            : 'Could not open photos. Please try again.'
        );
      }
    },
    []
  );

  const openSettings = useCallback(() => {
    Linking.openSettings().catch(() =>
      setError(
        'Open Android Settings to enable photo permissions, then try again.'
      )
    );
  }, []);

  const speakMessage = useCallback(
    async (message: Message) => {
      if (speakingId === message.id) {
        await Speech.stop();
        setSpeakingId(null);
        setFaceMode('idle');
        return;
      }

      await Speech.stop();

      const config = await getDeveloperConfig();

      if (!config.ttsEnabled) return;

      setSpeakingId(message.id);
      setFaceMode('speaking');

      await Speech.speak(message.content, {
        rate: config.ttsRate,
        pitch: config.ttsPitch,

        onDone: () => {
          setSpeakingId(null);
          setFaceMode('idle');
        },

        onStopped: () => {
          setSpeakingId(null);
          setFaceMode('idle');
        },

        onError: () => {
          setSpeakingId(null);
          setFaceMode('idle');
        }
      });
    },
    [speakingId]
  );

  const sendMessage = useCallback(async () => {
    const content = draft.trim();
    const imageToSend = selectedImage;

    if (
      (!content && !imageToSend) ||
      !activeId ||
      isSending
    ) {
      return;
    }

    if (content === DEVELOPER_COMMAND) {
      setDraft('');

      if (developerModeEnabled()) {
        setDeveloperVisible(true);
      }

      return;
    }

    Keyboard.dismiss();
    setDraft('');
    setIsSending(true);
    setFaceMode('thinking');
    setError(null);

    try {
      await Haptics.impactAsync(
        Haptics.ImpactFeedbackStyle.Light
      );

      const response = imageToSend
        ? await mayaApi.messages.analyzeImage(
            activeId,
            imageToSend.dataUrl,
            content || undefined
          )
        : await mayaApi.messages.create(
            activeId,
            content
          );

      setMessages((current) => [
        ...current,
        response.user_message,
        response.assistant_message
      ]);

      setSelectedImage(null);

      await loadConversations();
    } catch (sendError) {
      setDraft(content);

      setError(
        sendError instanceof Error
          ? sendError.message
          : 'MAYA could not respond right now.'
      );
    } finally {
      setIsSending(false);
      setFaceMode('idle');
    }
  }, [
    activeId,
    draft,
    isSending,
    loadConversations,
    selectedImage
  ]);

  if (!brainReady || brainError) {
    return (
      <BrainSetupScreen
        progress={brainProgress}
        error={brainError}
        onRetry={prepareBrain}
      />
    );
  }

  if (isLoading) {
    return (
      <View
        style={[
          styles.loadingScreen,
          { backgroundColor: colors.background }
        ]}
      >
        <BrandMark colors={colors} size={82} />

        <ActivityIndicator
          color={colors.primary}
          size="small"
        />

        <Text
          style={[
            styles.loadingLabel,
            { color: colors.mutedForeground }
          ]}
        >
          CONNECTING TO MAYA
        </Text>
      </View>
    );
  }

  const ActiveFace =
    character === 'ROCKY' ? RockyFace : MayaFace;

  return (
    <KeyboardAvoidingView
      style={[
        styles.screen,
        { backgroundColor: colors.background }
      ]}
      behavior="padding"
      keyboardVerticalOffset={0}
    >
      <View
        style={[
          styles.header,
          {
            paddingTop: insets.top + 12,
            borderBottomColor: colors.border
          }
        ]}
      >
        <Pressable
          accessibilityLabel="Open conversations"
          testID="open-conversations"
          onPress={() => setDrawerVisible(true)}
          style={({ pressed }) => [
            styles.iconButton,
            pressed && styles.pressed
          ]}
        >
          <Ionicons
            name="menu-outline"
            size={26}
            color={colors.foreground}
          />
        </Pressable>

        <View style={styles.brand}>
          <BrandMark colors={colors} />

          <View>
            <Text
              style={[
                styles.brandName,
                { color: colors.foreground }
              ]}
            >
              MAYA
            </Text>

            <Text
              style={[
                styles.brandStatus,
                { color: colors.primary }
              ]}
            >
              {character === 'ROCKY'
                ? 'ROCKY ACTIVE'
                : 'ONLINE'}
            </Text>
          </View>
        </View>

        <Pressable
          accessibilityLabel="Start a new conversation"
          testID="header-new-conversation"
          onPress={startNewConversation}
          style={({ pressed }) => [
            styles.iconButton,
            pressed && styles.pressed
          ]}
        >
          <Ionicons
            name="create-outline"
            size={23}
            color={colors.foreground}
          />
        </Pressable>
      </View>

      <View style={styles.chatMeta}>
        <View style={styles.chatMetaCopy}>
          <Text
            style={[
              styles.conversationHeading,
              { color: colors.foreground }
            ]}
            numberOfLines={1}
          >
            {activeConversation?.title ||
              'New conversation'}
          </Text>

          <Text
            style={[
              styles.conversationSubheading,
              { color: colors.mutedForeground }
            ]}
          >
            {character === 'ROCKY'
              ? 'Rocky is listening.'
              : 'Maya is listening.'}
          </Text>
        </View>

        <ActiveFace
          mode={faceMode}
          enabled={faceEnabled}
          colors={colors}
        />
      </View>

      {error ? (
        <View
          style={[
            styles.errorBanner,
            {
              backgroundColor: colors.secondary,
              borderColor: colors.border
            }
          ]}
        >
          <Ionicons
            name="warning-outline"
            size={17}
            color={colors.primary}
          />

          <Text
            style={[
              styles.errorText,
              { color: colors.foreground }
            ]}
            numberOfLines={2}
          >
            {error}
          </Text>

          <Pressable
            onPress={
              permissionSettingsAvailable
                ? openSettings
                : refresh
            }
            accessibilityLabel={
              permissionSettingsAvailable
                ? 'Open Android settings'
                : 'Retry'
            }
            style={styles.retryButton}
          >
            <Text
              style={[
                styles.retryText,
                { color: colors.primary }
              ]}
            >
              {permissionSettingsAvailable
                ? 'Settings'
                : 'Retry'}
            </Text>
          </Pressable>
        </View>
      ) : null}

      <FlatList
        inverted
        data={[...messages].reverse()}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => (
          <MessageRow
            message={item}
            colors={colors}
            onSpeak={speakMessage}
            speaking={speakingId === item.id}
            character={character}
          />
        )}
        contentContainerStyle={[
          styles.messageList,
          {
            paddingBottom: 18,
            paddingTop: isSending ? 12 : 28
          }
        ]}
        keyboardDismissMode="interactive"
        keyboardShouldPersistTaps="handled"
        refreshControl={
          <RefreshControl
            refreshing={isRefreshing}
            onRefresh={refresh}
            tintColor={colors.primary}
          />
        }
        ListEmptyComponent={
          <View
            style={[
              styles.welcome,
              styles.invertedListContent
            ]}
          >
            <View
              style={[
                styles.welcomeIcon,
                { backgroundColor: colors.primary }
              ]}
            >
              <Ionicons
                name={
                  character === 'ROCKY'
                    ? 'person-outline'
                    : 'sparkles'
                }
                size={26}
                color={colors.primaryForeground}
              />
            </View>

            <Text
              style={[
                styles.welcomeTitle,
                { color: colors.foreground }
              ]}
            >
              {character === 'ROCKY'
                ? 'Hello, I’m Rocky.'
                : 'Hello, I’m Maya.'}
            </Text>

            <Text
              style={[
                styles.welcomeBody,
                { color: colors.mutedForeground }
              ]}
            >
              A friendly tech-savvy chatbot for clear
              answers, curious questions, and good
              conversations.
            </Text>
          </View>
        }
        ListHeaderComponent={
          isSending ? (
            <View
              style={[
                styles.typingRow,
                styles.invertedListContent
              ]}
            >
              <ActivityIndicator
                color={colors.primary}
                size="small"
              />

              <Text
                style={[
                  styles.typingText,
                  { color: colors.mutedForeground }
                ]}
              >
                {character === 'ROCKY'
                  ? 'Rocky is thinking…'
                  : 'Maya is thinking…'}
              </Text>
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
            paddingBottom: Math.max(
              insets.bottom,
              12
            )
          }
        ]}
      >
        {selectedImage ? (
          <View
            style={[
              styles.photoPreview,
              {
                backgroundColor: colors.card,
                borderColor: colors.input
              }
            ]}
          >
            <Image
              source={{ uri: selectedImage.uri }}
              style={styles.photoPreviewImage}
              resizeMode="cover"
            />

            <View style={styles.photoPreviewCopy}>
              <Text
                style={[
                  styles.photoPreviewLabel,
                  { color: colors.foreground }
                ]}
              >
                Photo ready
              </Text>

              <Text
                style={[
                  styles.photoPreviewHint,
                  { color: colors.mutedForeground }
                ]}
              >
                {character === 'ROCKY'
                  ? 'Rocky will keep this with the conversation'
                  : 'MAYA will keep this with the conversation'}
              </Text>
            </View>

            <Pressable
              accessibilityLabel="Remove selected photo"
              testID="remove-selected-photo"
              onPress={() => setSelectedImage(null)}
              disabled={isSending}
              style={({ pressed }) => [
                styles.removePhotoButton,
                pressed && styles.pressed
              ]}
            >
              <Ionicons
                name="close-circle"
                size={23}
                color={colors.mutedForeground}
              />
            </Pressable>
          </View>
        ) : null}

        <View
          style={[
            styles.composer,
            {
              backgroundColor: colors.card,
              borderColor: colors.input
            }
          ]}
        >
          <View style={styles.composerActions}>
            <Pressable
              accessibilityLabel="Choose a photo"
              testID="choose-photo"
              onPress={() => choosePhoto('library')}
              disabled={isSending}
              style={({ pressed }) => [
                styles.composerActionButton,
                pressed && styles.pressed
              ]}
            >
              <Ionicons
                name="image-outline"
                size={21}
                color={colors.mutedForeground}
              />
            </Pressable>

            <Pressable
              accessibilityLabel="Take a photo"
              testID="take-photo"
              onPress={() => choosePhoto('camera')}
              disabled={isSending}
              style={({ pressed }) => [
                styles.composerActionButton,
                pressed && styles.pressed
              ]}
            >
              <Ionicons
                name="camera-outline"
                size={21}
                color={colors.mutedForeground}
              />
            </Pressable>
          </View>

          <TextInput
            ref={inputRef}
            value={draft}
            onChangeText={setDraft}
            onSubmitEditing={sendMessage}
            placeholder={
              character === 'ROCKY'
                ? 'Message Rocky'
                : 'Message Maya'
            }
            placeholderTextColor={
              colors.mutedForeground
            }
            multiline
            maxLength={4000}
            returnKeyType="send"
            blurOnSubmit={false}
            style={[
              styles.input,
              { color: colors.foreground }
            ]}
            accessibilityLabel="Message Maya"
            testID="message-input"
          />

          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Send message"
            testID="send-message"
            disabled={
              (!draft.trim() && !selectedImage) ||
              isSending
            }
            onPress={sendMessage}
            style={({ pressed }) => [
              styles.sendButton,
              {
                backgroundColor:
                  (draft.trim() || selectedImage) &&
                  !isSending
                    ? colors.primary
                    : colors.secondary
              },
              pressed && styles.pressed
            ]}
          >
            {isSending ? (
              <ActivityIndicator
                color={colors.mutedForeground}
                size="small"
              />
            ) : (
              <Ionicons
                name="arrow-up"
                size={21}
                color={
                  draft.trim() || selectedImage
                    ? colors.primaryForeground
                    : colors.mutedForeground
                }
              />
            )}
          </Pressable>
        </View>

        <Text
          style={[
            styles.disclaimer,
            { color: colors.mutedForeground }
          ]}
        >
          Maya can make mistakes. Check important
          information.
        </Text>
      </View>

      <ConversationDrawer
        visible={drawerVisible}
        conversations={conversations}
        activeId={activeId}
        character={character}
        colors={colors}
        onClose={() => setDrawerVisible(false)}
        onSelect={selectConversation}
        onNew={startNewConversation}
        onDelete={deleteConversation}
        onCharacterChange={changeCharacter}
      />

      <DeveloperPanel
        visible={developerVisible}
        onClose={() => setDeveloperVisible(false)}
      />
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1
  },

  loadingScreen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 16
  },

  loadingLabel: {
    fontFamily: 'Inter_600SemiBold',
    fontSize: 10,
    letterSpacing: 2.1
  },

  header: {
    minHeight: 76,
    paddingHorizontal: 18,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderBottomWidth: StyleSheet.hairlineWidth
  },

  brand: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10
  },

  brandName: {
    fontFamily: 'Inter_700Bold',
    fontSize: 17,
    letterSpacing: 2.2
  },

  brandStatus: {
    fontFamily: 'Inter_600SemiBold',
    fontSize: 8,
    letterSpacing: 1.5,
    marginTop: 2
  },

  iconButton: {
    width: 42,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center'
  },

  pressed: {
    opacity: 0.62
  },

  chatMeta: {
    minHeight: 86,
    paddingHorizontal: 22,
    paddingTop: 14,
    paddingBottom: 4,
    flexDirection: 'row',
    alignItems: 'center'
  },

  chatMetaCopy: {
    flex: 1
  },

  conversationHeading: {
    fontFamily: 'Inter_600SemiBold',
    fontSize: 18
  },

  conversationSubheading: {
    fontFamily: 'Inter_400Regular',
    fontSize: 13,
    marginTop: 4
  },

  messageList: {
    flexGrow: 1,
    paddingHorizontal: 18
  },

  messageRow: {
    flexDirection: 'row',
    marginBottom: 15,
    gap: 8,
    alignItems: 'flex-end'
  },

  userRow: {
    justifyContent: 'flex-end'
  },

  assistantRow: {
    justifyContent: 'flex-start'
  },

  mayaMark: {
    width: 26,
    height: 26,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center'
  },

  messageBubble: {
    maxWidth: '82%',
    borderRadius: 18,
    paddingHorizontal: 15,
    paddingVertical: 12,
    borderWidth: 1
  },

  messageImage: {
    width: 220,
    height: 165,
    borderRadius: 12,
    marginBottom: 8
  },

  messageText: {
    fontFamily: 'Inter_400Regular',
    fontSize: 15,
    lineHeight: 22
  },

  speakButton: {
    alignSelf: 'flex-start',
    paddingTop: 8,
    paddingRight: 3
  },

  welcome: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 36,
    paddingVertical: 60
  },

  invertedListContent: {
    transform: [{ scaleY: -1 }]
  },

  welcomeIcon: {
    width: 58,
    height: 58,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 18
  },

  welcomeTitle: {
    fontFamily: 'Inter_700Bold',
    fontSize: 23,
    marginBottom: 10
  },

  welcomeBody: {
    fontFamily: 'Inter_400Regular',
    fontSize: 14,
    lineHeight: 21,
    textAlign: 'center'
  },

  typingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
    paddingHorizontal: 8,
    paddingVertical: 12
  },

  typingText: {
    fontFamily: 'Inter_400Regular',
    fontSize: 13
  },

  composerWrap: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 16,
    paddingTop: 12
  },

  photoPreview: {
    minHeight: 72,
    borderRadius: 14,
    borderWidth: 1,
    marginBottom: 8,
    padding: 7,
    flexDirection: 'row',
    alignItems: 'center'
  },

  photoPreviewImage: {
    width: 58,
    height: 58,
    borderRadius: 10
  },

  photoPreviewCopy: {
    flex: 1,
    paddingHorizontal: 10
  },

  photoPreviewLabel: {
    fontFamily: 'Inter_600SemiBold',
    fontSize: 13
  },

  photoPreviewHint: {
    fontFamily: 'Inter_400Regular',
    fontSize: 11,
    marginTop: 3
  },

  removePhotoButton: {
    width: 34,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center'
  },

  composer: {
    minHeight: 52,
    borderRadius: 18,
    borderWidth: 1,
    flexDirection: 'row',
    alignItems: 'flex-end',
    padding: 6
  },

  composerActions: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingBottom: 1
  },

  composerActionButton: {
    width: 34,
    height: 38,
    alignItems: 'center',
    justifyContent: 'center'
  },

  input: {
    flex: 1,
    maxHeight: 116,
    minHeight: 38,
    paddingHorizontal: 10,
    paddingTop: 9,
    paddingBottom: 8,
    fontFamily: 'Inter_400Regular',
    fontSize: 15,
    lineHeight: 21
  },

  sendButton: {
    width: 38,
    height: 38,
    borderRadius: 13,
    alignItems: 'center',
    justifyContent: 'center'
  },

  disclaimer: {
    fontFamily: 'Inter_400Regular',
    fontSize: 10,
    textAlign: 'center',
    marginTop: 8
  },

  errorBanner: {
    marginHorizontal: 16,
    marginTop: 8,
    padding: 10,
    borderRadius: 12,
    borderWidth: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8
  },

  errorText: {
    flex: 1,
    fontFamily: 'Inter_400Regular',
    fontSize: 12,
    lineHeight: 17
  },

  retryButton: {
    padding: 5
  },

  retryText: {
    fontFamily: 'Inter_600SemiBold',
    fontSize: 12
  },

  drawerBackdrop: {
    flex: 1,
    justifyContent: 'flex-end'
  },

  drawer: {
    minHeight: '76%',
    borderTopLeftRadius: 28,
    borderTopRightRadius: 28,
    borderWidth: 1,
    paddingTop: 22,
    paddingHorizontal: 18,
    paddingBottom: 28
  },

  drawerHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 18
  },

  drawerEyebrow: {
    fontFamily: 'Inter_700Bold',
    fontSize: 10,
    letterSpacing: 2.2,
    marginBottom: 5
  },

  drawerTitle: {
    fontFamily: 'Inter_700Bold',
    fontSize: 22
  },

  newConversation: {
    minHeight: 48,
    borderRadius: 15,
    paddingHorizontal: 16,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
    marginBottom: 15
  },

  newConversationText: {
    fontFamily: 'Inter_600SemiBold',
    fontSize: 14
  },

  /*
   * Character selector
   */

  characterBox: {
    borderRadius: 18,
    borderWidth: 1,
    padding: 12,
    marginBottom: 14
  },

  characterHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 11
  },

  characterHeaderIcon: {
    width: 34,
    height: 34,
    borderRadius: 11,
    alignItems: 'center',
    justifyContent: 'center'
  },

  characterHeaderCopy: {
    flex: 1,
    marginLeft: 9
  },

  characterTitle: {
    fontFamily: 'Inter_600SemiBold',
    fontSize: 13
  },

  characterSubtitle: {
    fontFamily: 'Inter_400Regular',
    fontSize: 10,
    marginTop: 2
  },

  characterOptions: {
    flexDirection: 'row',
    gap: 8
  },

  characterOption: {
    flex: 1,
    minHeight: 58,
    borderRadius: 14,
    borderWidth: 1,
    paddingHorizontal: 9,
    flexDirection: 'row',
    alignItems: 'center'
  },

  characterOptionIcon: {
    width: 30,
    height: 30,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center'
  },

  characterOptionCopy: {
    flex: 1,
    marginLeft: 7
  },

  characterOptionTitle: {
    fontFamily: 'Inter_700Bold',
    fontSize: 12,
    letterSpacing: 0.7
  },

  characterOptionText: {
    fontFamily: 'Inter_400Regular',
    fontSize: 9,
    marginTop: 2
  },

  conversationList: {
    gap: 4,
    paddingBottom: 18
  },

  conversationItem: {
    minHeight: 62,
    borderRadius: 14,
    flexDirection: 'row',
    alignItems: 'center',
    paddingLeft: 13,
    paddingRight: 6
  },

  conversationSelect: {
    flex: 1,
    minHeight: 56,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 11
  },

  conversationCopy: {
    flex: 1
  },

  conversationTitle: {
    fontFamily: 'Inter_500Medium',
    fontSize: 14
  },

  conversationPreview: {
    fontFamily: 'Inter_400Regular',
    fontSize: 11,
    marginTop: 4
  },

  deleteButton: {
    width: 38,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center'
  },

  emptyDrawerText: {
    textAlign: 'center',
    fontFamily: 'Inter_400Regular',
    paddingVertical: 30
  }
});
