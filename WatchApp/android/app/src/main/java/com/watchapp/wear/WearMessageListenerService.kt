package com.watchapp.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

class WearMessageListenerService : WearableListenerService() {
  companion object {
    private const val TAG = "WatchMsgBgService"
    private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private const val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"
    private const val WEAR_TO_PHONE_PATH = "/wear-message-to-phone"
    private const val WEAR_APP_CHECK_PAYLOAD_PREFIX = "AppOpenWearable"
    private const val WEAR_APP_CHECK_ACK_PREFIX = "AppOpenWearableACK"
  }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    val path = messageEvent.path
    val data = String(messageEvent.data, StandardCharsets.UTF_8)
    val sourceNodeId = messageEvent.sourceNodeId

    Log.d(TAG, "[BG] onMessageReceived path=$path data=$data from=$sourceNodeId")

    when (path) {
      APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
        if (data.startsWith(WEAR_APP_CHECK_PAYLOAD_PREFIX)) {
          val parts = data.split(":", limit = 2)
          val nonce = if (parts.size == 2) parts[1] else ""
          val ack = if (nonce.isNotEmpty()) "$WEAR_APP_CHECK_ACK_PREFIX:$nonce" else WEAR_APP_CHECK_ACK_PREFIX
          Log.d(TAG, "[BG] Handshake from phone, sending ACK nonce=$nonce")
          Wearable.getMessageClient(this)
            .sendMessage(sourceNodeId, APP_OPEN_WEARABLE_PAYLOAD_PATH, ack.toByteArray(StandardCharsets.UTF_8))
            .addOnSuccessListener { Log.d(TAG, "[BG] ACK sent") }
            .addOnFailureListener { e -> Log.e(TAG, "[BG] Failed to send ACK", e) }
        }
      }
      MESSAGE_ITEM_RECEIVED_PATH -> {
        // Phone -> Watch user messages
        Log.d(TAG, "[BG] Phone says: $data")
      }
      WEAR_TO_PHONE_PATH -> {
        // Sent from watch to phone; nothing to handle here
      }
      else -> Log.d(TAG, "[BG] Unknown path: $path")
    }
  }
}
