import React from 'react';
import type {PropsWithChildren} from 'react';
import {
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
  Button,
  TextInput,
  NativeEventEmitter,
  NativeModules,
  Alert,
} from 'react-native';

const Section: React.FC<
  PropsWithChildren<{
    title: string;
  }>
> = ({children, title}) => {
  return (
    <View style={styles.sectionContainer}>
      <Text style={styles.sectionTitle}>
        {title}
      </Text>
      <Text style={styles.sectionDescription}>
        {children}
      </Text>
    </View>
  );
};

const { WearMessaging } = NativeModules as any;

export default function App(): React.JSX.Element {
  const [nodes, setNodes] = React.useState<Array<{id: string; displayName: string; isNearby: boolean}>>([]);
  const [message, setMessage] = React.useState('Hello from watch');
  const [lastMessage, setLastMessage] = React.useState<string>(''); // From phone → watch
  const [lastSent, setLastSent] = React.useState<string>('');       // From watch → phone
  const [connectionStatus, setConnectionStatus] = React.useState<'unknown' | 'connected' | 'disconnected'>('unknown');
  const [logs, setLogs] = React.useState<string[]>([]);
  const [messagesFromPhone, setMessagesFromPhone] = React.useState<string[]>([]);

  const addLog = React.useCallback((txt: string) => {
    const ts = new Date().toLocaleTimeString();
    console.log(`[${ts}] ${txt}`);
    setLogs(prev => [...prev.slice(-20), `[${ts}] ${txt}`]);
  }, []);
  
  React.useEffect(() => {
      const emitter = new NativeEventEmitter(WearMessaging as any);
    
    // Listen for messages from phone
    const messageListener = emitter.addListener('WearMessage', (evt: any) => {
      console.log('WearMessage evt:', evt);
      const payload = evt?.message ?? evt?.data;
      if (evt.path === '/APP_OPEN_WEARABLE_PAYLOAD') return;
      if (evt.path === '/message-item-received' && payload != null) {
        setLastMessage(String(payload));
        setMessagesFromPhone(prev => [...prev, String(payload)]);
        addLog(`⬅️ From Phone: ${payload}`);
      }
    });

    // Get connected nodes on startup
    const loadNodes = async () => {
      try {
        const connectedNodes = await WearMessaging.getConnectedNodes();
        setNodes(connectedNodes);
        setConnectionStatus(connectedNodes.length > 0 ? 'connected' : 'disconnected');
      } catch (error) {
        setConnectionStatus('disconnected');
      }
    };

    loadNodes();

    // Refresh nodes periodically
    const interval = setInterval(loadNodes, 5000);

    return () => {
      messageListener.remove();
      clearInterval(interval);
    };
  }, []);

  // No verbose list-change logs

  const sendToPhone = React.useCallback(async () => {
    const {WearMessaging} = NativeModules;
    
    if (nodes.length === 0) {
      Alert.alert('No Connection', 'No connected phone found');
      return;
    }

    try {
      const result = await WearMessaging.sendMessageToPhone(message);
      addLog('✅ Sent to Phone');
      setLastSent(message);
      setMessage(''); // Clear input after sending
    } catch (error) {
      Alert.alert('Error', 'Failed to send');
    }
  }, [nodes, message]);

  const refreshConnection = React.useCallback(async () => {
    const {WearMessaging} = NativeModules;
    try {
      const connectedNodes = await WearMessaging.getConnectedNodes();
      setNodes(connectedNodes);
      setConnectionStatus(connectedNodes.length > 0 ? 'connected' : 'disconnected');
    } catch (error) {
      setConnectionStatus('disconnected');
    }
  }, []);

  const getConnectionStatusColor = () => {
    switch (connectionStatus) {
      case 'connected': return '#4CAF50';
      case 'disconnected': return '#F44336';
      default: return '#FF9800';
    }
  };

  const getConnectionStatusText = () => {
    switch (connectionStatus) {
      case 'connected': return `Connected (${nodes.length} devices)`;
      case 'disconnected': return 'Disconnected';
      default: return 'Checking...';
    }
  };

  

  return (
    <>
      <StatusBar barStyle={'dark-content'} />
      <ScrollView contentInsetAdjustmentBehavior="automatic">
        <View style={styles.container}>
          
          {/* Connection Status */}
          <Section title="Connection Status">
            <View style={[styles.statusIndicator, {backgroundColor: getConnectionStatusColor()}]}>
              <Text style={styles.statusText}>{getConnectionStatusText()}</Text>
            </View>
          </Section>

          {/* Connected Devices */}
          <Section title="Connected Devices">
            {nodes.length > 0 ? (
              nodes.map((node) => (
                <View key={node.id} style={styles.nodeItem}>
                  <Text style={styles.nodeText}>
                    {node.displayName || node.id}
                    {node.isNearby && ' (Nearby)'}
                  </Text>
                </View>
              ))
            ) : (
              <Text style={styles.noNodesText}>No devices connected</Text>
            )}
          </Section>

          {/* Message Input */}
          <View style={styles.messageContainer}>
            <Text style={styles.inputLabel}>Message to Phone:</Text>
            <TextInput
              style={styles.textInput}
              value={message}
              onChangeText={setMessage}
              placeholder="Type your message here..."
              multiline
              numberOfLines={3}
            />
            
            <View style={styles.buttonContainer}>
              <Button 
                title="Send to Phone" 
                onPress={sendToPhone}
                disabled={nodes.length === 0 || message.trim().length === 0}
              />
              <View style={styles.buttonSpacer} />
              <Button 
                title="Refresh Connection" 
                onPress={refreshConnection}
                color="#2196F3"
              />
            </View>
          </View>

          {/* Last Sent and Received Messages */}
          {lastSent ? (
            <Section title="Last Sent to Phone">
              <Text style={styles.lastMessageText}>{lastSent}</Text>
            </Section>
          ) : null}

          {lastMessage ? (
            <Section title="Last Received from Phone">
              <Text style={styles.lastMessageText}>{lastMessage}</Text>
            </Section>
          ) : null}

          {/* All messages received from Phone */}
          <Section title={`All Received from Phone (${messagesFromPhone.length})`}>
            <View style={{flexDirection: 'row', justifyContent: 'flex-end', marginBottom: 8}}>
              <Button title="Clear" onPress={() => setMessagesFromPhone([])} color="#f44336" />
            </View>
            {messagesFromPhone.length > 0 ? (
              <View>
                {messagesFromPhone.map((m, i) => (
                  <Text key={i} style={styles.logText}>{m}</Text>
                ))}
              </View>
            ) : (
              <Text style={styles.noNodesText}>No messages received yet</Text>
            )}
          </Section>

          {/* Debug Logs */}
          {logs.length > 0 ? (
            <Section title="Debug Logs">
              {logs.map((l, i) => (
                <Text key={i} style={styles.logText}>{l}</Text>
              ))}
            </Section>
          ) : null}

        </View>
      </ScrollView>
    </>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
  },
  sectionContainer: {
    marginTop: 20,
    marginBottom: 16,
  },
  sectionTitle: {
    fontSize: 20,
    fontWeight: '600',
    marginBottom: 8,
    color: '#333',
  },
  sectionDescription: {
    fontSize: 16,
    fontWeight: '400',
    color: '#666',
  },
  statusIndicator: {
    padding: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  statusText: {
    color: 'white',
    fontWeight: 'bold',
    fontSize: 16,
  },
  nodeItem: {
    padding: 8,
    backgroundColor: '#f5f5f5',
    borderRadius: 4,
    marginBottom: 4,
  },
  nodeText: {
    fontSize: 14,
    color: '#333',
  },
  noNodesText: {
    fontStyle: 'italic',
    color: '#999',
  },
  messageContainer: {
    marginTop: 20,
  },
  inputLabel: {
    fontSize: 16,
    fontWeight: '500',
    marginBottom: 8,
    color: '#333',
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    padding: 12,
    fontSize: 16,
    backgroundColor: 'white',
    textAlignVertical: 'top',
    marginBottom: 16,
  },
  buttonContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  buttonSpacer: {
    width: 10,
  },
  lastMessageText: {
    padding: 12,
    backgroundColor: '#e3f2fd',
    borderRadius: 8,
    fontFamily: 'monospace',
    fontSize: 14,
  },
  logText: {
    fontSize: 12,
    fontFamily: 'monospace',
    color: '#333',
    marginBottom: 2,
  },
});