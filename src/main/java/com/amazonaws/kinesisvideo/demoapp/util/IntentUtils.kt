package com.amazonaws.kinesisvideo.demoapp.util

import android.content.Intent
import android.util.Log
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp
import com.amazonaws.kinesisvideo.demoapp.activity.WebRtcActivity
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.Companion.KEY_CHANNEL_ARN
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.Companion.KEY_CLIENT_ID
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.Companion.KEY_ICE_SERVER_PASSWORD
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.Companion.KEY_ICE_SERVER_URI
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.Companion.KEY_ICE_SERVER_USER_NAME
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.Companion.KEY_REGION
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.Companion.KEY_WSS_ENDPOINT
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient
import com.amazonaws.kinesisvideo.utils.AwsV4Signer
import com.amazonaws.mobile.auth.core.internal.util.ThreadUtils
import org.webrtc.PeerConnection
import java.net.URI
import java.util.concurrent.CompletableFuture

object IntentUtils {

    fun iceServersFromIntent(intent: Intent): List<PeerConnection.IceServer> {
        val mRegion = intent.getStringExtra(KEY_REGION)
        val stun = PeerConnection.IceServer
            .builder("stun:stun.kinesisvideo.$mRegion.amazonaws.com:443")
            .createIceServer()

        val mUserNames: ArrayList<String?>? =
            intent.getStringArrayListExtra(KEY_ICE_SERVER_USER_NAME)
        val mPasswords: ArrayList<String?>? =
            intent.getStringArrayListExtra(KEY_ICE_SERVER_PASSWORD)
        val mUrisList = intent.getSerializableExtra(KEY_ICE_SERVER_URI) as? List<*>?

        val uris = mUrisList.orEmpty()
        val usernames = mUserNames.orEmpty()
        val passwords = mPasswords.orEmpty()
        val turnServers = uris.zip(usernames.zip(passwords)).map { (turnServer, userpass) ->
            val (user, pass) = userpass
            val turnUri = turnServer.toString().replace("[", "").replace("]", "")
            val iceServer =
                PeerConnection.IceServer.builder(turnUri)
                    .setUsername(user)
                    .setPassword(pass)
                    .createIceServer()
            Log.d(WebRtcActivity.TAG, "IceServer details (TURN) = $iceServer")
            iceServer
        }
        return listOf(stun) + turnServers
    }

    fun signalingFromIntent(intent: Intent): SignalingServiceWebSocketClient {
        val mChannelArn = intent.getStringExtra(KEY_CHANNEL_ARN).orEmpty()
        val mWssEndpoint = intent.getStringExtra(KEY_WSS_ENDPOINT).orEmpty()
        val mRegion = intent.getStringExtra(KEY_REGION).orEmpty()
        val mClientId = intent.getStringExtra(KEY_CLIENT_ID).orEmpty()

        val masterEndpoint = "$mWssEndpoint?X-Amz-ChannelARN=$mChannelArn"
        val viewerEndpoint = "$mWssEndpoint?X-Amz-ChannelARN=$mChannelArn&X-Amz-ClientId=$mClientId"
        val cf = CompletableFuture<AWSCredentials>()
        ThreadUtils.runOnUiThread {
            cf.complete(KinesisVideoWebRtcDemoApp.credentialsProvider.credentials)
        }
        val mCreds = cf.get()
        val wsHost =
            AwsV4Signer.sign(
                URI.create(masterEndpoint),
                mCreds.awsAccessKeyId,
                mCreds.awsSecretKey,
                if (mCreds is AWSSessionCredentials) mCreds.sessionToken else "",
                URI.create(mWssEndpoint),
                mRegion
            ).toString()

        return SignalingServiceWebSocketClient(wsHost)
    }

}