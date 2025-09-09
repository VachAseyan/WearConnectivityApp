import React, { useEffect, useState, useRef } from 'react';
import { 
  View, 
  Text, 
  Button, 
  TextInput, 
  StyleSheet, 
  NativeEventEmitter, 
  NativeModules,
  ScrollView,
  Alert
} from 'react-native';

const { MobileCommunicationModule } = NativeModules;



const App = () => {
  const [message, setMessage] = useState('');
  const [logs, setLogs] = useState<string[]>([]);
  const [nodePresent, setNodePresent] = useState(false);
  const [ackReceived, setAckReceived] = useState(false);
  const [isInitialized, setIsInitialized] = useState(false);
  const eventEmitterRef = useRef<NativeEventEmitter | null>(null);
  const listenersRef = useRef<any[]>([]);

  const addLog = (msg: string) => {
    const timestamp = new Date().toLocaleTimeString();
    setLogs(prev => [`${timestamp}: ${msg}`, ...prev.slice(0, 19)]); // Keep last 20 logs
    console.log(`[Mobile App] ${msg}`);
  };

  useEffect(() => {
    let isMounted = true;

    const initializeApp = async () => {
      try {
        addLog('Starting initialization...');

        if (!MobileCommunicationModule) {
          throw new Error('MobileCommunicationModule not found');
        }

        // Create event emitter
        eventEmitterRef.current = new NativeEventEmitter(MobileCommunicationModule);
        addLog('Event emitter created');

        // Set up listeners BEFORE initializing the module
        const messageListener = eventEmitterRef.current.addListener('onMessageReceived', (data) => {
          addLog(`📩 Message from wearable: "${data.message}"`);
        });

        const wearableListener = eventEmitterRef.current.addListener('onWearableConnected', (data) => {
          addLog(`🔗 Wearable connected - Node: ${data.nodePresent ? '✅' : '❌'}, ACK: ${data.ackReceived ? '✅' : '❌'}`);
          setNodePresent(data.nodePresent);
          setAckReceived(data.ackReceived);
        });

        const sentListener = eventEmitterRef.current.addListener('onMessageSent', (data) => {
          addLog(`📤 Message sent: ${data.success ? '✅' : '❌'} - "${data.message}"`);
        });

        // Store listeners for cleanup
        listenersRef.current = [messageListener, wearableListener, sentListener];
        addLog('Event listeners registered');

        // Initialize the native module
        await MobileCommunicationModule.initialize();
        
        if (isMounted) {
          setIsInitialized(true);
          addLog('✅ Module initialized successfully');
        }

      } catch (error: any) {
        if (isMounted) {
          addLog(`❌ Initialization failed: ${error.message}`);
          Alert.alert('Initialization Error', error.message);
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
      if (MobileCommunicationModule && MobileCommunicationModule.cleanup) {
        MobileCommunicationModule.cleanup();
      }
      
      addLog('🧹 Cleanup completed');
    };
  }, []);

  const sendMessage = async () => {
    if (!message.trim()) {
      Alert.alert('Error', 'Please enter a message');
      return;
    }

    if (!isInitialized) {
      Alert.alert('Error', 'Module not initialized');
      return;
    }

    try {
      addLog(`📤 Sending: "${message}"`);
      await MobileCommunicationModule.sendMessageToWearable(message);
      setMessage('');
    } catch (error: any) {
      addLog(`❌ Send failed: ${error.message}`);
      Alert.alert('Send Error', error.message);
    }
  };

  const checkConnection = async () => {
    try {
      addLog("🔍 Sending ping...");
      await MobileCommunicationModule.sendMessageToWearable("ping");
  
      setTimeout(async () => {
        const result = await MobileCommunicationModule.checkWearableConnection();
        addLog(`Node=${result.nodePresent}, ACK=${result.ackReceived}`);
        setNodePresent(result.nodePresent);
        setAckReceived(result.ackReceived);
      }, 2000); // սպասում ես ack գալուն
    } catch (e: any) {
      addLog("❌ Connection check failed: " + e.message);
    }
  };

  const clearLogs = () => {
    setLogs([]);
  };

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.title}>📱 Phone ↔️ ⌚ Wear Communication</Text>
      
      <View style={styles.statusContainer}>
        <Text style={styles.statusTitle}>Status:</Text>
        <Text style={[styles.status, { color: isInitialized ? 'green' : 'red' }]}>
          Module: {isInitialized ? '✅ Ready' : '❌ Not Ready'}
        </Text>
        <Text style={[styles.status, { color: nodePresent ? 'green' : 'red' }]}>
          Node: {nodePresent ? '✅ Connected' : '❌ Disconnected'}
        </Text>
        <Text style={[styles.status, { color: ackReceived ? 'green' : 'red' }]}>
          ACK: {ackReceived ? '✅ Received' : '❌ Not Received'}
        </Text>
      </View>

      <View style={styles.inputContainer}>
        <TextInput
          style={styles.input}
          placeholder="Type message to wearable..."
          value={message}
          onChangeText={setMessage}
          multiline
        />
        <View style={styles.buttonRow}>
          <Button title="📤 Send" onPress={sendMessage} disabled={!isInitialized} />
          <Button title="🔍 Check" onPress={checkConnection} disabled={!isInitialized} />
        </View>
      </View>

      <View style={styles.logsContainer}>
        <View style={styles.logsHeader}>
          <Text style={styles.logsTitle}>📋 Logs ({logs.length})</Text>
          <Button title="🗑️ Clear" onPress={clearLogs} />
        </View>
        <ScrollView style={styles.logsScroll} nestedScrollEnabled>
          {logs.length === 0 ? (
            <Text style={styles.noLogs}>No logs yet...</Text>
          ) : (
            logs.map((log, index) => (
              <Text key={index} style={styles.logItem}>{log}</Text>
            ))
          )}
        </ScrollView>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { 
    flex: 1, 
    padding: 20, 
    backgroundColor: '#f5f5f5' 
  },
  title: { 
    fontSize: 22, 
    fontWeight: 'bold', 
    marginBottom: 20, 
    textAlign: 'center',
    color: '#333'
  },
  statusContainer: {
    backgroundColor: 'white',
    padding: 15,
    borderRadius: 8,
    marginBottom: 20,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  statusTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    marginBottom: 8,
    color: '#333'
  },
  status: { 
    fontSize: 14, 
    marginBottom: 4,
    fontWeight: '500'
  },
  inputContainer: {
    backgroundColor: 'white',
    padding: 15,
    borderRadius: 8,
    marginBottom: 20,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  input: { 
    borderWidth: 1, 
    borderColor: '#ddd', 
    padding: 12, 
    marginBottom: 10,
    borderRadius: 6,
    backgroundColor: '#fafafa',
    minHeight: 80,
    textAlignVertical: 'top'
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-around'
  },
  logsContainer: {
    backgroundColor: 'white',
    borderRadius: 8,
    flex: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  logsHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 15,
    borderBottomWidth: 1,
    borderBottomColor: '#eee'
  },
  logsTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#333'
  },
  logsScroll: {
    flex: 1,
    maxHeight: 300,
  },
  noLogs: {
    padding: 20,
    textAlign: 'center',
    color: '#999',
    fontStyle: 'italic'
  },
  logItem: { 
    marginBottom: 8, 
    fontSize: 12, 
    paddingHorizontal: 15,
    paddingVertical: 4,
    backgroundColor: '#fafafa',
    marginHorizontal: 10,
    borderRadius: 4,
    borderLeftWidth: 3,
    borderLeftColor: '#007AFF'
  }
});

export default App;