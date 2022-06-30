package com.amazonaws.kinesisvideo.signaling

import android.util.Log
import com.google.gson.Gson
import javax.websocket.MessageHandler
import javax.websocket.MessageHandler.Whole
import com.amazonaws.kinesisvideo.signaling.model.Event
import com.amazonaws.kinesisvideo.signaling.model.Event.Companion.decodeBase64
import com.amazonaws.kinesisvideo.signaling.model.Message.Companion.ICE_CANDIDATE
import com.amazonaws.kinesisvideo.signaling.model.Message.Companion.SDP_ANSWER
import com.amazonaws.kinesisvideo.signaling.model.Message.Companion.SDP_OFFER

abstract class SignalingListener : Signaling {
    private val gson = Gson()
    val messageHandler: MessageHandler.Whole<String> = Whole<String> { message ->
        Log.d(TAG, "Received message $message")
        if (message.isEmpty() || !message.contains("messagePayload")) return@Whole
        val evt = gson.fromJson(message, Event::class.java) ?: return@Whole
        val messageType = evt.messageType?.uppercase() ?: return@Whole
        val messagePayload = evt.messagePayload ?: return@Whole
        if (messagePayload.isEmpty()) return@Whole
        when (messageType) {
            SDP_OFFER -> {
                Log.d(TAG, "Offer received: SenderClientId=" + evt.senderClientId)
                Log.d(TAG, messagePayload.decodeBase64())
                onSdpOffer(evt)
            }
            SDP_ANSWER -> {
                Log.d(TAG, "Answer received: SenderClientId=" + evt.senderClientId)
                onSdpAnswer(evt)
            }
            ICE_CANDIDATE -> {
                Log.d(TAG, "Ice Candidate received: SenderClientId=" + evt.senderClientId)
                Log.d(TAG, messagePayload.decodeBase64())
                onIceCandidate(evt)
            }
        }
    }

    companion object {
        private const val TAG = "CustomMessageHandler"
    }
}