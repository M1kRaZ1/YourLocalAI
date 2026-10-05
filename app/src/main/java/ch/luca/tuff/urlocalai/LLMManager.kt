package ch.luca.tuff.urlocalai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Moteur de gestion de l'IA locale (llama.cpp) optimisé pour faible RAM.
 */
class LLMManager(private val context: Context) {

    /**
     * Interface de rappel (callback) pour recevoir les tokens un par un.
     */
    interface TokenCallback {
        fun onToken(token: String)
    }

    companion object {
        init {
            // Charger la bibliothèque C++ compilée via NDK
            System.loadLibrary("native-lib")
        }
    }

    private external fun initNative(path: String): Boolean
    private external fun freeNative()
    private external fun generateStreamNative(prompt: String, callback: TokenCallback)

    /**
     * Copie le modèle .gguf depuis le dossier assets vers le stockage interne privé (filesDir)
     * s'il n'existe pas encore, puis initialise llama.cpp en C++ hors du Thread UI.
     */
    suspend fun initializeModel(assetFileName: String): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(context.filesDir, assetFileName)

        // Copie des assets vers le stockage interne si nécessaire
        if (!targetFile.exists() || targetFile.length() == 0L) {
            context.assets.open(assetFileName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        // Initialisation C++ avec use_mmap = false pour préserver la RAM
        initNative(targetFile.absolutePath)
    }

    /**
     * Décharge complètement le modèle de la RAM pour libérer ~278 Mo (lors du passage à l'écran Math).
     */
    suspend fun freeModel() = withContext(Dispatchers.IO) {
        freeNative()
    }

    /**
     * Exécute l'inférence et transmet les fragments de texte au fil de l'eau (streaming).
     */
    suspend fun generateResponseStream(prompt: String, onToken: (String) -> Unit) = withContext(Dispatchers.IO) {
        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                onToken(token)
            }
        }
        generateStreamNative(prompt, callback)
    }
}
