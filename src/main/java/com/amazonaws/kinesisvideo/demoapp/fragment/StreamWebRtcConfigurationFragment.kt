package com.amazonaws.kinesisvideo.demoapp.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp
import com.amazonaws.kinesisvideo.demoapp.R
import com.amazonaws.kinesisvideo.demoapp.activity.SimpleNavActivity
import com.amazonaws.kinesisvideo.demoapp.activity.WebRtcActivity
import com.amazonaws.regions.Region
import com.amazonaws.services.kinesisvideo.AWSKinesisVideoClient
import com.amazonaws.services.kinesisvideo.model.*
import com.amazonaws.services.kinesisvideosignaling.AWSKinesisVideoSignalingClient
import com.amazonaws.services.kinesisvideosignaling.model.GetIceServerConfigRequest
import com.amazonaws.services.kinesisvideosignaling.model.IceServer
import java.lang.ref.WeakReference
import java.util.*

class StreamWebRtcConfigurationFragment : Fragment() {
    private var mChannelName: EditText? = null
    private var mClientId: EditText? = null
    private var mRegion: EditText? = null
    private var mCameras: Spinner? = null
    private val mEndpointList: MutableList<ResourceEndpointListItem> = ArrayList()
    private val mIceServerList: MutableList<IceServer> = ArrayList()
    private var mChannelArn: String? = null
    private var mOptions: ListView? = null
    private var navActivity: SimpleNavActivity? = null
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val activity = this.activity
        if (activity != null) {
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.CAMERA
                )
                || PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                )
            ) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    9393
                )
            }
            activity.title = activity.getString(R.string.title_fragment_channel)
        }
        return inflater.inflate(R.layout.fragment_stream_webrtc_configuration, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val mStartMasterButton = view.findViewById<Button>(R.id.start_master)
        mStartMasterButton.setOnClickListener(View.OnClickListener { startMasterActivity() })
        val mStartViewerButton = view.findViewById<Button>(R.id.start_viewer)
        mStartViewerButton.setOnClickListener { startViewerActivity() }
        mChannelName = view.findViewById(R.id.channel_name)
        mClientId = view.findViewById(R.id.client_id)
        mRegion = view.findViewById(R.id.region)
        setRegionFromCognito()
        mOptions = view.findViewById(R.id.webrtc_options)
        mOptions?.adapter = object : ArrayAdapter<String?>(
            activity!!,
            android.R.layout.simple_list_item_multiple_choice,
            WEBRTC_OPTIONS
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                if (convertView == null) {
                    val v = layoutInflater.inflate(
                        android.R.layout.simple_list_item_multiple_choice,
                        null
                    )
                    val ctv = v.findViewById<CheckedTextView>(android.R.id.text1)
                    ctv.text = WEBRTC_OPTIONS[position]

                    // Send video is enabled by default and cannot uncheck
                    if (position == 0) {
                        ctv.isEnabled = false
                        ctv.setOnClickListener { ctv.isChecked = true }
                    }
                    return v
                }
                return convertView
            }
        }
        mOptions?.itemsCanFocus = false
        mOptions?.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        mOptions?.setItemChecked(0, true)
        mCameras = view.findViewById(R.id.camera_spinner)
        val cameraList: List<String> = ArrayList(listOf("Front Camera", "Back Camera"))
        if (context != null) {
            mCameras?.adapter = ArrayAdapter(
                context!!,
                android.R.layout.simple_spinner_dropdown_item,
                cameraList
            )
        }
    }

    private fun setRegionFromCognito() {
        val region = KinesisVideoWebRtcDemoApp.region
        if (region != null) {
            mRegion!!.setText(region)
        }
    }

    private fun startMasterActivity() {
        updateSignalingChannelInfo(
            mRegion!!.text.toString(),
            mChannelName!!.text.toString(),
            ChannelRole.MASTER
        )
        if (mChannelArn != null) {
            val extras = setExtras(true)
            val intent = Intent(activity, WebRtcActivity::class.java)
            intent.putExtras(extras)
            startActivity(intent)
        }
    }

    private fun startViewerActivity() {
        updateSignalingChannelInfo(
            mRegion!!.text.toString(),
            mChannelName!!.text.toString(),
            ChannelRole.VIEWER
        )
        if (mChannelArn != null) {
            val extras = setExtras(false)
            val intent = Intent(activity, WebRtcActivity::class.java)
            intent.putExtras(extras)
            startActivity(intent)
        }
    }

    private fun setExtras(isMaster: Boolean): Bundle {
        val extras = Bundle()
        val channelName = mChannelName!!.text.toString()
        val clientId = mClientId!!.text.toString()
        val region = mRegion!!.text.toString()
        extras.putString(KEY_CHANNEL_NAME, channelName)
        extras.putString(KEY_CLIENT_ID, clientId)
        extras.putString(KEY_REGION, region)
        extras.putString(KEY_REGION, region)
        extras.putString(KEY_CHANNEL_ARN, mChannelArn)
        extras.putBoolean(KEY_IS_MASTER, isMaster)
        if (mIceServerList.size > 0) {
            val userNames = ArrayList<String>(mIceServerList.size)
            val passwords = ArrayList<String>(mIceServerList.size)
            val ttls = ArrayList<Int>(mIceServerList.size)
            val urisList = ArrayList<List<String>>()
            for (iceServer in mIceServerList) {
                userNames.add(iceServer.username)
                passwords.add(iceServer.password)
                ttls.add(iceServer.ttl)
                urisList.add(iceServer.uris)
            }
            extras.putStringArrayList(KEY_ICE_SERVER_USER_NAME, userNames)
            extras.putStringArrayList(KEY_ICE_SERVER_PASSWORD, passwords)
            extras.putIntegerArrayList(KEY_ICE_SERVER_TTL, ttls)
            extras.putSerializable(KEY_ICE_SERVER_URI, urisList)
        } else {
            extras.putStringArrayList(KEY_ICE_SERVER_USER_NAME, null)
            extras.putStringArrayList(KEY_ICE_SERVER_PASSWORD, null)
            extras.putIntegerArrayList(KEY_ICE_SERVER_TTL, null)
            extras.putSerializable(KEY_ICE_SERVER_URI, null)
        }
        for (endpoint in mEndpointList) {
            if (endpoint.protocol == "WSS") {
                extras.putString(KEY_WSS_ENDPOINT, endpoint.resourceEndpoint)
            }
        }
        val checked = mOptions!!.checkedItemPositions
        for (i in 0 until mOptions!!.count) {
            extras.putBoolean(KEY_OF_OPTIONS[i], checked[i])
        }
        extras.putBoolean(KEY_CAMERA_FRONT_FACING, mCameras!!.selectedItem == "Front Camera")
        return extras
    }

    private fun getAwsKinesisVideoClient(region: String): AWSKinesisVideoClient {
        val awsKinesisVideoClient = AWSKinesisVideoClient(
            KinesisVideoWebRtcDemoApp.credentialsProvider.credentials
        )
        awsKinesisVideoClient.setRegion(Region.getRegion(region))
        awsKinesisVideoClient.signerRegionOverride = region
        awsKinesisVideoClient.setServiceNameIntern("kinesisvideo")
        return awsKinesisVideoClient
    }

    private fun getAwsKinesisVideoSignalingClient(
        region: String,
        endpoint: String?
    ): AWSKinesisVideoSignalingClient {
        val client = AWSKinesisVideoSignalingClient(
            KinesisVideoWebRtcDemoApp.credentialsProvider.credentials
        )
        client.setRegion(Region.getRegion(region))
        client.signerRegionOverride = region
        client.setServiceNameIntern("kinesisvideo")
        client.endpoint = endpoint
        return client
    }

    private fun updateSignalingChannelInfo(region: String, channelName: String, role: ChannelRole) {
        mEndpointList.clear()
        mIceServerList.clear()
        mChannelArn = null
        val task = UpdateSignalingChannelInfoTask(this)
        try {
            task.execute(region, channelName, role).get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wait for response of UpdateSignalingChannelInfoTask", e)
        }
    }

    internal class UpdateSignalingChannelInfoTask(fragment: StreamWebRtcConfigurationFragment) :
        AsyncTask<Any?, String?, String?>() {
        val mFragment: WeakReference<StreamWebRtcConfigurationFragment>
        override fun doInBackground(vararg objects: Any?): String? {
            val region = objects[0] as String
            val channelName = objects[1] as String
            val role = objects[2] as ChannelRole
            val awsKinesisVideoClient = try {
                mFragment.get()!!.getAwsKinesisVideoClient(region)
            } catch (e: Exception) {
                return "Create client failed with " + e.localizedMessage
            }
            try {
                val describeSignalingChannelResult = awsKinesisVideoClient.describeSignalingChannel(
                    DescribeSignalingChannelRequest()
                        .withChannelName(channelName)
                )
                Log.i(
                    TAG,
                    "Channel ARN is " + describeSignalingChannelResult?.channelInfo?.channelARN
                )
                mFragment.get()!!.mChannelArn =
                    describeSignalingChannelResult?.channelInfo?.channelARN
            } catch (e: ResourceNotFoundException) {
                if (role == ChannelRole.MASTER) {
                    try {
                        val createSignalingChannelResult =
                            awsKinesisVideoClient.createSignalingChannel(
                                CreateSignalingChannelRequest()
                                    .withChannelName(channelName)
                            )
                        mFragment.get()!!.mChannelArn = createSignalingChannelResult?.channelARN
                    } catch (ex: Exception) {
                        return "Create Signaling Channel failed with Exception " + ex.localizedMessage
                    }
                } else {
                    return "Signaling Channel $channelName doesn't exist!"
                }
            } catch (ex: Exception) {
                return "Describe Signaling Channel failed with Exception " + ex.localizedMessage
            }
            try {
                val getSignalingChannelEndpointResult =
                    awsKinesisVideoClient.getSignalingChannelEndpoint(
                        GetSignalingChannelEndpointRequest()
                            .withChannelARN(mFragment.get()!!.mChannelArn)
                            .withSingleMasterChannelEndpointConfiguration(
                                SingleMasterChannelEndpointConfiguration()
                                    .withProtocols("WSS", "HTTPS")
                                    .withRole(role)
                            )
                    )
                Log.i(TAG, "Endpoints $getSignalingChannelEndpointResult")
                mFragment.get()!!.mEndpointList.addAll(getSignalingChannelEndpointResult.resourceEndpointList)
            } catch (e: Exception) {
                return "Get Signaling Endpoint failed with Exception " + e.localizedMessage
            }
            var dataEndpoint: String? = null
            for (endpoint in mFragment.get()!!.mEndpointList) {
                if (endpoint.protocol == "HTTPS") {
                    dataEndpoint = endpoint.resourceEndpoint
                }
            }
            try {
                val awsKinesisVideoSignalingClient = mFragment.get()!!
                    .getAwsKinesisVideoSignalingClient(region, dataEndpoint)
                val getIceServerConfigResult = awsKinesisVideoSignalingClient.getIceServerConfig(
                    GetIceServerConfigRequest().withChannelARN(mFragment.get()!!.mChannelArn)
                        .withClientId(role.name)
                )
                mFragment.get()!!.mIceServerList.addAll(getIceServerConfigResult.iceServerList)
            } catch (e: Exception) {
                return "Get Ice Server Config failed with Exception " + e.localizedMessage
            }
            return null
        }

        override fun onPostExecute(result: String?) {
            if (result != null) {
                val diag = AlertDialog.Builder(
                    mFragment.get()!!.context!!
                )
                diag.setPositiveButton("OK", null).setMessage(result).create().show()
            }
        }

        init {
            mFragment = WeakReference(fragment)
        }
    }

    companion object {
        private val TAG = StreamWebRtcConfigurationFragment::class.java.simpleName
        private const val KEY_CHANNEL_NAME = "channelName"
        const val KEY_CLIENT_ID = "clientId"
        const val KEY_REGION = "region"
        const val KEY_CHANNEL_ARN = "channelArn"
        const val KEY_WSS_ENDPOINT = "wssEndpoint"
        const val KEY_IS_MASTER = "isMaster"
        const val KEY_ICE_SERVER_USER_NAME = "iceServerUserName"
        const val KEY_ICE_SERVER_PASSWORD = "iceServerPassword"
        const val KEY_ICE_SERVER_TTL = "iceServerTTL"
        const val KEY_ICE_SERVER_URI = "iceServerUri"
        const val KEY_CAMERA_FRONT_FACING = "cameraFrontFacing"
        private const val KEY_SEND_VIDEO = "sendVideo"
        const val KEY_SEND_AUDIO = "sendAudio"
        private val WEBRTC_OPTIONS = arrayOf(
            "Send Video",
            "Send Audio"
        )
        private val KEY_OF_OPTIONS = arrayOf(
            KEY_SEND_VIDEO,
            KEY_SEND_AUDIO
        )

        fun newInstance(navActivity: SimpleNavActivity?): StreamWebRtcConfigurationFragment {
            val s = StreamWebRtcConfigurationFragment()
            s.navActivity = navActivity
            return s
        }
    }
}