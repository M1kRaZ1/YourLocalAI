package ch.luca.tuff.urlocalai

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Main local chat activity for Android 6.0 (API 23) with native chat bubbles,
 * Send-button Easter egg, Theme Switcher, and Math Activity RAM management.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnClear: Button
    private lateinit var btnSettings: Button
    private lateinit var btnMath: Button

    private lateinit var llmManager: LLMManager

    // Language state: false = English (default), true = French
    private var isFrench = false

    // State tracking for LLM
    private var isModelLoaded = false
    private var isGenerating = false

    // Easter Egg 1: Top bar 10 taps
    private var headerTapCount = 0
    private var lastTapTime = 0L

    // Easter Egg 2: Send button 10 rapid taps
    private var sendTapCount = 0
    private var sendLastTapTime = 0L

    // Theme definitions (0: Cyberpunk Blue, 1: Emerald Matrix, 2: Sunset Purple, 3: Classic Dark)
    private var currentThemeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        tvStatus = findViewById(R.id.tvStatus)
        chatContainer = findViewById(R.id.chatContainer)
        scrollView = findViewById(R.id.scrollView)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnClear = findViewById(R.id.btnClear)
        btnSettings = findViewById(R.id.btnSettings)
        btnMath = findViewById(R.id.btnMath)

        llmManager = LLMManager(this)

        // Load saved theme
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        currentThemeIndex = prefs.getInt("theme_index", 0)
        applyTheme(currentThemeIndex)

        // Easter Egg 1: 10 taps on top status bar switches language
        tvStatus.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime > 3000) {
                headerTapCount = 1
            } else {
                headerTapCount++
            }
            lastTapTime = currentTime

            if (headerTapCount >= 10) {
                headerTapCount = 0
                toggleLanguage()
            }
        }

        // Open Math Panel and unload LLM to free ~278 MB RAM
        btnMath.setOnClickListener {
            lifecycleScope.launch {
                tvStatus.text = if (isFrench) "Déchargement RAM..." else "Unloading RAM..."
                llmManager.freeModel()
                isModelLoaded = false
                startActivity(Intent(this@MainActivity, MathActivity::class.java))
            }
        }

        // Theme settings dialog
        btnSettings.setOnClickListener {
            showThemeSelectionDialog()
        }

        // Clear chat history button
        btnClear.setOnClickListener {
            chatContainer.removeAllViews()
            val toastMsg = if (isFrench) "Historique effacé !" else "Chat cleared!"
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        }

        // Send button click listener + Easter Egg 2 (10 rapid taps on SEND switches language)
        btnSend.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - sendLastTapTime > 3000) {
                sendTapCount = 1
            } else {
                sendTapCount++
            }
            sendLastTapTime = currentTime

            if (sendTapCount >= 10) {
                sendTapCount = 0
                toggleLanguage()
                return@setOnClickListener
            }

            val userPrompt = etInput.text.toString().trim()
            if (userPrompt.isNotEmpty()) {
                sendTapCount = 0
                sendMessage(userPrompt)
            }
        }

        updateLanguageUI()
    }

    override fun onResume() {
        super.onResume()
        // Reload LLM into RAM when returning from MathActivity
        if (!isModelLoaded) {
            initLLMAsync()
        }
    }

    private fun toggleLanguage() {
        isFrench = !isFrench
        val toastMsg = if (isFrench) "Langue changée en français !" else "Language switched to English!"
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        updateLanguageUI()
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf(
            if (isFrench) "Cyberpunk Bleu (Défaut)" else "Cyberpunk Blue (Default)",
            if (isFrench) "Matrice Émeraude" else "Emerald Matrix",
            if (isFrench) "Violet Couché de Soleil" else "Sunset Purple",
            if (isFrench) "Classique Sombre" else "Classic Dark"
        )

        val title = if (isFrench) "Choisir un thème" else "Choose a Theme"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(themes) { _, which ->
                currentThemeIndex = which
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                prefs.edit().putInt("theme_index", which).apply()
                applyTheme(which)
            }
            .show()
    }

    private fun applyTheme(themeIndex: Int) {
        val bgColor: Int
        val sendColor: Int

        when (themeIndex) {
            1 -> { // Emerald Matrix
                bgColor = Color.parseColor("#022C22")
                sendColor = Color.parseColor("#059669")
            }
            2 -> { // Sunset Purple
                bgColor = Color.parseColor("#2E1065")
                sendColor = Color.parseColor("#7C3AED")
            }
            3 -> { // Classic Dark
                bgColor = Color.parseColor("#121212")
                sendColor = Color.parseColor("#444444")
            }
            else -> { // Cyberpunk Blue (Default)
                bgColor = Color.parseColor("#0B0F19")
                sendColor = Color.parseColor("#2563EB")
            }
        }

        rootLayout.setBackgroundColor(bgColor)
        btnSend.setBackgroundColor(sendColor)
        btnClear.setBackgroundColor(Color.parseColor("#334155"))
        btnSettings.setBackgroundColor(Color.parseColor("#334155"))
        btnMath.setBackgroundColor(Color.parseColor("#334155"))
    }

    /**
     * Updates all UI elements based on current language (English vs French).
     */
    private fun updateLanguageUI() {
        if (isFrench) {
            btnSend.text = "Envoyer"
            btnClear.text = "Effacer"
            btnSettings.text = "Thème"
            btnMath.text = "Maths"
            etInput.hint = "Tapez votre message..."
            if (isGenerating) {
                tvStatus.text = "Statut : Génération en cours..."
            } else if (isModelLoaded) {
                tvStatus.text = "Statut : IA Prête (Gemma 3 270M)"
            } else {
                tvStatus.text = "Statut : Chargement du modèle en RAM..."
            }
        } else {
            btnSend.text = "Send"
            btnClear.text = "Clear"
            btnSettings.text = "Theme"
            btnMath.text = "Math"
            etInput.hint = "Type your message..."
            if (isGenerating) {
                tvStatus.text = "Status: Generating response..."
            } else if (isModelLoaded) {
                tvStatus.text = "Status: AI Ready (Gemma 3 270M)"
            } else {
                tvStatus.text = "Status: Loading model into RAM..."
            }
        }
    }

    /**
     * Asynchronously loads the .gguf model in background without blocking UI thread.
     */
    private fun initLLMAsync() {
        btnSend.isEnabled = false
        isModelLoaded = false
        updateLanguageUI()

        lifecycleScope.launch {
            val success = llmManager.initializeModel("gemma-3-270m-it-Q8_0.gguf")
            if (success) {
                isModelLoaded = true
                btnSend.isEnabled = true
            } else {
                isModelLoaded = false
                tvStatus.text = if (isFrench) "Statut : Échec du chargement du modèle !" else "Status: Failed to load model!"
            }
            updateLanguageUI()
        }
    }

    /**
     * Adds a chat bubble (User or AI) to the chat container, themed according to current theme.
     */
    private fun addBubble(text: String, isUser: Boolean): TextView {
        val bubbleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val radius = 16 * resources.displayMetrics.density
            val smallRadius = 4 * resources.displayMetrics.density
            if (isUser) {
                setCornerRadii(floatArrayOf(radius, radius, radius, radius, smallRadius, smallRadius, radius, radius))
                color = android.content.res.ColorStateList.valueOf(
                    when (currentThemeIndex) {
                        1 -> Color.parseColor("#059669")
                        2 -> Color.parseColor("#7C3AED")
                        3 -> Color.parseColor("#444444")
                        else -> Color.parseColor("#2563EB")
                    }
                )
            } else {
                setCornerRadii(floatArrayOf(radius, radius, radius, radius, radius, radius, smallRadius, smallRadius))
                color = android.content.res.ColorStateList.valueOf(
                    when (currentThemeIndex) {
                        1 -> Color.parseColor("#064E3B")
                        2 -> Color.parseColor("#4C1D95")
                        3 -> Color.parseColor("#1E1E1E")
                        else -> Color.parseColor("#1E293B")
                    }
                )
            }
        }

        val bubble = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (isUser) 0xFFFFFFFF.toInt() else 0xFFF1F5F9.toInt())
            background = bubbleBg
            
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (12 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = (8 * resources.displayMetrics.density).toInt()
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
                marginStart = if (isUser) (48 * resources.displayMetrics.density).toInt() else (8 * resources.displayMetrics.density).toInt()
                marginEnd = if (isUser) (8 * resources.displayMetrics.density).toInt() else (48 * resources.displayMetrics.density).toInt()
            }
            layoutParams = params
            setTextIsSelectable(true)
        }

        chatContainer.addView(bubble)
        scrollToBottom()
        return bubble
    }

    /**
     * Sends prompt to LLM and streams response into an AI chat bubble.
     */
    private fun sendMessage(prompt: String) {
        btnSend.isEnabled = false
        isGenerating = true
        etInput.text.clear()
        updateLanguageUI()

        addBubble(prompt, true)
        val aiBubble = addBubble("", false)

        lifecycleScope.launch {
            llmManager.generateResponseStream(prompt) { token ->
                runOnUiThread {
                    aiBubble.append(token)
                    scrollToBottom()
                }
            }

            isGenerating = false
            btnSend.isEnabled = true
            updateLanguageUI()
        }
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
