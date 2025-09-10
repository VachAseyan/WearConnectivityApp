import React, { useEffect, useState, useRef } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  NativeModules,
  NativeEventEmitter,
  EmitterSubscription,
  Alert,
} from 'react-native';

const { WearableCommunicationModule } = NativeModules;

interface Message {
  id: number;
  text: string;
  type: string;
}

export default function App() {
  const [status, setStatus] = useState('Initializing...');
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [initialized, setInitialized] = useState(false);
  const [connected, setConnected] = useState(false);
  const eventEmitterRef = useRef<NativeEventEmitter | null>(null);
  const listenersRef = useRef<EmitterSubscription[]>([]);

  const addMessage = (msg: string, type: string = 'info') => {
    const timestamp = new Date().toLocaleTimeString();
    setMessages(prev => [{
      id: Date.now() + Math.random(),
      text: `${timestamp}: ${msg}`,
      type
    }, ...prev.slice(0, 19)]);
    console.log(`[Wearable] ${msg}`);
  };

  useEffect(() => {
    let isMounted = true;

    const initializeApp = async () => {
      try {
        if (!WearableCommunicationModule) {
          throw new Error('WearableCommunicationModule not found');
        }

        addMessage('Starting initialization...', 'info');

        // Create event emitter
        eventEmitterRef.current = new NativeEventEmitter(WearableCommunicationModule);
        addMessage('Event emitter created', 'info');

        // Set up listeners BEFORE initializing
        const listeners: EmitterSubscription[] = [];

        // Initialization complete listener
        listeners.push(
          eventEmitterRef.current.addListener('onInitialized', (data) => {
            if (isMounted) {
              addMessage(`Initialization ${data.success ? 'successful' : 'failed'}`, data.success ? 'success' : 'error');
              if (data.success) {
                setStatus('Ready ✅');
                setInitialized(true);
              }
            }
          })
        );

        // Mobile connected listener
        listeners.push(
          eventEmitterRef.current.addListener('onMobileConnected', (data) => {
            if (isMounted) {
              addMessage('Mobile device connected!', 'success');
              setStatus('Connected to Mobile ✅');
              setConnected(true);
            }
          })
        );

        // Message received listener
        listeners.push(
          eventEmitterRef.current.addListener('onMessageReceived', (data) => {
            if (isMounted) {
              if (data.message === 'ping') {
                addMessage('📩 Received ping, sending ack', 'info');
              } else {
                addMessage(`📩 From Mobile: "${data.message}"`, 'received');
              }
            }
          })
        );

        // Message sent listener
        listeners.push(
          eventEmitterRef.current.addListener('onMessageSent', (data) => {
            if (isMounted) {
              if (data.success) {
                if (data.message === 'ack') {
                  addMessage('📤 Ack sent', 'info');
                } else {
                  addMessage(`📤 Sent: "${data.message}"`, 'sent');
                }
              } else {
                addMessage(`❌ Failed to send: "${data.message}"`, 'error');
              }
            }
          })
        );

        // Store listeners for cleanup
        listenersRef.current = listeners;
        addMessage('Event listeners registered', 'info');

        // Initialize the module
        await WearableCommunicationModule.initialize();
        addMessage('Module initialization requested', 'info');

      } catch (error: any) {
        if (isMounted) {
          addMessage(`❌ Initialization error: ${error.message}`, 'error');
          setStatus('Initialization Failed ❌');
          Alert.alert('Error', `Initialization failed: ${error.message}`);
        }
      }
    };

    initializeApp();

    return () => {
      isMounted = false;
      
      // Cleanup listeners
      listenersRef.current.forEach(listener => {
        if (listener && listener.remove) {
          listener.remove();
        }
      });
      listenersRef.current = [];

      // Cleanup module
      if (WearableCommunicationModule && WearableCommunicationModule.cleanup) {
        WearableCommunicationModule.cleanup();
      }
      
      addMessage('🧹 App cleanup completed', 'info');
    };
  }, []);

  const sendMessage = async () => {
    if (!input.trim()) {
      Alert.alert('Error', 'Please enter a message');
      return;
    }

    if (!initialized) {
      Alert.alert('Error', 'Module not initialized yet');
      return;
    }

    try {
      addMessage(`Sending: "${input}"`, 'sending');
      await WearableCommunicationModule.sendMessageToMobile(input);
      setInput('');
    } catch (error: any) {
      addMessage(`❌ Send failed: ${error.message}`, 'error');
      Alert.alert('Send Error', error.message);
    }
  };

  const sendTestMessage = async () => {
    try {
      const testMsg = `Test from Wear ${new Date().getSeconds()}`;
      addMessage(`Sending test: "${testMsg}"`, 'sending');
      await WearableCommunicationModule.sendMessageToMobile(testMsg);
    } catch (error: any) {
      addMessage(`❌ Test send failed: ${error.message}`, 'error');
    }
  };

  const checkStatus = async () => {
    try {
      const result = await WearableCommunicationModule.getConnectionStatus();
      addMessage(`Status - Init: ${result.initialized}, Manager: ${result.managerExists}`, 'info');
    } catch (error: any) {
      addMessage(`❌ Status check failed: ${error.message}`, 'error');
    }
  };

  const clearMessages = () => {
    setMessages([]);
  };

  const getMessageStyle = (type: string) => {
    switch (type) {
      case 'success': return { ...styles.message, backgroundColor: '#d4f8d4' };
      case 'error': return { ...styles.message, backgroundColor: '#fdd4d4' };
      case 'sent': return { ...styles.message, backgroundColor: '#d4e8ff' };
      case 'received': return { ...styles.message, backgroundColor: '#fff4d4' };
      case 'sending': return { ...styles.message, backgroundColor: '#f0f0f0' };
      default: return styles.message;
    }
  };

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.title}>⌚ Wear OS Communication</Text>
      
      <View style={styles.statusContainer}>
        <Text style={styles.statusLabel}>Status:</Text>
        <Text style={[
          styles.status, 
          { color: initialized ? (connected ? 'green' : 'orange') : 'red' }
        ]}>
          {status}
        </Text>
      </View>

      <View style={styles.inputContainer}>
        <TextInput
          style={styles.input}
          placeholder="Message to mobile..."
          value={input}
          onChangeText={setInput}
          multiline={true}
          numberOfLines={2}
        />
        
        <View style={styles.buttonRow}>
          <TouchableOpacity 
            style={[styles.button, !initialized && styles.buttonDisabled]} 
            onPress={sendMessage}
            disabled={!initialized}
          >
            <Text style={styles.buttonText}>📤 Send</Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            style={[styles.button, styles.secondaryButton]} 
            onPress={sendTestMessage}
            disabled={!initialized}
          >
            <Text style={styles.buttonText}>🧪 Test</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.buttonRow}>
          <TouchableOpacity 
            style={[styles.button, styles.secondaryButton]} 
            onPress={checkStatus}
          >
            <Text style={styles.buttonText}>📊 Status</Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            style={[styles.button, styles.secondaryButton]} 
            onPress={clearMessages}
          >
            <Text style={styles.buttonText}>🗑️ Clear</Text>
          </TouchableOpacity>
        </View>
      </View>

      <View style={styles.messagesContainer}>
        <Text style={styles.messagesTitle}>📋 Messages ({messages.length})</Text>
        <ScrollView style={styles.messagesScroll} nestedScrollEnabled>
          {messages.length === 0 ? (
            <Text style={styles.noMessages}>No messages yet...</Text>
          ) : (
            messages.map((msg) => (
              <Text key={msg.id} style={getMessageStyle(msg.type)}>
                {msg.text}
              </Text>
            ))
          )}
        </ScrollView>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
    backgroundColor: '#f5f5f5',
  },
  title: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 16,
    textAlign: 'center',
    color: '#333',
  },
  statusContainer: {
    backgroundColor: 'white',
    padding: 12,
    borderRadius: 8,
    marginBottom: 16,
    flexDirection: 'row',
    alignItems: 'center',
  },
  statusLabel: {
    fontSize: 14,
    fontWeight: 'bold',
    marginRight: 8,
    color: '#333',
  },
  status: {
    fontSize: 14,
    fontWeight: '500',
  },
  inputContainer: {
    backgroundColor: 'white',
    padding: 12,
    borderRadius: 8,
    marginBottom: 16,
  },
  input: {
    borderWidth: 1,
    borderColor: '#ddd',
    padding: 10,
    marginBottom: 12,
    borderRadius: 6,
    backgroundColor: '#fafafa',
    minHeight: 60,
    textAlignVertical: 'top',
    fontSize: 14,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 10,
    borderRadius: 6,
    flex: 0.48,
    alignItems: 'center',
  },
  secondaryButton: {
    backgroundColor: '#6c757d',
  },
  buttonDisabled: {
    backgroundColor: '#ccc',
  },
  buttonText: {
    color: 'white',
    fontWeight: '500',
    fontSize: 12,
  },
  messagesContainer: {
    backgroundColor: 'white',
    borderRadius: 8,
    flex: 1,
    minHeight: 200,
  },
  messagesTitle: {
    fontSize: 14,
    fontWeight: 'bold',
    padding: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
    color: '#333',
  },
  messagesScroll: {
    flex: 1,
    maxHeight: 300,
  },
  noMessages: {
    padding: 20,
    textAlign: 'center',
    color: '#999',
    fontStyle: 'italic',
    fontSize: 12,
  },
  message: {
    marginBottom: 4,
    fontSize: 11,
    paddingHorizontal: 12,
    paddingVertical: 6,
    marginHorizontal: 8,
    borderRadius: 4,
    borderLeftWidth: 3,
    borderLeftColor: '#007AFF',
    lineHeight: 14,
  },
});