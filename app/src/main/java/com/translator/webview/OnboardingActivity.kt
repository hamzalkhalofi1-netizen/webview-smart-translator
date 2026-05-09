package com.translator.webview

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.translator.webview.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val totalSteps = 5
    private var step = 0

    private var gender        = ""
    private var country       = ""
    private var age           = 20
    private val intents       = mutableSetOf<String>()
    private var appLang       = LocaleManager.LANG_EN
    private var translateLang = LocaleManager.LANG_EN

    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(LocaleManager.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStepIndicator()
        setupAgeControls()
        setupGenderCards()
        setupIntentCards()
        setupCountrySpinner()
        setupLanguageToggles()
        setupButtons()
        updateUI()
    }

    private fun setupStepIndicator() {
        binding.stepIndicator.removeAllViews()
        repeat(totalSteps) { i ->
            val dot = View(this).apply {
                val dp8  = (8 * resources.displayMetrics.density).toInt()
                val dp12 = (12 * resources.displayMetrics.density).toInt()
                layoutParams = ViewGroup.MarginLayoutParams(dp8, dp8).apply {
                    marginStart = 6; marginEnd = 6
                }
                setBackgroundResource(R.drawable.bg_step_dot)
                tag = i
            }
            binding.stepIndicator.addView(dot)
        }
    }

    private fun refreshDots() {
        for (i in 0 until binding.stepIndicator.childCount) {
            val dot = binding.stepIndicator.getChildAt(i)
            dot.alpha = if (i == step) 1f else 0.35f
            val dp  = resources.displayMetrics.density
            val size = if (i == step) (12 * dp).toInt() else (8 * dp).toInt()
            dot.layoutParams = (dot.layoutParams as ViewGroup.MarginLayoutParams).apply {
                width = size; height = size
            }
            dot.requestLayout()
        }
    }

    private fun setupAgeControls() {
        binding.tvAge.text = age.toString()
        binding.btnAgePlus.setOnClickListener  { if (age < 80) { age++; binding.tvAge.text = age.toString() } }
        binding.btnAgeMinus.setOnClickListener { if (age > 10) { age--; binding.tvAge.text = age.toString() } }
        binding.sliderAge.value = age.toFloat()
        binding.sliderAge.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { age = value.toInt(); binding.tvAge.text = age.toString() }
        }
    }

    private fun setupGenderCards() {
        fun selectGender(g: String) {
            gender = g
            val primary = ContextCompat.getColor(this, R.color.primary)
            val surface = ContextCompat.getColor(this, R.color.surface_variant)
            binding.cardMale.strokeColor   = if (g == "male")   primary else surface
            binding.cardFemale.strokeColor = if (g == "female") primary else surface
            binding.cardMale.strokeWidth   = if (g == "male")   6 else 0
            binding.cardFemale.strokeWidth = if (g == "female") 6 else 0
        }
        binding.cardMale.setOnClickListener   { selectGender("male") }
        binding.cardFemale.setOnClickListener { selectGender("female") }
    }

    private fun setupIntentCards() {
        val cards = mapOf(
            binding.cardComics      to "comics",
            binding.cardManga       to "manga",
            binding.cardNovels      to "novels",
            binding.cardTranslation to "translation"
        )
        val primary = ContextCompat.getColor(this, R.color.primary)
        val surface = ContextCompat.getColor(this, R.color.surface_variant)
        cards.forEach { (card, key) ->
            card.setOnClickListener {
                if (intents.contains(key)) intents.remove(key) else intents.add(key)
                card.strokeColor = if (intents.contains(key)) primary else surface
                card.strokeWidth = if (intents.contains(key)) 6 else 0
            }
        }
    }

    private fun setupCountrySpinner() {
        val countries = resources.getStringArray(R.array.countries)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, countries)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCountry.adapter = adapter
        binding.spinnerCountry.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                country = countries[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun setupLanguageToggles() {
        binding.btnLangEn.setOnClickListener {
            appLang = LocaleManager.LANG_EN
            binding.btnLangEn.isSelected = true
            binding.btnLangAr.isSelected = false
        }
        binding.btnLangAr.setOnClickListener {
            appLang = LocaleManager.LANG_AR
            binding.btnLangAr.isSelected = true
            binding.btnLangEn.isSelected = false
        }
        binding.btnTranslateEn.setOnClickListener { translateLang = LocaleManager.LANG_EN; refreshTranslateLangUI() }
        binding.btnTranslateAr.setOnClickListener { translateLang = LocaleManager.LANG_AR; refreshTranslateLangUI() }
        binding.btnTranslateFr.setOnClickListener { translateLang = "fr"; refreshTranslateLangUI() }
        binding.btnTranslateEs.setOnClickListener { translateLang = "es"; refreshTranslateLangUI() }
        binding.btnTranslateJa.setOnClickListener { translateLang = "ja"; refreshTranslateLangUI() }
    }

    private fun refreshTranslateLangUI() {
        val btns = listOf(
            binding.btnTranslateEn to LocaleManager.LANG_EN,
            binding.btnTranslateAr to LocaleManager.LANG_AR,
            binding.btnTranslateFr to "fr",
            binding.btnTranslateEs to "es",
            binding.btnTranslateJa to "ja"
        )
        btns.forEach { (b, l) -> b.isSelected = (l == translateLang) }
    }

    private fun setupButtons() {
        binding.btnNext.setOnClickListener { advance() }
        binding.btnBack.setOnClickListener { goBack() }
        binding.btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun advance() {
        if (step < totalSteps - 1) {
            binding.viewFlipper.setInAnimation(this, R.anim.slide_in_right)
            binding.viewFlipper.setOutAnimation(this, R.anim.slide_out_left)
            binding.viewFlipper.showNext()
            step++
            updateUI()
        } else {
            finishOnboarding()
        }
    }

    private fun goBack() {
        if (step > 0) {
            binding.viewFlipper.setInAnimation(this, R.anim.slide_in_left)
            binding.viewFlipper.setOutAnimation(this, R.anim.slide_out_right)
            binding.viewFlipper.showPrevious()
            step--
            updateUI()
        }
    }

    private fun updateUI() {
        refreshDots()
        binding.stepProgress.progress = ((step + 1) * 100 / totalSteps)
        binding.btnBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        binding.btnNext.text = if (step == totalSteps - 1)
            getString(R.string.onboard_btn_finish) else getString(R.string.onboard_btn_next)
        binding.tvStepLabel.text = getString(R.string.onboard_step, step + 1, totalSteps)
    }

    private fun finishOnboarding() {
        getSharedPreferences(LocaleManager.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_done", true)
            .putString("gender", gender)
            .putString("country", country)
            .putInt("age", age)
            .putString("intents", intents.joinToString(","))
            .putString("translate_lang", translateLang)
            .apply()
        LocaleManager.setLocale(this, appLang)
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
