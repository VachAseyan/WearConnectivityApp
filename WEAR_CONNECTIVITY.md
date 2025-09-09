# Wear Connectivity Module

This module provides a simple way to communicate between a phone and a Wear OS watch in a React Native application.

## Setup

### Phone App (MyWearApp)

1. Ensure the AAR file is in the correct location:
   - `MyWearApp/android/app/libs/mobilecommunication-release.aar`

2. The native module is already set up in:
   - `WearModule.kt`
   - `WearPackage.kt`
   - `MainApplication.kt`

3. Import and use the module in your React Native code:

```javascript
import wearConnectivity from './src/utils/WearConnectivity';

// Initialize the connection
const initConnection = async () => {
  const status = await wearConnectivity.initialize();
  console.log('Connection status:', status);
};

// Send a message to the watch
const sendMessage = async () => {
  const success = await wearConnectivity.sendMessage('Hello from phone!');
  console.log('Message sent:', success);
};

// Listen for messages from the watch
useEffect(() => {
  const unsubscribe = wearConnectivity.onMessageReceived(({ message, isFromWear }) => {
    console.log('Received from watch:', message);
  });
  
  return () => {
    unsubscribe();
    wearConnectivity.cleanup();
  };
}, []);
```

### Watch App (WatchApp)

1. Ensure the AAR file is in the correct location:
   - `WatchApp/android/app/libs/wearablecommunication-release.aar`

2. The native module is already set up in:
   - `WearModule.kt`
   - `WearPackage.kt`
   - `MainApplication.kt`

3. Import and use the module in your React Native code:

```javascript
import wearConnectivity from './src/utils/WearConnectivity';

// Send a message to the phone
const sendMessage = async () => {
  const success = await wearConnectivity.sendMessage('Hello from watch!');
  console.log('Message sent:', success);
};

// Listen for messages from the phone
useEffect(() => {
  const unsubscribe = wearConnectivity.onMessageReceived(({ message, isFromMobile }) => {
    console.log('Received from phone:', message);
  });
  
  return () => {
    unsubscribe();
    wearConnectivity.cleanup();
  };
}, []);
```

## Message Flow

1. **Phone to Watch**: 
   - Phone sends to path: `/message-item-received`
   - Watch receives and forwards to JS

2. **Watch to Phone**:
   - Watch sends to path: `/wear-message-to-phone`
   - Phone receives and forwards to JS

3. **Handshake**:
   - Both use `/APP_OPEN_WEARABLE_PAYLOAD`
   - Not forwarded to JS

## Troubleshooting

1. **Connection Issues**:
   - Ensure both devices are paired and connected via Bluetooth
   - Check Logcat for any error messages
   - Verify both apps have the necessary permissions in their `AndroidManifest.xml`

2. **Message Not Received**:
   - Check if the message path matches exactly
   - Verify the message listener is set up before sending messages
   - Ensure the app is in the foreground (background message handling requires additional setup)

3. **Build Errors**:
   - Clean and rebuild the project if you encounter any build errors
   - Verify the AAR files are in the correct locations
   - Check for any version conflicts in `build.gradle`

## Notes

- The module is designed to work with the specific AAR files provided
- For production, you may want to add error handling and retry logic
- The current implementation assumes the watch app is a companion to the phone app
