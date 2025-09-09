import React, { useEffect, useState } from "react";
import {
  View,
  Text,
  TextInput,
  Button,
  StyleSheet,
  ScrollView,
  NativeModules,
  NativeEventEmitter,
} from "react-native";

const { WearableCommunicationModule } = NativeModules;
const wearableEmitter = new NativeEventEmitter(WearableCommunicationModule);

export default function App() {
  const [status, setStatus] = useState("Disconnected ❌");
  const [messages, setMessages] = useState<string[]>([]);
  const [input, setInput] = useState("");
  const [initialized, setInitialized] = useState(false);

  useEffect(() => {
    const initModule = async () => {
      try {
        await WearableCommunicationModule.initialize();
        console.log("Module initialized");
        setInitialized(true);
      } catch (err) {
        console.error("Init error:", err);
      }
    };

    initModule();

    const subInit = wearableEmitter.addListener("onInitialized", (e) => {
      setStatus(e.success ? "Initialized ✅" : "Init Failed ❌");
    });

    const subConnected = wearableEmitter.addListener("onMobileConnected", () => {
      setStatus("Connected ✅");
    });

    const subReceived = wearableEmitter.addListener("onMessageReceived", (e) => {
      setMessages((prev) => [`From Mobile: ${e.message}`, ...prev]);
    });

    const subSent = wearableEmitter.addListener("onMessageSent", (e) => {
      setMessages((prev) => [`Sent: ${e.message}`, ...prev]);
    });

    return () => {
      subInit.remove();
      subConnected.remove();
      subReceived.remove();
      subSent.remove();
    };
  }, []);

  const sendMessage = () => {
    if (!initialized || !input.trim()) return;

    WearableCommunicationModule.sendMessageToMobile(input)
      .then(() => setMessages(prev => [`Sent: ${input}`, ...prev]))
      .catch((err: any) => setMessages(prev => [`Failed to send: ${err}`, ...prev]));

    setInput("");
  };

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.title}>Wear OS Communication Demo</Text>
      <Text style={styles.status}>Status: {status}</Text>

      <TextInput
        style={styles.input}
        placeholder="Enter message..."
        value={input}
        onChangeText={setInput}
      />
      <Button title="Send to Mobile" onPress={sendMessage} />

      <ScrollView style={styles.messages}>
        {messages.map((msg, i) => (
          <Text key={i} style={styles.message}>
            {msg}
          </Text>
        ))}
      </ScrollView>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
    backgroundColor: "#fff",
  },
  title: {
    fontSize: 20,
    fontWeight: "bold",
    marginBottom: 12,
  },
  status: {
    marginBottom: 20,
    fontSize: 16,
  },
  input: {
    borderWidth: 1,
    borderColor: "#aaa",
    borderRadius: 8,
    padding: 8,
    marginBottom: 10,
  },
  messages: {
    marginTop: 20,
  },
  message: {
    fontSize: 14,
    paddingVertical: 4,
  },
});
