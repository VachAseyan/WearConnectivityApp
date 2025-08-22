import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  TextInput,
  Button,
  StyleSheet,
  NativeModules,
  NativeEventEmitter,
  ScrollView,
} from 'react-native';

const { WearMessage } = NativeModules as {
  WearMessage: {
    checkConnection: () => Promise<boolean>;
    sendMessageToWear: (msg: string) => Promise<string>;
    getConnectedNodes: () => Promise<Array<{id: string; displayName: string; isNearby: boolean}>>;
  }
};

const wearEmitter = new NativeEventEmitter(WearMessage as any);

export default function App() {
  const [message, setMessage] = useState('');
  const [isConnected, setIsConnected] = useState(false);
  const [logs, setLogs] = useState<string[]>([]);
  const [connectedNodes, setConnectedNodes] = useState<Array<{id: string; displayName: string; isNearby: boolean}>>([]);
  const [lastFromWatch, setLastFromWatch] = useState<string>('');
  const [lastSentToWatch, setLastSentToWatch] = useState<string>('');
  const [messagesFromWatch, setMessagesFromWatch] = useState<string[]>([]);

  const addLog = (logMessage: string) => {
    const timestamp = new Date().toLocaleTimeString();
    console.log(`[${timestamp}] ${logMessage}`);
    setLogs(prev => [...prev.slice(-20), `[${timestamp}] ${logMessage}`]);
  };

  useEffect(() => {
    checkConnection();
    getNodes();

    const handler = (data: {
      path: string;
      message?: string;
      data?: string;
      payload?: string;
      sourceNodeId?: string;
      timestamp?: number;
    }) => {
      const payload = (data as any)?.message ?? (data as any)?.data ?? (data as any)?.payload;
      // Ignore handshake traffic in UI
      if (data.path === '/APP_OPEN_WEARABLE_PAYLOAD') return;
      if (data.path === '/wear-message-to-phone' && payload != null) {
        addLog(`⬅️ From Watch: ${payload}`);
        setLastFromWatch(String(payload));
        setMessagesFromWatch(prev => [...prev, String(payload)]);
      }
    };

    // Primary listener via NativeEventEmitter bound to module
    const subscription = wearEmitter.addListener('WearMessage', handler);

    const nodeSubscription = wearEmitter.addListener('WearNodesChanged', (data: {
      count: number;
      hasConnection: boolean;
    }) => {
      addLog(`🔄 Nodes changed - Count: ${data.count}`);
      getNodes();
    });

    const capabilitySubscription = wearEmitter.addListener('WearCapabilityChanged', (data: {
      name: string;
      reachable: boolean;
      nodeCount: number;
    }) => {
      addLog(`🔔 Capability ${data.name} reachable=${data.reachable} nodes=${data.nodeCount}`);
    });

    return () => {
      subscription.remove();
      // No fallback DeviceEventEmitter to avoid duplicate events

      nodeSubscription.remove();
      capabilitySubscription.remove();
    };
  }, []);

  const checkConnection = async () => {
    try {
      const connected = await WearMessage.checkConnection();
      setIsConnected(connected);
      addLog(connected ? '✅ Connected to Watch' : '❌ Disconnected');

      if (!connected) {
        await getNodes();
      }
    } catch (err: unknown) {
      const errorMsg = String((err as any)?.message ?? err);
      addLog(`❌ Disconnected (${errorMsg})`);
      setIsConnected(false);
    }
  };

  const getNodes = async () => {
    try {
      const nodes = await WearMessage.getConnectedNodes();
      setConnectedNodes(nodes);
      addLog("Hello")
    } catch (err: unknown) {
      const errorMsg = String((err as any)?.message ?? err);
      addLog(`❌ Nodes error: ${errorMsg}`);
      setConnectedNodes([]);
    }
  };

  const sendMessage = async () => {
    if (!message.trim()) {
      addLog('⚠️ Cannot send empty message');
      return;
    }

    try {
      const result = await WearMessage.sendMessageToWear(message);
      addLog('✅ Sent to Watch');
      setLastSentToWatch(message);
      setMessage('');
    } catch (err: any) {
      const errorMsg = String(err?.message ?? err);
      addLog(`❌ Send error: ${errorMsg}`);
    }
  };

  const clearLogs = () => {
    setLogs([]);
    addLog('🧹 Logs cleared');
  };

  return (
    <ScrollView style={styles.container}>
      <View style={styles.section}>
        <Text style={styles.title}>📱 Phone App - Connection Status</Text>
        <Text>
          {isConnected ? '✅ Connected to Watch' : '❌ Disconnected'}
        </Text>
      </View>

      <View style={styles.section}>
        <Text style={styles.title}>⌚ Connected Devices ({connectedNodes.length})</Text>
        {connectedNodes.length > 0 ? (
          connectedNodes.map((node, index) => (
            <View key={node.id} style={styles.nodeItem}>
              <Text style={styles.nodeText}>
                {index + 1}. {node.displayName || 'Unknown Device'}
              </Text>
              <Text style={styles.nodeSubText}>
                ID: {node.id.substring(0, 8)}... | Nearby: {node.isNearby ? 'Yes' : 'No'}
              </Text>
            </View>
          ))
        ) : (
          <Text style={styles.noNodes}>No connected devices found</Text>
        )}
      </View>

      <View style={styles.section}>
        <View style={styles.buttonRow}>
          <Button title="🤝 Check Connection" onPress={checkConnection} />
          <Button title="🔍 Get Nodes" onPress={getNodes} color="#2196F3" />
        </View>
      </View>

      <View style={styles.section}>
        <Text style={styles.title}>📤 Send Message to Watch</Text>
        <TextInput
          style={styles.input}
          value={message}
          onChangeText={setMessage}
          placeholder="Type your message here..."
          multiline
        />
        <Button
          title="📱➡️⌚ Send to Watch"
          onPress={sendMessage}
          disabled={!isConnected || !message.trim()}
        />
      </View>

      {(lastSentToWatch || lastFromWatch) ? (
        <View style={styles.section}>
          {lastSentToWatch ? (
            <View style={{marginBottom: 10}}>
              <Text style={styles.title}>Last Sent to Watch</Text>
              <Text style={styles.lastMessageText}>{lastSentToWatch}</Text>
            </View>
          ) : null}
          {lastFromWatch ? (
            <View>
              <Text style={styles.title}>Last Received from Watch</Text>
              <Text style={styles.lastMessageText}>{lastFromWatch}</Text>
            </View>
          ) : null}
        </View>
      ) : null}

      {/* All messages received from Watch */}
      <View style={styles.section}>
        <View style={styles.buttonRow}>
          <Text style={styles.title}>All Received from Watch ({messagesFromWatch.length})</Text>
          <Button title="Clear" onPress={() => setMessagesFromWatch([])} color="#f44336" />
        </View>
        {messagesFromWatch.length > 0 ? (
          <ScrollView style={styles.logsContainer} nestedScrollEnabled>
            {messagesFromWatch.map((m, i) => (
              <Text key={i} style={styles.logText}>{m}</Text>
            ))}
          </ScrollView>
        ) : (
          <Text style={styles.noNodes}>No messages received yet</Text>
        )}
      </View>

      <View style={styles.section}>
        <View style={styles.buttonRow}>
          <Text style={styles.title}>📋 Debug Logs ({logs.length})</Text>
          <Button title="🧹 Clear" onPress={clearLogs} color="#f44336" />
        </View>
        <ScrollView style={styles.logsContainer} nestedScrollEnabled>
          {logs.map((log, i) => (
            <Text key={i} style={styles.logText}>{log}</Text>
          ))}
        </ScrollView>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    backgroundColor: '#f5f5f5',
  },
  section: {
    backgroundColor: 'white',
    padding: 15,
    marginBottom: 15,
    borderRadius: 10,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  title: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 10,
    color: '#333',
  },
  status: {
    fontSize: 16,
    fontWeight: '600',
  },
  nodeItem: {
    backgroundColor: '#f8f9fa',
    padding: 10,
    borderRadius: 5,
    marginBottom: 5,
  },
  nodeText: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  nodeSubText: {
    fontSize: 12,
    color: '#666',
    marginTop: 2,
  },
  noNodes: {
    fontStyle: 'italic',
    color: '#999',
    textAlign: 'center',
    padding: 20,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  input: {
    borderWidth: 1,
    borderColor: '#ddd',
    padding: 12,
    marginBottom: 10,
    borderRadius: 8,
    backgroundColor: '#fff',
    minHeight: 80,
    textAlignVertical: 'top',
  },
  logsContainer: {
    maxHeight: 200,
    backgroundColor: '#f8f9fa',
    padding: 10,
    borderRadius: 5,
  },
  logText: {
    fontSize: 12,
    fontFamily: 'monospace',
    color: '#333',
    marginBottom: 2,
  },
  lastMessageText: {
    padding: 12,
    backgroundColor: '#e3f2fd',
    borderRadius: 8,
    fontFamily: 'monospace',
    fontSize: 14,
  },
});