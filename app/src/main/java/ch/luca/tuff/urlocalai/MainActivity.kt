package ch.luca.tuff.urlocalai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Main local chat activity featuring OLED Black & White theme, session history archiving,
 * new chat button, custom GGUF model selector, About AI model dialog with Markdown, and token speed options.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMenu: Button
    private lateinit var btnNewChat: ImageButton

    private lateinit var llmManager: LLMManager

    // Language state: false = English (default), true = French
    private var isFrench = false

    // State tracking for LLM
    private var isModelLoaded = false
    private var isGenerating = false

    data class GenStats(val tokenCount: Int, val durationMs: Long, val tokensPerSec: Double)
    private val bubbleStats = mutableMapOf<TextView, GenStats>()

    data class ChatMessage(
        val text: String,
        val isUser: Boolean,
        val tokenCount: Int = 0,
        val durationMs: Long = 0L,
        val tokensPerSec: Double = 0.0
    )
    private val chatMessages = mutableListOf<ChatMessage>()
    private var currentSessionId: Long = System.currentTimeMillis()

    // Easter Egg 1: Top bar 10 taps
    private var headerTapCount = 0
    private var lastTapTime = 0L

    // Easter Egg 2: Send button 10 rapid taps while text field is empty
    private var sendTapCount = 0
    private var sendLastTapTime = 0L

    // File picker for custom .gguf models
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                loadCustomModelFromUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        tvStatus = findViewById(R.id.tvStatus)
        chatContainer = findViewById(R.id.chatContainer)
        scrollView = findViewById(R.id.scrollView)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnMenu = findViewById(R.id.btnMenu)
        btnNewChat = findViewById(R.id.btnNewChat)

        llmManager = LLMManager(this)

        // Set initial disabled color filter for paper plane icon
        btnSend.setColorFilter(Color.parseColor("#8E8E93"))
        btnNewChat.setColorFilter(Color.parseColor("#FFFFFF"))

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

        // New Chat button
        btnNewChat.setOnClickListener {
            startNewChat()
        }

        // 3-Dot Dropdown Menu setup (Math Solver, Load Custom Model, About AI Model, History, Clear History)
        btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, if (isFrench) "Calculateur de maths" else "Math Solver")
            popup.menu.add(0, 2, 1, if (isFrench) "Charger un autre modèle (.gguf)" else "Load Custom Model (.gguf)")
            popup.menu.add(0, 3, 2, if (isFrench) "À propos du modèle IA" else "About AI Model")
            popup.menu.add(0, 4, 3, if (isFrench) "Historique" else "History")
            popup.menu.add(0, 5, 4, if (isFrench) "Effacer l'historique" else "Clear History")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        saveCurrentSession()
                        lifecycleScope.launch {
                            tvStatus.text = if (isFrench) "Déchargement RAM..." else "Unloading RAM..."
                            llmManager.freeModel()
                            isModelLoaded = false
                            startActivity(Intent(this@MainActivity, MathActivity::class.java))
                        }
                        true
                    }
                    2 -> {
                        openModelFilePicker()
                        true
                    }
                    3 -> {
                        showAboutModelDialog()
                        true
                    }
                    4 -> {
                        showHistoryDialog()
                        true
                    }
                    5 -> {
                        clearChatHistory()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // Send on keyboard action (Done / Send)
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                val prompt = etInput.text.toString().trim()
                if (prompt.isNotEmpty() && !isGenerating && isModelLoaded) {
                    sendMessage(prompt)
                }
                true
            } else {
                false
            }
        }

        // Send button click listener + Easter Egg 2 (10 rapid taps while text field is empty switches language)
        btnSend.setOnClickListener {
            val userPrompt = etInput.text.toString().trim()
            if (userPrompt.isEmpty()) {
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
                }
            } else {
                sendTapCount = 0
                if (!isGenerating && isModelLoaded) {
                    sendMessage(userPrompt)
                }
            }
        }

        loadLatestOrActiveSession()
        updateLanguageUI()
    }

    override fun onResume() {
        super.onResume()
        // Reload LLM into RAM when returning from MathActivity
        if (!isModelLoaded) {
            initLLMAsync()
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentSession()
    }

    private fun toggleLanguage() {
        isFrench = !isFrench
        val toastMsg = if (isFrench) "Langue changée en français !" else "Language switched to English!"
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        updateLanguageUI()
    }

    private fun openModelFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    private fun loadCustomModelFromUri(uri: Uri) {
        lifecycleScope.launch {
            tvStatus.text = if (isFrench) "Importation du modèle..." else "Importing model..."
            btnSend.isEnabled = false
            btnSend.setColorFilter(Color.parseColor("#8E8E93"))
            isModelLoaded = false

            val success = withContext(Dispatchers.IO) {
                try {
                    var fileName = "custom_model.gguf"
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) {
                                fileName = it.getString(nameIndex)
                            }
                        }
                    }

                    val targetFile = File(filesDir, fileName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    llmManager.initializeModel(fileName)
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            if (success) {
                isModelLoaded = true
                btnSend.isEnabled = true
                btnSend.setColorFilter(Color.parseColor("#000000"))
                tvStatus.text = if (isFrench) "Statut : IA Prête" else "Status: AI Ready"
                Toast.makeText(this@MainActivity, if (isFrench) "Modèle chargé avec succès !" else "Model loaded successfully!", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = if (isFrench) "Statut : Échec du chargement !" else "Status: Failed to load model!"
                Toast.makeText(this@MainActivity, if (isFrench) "Erreur lors du chargement du modèle." else "Error loading model.", Toast.LENGTH_SHORT).show()
            }
            updateLanguageUI()
        }
    }

    private fun showAboutModelDialog() {
        val aboutTitle = if (isFrench) "À propos du modèle IA par défaut" else "About Default AI Model"
        val aboutMsg = if (isFrench) {
            "Modèle par défaut : Gemma 3 270M (INT8 / Q8_0)\n\n" +
                    "• Paramètres : 270 Millions\n" +
                    "• Quantïsation : 8-bit (Q8_0)\n" +
                    "• Accélération : Instructions matérielles ARM NEON\n" +
                    "• Performance : Optimisé pour une exécution ultra-rapide et locale sur appareils à faible RAM\n" +
                    "• Exécution : 100% hors-ligne via llama.cpp (zéro dépendance cloud)\n\n" +
                    "Astuce : Vous pouvez également charger n'importe quel autre modèle .gguf personnalisé depuis le menu."
        } else {
            "Default AI Model: Gemma 3 270M (INT8 / Q8_0)\n\n" +
                    "• Parameters: 270 Million\n" +
                    "• Quantization: 8-bit (Q8_0)\n" +
                    "• Hardware Acceleration: ARM NEON SIMD Instructions\n" +
                    "• Performance: Hand-optimized for low-RAM on-device inference\n" +
                    "• Execution: 100% offline via llama.cpp (zero cloud dependency)\n\n" +
                    "Tip: You can load any custom .gguf model file using the menu."
        }

        AlertDialog.Builder(this)
            .setTitle(aboutTitle)
            .setMessage(aboutMsg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun saveCurrentSession() {
        if (chatMessages.isEmpty()) return
        val prefs = getSharedPreferences("chat_sessions", MODE_PRIVATE)
        val sessionsJson = prefs.getString("sessions_list", "[]") ?: "[]"
        val jsonArray = JSONArray(sessionsJson)

        val title = chatMessages.firstOrNull()?.text?.let {
            if (it.length > 25) it.substring(0, 25) + "..." else it
        } ?: "Chat $currentSessionId"

        val newSessionsArray = JSONArray()
        val currentObj = JSONObject().apply {
            put("id", currentSessionId)
            put("title", title)
            val msgsArray = JSONArray()
            for (msg in chatMessages) {
                msgsArray.put(JSONObject().apply {
                    put("text", msg.text)
                    put("isUser", msg.isUser)
                    put("tokenCount", msg.tokenCount)
                    put("durationMs", msg.durationMs)
                    put("tokensPerSec", msg.tokensPerSec)
                })
            }
            put("messages", msgsArray)
        }

        newSessionsArray.put(currentObj)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.getLong("id") != currentSessionId) {
                newSessionsArray.put(obj)
            }
        }

        prefs.edit().putString("sessions_list", newSessionsArray.toString()).apply()
    }

    private fun loadLatestOrActiveSession() {
        val prefs = getSharedPreferences("chat_sessions", MODE_PRIVATE)
        val sessionsJson = prefs.getString("sessions_list", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(sessionsJson)
            if (jsonArray.length() > 0) {
                val latest = jsonArray.getJSONObject(0)
                loadSession(latest)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startNewChat() {
        saveCurrentSession()
        chatContainer.removeAllViews()
        chatMessages.clear()
        bubbleStats.clear()
        currentSessionId = System.currentTimeMillis()
        val toastMsg = if (isFrench) "Nouvelle conversation !" else "New chat started!"
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun showHistoryDialog() {
        saveCurrentSession()
        val prefs = getSharedPreferences("chat_sessions", MODE_PRIVATE)
        val sessionsJson = prefs.getString("sessions_list", "[]") ?: "[]"
        val jsonArray = JSONArray(sessionsJson)

        if (jsonArray.length() == 0) {
            val msg = if (isFrench) "Aucun historique trouvé." else "No history found."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        val titles = arrayOfNulls<String>(jsonArray.length())
        val sessions = mutableListOf<JSONObject>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            sessions.add(obj)
            titles[i] = obj.getString("title")
        }

        AlertDialog.Builder(this)
            .setTitle(if (isFrench) "Historique des conversations" else "Conversation History")
            .setItems(titles) { _, which ->
                val selectedSession = sessions[which]
                loadSession(selectedSession)
            }
            .setNegativeButton(if (isFrench) "Fermer" else "Close", null)
            .show()
    }

    private fun loadSession(sessionObj: JSONObject) {
        currentSessionId = sessionObj.getLong("id")
        chatContainer.removeAllViews()
        chatMessages.clear()
        bubbleStats.clear()

        val msgsArray = sessionObj.getJSONArray("messages")
        for (i in 0 until msgsArray.length()) {
            val obj = msgsArray.getJSONObject(i)
            val text = obj.getString("text")
            val isUser = obj.getBoolean("isUser")
            val tokenCount = obj.getInt("tokenCount")
            val durationMs = obj.getLong("durationMs")
            val tokensPerSec = obj.getDouble("tokensPerSec")

            val msg = ChatMessage(text, isUser, tokenCount, durationMs, tokensPerSec)
            chatMessages.add(msg)
            val bubble = addBubble(text, isUser, saveToHistory = false)
            if (!isUser && tokenCount > 0) {
                bubbleStats[bubble] = GenStats(tokenCount, durationMs, tokensPerSec)
            }
        }
    }

    private fun clearChatHistory() {
        chatContainer.removeAllViews()
        chatMessages.clear()
        bubbleStats.clear()
        val prefs = getSharedPreferences("chat_sessions", MODE_PRIVATE)
        prefs.edit().clear().apply()
        val toastMsg = if (isFrench) "Historique effacé !" else "Chat cleared!"
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Updates all UI elements based on current language (English vs French).
     */
    private fun updateLanguageUI() {
        btnSend.setColorFilter(if (btnSend.isEnabled) Color.parseColor("#000000") else Color.parseColor("#8E8E93"))
        if (isFrench) {
            etInput.hint = "Tapez votre message..."
            if (isGenerating) {
                tvStatus.text = "Statut : Génération en cours..."
            } else if (isModelLoaded) {
                tvStatus.text = "Statut : IA Prête (Gemma 3 270M)"
            } else {
                tvStatus.text = "Statut : Chargement du modèle en RAM..."
            }
        } else {
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
        btnSend.setColorFilter(Color.parseColor("#8E8E93"))
        isModelLoaded = false
        updateLanguageUI()

        lifecycleScope.launch {
            val success = llmManager.initializeModel("gemma-3-270m-it-Q8_0.gguf")
            if (success) {
                isModelLoaded = true
                btnSend.isEnabled = true
                btnSend.setColorFilter(Color.parseColor("#000000"))
            } else {
                isModelLoaded = false
                tvStatus.text = if (isFrench) "Statut : Échec du chargement du modèle !" else "Status: Failed to load model!"
            }
            updateLanguageUI()
        }
    }

    /**
     * Adds a chat bubble (User or AI) to the chat container with OLED B&W theme and Markdown.
     */
    private fun addBubble(text: String, isUser: Boolean, saveToHistory: Boolean = true): TextView {
        if (saveToHistory && text.isNotEmpty()) {
            chatMessages.add(ChatMessage(text, isUser))
            saveCurrentSession()
        }

        val bubbleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val radius = 16 * resources.displayMetrics.density
            val smallRadius = 4 * resources.displayMetrics.density
            if (isUser) {
                setCornerRadii(floatArrayOf(radius, radius, radius, radius, smallRadius, smallRadius, radius, radius))
                color = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            } else {
                setCornerRadii(floatArrayOf(radius, radius, radius, radius, radius, radius, smallRadius, smallRadius))
                color = android.content.res.ColorStateList.valueOf(Color.parseColor("#1C1C1E"))
            }
        }

        val bubble = TextView(this).apply {
            this.text = MarkdownFormatter.format(text)
            textSize = 14f
            setTextColor(if (isUser) Color.parseColor("#000000") else Color.parseColor("#FFFFFF"))
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

            // Long click to show options (Copy message & See token speed)
            setOnLongClickListener {
                val options = if (isFrench) {
                    arrayOf("Copier le message", "Voir la vitesse de génération (Token Speed)")
                } else {
                    arrayOf("Copy Message", "View Token Speed")
                }

                val title = if (isFrench) "Options du message" else "Message Options"

                AlertDialog.Builder(this@MainActivity)
                    .setTitle(title)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("AI Chat Message", text)
                                clipboard.setPrimaryClip(clip)
                                val toastMsg = if (isFrench) "Texte copié !" else "Text copied!"
                                Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                if (isUser) {
                                    val msg = if (isFrench) "Statistiques non disponibles pour les messages utilisateur." else "Token speed stats not applicable for user prompts."
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                } else {
                                    val stats = bubbleStats[this]
                                    val speedMsg = if (stats != null) {
                                        if (isFrench) {
                                            "⚡ Vitesse de génération :\n- Tokens générés : ${stats.tokenCount}\n- Durée : ${String.format(java.util.Locale.getDefault(), "%.2f", stats.durationMs / 1000.0)} s\n- Vitesse : ${String.format(java.util.Locale.getDefault(), "%.1f", stats.tokensPerSec)} tokens/sec"
                                        } else {
                                            "⚡ Generation Speed:\n- Tokens Generated: ${stats.tokenCount}\n- Duration: ${String.format(java.util.Locale.getDefault(), "%.2f", stats.durationMs / 1000.0)} s\n- Speed: ${String.format(java.util.Locale.getDefault(), "%.1f", stats.tokensPerSec)} tokens/sec"
                                        }
                                    } else {
                                        if (isFrench) "Aucune statistique enregistrée pour ce message." else "No generation stats recorded for this message."
                                    }
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle(if (isFrench) "Vitesse des Tokens" else "Token Speed")
                                        .setMessage(speedMsg)
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                        }
                    }
                    .show()
                true
            }
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
        btnSend.setColorFilter(Color.parseColor("#8E8E93"))
        isGenerating = true
        etInput.text.clear()
        updateLanguageUI()

        addBubble(prompt, true)
        val aiBubble = addBubble("", false)

        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        val fullText = StringBuilder()

        lifecycleScope.launch {
            llmManager.generateResponseStream(prompt) { token ->
                tokenCount++
                fullText.append(token)
                runOnUiThread {
                    aiBubble.text = MarkdownFormatter.format(fullText.toString())
                    scrollToBottom()
                }
            }

            val durationMs = System.currentTimeMillis() - startTime
            val tps = if (durationMs > 0) (tokenCount * 1000.0) / durationMs else 0.0
            bubbleStats[aiBubble] = GenStats(tokenCount, durationMs, tps)

            // Update last message in chat history with stats
            if (chatMessages.isNotEmpty() && !chatMessages.last().isUser) {
                chatMessages[chatMessages.size - 1] = ChatMessage(aiBubble.text.toString(), false, tokenCount, durationMs, tps)
                saveCurrentSession()
            }

            isGenerating = false
            btnSend.isEnabled = true
            btnSend.setColorFilter(Color.parseColor("#000000"))
            updateLanguageUI()
        }
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
