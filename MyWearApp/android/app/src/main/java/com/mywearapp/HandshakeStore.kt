package com.mywearapp

object HandshakeStore {
  @Volatile var lastAck: String? = null
  @Volatile var lastNodeId: String? = null
  fun clear() {
    lastAck = null
    lastNodeId = null
  }
}
