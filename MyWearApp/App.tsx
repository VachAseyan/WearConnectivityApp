import React, { useEffect, useState } from 'react';
import { View, Text, Button, TextInput, StyleSheet, NativeEventEmitter, NativeModules } from 'react-native';

const { MobileCommunicationModule } = NativeModules;

const App = () => {
  const [message, setMessage] = useState('');
  const [logs, setLogs] = useState<string[]>([]);
  const [nodePresent, setNodePresent] = useState(false);
  const [ackReceived, setAckReceived] = useState(false);

  useEffect(() => {
    // Initialize the module
    MobileCommunicationModule.initialize()
      .then(() => addLog('Module initialized'))
      .catch((e: any) => addLog('Initialization failed: ' + e));

    const eventEmitter = new NativeEventEmitter(MobileCommunicationModule);

    const messageListener = eventEmitter.addListener('onMessageReceived', (data: any) => {
      addLog(`Message received from wear: ${data.message}`);
    });

    const wearableListener = eventEmitter.addListener('onWearableConnected', (data: any) => {
      addLog(`Wearable connected - Node present: ${data.nodePresent}, ACK received: ${data.ackReceived}`);
      setNodePresent(data.nodePresent);
      setAckReceived(data.ackReceived);
    });

    const sentListener = eventEmitter.addListener('onMessageSent', (data: any) => {
      addLog(`Message sent: ${data.success}, content: ${data.message}`);
    });

    // Cleanup listeners on unmount
    return () => {
      messageListener.remove();
      wearableListener.remove();
      sentListener.remove();
      MobileCommunicationModule.cleanup();
    };
  }, []);

  const addLog = (msg: string) => setLogs(prev => [msg, ...prev]);

  const sendMessage = () => {
    if (message.trim().length === 0) return;
    MobileCommunicationModule.sendMessageToWearable(message)
      .then(() => addLog(`Message sent to wear: ${message}`))
      .catch((err: any) => addLog(`Failed to send: ${err}`));
    setMessage('');
  };

  const checkConnection = () => {
    MobileCommunicationModule.checkWearableConnection()
      .then((result: boolean[]) => {
        console.log("Node present: " + result[0]);
        
        addLog(`Node present: ${result[0]}, ACK received: ${result[1]}`);
        setNodePresent(result[0]);
        setAckReceived(result[1]);
      })
      .catch((e: any) => addLog('Check connection failed: ' + e));
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Phone Wear Communication</Text>
      <TextInput
        style={styles.input}
        placeholder="Type message..."
        value={message}
        onChangeText={setMessage}
      />
      <Button title="Send to Wear" onPress={sendMessage} />
      <View style={styles.status}>
        <Text>Node Present: {nodePresent ? 'Yes' : 'No'}</Text>
        <Text>ACK Received: {ackReceived ? 'Yes' : 'No'}</Text>
        <Button title="Check Connection" onPress={checkConnection} />
      </View>
      <View style={styles.logs}>
        {logs.map((log, index) => (
          <Text key={index} style={styles.logItem}>{log}</Text>
        ))}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20, backgroundColor: '#fff' },
  title: { fontSize: 20, fontWeight: 'bold', marginBottom: 20 },
  input: { borderWidth: 1, borderColor: '#ccc', padding: 10, marginBottom: 10 },
  status: { marginVertical: 20 },
  logs: { flex: 1 },
  logItem: { marginBottom: 5, fontSize: 14 },
});

export default App;
