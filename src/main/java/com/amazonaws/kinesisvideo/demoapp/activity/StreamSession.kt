package com.amazonaws.kinesisvideo.demoapp.activity

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.amazonaws.kinesisvideo.demoapp.activity.WebRtcActivity.Companion.TAG
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.Companion.KEY_CLIENT_ID
import com.amazonaws.kinesisvideo.demoapp.util.IntentUtils
import com.amazonaws.kinesisvideo.demoapp.util.IntentUtils.signalingFromIntent
import com.amazonaws.kinesisvideo.signaling.SignalingListener
import com.amazonaws.kinesisvideo.signaling.model.Event
import com.amazonaws.kinesisvideo.signaling.model.Message
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoPeerConnection
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver
import org.webrtc.*
import java.util.*

class StreamSession(intent: Intent, context: Context) : SignalingListener() {
    private val client: SignalingServiceWebSocketClient
    private var gotException: Boolean = false
    private var recipientClientId: String? = null

    private val localPeer: PeerConnection
    private val videoCapturer: VideoCapturer
    private val localVideoTrack: VideoTrack
    private val videoSource: VideoSource
    private val peerConnectionFactory: PeerConnectionFactory
    private val rootEglBase: EglBase = EglBase.create()
    private val mClientId: String
    private val peerConnectionFoundMap = HashMap<String, PeerConnection>()
    private val pendingIceCandidatesMap = HashMap<String, Queue<IceCandidate>>()

    init {
        mClientId = intent.getStringExtra(KEY_CLIENT_ID).orEmpty()
        val peerIceServers = IntentUtils.iceServersFromIntent(intent)
        val permissionData: Intent? =
            intent.getParcelableExtra(ScreenRecorderService.PermissionData)
        client = signalingFromIntent(intent)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    rootEglBase.eglBaseContext,
                    WebRtcActivity.ENABLE_INTEL_VP8_ENCODER,
                    WebRtcActivity.ENABLE_H264_HIGH_PROFILE
                )
            )
            .createPeerConnectionFactory()

        videoCapturer = createVideoCapturer(permissionData)
        videoSource = peerConnectionFactory.createVideoSource(false)
        val surfaceTextureHelper =
            SurfaceTextureHelper.create(Thread.currentThread().name, rootEglBase.eglBaseContext)
        videoCapturer.initialize(
            surfaceTextureHelper,
            context,
            videoSource.capturerObserver
        )
        localVideoTrack =
            peerConnectionFactory.createVideoTrack(WebRtcActivity.VideoTrackID, videoSource)

        // Start capturing video
        videoCapturer.startCapture(
            WebRtcActivity.VIDEO_SIZE_WIDTH,
            WebRtcActivity.VIDEO_SIZE_HEIGHT,
            WebRtcActivity.VIDEO_FPS
        )
        localVideoTrack.setEnabled(true)
//        createNotificationChannel()

        localPeer = createLocalPeerConnection(peerIceServers)
        addDataChannelToLocalPeer()
        addStreamToLocalPeer()
        // Start websocket after adding local audio/video tracks
        client.connect(this)
    }

    private fun createIceCandidateMessage(iceCandidate: IceCandidate): Message {
        return Message.createIceCandidateMessage(iceCandidate, recipientClientId.orEmpty())
    }

    private fun addDataChannelToLocalPeer() {
        Log.d(TAG, "Data channel addDataChannelToLocalPeer")
        val localDataChannel = localPeer.createDataChannel(
            "data-channel-of-$mClientId",
            DataChannel.Init()
        )
        localDataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {
                Log.d(TAG, "Local Data Channel onBufferedAmountChange called with amount $l")
            }

            override fun onStateChange() {
                Log.d(TAG, "Local Data Channel onStateChange: state: ${localDataChannel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
            }
        })
    }

    private fun addStreamToLocalPeer() {
        val stream =
            peerConnectionFactory.createLocalMediaStream(WebRtcActivity.LOCAL_MEDIA_STREAM_LABEL)
        if (!stream.addTrack(localVideoTrack)) {
            Log.e(TAG, "Add video track failed")
        }
        localPeer.addTrack(stream.videoTracks[0], listOf(stream.id))
    }

    private fun createLocalPeerConnection(peerIceServers: List<PeerConnection.IceServer>): PeerConnection {
        val rtcConfig = PeerConnection.RTCConfiguration(peerIceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val localPeer = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : KinesisVideoPeerConnection() {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    super.onIceCandidate(iceCandidate)
                    val message = createIceCandidateMessage(iceCandidate)
                    Log.d(TAG, "Sending IceCandidate to remote peer $iceCandidate")
                    client.sendIceCandidate(message) /* Send to Peer */
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    super.onAddStream(mediaStream)
                    Log.d(TAG, "Adding remote video stream (and audio) to the view")
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    super.onDataChannel(dataChannel)
                    dataChannel.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(l: Long) {
                            // no op on receiver side
                        }

                        override fun onStateChange() {
                            Log.d(
                                TAG,
                                "Remote Data Channel onStateChange: state: ${dataChannel.state()}"
                            )
                        }

                        override fun onMessage(buffer: DataChannel.Buffer) {
                            Log.d(TAG, "onmessage $buffer")
                        }
                    })
                }
            }) ?: TODO("localPeer == null")

        localPeer.getStats { rtcStatsReport ->
            for ((key, value) in rtcStatsReport.statsMap.entries) {
                Log.d(TAG, "Stats: $key $value")
            }
        }
        return localPeer
    }

    private fun handlePendingIceCandidates(clientId: String) {
        // Add any pending ICE candidates from the queue for the client ID
        Log.d(TAG, "Pending ice candidates found? " + pendingIceCandidatesMap[clientId])
        val iceCandidates = pendingIceCandidatesMap[clientId]

        while (!iceCandidates.isNullOrEmpty()) {
            val iceCandidate = iceCandidates.peek()
            val peer = peerConnectionFoundMap[clientId]
            val addIce = true == peer?.addIceCandidate(iceCandidate)
            val ok = if (addIce) "Successfully" else "Failed"
            Log.d(TAG, "Added ice candidate after SDP exchange $iceCandidate $ok")
            iceCandidates.remove()
        }
        // After sending pending ICE candidates, the client ID's peer connection need not be tracked
        pendingIceCandidatesMap.remove(clientId)
    }

    // when local is set to be the master
    private fun createSdpAnswer() {
        localPeer.createAnswer(object : KinesisVideoSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.d(TAG, "Creating answer : success")
                super.onCreateSuccess(sessionDescription)
                localPeer.setLocalDescription(KinesisVideoSdpObserver(), sessionDescription)
                val recipientId = recipientClientId.orEmpty()
                val answer =
                    Message.createAnswerMessage(sessionDescription, true, recipientId)
                client.sendSdpAnswer(answer)
                peerConnectionFoundMap[recipientId] = localPeer
                handlePendingIceCandidates(recipientId)
            }
        }, MediaConstraints())
    }

    private fun checkAndAddIceCandidate(message: Event, iceCandidate: IceCandidate) {
        // if answer/offer is not received, it means peer connection is not found. Hold the received ICE candidates in the map.
        val senderClientId = message.senderClientId.orEmpty()
        if (!peerConnectionFoundMap.containsKey(senderClientId)) {
            Log.d(
                TAG,
                "SDP exchange is not complete. Ice candidate $iceCandidate + added to pending queue"
            )

            // If the entry for the client ID already exists (in case of subsequent ICE candidates), update the queue
            pendingIceCandidatesMap.getOrPut(senderClientId) { LinkedList() }.add(iceCandidate)
        } else {
            Log.d(TAG, "Peer connection found already")
            // Remote sent us ICE candidates, add to local peer connection
            val peer = peerConnectionFoundMap[senderClientId]
            val addIce = true == peer?.addIceCandidate(iceCandidate)
            val ok = if (addIce) "Successfully" else "Failed"
            Log.d(TAG, "Added ice candidate $iceCandidate $ok")
        }
    }

    private fun createVideoCapturer(permissionData: Intent?): VideoCapturer {
        return ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.e(TAG, "user has revoked permissions")
            }
        })
    }

    override fun onSdpOffer(offerEvent: Event) {
        Log.d(TAG, "Received SDP Offer: Setting Remote Description ")
        val sdp = Event.parseOfferEvent(offerEvent)
        localPeer.setRemoteDescription(
            KinesisVideoSdpObserver(),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
        recipientClientId = offerEvent.senderClientId
        Log.d(TAG, "Received SDP offer for client ID: $recipientClientId. Creating answer")
        createSdpAnswer()
    }

    override fun onSdpAnswer(answerEvent: Event) {
        Log.d(TAG, "SDP answer received from signaling")
        val sdp = Event.parseAnswerEvent(answerEvent)
        val sdpAnswer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        localPeer.setRemoteDescription(KinesisVideoSdpObserver(), sdpAnswer)
        val senderClientId = answerEvent.senderClientId.orEmpty()
        Log.d(TAG, "Answer Client ID: $senderClientId")
        peerConnectionFoundMap[senderClientId] = localPeer
        // Check if ICE candidates are available in the queue and add the candidate
        handlePendingIceCandidates(senderClientId)
    }

    override fun onIceCandidate(iceCandidateEvent: Event) {
        Log.d(TAG, "Received IceCandidate from remote")
        val iceCandidate = Event.parseIceCandidate(iceCandidateEvent)
        iceCandidate?.let { checkAndAddIceCandidate(iceCandidateEvent, it) }
            ?: Log.e(TAG, "Invalid Ice candidate")
    }

    override fun onSignalingError(errorEvent: Event) {
        Log.e(TAG, "Received error message $errorEvent")
    }

    override fun onSignalingException(e: Exception) {
        Log.e(TAG, "Signaling client returned exception ${e.message}")
        gotException = true
    }
}