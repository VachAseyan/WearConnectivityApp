package com.mywearapp

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

class WearMessageListenerService : WearableListenerService() {
  companion object {
    private const val TAG = "WearMsgBgService"
    private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private const val WEAR_TO_PHONE_PATH = "/wear-message-to-phone"
    private const val WEAR_APP_CHECK_PAYLOAD_PREFIX = "AppOpenWearable"
    private const val WEAR_APP_CHECK_ACK_PREFIX = "AppOpenWearableACK"
  }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    val path = messageEvent.path
    val data = String(messageEvent.data, StandardCharsets.UTF_8)
    val sourceNodeId = messageEvent.sourceNodeId

    when (path) {
      APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
        // Plain handshake: check ACK first, then request. Use exact matches to avoid loops.
        when {
          data == WEAR_APP_CHECK_ACK_PREFIX -> {
            HandshakeStore.lastAck = data
            HandshakeStore.lastNodeId = sourceNodeId
          }
          data == WEAR_APP_CHECK_PAYLOAD_PREFIX -> {
            Wearable.getMessageClient(this)
              .sendMessage(sourceNodeId, APP_OPEN_WEARABLE_PAYLOAD_PATH, WEAR_APP_CHECK_ACK_PREFIX.toByteArray(StandardCharsets.UTF_8))
              .addOnSuccessListener { }
              .addOnFailureListener { }
          }
        }
      }
      WEAR_TO_PHONE_PATH -> {
        // Optionally: persist or notify. For now, rely on app-level listener when app is open.
      }
      else -> { /* ignore */ }
    }
  }
}
