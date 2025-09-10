import React, { useEffect, useState, useRef } from 'react';
import { 
  View, 
  Text, 
  Button, 
  TextInput, 
  StyleSheet, 
  NativeEventEmitter, 
  EmitterSubscription,
  NativeModules,
  ScrollView,
  Alert,
  TouchableOpacity
} from 'react-native';

const { MobileCommunicationModule } = NativeModules;

interface Message {
  id: number;
  text: string;
  type: string;
}

const App = () => {
  const [message, setMessage] = useState('');
  const [logs, setLogs] = useState<Message[]>([]);
  const [nodePresent, setNodePresent] = useState(false);
  const [ackReceived, setAckReceived] = useState(false);
  const [isInitialized, setIsInitialized] = useState(false);
  const [lastPingTime, setLastPingTime] = useState(0);
  const eventEmitterRef = useRef<NativeEventEmitter | null>(null);
  const listenersRef = useRef<EmitterSubscription[]>([]);

  const addLog = (msg: string, type: string = 'info') => {
    const timestamp = new Date().toLocaleTimeString();
    setLogs(prev => [{
      id: Date.now() + Math.random(),
      text: `${timestamp}: ${msg}`,
      type
    }, ...prev.slice(0, 19)]); // Keep last 20 logs
    console.log(`[Mobile App] ${msg}`);
  };

  useEffect(() => {
    let isMounted = true;

    const initializeApp = async () => {
      try {
        addLog('Starting mobile app initialization...', 'info');

        if (!MobileCommunicationModule) {
          throw new Error('MobileCommunicationModule not found');
        }

        // Create event emitter
        eventEmitterRef.current = new NativeEventEmitter(MobileCommunicationModule);
        const emitter = eventEmitterRef.current as NativeEventEmitter;
        addLog('Event emitter created', 'info');

        // Set up listeners BEFORE initializing the module
        const listeners: EmitterSubscription[] = [];

        listeners.push(
          emitter.addListener('onMessageReceived', (data) => {
            if (isMounted) {
              if (data.message === 'ack') {
                addLog('📩 Received ACK from wearable', 'success');
                setAckReceived(true);
              } else {
                addLog(`📩 Message from wearable: "${data.message}"`, 'received');
              }
            }
          })
        );

        listeners.push(
          emitter.addListener('onWearableConnected', (data) => {
            if (isMounted) {
              addLog(`🔗 Wearable connected - Node: ${data.nodePresent ? '✅' : '❌'}, ACK: ${data.ackReceived ? '✅' : '❌'}`, 'success');
              setNodePresent(data.nodePresent);
              setAckReceived(data.ackReceived);
            }
          })
        );

        listeners.push(
          emitter.addListener('onMessageSent', (data) => {
            if (isMounted) {
              if (data.message === 'ping') {
                addLog('📤 Ping sent to wearable', 'info');
              } else {
                addLog(`📤 Message sent: ${data.success ? '✅' : '❌'} - "${data.message}"`, data.success ? 'sent' : 'error');
              }
            }
          })
        );

        // Store listeners for cleanup
        listenersRef.current = listeners;
        addLog('Event listeners registered', 'info');

        // Initialize the native module
        await MobileCommunicationModule.initialize();
        
        if (isMounted) {
          setIsInitialized(true);
          addLog('✅ Mobile module initialized successfully', 'success');
        }

      } catch (error: any) {
        if (isMounted) {
          addLog(`❌ Initialization failed: ${error.message}`, 'error');
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
      
      addLog('🧹 Mobile app cleanup completed', 'info');
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
      addLog(`📤 Sending: "${message}"`, 'sending');
      await MobileCommunicationModule.sendMessageToWearable(message);
      setMessage('');
    } catch (error: any) {
      addLog(`❌ Send failed: ${error.message}`, 'error');
      Alert.alert('Send Error', error.message);
    }
  };

  const checkConnection = async () => {
    if (!isInitialized) {
      Alert.alert('Error', 'Module not initialized');
      return;
    }

    try {
      addLog('🔍 Checking connection...', 'info');
      const result = await MobileCommunicationModule.checkWearableConnection();
      addLog(`Connection result - Node: ${result.nodePresent ? '✅' : '❌'}, ACK: ${result.ackReceived ? '✅' : '❌'}`, 'info');
      setNodePresent(result.nodePresent);
      setAckReceived(result.ackReceived);
    } catch (error: any) {
      addLog(`❌ Connection check failed: ${error.message}`, 'error');
      Alert.alert('Connection Check Error', error.message);
    }
  };

  const pingWearable = async () => {
    if (!isInitialized) {
      Alert.alert('Error', 'Module not initialized');
      return;
    }

    try {
      addLog('🏓 Pinging wearable...', 'info');
      const startTime = Date.now();
      const result = await MobileCommunicationModule.pingWearable();
      const responseTime = result.responseTime || (Date.now() - startTime);
      
      setLastPingTime(responseTime);
      
      if (result.ackReceived) {
        addLog(`🏓 Ping successful! Response time: ${responseTime}ms`, 'success');
        setAckReceived(true);
      } else {
        addLog(`🏓 Ping timeout after ${responseTime}ms`, 'error');
        setAckReceived(false);
      }
    } catch (error: any) {
      addLog(`❌ Ping failed: ${error.message}`, 'error');
      Alert.alert('Ping Error', error.message);
    }
  };

  const getStatus = async () => {
    try {
      const status = await MobileCommunicationModule.getConnectionStatus();
      addLog(`📊 Status - Init: ${status.initialized}, Manager: ${status.managerExists}, LastACK: ${status.lastAckReceived}`, 'info');
    } catch (error: any) {
      addLog(`❌ Status check failed: ${error.message}`, 'error');
    }
  };

  const clearLogs = () => {
    setLogs([]);
  };

  const getLogStyle = (type: string) => {
    switch (type) {
      case 'success': return { ...styles.logItem, backgroundColor: '#d4f8d4', borderLeftColor: '#28a745' };
      case 'error': return { ...styles.logItem, backgroundColor: '#fdd4d4', borderLeftColor: '#dc3545' };
      case 'sent': return { ...styles.logItem, backgroundColor: '#d4e8ff', borderLeftColor: '#007bff' };
      case 'received': return { ...styles.logItem, backgroundColor: '#fff4d4', borderLeftColor: '#ffc107' };
      case 'sending': return { ...styles.logItem, backgroundColor: '#f0f0f0', borderLeftColor: '#6c757d' };
      default: return styles.logItem;
    }
  };

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.title}>📱 Mobile ↔️ ⌚ Wear Communication</Text>
      
      <View style={styles.statusContainer}>
        <Text style={styles.statusTitle}>Connection Status:</Text>
        <Text style={[styles.status, { color: isInitialized ? 'green' : 'red' }]}>
          Module: {isInitialized ? '✅ Ready' : '❌ Not Ready'}
        </Text>
        <Text style={[styles.status, { color: nodePresent ? 'green' : 'red' }]}>
          Node: {nodePresent ? '✅ Connected' : '❌ Disconnected'}
        </Text>
        <Text style={[styles.status, { color: ackReceived ? 'green' : 'red' }]}>
          ACK: {ackReceived ? '✅ Received' : '❌ Not Received'}
        </Text>
        {lastPingTime > 0 && (
          <Text style={styles.status}>
            Last Ping: {lastPingTime}ms
          </Text>
        )}
      </View>

      <View style={styles.inputContainer}>
        <TextInput
          style={styles.input}
          placeholder="Type message to wearable..."
          value={message}
          onChangeText={setMessage}
          multiline
        />
        
        <View style={styles.buttonGrid}>
          <TouchableOpacity 
            style={[styles.gridButton, !isInitialized && styles.buttonDisabled]} 
            onPress={sendMessage} 
            disabled={!isInitialized}
          >
            <Text style={styles.buttonText}>📤 Send</Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            style={[styles.gridButton, !isInitialized && styles.buttonDisabled]} 
            onPress={pingWearable} 
            disabled={!isInitialized}
          >
            <Text style={styles.buttonText}>🏓 Ping</Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            style={[styles.gridButton, !isInitialized && styles.buttonDisabled]} 
            onPress={checkConnection} 
            disabled={!isInitialized}
          >
            <Text style={styles.buttonText}>🔍 Check</Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            style={styles.gridButton} 
            onPress={getStatus}
          >
            <Text style={styles.buttonText}>📊 Status</Text>
          </TouchableOpacity>
        </View>
      </View>

      <View style={styles.logsContainer}>
        <View style={styles.logsHeader}>
          <Text style={styles.logsTitle}>📋 Logs ({logs.length})</Text>
          <TouchableOpacity style={styles.clearButton} onPress={clearLogs}>
            <Text style={styles.clearButtonText}>🗑️ Clear</Text>
          </TouchableOpacity>
        </View>
        <ScrollView style={styles.logsScroll} nestedScrollEnabled>
          {logs.length === 0 ? (
            <Text style={styles.noLogs}>No logs yet...</Text>
          ) : (
            logs.map((log) => (
              <Text key={log.id} style={getLogStyle(log.type)}>
                {log.text}
              </Text>
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
    marginBottom: 15,
    borderRadius: 6,
    backgroundColor: '#fafafa',
    minHeight: 80,
    textAlignVertical: 'top'
  },
  buttonGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between'
  },
  gridButton: {
    backgroundColor: '#007AFF',
    padding: 12,
    borderRadius: 6,
    width: '48%',
    alignItems: 'center',
    marginBottom: 8,
  },
  buttonDisabled: {
    backgroundColor: '#ccc',
  },
  buttonText: {
    color: 'white',
    fontWeight: '500',
    fontSize: 14,
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
  clearButton: {
    backgroundColor: '#dc3545',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 4,
  },
  clearButtonText: {
    color: 'white',
    fontSize: 12,
    fontWeight: '500',
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
    marginBottom: 6, 
    fontSize: 12, 
    paddingHorizontal: 15,
    paddingVertical: 8,
    backgroundColor: '#fafafa',
    marginHorizontal: 10,
    borderRadius: 4,
    borderLeftWidth: 3,
    borderLeftColor: '#007AFF',
    lineHeight: 16,
  }
});

export default App;