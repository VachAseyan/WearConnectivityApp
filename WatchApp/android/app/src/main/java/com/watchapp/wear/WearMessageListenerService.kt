package com.watchapp.wear

import android.util.Log
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

class WearMessageListenerService : WearableListenerService() {
  companion object {
    private const val TAG = "WatchMsgBgService"
    private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private const val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"
    private const val WEAR_TO_PHONE_PATH = "/wear-message-to-phone"
    private const val WEAR_APP_CHECK_PAYLOAD_PREFIX = "AppOpenWearable"
    private const val WEAR_APP_CHECK_ACK_PREFIX = "AppOpenWearableACK"

    // Pending store
    private const val PREFS_NAME = "wear_msg_store"
    private const val KEY_PENDING = "pending_phone_to_watch"
  }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    val path = messageEvent.path
    val data = String(messageEvent.data, StandardCharsets.UTF_8)
    val sourceNodeId = messageEvent.sourceNodeId

    when (path) {
      APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
        when {
          data == WEAR_APP_CHECK_ACK_PREFIX -> { /* no-op */ }
          data == WEAR_APP_CHECK_PAYLOAD_PREFIX -> {
            Wearable.getMessageClient(this)
              .sendMessage(sourceNodeId, APP_OPEN_WEARABLE_PAYLOAD_PATH, WEAR_APP_CHECK_ACK_PREFIX.toByteArray(StandardCharsets.UTF_8))
              .addOnSuccessListener { }
              .addOnFailureListener { }
          }
        }
      }
      MESSAGE_ITEM_RECEIVED_PATH -> {
        Log.i(TAG, "[BG] Received from phone: $data")
        // Persist so UI can read later if app isn't running
        try {
          savePendingMessage(this, path, data, sourceNodeId)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to persist pending message", e)
        }
        // Forward to app via explicit broadcast so foreground module can emit to JS
        val intent = Intent("com.watchapp.wear.MESSAGE_FROM_PHONE").apply {
          putExtra("path", path)
          putExtra("data", data)
          putExtra("sourceNodeId", sourceNodeId)
          putExtra("timestamp", System.currentTimeMillis())
        }
        try {
          // Make explicit to this app package to avoid implicit broadcast restrictions
          intent.setPackage(applicationContext.packageName)
          Log.d(TAG, "Broadcasting to package: ${applicationContext.packageName}")
          sendBroadcast(intent)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to broadcast message to app", e)
        }
      }
      WEAR_TO_PHONE_PATH -> {
        // Sent from watch to phone; nothing to handle here
      }
      else -> { /* ignore */ }
    }
  }

  private fun savePendingMessage(ctx: Context, path: String, data: String, sourceNodeId: String) {
    val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val json = prefs.getString(KEY_PENDING, null)
    val arr = if (json.isNullOrEmpty()) JSONArray() else try { JSONArray(json) } catch (_: Exception) { JSONArray() }
    val obj = JSONObject().apply {
      put("path", path)
      put("data", data)
      put("sourceNodeId", sourceNodeId)
      put("timestamp", System.currentTimeMillis())
    }
    arr.put(obj)
    prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
  }
}
