package com.amazonaws.kinesisvideo.demoapp

import android.app.Application
import android.util.Log
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.kinesisvideo.common.logging.LogLevel
import com.amazonaws.kinesisvideo.common.logging.OutputChannel
import com.amazonaws.mobile.client.AWSMobileClient
import com.amazonaws.mobile.client.Callback
import com.amazonaws.mobile.client.UserStateDetails
import com.amazonaws.mobileconnectors.kinesisvideo.util.AndroidLogOutputChannel
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

class KinesisVideoWebRtcDemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val latch = CountDownLatch(1)
        AWSMobileClient.getInstance()
            .initialize(applicationContext, object : Callback<UserStateDetails> {
                override fun onResult(result: UserStateDetails) {
                    Log.d(TAG, "onResult: user state: " + result.userState)
                    latch.countDown()
                }

                override fun onError(e: Exception) {
                    Log.e(TAG, "onError: Initialization error of the mobile client", e)
                    latch.countDown()
                }
            })
        try {
            latch.await()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    companion object {
        private val TAG = KinesisVideoWebRtcDemoApp::class.java.simpleName
        val credentialsProvider: AWSCredentialsProvider
            get() {
                val outputChannel: OutputChannel = AndroidLogOutputChannel()
                val log = com.amazonaws.kinesisvideo.common.logging.Log(
                    outputChannel,
                    LogLevel.VERBOSE,
                    TAG
                )
                return AWSMobileClient.getInstance()
            }
        val region: String?
            get() {
                val configuration = AWSMobileClient.getInstance().configuration
                val jsonObject = configuration.optJsonObject("CredentialsProvider")
                var region: String? = null
                try {
                    region =
                        ((jsonObject["CognitoIdentity"] as JSONObject)["Default"] as JSONObject)["Region"] as String
                } catch (e: JSONException) {
                    Log.e(TAG, "Got exception when extracting region from cognito setting.", e)
                }
                return region
            }
    }
}