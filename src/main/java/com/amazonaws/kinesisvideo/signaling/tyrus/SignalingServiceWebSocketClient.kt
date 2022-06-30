package com.amazonaws.kinesisvideo.signaling.tyrus

import android.util.Log
import com.amazonaws.kinesisvideo.signaling.SignalingListener
import com.amazonaws.kinesisvideo.signaling.model.Event.Companion.decodeBase64
import com.amazonaws.kinesisvideo.signaling.model.Message
import com.amazonaws.kinesisvideo.signaling.model.Message.Companion.ICE_CANDIDATE
import com.amazonaws.kinesisvideo.signaling.model.Message.Companion.SDP_ANSWER
import com.google.gson.Gson
import java.lang.InterruptedException
import org.glassfish.tyrus.client.ClientManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


/**
 * Signaling service client based on websocket.
 */
class SignalingServiceWebSocketClient(private val uri: String) {
    private val executorService = Executors.newScheduledThreadPool(10)
    private lateinit var websocketClient: WebSocketClient
    private val gson = Gson()

    fun connect(signalingListener: SignalingListener) {
        Log.d(TAG, "Connecting to URI $uri as master")
        websocketClient =
            WebSocketClient(uri, ClientManager(), signalingListener, executorService)
    }

    val isOpen: Boolean
        get() = websocketClient.isOpen

    fun sendSdpAnswer(answer: Message) {
        executorService.submit {
            if (answer.action.equals(SDP_ANSWER, ignoreCase = true)) {
                Log.d(TAG, "Answer sent ${answer.messagePayload.decodeBase64()}")
                send(answer)
            }
        }
    }

    fun sendIceCandidate(candidate: Message) {
        executorService.submit {
            if (candidate.action.equals(ICE_CANDIDATE, ignoreCase = true)) {
                send(candidate)
            }
            Log.d(TAG, "Sent Ice candidate message")
        }
    }

    fun disconnect() {
        executorService.submit { websocketClient.disconnect() }
    }

    private fun send(message: Message) {
        val jsonMessage = gson.toJson(message)
        Log.d(TAG, "Sending JSON Message= $jsonMessage")
        websocketClient.send(jsonMessage)
        Log.d(TAG, "Sent JSON Message= $jsonMessage")
    }

    companion object {
        private const val TAG = "SignalingServiceWebSocketClient"
    }


}