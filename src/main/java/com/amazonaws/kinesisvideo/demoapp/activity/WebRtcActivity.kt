package com.amazonaws.kinesisvideo.demoapp.activity

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp
import com.amazonaws.kinesisvideo.demoapp.R
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment
import com.amazonaws.kinesisvideo.demoapp.util.IntentUtils
import com.amazonaws.kinesisvideo.signaling.SignalingListener
import com.amazonaws.kinesisvideo.signaling.model.Event
import com.amazonaws.kinesisvideo.signaling.model.Message
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient
import com.amazonaws.kinesisvideo.utils.AwsV4Signer
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoPeerConnection
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver
import org.webrtc.*
import org.webrtc.DataChannel.Init
import org.webrtc.PeerConnection.RTCConfiguration
import java.net.URI
import java.nio.charset.Charset
import java.util.*

class WebRtcActivity : AppCompatActivity() {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private var localPeer: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private var peerIceServers: List<PeerConnection.IceServer> = ArrayList()
    private var gotException = false
    private var recipientClientId: String? = null
    private var mNotificationId = 0

    private var mChannelArn: String? = null
    private var mClientId: String? = null
    private var mWssEndpoint: String? = null
    private var mRegion: String? = null
    private var mCreds: AWSCredentials? = null

    // Map to keep track of established peer connections by IDs
    private val peerConnectionFoundMap = HashMap<String?, PeerConnection?>()

    // Map to keep track of ICE candidates received for a client ID before peer connection is established
    private val pendingIceCandidatesMap = HashMap<String?, Queue<IceCandidate>>()
    private fun initWsConnection() {
        val masterEndpoint = "$mWssEndpoint?X-Amz-ChannelARN=$mChannelArn"
        val viewerEndpoint = "$mWssEndpoint?X-Amz-ChannelARN=$mChannelArn&X-Amz-ClientId=$mClientId"
        runOnUiThread { mCreds = KinesisVideoWebRtcDemoApp.credentialsProvider.credentials }
        val signedUri = getSignedUri(masterEndpoint, viewerEndpoint)
        createLocalPeerConnection()
        val wsHost = signedUri.toString()
        val signalingListener: SignalingListener = object : SignalingListener() {
            override fun onSdpOffer(offerEvent: Event) {
                Log.d(TAG, "Received SDP Offer: Setting Remote Description ")
                val sdp = Event.parseOfferEvent(offerEvent)
                localPeer?.setRemoteDescription(
                    KinesisVideoSdpObserver(),
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
                recipientClientId = offerEvent.senderClientId
                Log.d(TAG, "Received SDP offer for client ID: $recipientClientId.Creating answer")
                createSdpAnswer()
            }

            override fun onSdpAnswer(answerEvent: Event) {
                Log.d(TAG, "SDP answer received from signaling")
                val sdp = Event.parseAnswerEvent(answerEvent)
                val sdpAnswer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                localPeer?.setRemoteDescription(KinesisVideoSdpObserver(), sdpAnswer)
                Log.d(TAG, "Answer Client ID: " + answerEvent.senderClientId)
                peerConnectionFoundMap[answerEvent.senderClientId] = localPeer
                // Check if ICE candidates are available in the queue and add the candidate
                handlePendingIceCandidates(answerEvent.senderClientId)
            }

            override fun onIceCandidate(iceCandidateEvent: Event) {
                Log.d(TAG, "Received IceCandidate from remote ")
                val iceCandidate = Event.parseIceCandidate(iceCandidateEvent)
                iceCandidate?.let { checkAndAddIceCandidate(iceCandidateEvent, it) }
                    ?: Log.e(TAG, "Invalid Ice candidate")
            }

            override fun onSignalingError(errorEvent: Event) {
                Log.e(TAG, "Received error message $errorEvent")
            }

            override fun onSignalingException(e: Exception) {
                Log.e(TAG, "Signaling client returned exception " + e.message)
                gotException = true
            }
        }
        try {
            client = SignalingServiceWebSocketClient(wsHost)
            client?.connect(signalingListener)
            Log.d(
                TAG,
                "Client connection " + if (client?.isOpen == true) "Successful" else "Failed"
            )
        } catch (e: Exception) {
            gotException = true
        }
        if (isValidClient) {
            Log.d(TAG, "Client connected to Signaling service " + client?.isOpen)
        } else {
            Log.e(TAG, "Error in connecting to signaling service")
            gotException = true
        }
    }

    private val isValidClient: Boolean
        get() = client?.isOpen == true

    private fun handlePendingIceCandidates(clientId: String?) {
        // Add any pending ICE candidates from the queue for the client ID
        Log.d(TAG, "Pending ice candidates found? " + pendingIceCandidatesMap[clientId])
        val pendingIceCandidatesQueueByClientId = pendingIceCandidatesMap[clientId]
        while (pendingIceCandidatesQueueByClientId != null && !pendingIceCandidatesQueueByClientId.isEmpty()) {
            val iceCandidate = pendingIceCandidatesQueueByClientId.peek()
            val peer = peerConnectionFoundMap[clientId]
            val addIce = true == peer?.addIceCandidate(iceCandidate)
            Log.d(
                TAG,
                "Added ice candidate after SDP exchange " + iceCandidate + " " + if (addIce) "Successfully" else "Failed"
            )
            pendingIceCandidatesQueueByClientId.remove()
        }
        // After sending pending ICE candidates, the client ID's peer connection need not be tracked
        pendingIceCandidatesMap.remove(clientId)
    }

    private fun checkAndAddIceCandidate(message: Event, iceCandidate: IceCandidate) {
        // if answer/offer is not received, it means peer connection is not found. Hold the received ICE candidates in the map.
        if (!peerConnectionFoundMap.containsKey(message.senderClientId)) {
            Log.d(
                TAG,
                "SDP exchange is not complete. Ice candidate $iceCandidate + added to pending queue"
            )

            // If the entry for the client ID already exists (in case of subsequent ICE candidates), update the queue
            if (pendingIceCandidatesMap.containsKey(message.senderClientId)) {
                val pendingIceCandidatesQueueByClientId =
                    pendingIceCandidatesMap[message.senderClientId]!!
                pendingIceCandidatesQueueByClientId.add(iceCandidate)
                pendingIceCandidatesMap[message.senderClientId] =
                    pendingIceCandidatesQueueByClientId
            } else {
                val pendingIceCandidatesQueueByClientId: Queue<IceCandidate> = LinkedList()
                pendingIceCandidatesQueueByClientId.add(iceCandidate)
                pendingIceCandidatesMap[message.senderClientId] =
                    pendingIceCandidatesQueueByClientId
            }
        } else {
            Log.d(TAG, "Peer connection found already")
            // Remote sent us ICE candidates, add to local peer connection
            val peer = peerConnectionFoundMap[message.senderClientId]
            val addIce = true == peer?.addIceCandidate(iceCandidate)
            Log.d(
                TAG,
                "Added ice candidate " + iceCandidate + " " + if (addIce) "Successfully" else "Failed"
            )
        }
    }

    override fun onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        if (rootEglBase != null) {
            rootEglBase?.release()
            rootEglBase = null
        }
        if (localPeer != null) {
            localPeer?.dispose()
            localPeer = null
        }
        if (videoSource != null) {
            videoSource?.dispose()
            videoSource = null
        }
        if (videoCapturer != null) {
            try {
                videoCapturer?.stopCapture()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Failed to stop webrtc video capture. ", e)
            }
            videoCapturer = null
        }
        if (client != null) {
            client?.disconnect()
            client = null
        }
        peerConnectionFoundMap.clear()
        pendingIceCandidatesMap.clear()
        finish()
        super.onDestroy()
    }

    private fun notifySignalingConnectionFailed() {
        finish()
        Toast.makeText(this, "Connection error to signaling", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val intent = intent
        mChannelArn = intent.getStringExtra(StreamWebRtcConfigurationFragment.KEY_CHANNEL_ARN)
        mWssEndpoint = intent.getStringExtra(StreamWebRtcConfigurationFragment.KEY_WSS_ENDPOINT)
        mClientId = intent.getStringExtra(StreamWebRtcConfigurationFragment.KEY_CLIENT_ID)
        mRegion = intent.getStringExtra(StreamWebRtcConfigurationFragment.KEY_REGION)
        rootEglBase = EglBase.create()

        peerIceServers = IntentUtils.iceServersFromIntent(intent)

        setContentView(R.layout.activity_webrtc_main)
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(this)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    rootEglBase?.eglBaseContext,
                    ENABLE_INTEL_VP8_ENCODER,
                    ENABLE_H264_HIGH_PROFILE
                )
            )
            .createPeerConnectionFactory()
        startScreenCapture()
    }

    @TargetApi(21)
    private fun startScreenCapture() {
        val mMediaProjectionManager =
            application.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mMediaProjectionManager.createScreenCaptureIntent(),
            CAPTURE_PERMISSION_REQUEST_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_PERMISSION_REQUEST_CODE) {
            return
        }
        val intent = Intent(this, ScreenRecorderService::class.java)
        intent.putExtra(ScreenRecorderService.PermissionData, data)
        gagaga(data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun gagaga(permissionData: Intent?) {
        videoCapturer = createVideoCapturer(permissionData)
        videoSource = peerConnectionFactory?.createVideoSource(false)
        val surfaceTextureHelper =
            SurfaceTextureHelper.create(Thread.currentThread().name, rootEglBase?.eglBaseContext)
        videoCapturer?.initialize(
            surfaceTextureHelper,
            this.applicationContext,
            videoSource?.capturerObserver
        )
        localVideoTrack = peerConnectionFactory?.createVideoTrack(VideoTrackID, videoSource)

        // Start capturing video
//        videoCapturer.startCapture(VIDEO_SIZE_WIDTH, VIDEO_SIZE_HEIGHT, VIDEO_FPS);
        localVideoTrack?.setEnabled(true)
        createNotificationChannel()

        // Start websocket after adding local audio/video tracks
        initWsConnection()
        if (!gotException && isValidClient) {
            Toast.makeText(this, "Signaling Connected", Toast.LENGTH_LONG).show()
        } else {
            notifySignalingConnectionFailed()
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

    private fun createLocalPeerConnection() {
        val rtcConfig = RTCConfiguration(peerIceServers)
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy =
            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        localPeer = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : KinesisVideoPeerConnection() {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    super.onIceCandidate(iceCandidate)
                    val message =
                        Message.createIceCandidateMessage(iceCandidate, recipientClientId.orEmpty())
                    Log.d(TAG, "Sending IceCandidate to remote peer $iceCandidate")
                    client?.sendIceCandidate(message) /* Send to Peer */
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
                                "Remote Data Channel onStateChange: state: " + dataChannel.state()
                                    .toString()
                            )
                        }

                        override fun onMessage(buffer: DataChannel.Buffer) {
                            runOnUiThread {
                                val bytes: ByteArray
                                if (buffer.data.hasArray()) {
                                    bytes = buffer.data.array()
                                } else {
                                    bytes = ByteArray(buffer.data.remaining())
                                    buffer.data[bytes]
                                }
                                val builder =
                                    NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                                        .setSmallIcon(R.mipmap.ic_launcher)
                                        .setLargeIcon(
                                            BitmapFactory.decodeResource(
                                                applicationContext.resources,
                                                R.mipmap.ic_launcher
                                            )
                                        )
                                        .setContentTitle("Message from Peer!")
                                        .setContentText(String(bytes, Charset.defaultCharset()))
                                        .setPriority(NotificationCompat.PRIORITY_MAX)
                                        .setAutoCancel(true)
                                val notificationManager = NotificationManagerCompat.from(
                                    applicationContext
                                )

                                // notificationId is a unique int for each notification that you must define
                                notificationManager.notify(mNotificationId++, builder.build())
                                Toast.makeText(
                                    applicationContext,
                                    "New message from peer, check notification.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
                }
            })
        if (localPeer != null) {
            localPeer?.getStats { rtcStatsReport ->
                val statsMap = rtcStatsReport.statsMap
                val entries: Set<Map.Entry<String, RTCStats>> = statsMap.entries
                for ((key, value) in entries) {
                    Log.d(TAG, "Stats: $key ,$value")
                }
            }
        }
        addDataChannelToLocalPeer()
        addStreamToLocalPeer()
    }

    private fun addStreamToLocalPeer() {
        val stream = peerConnectionFactory!!.createLocalMediaStream(LOCAL_MEDIA_STREAM_LABEL)
        if (!stream.addTrack(localVideoTrack)) {
            Log.e(TAG, "Add video track failed")
        }
        localPeer?.addTrack(stream.videoTracks[0], listOf(stream.id))
    }

    private fun addDataChannelToLocalPeer() {
        Log.d(TAG, "Data channel addDataChannelToLocalPeer")
        val localDataChannel = localPeer?.createDataChannel("data-channel-of-$mClientId", Init())
        localDataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {
                Log.d(TAG, "Local Data Channel onBufferedAmountChange called with amount $l")
            }

            override fun onStateChange() {
                Log.d(
                    TAG,
                    "Local Data Channel onStateChange: state: " + localDataChannel.state()
                        .toString()
                )
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
            }
        })
    }

    // when local is set to be the master
    private fun createSdpAnswer() {
        localPeer?.createAnswer(object : KinesisVideoSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.d(TAG, "Creating answer : success")
                super.onCreateSuccess(sessionDescription)
                localPeer?.setLocalDescription(KinesisVideoSdpObserver(), sessionDescription)
                val answer =
                    Message.createAnswerMessage(
                        sessionDescription,
                        true,
                        recipientClientId.orEmpty()
                    )
                client?.sendSdpAnswer(answer)
                peerConnectionFoundMap[recipientClientId] = localPeer
                handlePendingIceCandidates(recipientClientId)
            }
        }, MediaConstraints())
    }

    private fun getSignedUri(masterEndpoint: String, viewerEndpoint: String): URI? {
        return AwsV4Signer.sign(
            URI.create(masterEndpoint),
            mCreds!!.awsAccessKeyId,
            mCreds!!.awsSecretKey,
            if (mCreds is AWSSessionCredentials) (mCreds as AWSSessionCredentials).sessionToken else "",
            URI.create(mWssEndpoint),
            mRegion.orEmpty()
        )
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = getString(R.string.data_channel_notification)
            val description = getString(R.string.data_channel_notification_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            channel.description = description
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = getSystemService(
                NotificationManager::class.java
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val TAG = "KVSWebRtcActivity"
        const val VideoTrackID = "KvsVideoTrack"
        const val LOCAL_MEDIA_STREAM_LABEL = "KvsLocalMediaStream"
        const val VIDEO_SIZE_WIDTH = 1280
        const val VIDEO_SIZE_HEIGHT = 720
        const val VIDEO_FPS = 30
        const val CHANNEL_ID = "WebRtcDataChannel"
        const val ENABLE_INTEL_VP8_ENCODER = true
        const val ENABLE_H264_HIGH_PROFILE = true

        @Volatile
        private var client: SignalingServiceWebSocketClient? = null
        private var videoCapturer: VideoCapturer? = null
        const val CAPTURE_PERMISSION_REQUEST_CODE = 4242
    }
}