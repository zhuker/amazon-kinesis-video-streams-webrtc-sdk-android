package com.amazonaws.kinesisvideo.demoapp.activity

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.navigation.NavigationView
import com.amazonaws.kinesisvideo.demoapp.R
import androidx.drawerlayout.widget.DrawerLayout
import com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment
import androidx.core.view.GravityCompat
import android.view.MenuItem
import com.amazonaws.mobile.client.AWSMobileClient
import com.amazonaws.mobile.client.SignInUIOptions
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.amazonaws.kinesisvideo.demoapp.activity.SimpleNavActivity
import com.amazonaws.mobile.client.Callback
import com.amazonaws.mobile.client.UserStateDetails
import java.lang.Exception

class SimpleNavActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private var streamFragment: Fragment? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_nav)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val toggle = ActionBarDrawerToggle(
            this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawer.setDrawerListener(toggle)
        toggle.syncState()
        val navigationView = findViewById<NavigationView>(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener(this)
        if (savedInstanceState != null) {
            streamFragment = supportFragmentManager.findFragmentByTag(
                StreamWebRtcConfigurationFragment::class.java.name
            )
        }
        // Video only
        startConfigFragment()
    }

    override fun onBackPressed() {
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        val id = item.itemId
        if (id == R.id.nav_logout) {
            AWSMobileClient.getInstance().signOut()
            AWSMobileClient.getInstance().showSignIn(this,
                SignInUIOptions.builder()
                    .logo(R.mipmap.kinesisvideo_logo)
                    .backgroundColor(Color.WHITE)
                    .nextActivity(SimpleNavActivity::class.java)
                    .build(),
                object : Callback<UserStateDetails> {
                    override fun onResult(result: UserStateDetails) {
                        Log.d(TAG, "onResult: User sign-in " + result.userState)
                    }

                    override fun onError(e: Exception) {
                        Log.e(TAG, "onError: User sign-in", e)
                    }
                })
        }
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        drawer.closeDrawer(GravityCompat.START)
        return true
    }

    fun startFragment(fragment: Fragment?) {
        val fragmentManager = supportFragmentManager
        fragmentManager.beginTransaction().replace(
            R.id.content_simple,
            fragment!!,
            StreamWebRtcConfigurationFragment::class.java.name
        ).commit()
    }

    fun startConfigFragment() {
        try {
            if (streamFragment == null) {
                streamFragment = StreamWebRtcConfigurationFragment.newInstance(this)
                startFragment(streamFragment)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to go back to configure stream.")
            e.printStackTrace()
        }
    }

    companion object {
        private val TAG = SimpleNavActivity::class.java.simpleName
    }
}