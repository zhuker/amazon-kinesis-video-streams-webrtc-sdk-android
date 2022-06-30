package com.amazonaws.kinesisvideo.signaling.tyrus

import android.util.Log
import org.glassfish.tyrus.client.ClientManager
import com.amazonaws.kinesisvideo.signaling.SignalingListener
import java.io.IOException
import org.glassfish.tyrus.client.ClientProperties
import java.net.URI
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.websocket.*

/**
 * A JSR356 based websocket client.
 */
internal class WebSocketClient(
    uri: String, clientManager: ClientManager,
    signalingListener: SignalingListener,
    executorService: ScheduledExecutorService
) {
    var connectTimeoutTimer: ScheduledFuture<*>? = null

    init {
        val cec = ClientEndpointConfig.Builder.create().build()
        clientManager.properties[ClientProperties.LOG_HTTP_UPGRADE] = true
        val endpoint = object : Endpoint() {
            override fun onOpen(session: Session, endpointConfig: EndpointConfig) {
                Log.d(TAG, "Registering message handler")
                session.addMessageHandler(String::class.java, signalingListener.messageHandler)
                connectTimeoutTimer?.cancel(false)
            }

            override fun onClose(session: Session, closeReason: CloseReason) {
                super.onClose(session, closeReason)
                Log.d(
                    TAG,
                    "Session ${session.requestURI} closed with reason ${closeReason.reasonPhrase}"
                )
                connectTimeoutTimer?.cancel(false)
            }

            override fun onError(session: Session, thr: Throwable) {
                super.onError(session, thr)
                connectTimeoutTimer?.cancel(false)
                thr.printStackTrace()
                Log.w(TAG, thr)
                if (thr is Exception)
                    signalingListener.onSignalingException(thr)
            }
        }
        executorService.submit {
            try {
                session = clientManager.connectToServer(endpoint, cec, URI(uri))
            } catch (e: Exception) {
                connectTimeoutTimer?.cancel(false)
                signalingListener.onSignalingException(e)
            }
        }
        connectTimeoutTimer = executorService.schedule({
            signalingListener.onSignalingException(TimeoutException("timed out waiting for server connect $uri"))
        }, 10, TimeUnit.SECONDS)
    }

    private var session: Session? = null
    val isOpen: Boolean
        get() = session?.isOpen == true

    fun send(message: String?) {
        try {
            session?.basicRemote?.sendText(message)
        } catch (e: IOException) {
            Log.e(TAG, "Exception" + e.message)
        }
    }

    fun disconnect() {
        if (isOpen) {
            try {
                session?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Exception" + e.message)
            }
        } else {
            Log.w(TAG, "Connection already closed for " + session?.requestURI)
        }
    }

    companion object {
        private const val TAG = "WebSocketClient"
    }


}