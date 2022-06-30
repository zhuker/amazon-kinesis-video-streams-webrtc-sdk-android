package com.amazonaws.kinesisvideo.signaling.model

import android.util.Base64
import android.util.Log
import org.webrtc.IceCandidate
import com.google.gson.JsonParser
import java.lang.NumberFormatException

class Event(senderClientId: String?, messageType: String?, messagePayload: String?) {
    var senderClientId: String? = senderClientId
        private set
    var messageType: String? = messageType
        private set
    var messagePayload: String? = messagePayload
        private set
    var statusCode: String? = null
    var body: String? = null

    companion object {
        private const val TAG = "Event"
        fun parseIceCandidate(event: Event): IceCandidate? {
            val candidateString = event.messagePayload?.decodeBase64() ?: return null
            val jsonObject = JsonParser.parseString(candidateString).asJsonObject
            var sdpMid = jsonObject["sdpMid"].toString()
            if (sdpMid.length > 2) {
                sdpMid = sdpMid.substring(1, sdpMid.length - 1)
            }
            val sdpMLineIndex = try {
                jsonObject["sdpMLineIndex"].toString().toInt()
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Invalid sdpMLineIndex")
                return null
            }
            var candidate = jsonObject["candidate"].toString()
            if (candidate.length > 2) {
                candidate = candidate.substring(1, candidate.length - 1)
            }
            return IceCandidate(sdpMid, sdpMLineIndex, candidate)
        }

        fun parseAnswerEvent(answerEvent: Event): String {
            val message = answerEvent.messagePayload?.decodeBase64()
            val jsonObject = JsonParser.parseString(message).asJsonObject
            val type = jsonObject["type"].toString()
            if (!type.equals("\"answer\"", ignoreCase = true)) {
                Log.e(TAG, "Error in answer message")
            }
            val sdp = jsonObject["sdp"].asString
            Log.d(TAG, "SDP answer received from master:$sdp")
            return sdp
        }

        fun parseOfferEvent(offerEvent: Event): String {
            val s = offerEvent.messagePayload?.decodeBase64()
            val jsonObject = JsonParser.parseString(s).asJsonObject
            return jsonObject["sdp"].asString
        }

        fun String.decodeBase64(): String = String(Base64.decode(this, Base64.DEFAULT))
    }
}