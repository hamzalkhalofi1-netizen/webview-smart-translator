package com.translator.webview

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupLinks()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupLinks() {
        binding.btnEmail.setOnClickListener {
            openLink("mailto:yomuai.app@gmail.com")
        }

        binding.btnYoutube.setOnClickListener {
            openLink("https://www.youtube.com/@YomuAI")
        }

        binding.btnTwitter.setOnClickListener {
            openLink("https://twitter.com/YomuAIApp")
        }

        binding.btnDiscord.setOnClickListener {
            openLink("https://discord.gg/yomuai")
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.open_link), Toast.LENGTH_SHORT).show()
        }
    }
}
