package com.translator.webview

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupLinks()
        setupLanguageCard()
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
        binding.btnInstagram.setOnClickListener {
            openLink("https://www.instagram.com/YomuAI")
        }
        binding.btnYoutube.setOnClickListener {
            openLink("https://www.youtube.com/@YomuAI")
        }
        binding.btnFacebook.setOnClickListener {
            openLink("https://www.facebook.com/YomuAI")
        }
    }

    private fun setupLanguageCard() {
        val langs = arrayOf(
            getString(R.string.setting_language_en),
            getString(R.string.setting_language_ar)
        )
        val codes = arrayOf("en", "ar")
        val current = LocaleManager.getLanguage(this)
        binding.tvCurrentLang.text = if (current == "ar")
            getString(R.string.setting_language_ar)
        else
            getString(R.string.setting_language_en)

        binding.cardLanguage.setOnClickListener {
            val idx = codes.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.setting_language))
                .setSingleChoiceItems(langs, idx) { dialog, which ->
                    dialog.dismiss()
                    val selected = codes[which]
                    if (selected != current) {
                        LocaleManager.setLanguage(this, selected)
                        recreate()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
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
