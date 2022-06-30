package com.amazonaws.kinesisvideo.demoapp.activity

import android.graphics.Color
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amazonaws.kinesisvideo.demoapp.R
import com.amazonaws.kinesisvideo.demoapp.util.ActivityUtils
import com.amazonaws.mobile.client.AWSMobileClient
import com.amazonaws.mobile.client.Callback
import com.amazonaws.mobile.client.SignInUIOptions
import com.amazonaws.mobile.client.UserStateDetails

class StartUpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = AWSMobileClient.getInstance()
        val thisActivity: AppCompatActivity = this
        AsyncTask.execute {
            if (auth.isSignedIn) {
                ActivityUtils.startActivity(thisActivity, SimpleNavActivity::class.java)
            } else {
                auth.showSignIn(thisActivity,
                    SignInUIOptions.builder()
                        .logo(R.mipmap.kinesisvideo_logo)
                        .backgroundColor(Color.WHITE)
                        .nextActivity(SimpleNavActivity::class.java)
                        .build(),
                    object : Callback<UserStateDetails> {
                        override fun onResult(result: UserStateDetails) {
                            Log.d(TAG, "onResult: User signed-in " + result.userState)
                        }

                        override fun onError(e: Exception) {
                            runOnUiThread {
                                Log.e(TAG, "onError: User sign-in error", e)
                                Toast.makeText(
                                    this@StartUpActivity,
                                    "User sign-in error: " + e.message,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    })
            }
        }
    }

    companion object {
        private val TAG = StartUpActivity::class.java.simpleName
    }
}