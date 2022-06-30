package com.amazonaws.kinesisvideo.signaling

import com.amazonaws.kinesisvideo.signaling.model.Event
import java.lang.Exception

interface Signaling {
    fun onSdpOffer(offerEvent: Event)
    fun onSdpAnswer(answerEvent: Event)
    fun onIceCandidate(iceCandidateEvent: Event)
    fun onSignalingError(errorEvent: Event)
    fun onSignalingException(e: Exception)
}