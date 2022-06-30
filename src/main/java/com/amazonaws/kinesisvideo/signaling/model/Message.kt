package com.amazonaws.kinesisvideo.signaling.model

import android.util.Base64
import com.amazonaws.kinesisvideo.signaling.model.Event.Companion.decodeBase64
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class Message(
    val action: String?,
    val recipientClientId: String,
    val senderClientId: String,
    val messagePayload: String
) {

    companion object {
        const val SDP_ANSWER = "SDP_ANSWER"
        const val ICE_CANDIDATE = "ICE_CANDIDATE"
        const val SDP_OFFER = "SDP_OFFER"

        /**
         * @param sessionDescription SDP description to be converted & sent to signaling service
         * @param master true if local is set to be the master
         * @param recipientClientId - has to be set to null if this is set as viewer
         * @return SDP Answer message to be sent to signaling service
         */
        fun createAnswerMessage(
            sessionDescription: SessionDescription,
            master: Boolean,
            recipientClientId: String
        ): Message {
            val description = sessionDescription.description
            val replace = description.replace("\r\n", "\\r\\n")
            val answerPayload = """{"type":"answer","sdp":"$replace"}"""
            val encodedString = answerPayload.base64()

            // SenderClientId should always be "" for master creating answer case
            return Message(SDP_ANSWER, recipientClientId, "", encodedString)
        }


        /**
         * @param sessionDescription SDP description to be converted  as Offer Message & sent to signaling service
         * @param clientId Client Id to mark this viewer in signaling service
         * @return SDP Offer message to be sent to signaling service
         */
        fun createOfferMessage(sessionDescription: SessionDescription, clientId: String): Message {
            val description = sessionDescription.description
            val desc = description.replace("\r\n", "\\r\\n")
            val offerPayload = """{"type":"offer","sdp":"$desc"}"""
            val encodedString = offerPayload.base64()
            return Message(SDP_OFFER, "", clientId, encodedString)
        }

        fun createIceCandidateMessage(
            iceCandidate: IceCandidate,
            recipientClientId: String
        ): Message {
            val messagePayload =
                """{"candidate":"${iceCandidate.sdp}","sdpMid":"${iceCandidate.sdpMid}","sdpMLineIndex":${iceCandidate.sdpMLineIndex}}"""
            return Message(
                ICE_CANDIDATE, recipientClientId, "",
                messagePayload.base64()
            )
        }

        fun String.base64() = String(
            Base64.encode(
                this.toByteArray(),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        )
    }
}