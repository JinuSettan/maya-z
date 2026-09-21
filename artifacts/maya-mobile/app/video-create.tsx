import React, { useState } from "react";
import {
  Alert,
  Image,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import * as ImagePicker from "expo-image-picker";
import { router } from "expo-router";

export default function VideoCreateScreen() {
  const [imageUri, setImageUri] = useState<string | null>(null);
  const [prompt, setPrompt] = useState("");

  const pickImage = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ["images"],
      quality: 1,
    });

    if (!result.canceled && result.assets[0]?.uri) {
      setImageUri(result.assets[0].uri);
    }
  };

  const generateVideo = () => {
    if (!imageUri) {
      Alert.alert("MAYA", "Please select an image first.");
      return;
    }

    if (!prompt.trim()) {
      Alert.alert("MAYA", "Tell me what you want to create.");
      return;
    }

    Alert.alert(
      "MAYA Video",
      "Video generation connection will be added next."
    );
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()}>
          <Text style={styles.back}>‹</Text>
        </Pressable>

        <Text style={styles.title}>Create Video</Text>

        <View style={{ width: 40 }} />
      </View>

      <View style={styles.content}>
        <Text style={styles.sectionTitle}>Reference Image</Text>

        <Pressable style={styles.imageBox} onPress={pickImage}>
          {imageUri ? (
            <Image source={{ uri: imageUri }} style={styles.preview} />
          ) : (
            <>
              <Text style={styles.plus}>+</Text>
              <Text style={styles.imageText}>Choose an image</Text>
            </>
          )}
        </Pressable>

        <Text style={styles.sectionTitle}>What should MAYA create?</Text>

        <TextInput
          value={prompt}
          onChangeText={setPrompt}
          placeholder="Example: cinematic camera movement..."
          placeholderTextColor="#777"
          multiline
          style={styles.input}
        />

        <Pressable style={styles.generateButton} onPress={generateVideo}>
          <Text style={styles.generateText}>Generate Video</Text>
        </Pressable>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#090909",
  },
  header: {
    height: 64,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 18,
  },
  back: {
    color: "#fff",
    fontSize: 38,
    fontWeight: "300",
  },
  title: {
    color: "#fff",
    fontSize: 20,
    fontWeight: "700",
  },
  content: {
    flex: 1,
    padding: 20,
  },
  sectionTitle: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "700",
    marginBottom: 12,
    marginTop: 12,
  },
  imageBox: {
    height: 280,
    borderRadius: 18,
    borderWidth: 1,
    borderColor: "#333",
    backgroundColor: "#111",
    alignItems: "center",
    justifyContent: "center",
    overflow: "hidden",
  },
  preview: {
    width: "100%",
    height: "100%",
    resizeMode: "contain",
  },
  plus: {
    color: "#fff",
    fontSize: 42,
    fontWeight: "300",
  },
  imageText: {
    color: "#aaa",
    marginTop: 8,
    fontSize: 15,
  },
  input: {
    minHeight: 120,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: "#333",
    backgroundColor: "#111",
    color: "#fff",
    padding: 16,
    fontSize: 15,
    textAlignVertical: "top",
  },
  generateButton: {
    height: 56,
    borderRadius: 16,
    backgroundColor: "#fff",
    alignItems: "center",
    justifyContent: "center",
    marginTop: 20,
  },
  generateText: {
    color: "#000",
    fontSize: 16,
    fontWeight: "800",
  },
});