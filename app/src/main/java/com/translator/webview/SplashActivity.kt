package com.translator.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var cm: ConnectivityManager
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(LocaleManager.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        animateIn()

        binding.btnRetry.setOnClickListener {
            showLoading()
            checkConnectivity()
        }
        binding.root.postDelayed({ checkConnectivity() }, 1800)
    }

    private fun animateIn() {
        listOf(binding.ivLogo, binding.tvAppName, binding.tvTagline, binding.progressIndicator)
            .forEach { it.alpha = 0f }
        binding.ivLogo.scaleX = 0.6f
        binding.ivLogo.scaleY = 0.6f
        binding.tvAppName.translationY = 24f
        binding.tvTagline.translationY = 16f

        binding.ivLogo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(700).setStartDelay(100).start()
        binding.tvAppName.animate().alpha(1f).translationY(0f)
            .setDuration(600).setStartDelay(500).start()
        binding.tvTagline.animate().alpha(1f).translationY(0f)
            .setDuration(600).setStartDelay(700).start()
        binding.progressIndicator.animate().alpha(1f)
            .setDuration(400).setStartDelay(1000).start()
    }

    private fun checkConnectivity() {
        if (isOnline()) proceed() else { showNoInternet(); listenForReconnect() }
    }

    private fun isOnline(): Boolean {
        val net  = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showLoading() {
        binding.layoutNoInternet.visibility = View.GONE
        binding.progressIndicator.visibility = View.VISIBLE
    }

    private fun showNoInternet() {
        binding.progressIndicator.visibility = View.GONE
        binding.layoutNoInternet.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(400).start()
        }
    }

    private fun listenForReconnect() {
        netCallback?.let { cm.unregisterNetworkCallback(it) }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { showLoading(); binding.root.postDelayed({ proceed() }, 800) }
            }
        }
        cm.registerNetworkCallback(req, netCallback!!)
    }

    private fun proceed() {
        val done = getSharedPreferences(LocaleManager.PREFS, Context.MODE_PRIVATE)
            .getBoolean("onboarding_done", false)
        startActivity(
            Intent(this, if (done) MainActivity::class.java else OnboardingActivity::class.java)
        )
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        netCallback?.let { cm.unregisterNetworkCallback(it) }
    }
}
