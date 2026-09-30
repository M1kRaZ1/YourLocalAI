#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "YourLocalAI_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_ch_luca_tuff_urlocalai_LLMManager_initNative(JNIEnv *env, jobject thiz, jstring model_path_str) {
    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);

    // Initialisation llama.cpp sans mmap (chargement direct en RAM pour 1 Go de RAM)
    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_NONE; // En RAM pure, sans memory mapping

    g_model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(model_path_str, model_path);

    if (!g_model) {
        LOGE("Échec du chargement du modèle depuis %s", model_path);
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 256;   // Limite le KV cache à 256 tokens pour économiser la RAM
    cparams.n_batch = 256; // Taille maximale du batch

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Échec de création du contexte llama.cpp");
        return JNI_FALSE;
    }

    LOGI("Modèle Gemma 3 initialisé avec succès en RAM (sans mmap).");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_ch_luca_tuff_urlocalai_LLMManager_generateStreamNative(JNIEnv *env, jobject thiz, jstring prompt_str, jobject callback) {
    if (!g_ctx || !g_model) return;

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    if (!onTokenMethod) return;

    const char *raw_prompt = env->GetStringUTFChars(prompt_str, nullptr);
    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);

    // Formatage avec le template de chat natif du modèle
    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    llama_chat_message msg{"user", raw_prompt};

    std::vector<char> formatted(1024);
    int new_len = llama_chat_apply_template(tmpl, &msg, 1, true, formatted.data(), formatted.size());
    if (new_len > (int)formatted.size()) {
        formatted.resize(new_len);
        new_len = llama_chat_apply_template(tmpl, &msg, 1, true, formatted.data(), formatted.size());
    }

    std::string prompt_formatted;
    if (new_len > 0) {
        prompt_formatted = std::string(formatted.data(), new_len);
    } else {
        prompt_formatted = "<start_of_turn>user\n" + std::string(raw_prompt) + "<end_of_turn>\n<start_of_turn>model\n";
    }

    env->ReleaseStringUTFChars(prompt_str, raw_prompt);

    // Réinitialiser le KV Cache pour la nouvelle question
    llama_memory_clear(llama_get_memory(g_ctx), true);

    // Tokenisation du prompt
    const bool is_first = llama_memory_seq_pos_max(llama_get_memory(g_ctx), 0) == -1;
    int n_prompt_tokens = -llama_tokenize(vocab, prompt_formatted.c_str(), prompt_formatted.size(), nullptr, 0, is_first, true);
    if (n_prompt_tokens <= 0) n_prompt_tokens = 128;

    std::vector<llama_token> prompt_tokens(n_prompt_tokens);
    int token_count = llama_tokenize(vocab, prompt_formatted.c_str(), prompt_formatted.size(), prompt_tokens.data(), prompt_tokens.size(), is_first, true);
    if (token_count < 0) {
        LOGE("Échec de la tokenisation du prompt");
        return;
    }
    prompt_tokens.resize(token_count);

    // Sampler avec température et distribution
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    // Batch initial avec llama_batch_get_one
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    std::string utf8_buf;
    int max_tokens = 128;
    int generated_count = 0;

    while (generated_count < max_tokens) {
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Échec de llama_decode pendant l'inférence");
            break;
        }

        llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);

        // 1. ARRÊT PAR ID DE TOKEN : EOS, EOG ou token d'arrêt natif
        if (new_token_id == llama_vocab_eos(vocab) || llama_vocab_is_eog(vocab, new_token_id)) {
            LOGI("Jeton d'arrêt natif (EOS/EOG) détecté. Arrêt immédiat.");
            break;
        }

        // Ignorer les jetons de contrôle système
        if (llama_vocab_is_control(vocab, new_token_id)) {
            batch = llama_batch_get_one(&new_token_id, 1);
            generated_count++;
            continue;
        }

        char piece_buf[256] = {0};
        int n_chars = llama_token_to_piece(vocab, new_token_id, piece_buf, sizeof(piece_buf), 0, false);
        if (n_chars > 0) {
            std::string piece_str(piece_buf, n_chars);

            // 2. FILTRE SÉCURITÉ TEXTUELLE : Intercepter les balises système ChatML / Gemma / Qwen
            if (piece_str.find("<|im_") != std::string::npos ||
                piece_str.find("<|im_start|>") != std::string::npos ||
                piece_str.find("<|im_end|>") != std::string::npos ||
                piece_str.find("<|EOT|>") != std::string::npos ||
                piece_str.find("<end") != std::string::npos ||
                piece_str.find("<end_of_turn>") != std::string::npos ||
                piece_str.find("<eos>") != std::string::npos ||
                piece_str.find("<start_of_turn>") != std::string::npos) {
                LOGI("Balise système détectée dans le texte (%s). Arrêt de la génération.", piece_str.c_str());
                break;
            }

            utf8_buf.append(piece_str);

            // 3. NETTOYAGE RÉSIDUEL DES BALISES DANS LE BUFFER
            auto filter_tag = [&](const std::string & tag) {
                size_t pos = 0;
                while ((pos = utf8_buf.find(tag, pos)) != std::string::npos) {
                    utf8_buf.erase(pos, tag.length());
                }
            };

            filter_tag("<start_of_turn>");
            filter_tag("<end_of_turn>");
            filter_tag("<eos>");
            filter_tag("<pad>");
            filter_tag("<|im_start|>");
            filter_tag("<|im_end|>");
            filter_tag("<|EOT|>");

            // Validation UTF-8 avant émission vers Kotlin
            size_t valid_len = 0;
            size_t i = 0;
            while (i < utf8_buf.length()) {
                unsigned char c = static_cast<unsigned char>(utf8_buf[i]);
                size_t char_len = 1;
                if (c >= 0xF0) char_len = 4;
                else if (c >= 0xE0) char_len = 3;
                else if (c >= 0xC0) char_len = 2;

                if (i + char_len <= utf8_buf.length()) {
                    i += char_len;
                    valid_len = i;
                } else {
                    break;
                }
            }

            if (valid_len > 0) {
                std::string valid_utf8_str = utf8_buf.substr(0, valid_len);
                if (!valid_utf8_str.empty()) {
                    jstring jtoken = env->NewStringUTF(valid_utf8_str.c_str());
                    if (jtoken) {
                        env->CallVoidMethod(callback, onTokenMethod, jtoken);
                        env->DeleteLocalRef(jtoken);
                    }
                }
                utf8_buf.erase(0, valid_len);
            }
        }

        // Token suivant via llama_batch_get_one
        batch = llama_batch_get_one(&new_token_id, 1);
        generated_count++;
    }

    // Nettoyage final du buffer
    if (!utf8_buf.empty()) {
        auto filter_tag = [&](const std::string & tag) {
            size_t pos = 0;
            while ((pos = utf8_buf.find(tag, pos)) != std::string::npos) {
                utf8_buf.erase(pos, tag.length());
            }
        };
        filter_tag("<start_of_turn>");
        filter_tag("<end_of_turn>");
        filter_tag("<eos>");
        filter_tag("<pad>");
        filter_tag("<|im_start|>");
        filter_tag("<|im_end|>");
        filter_tag("<|EOT|>");

        if (!utf8_buf.empty()) {
            jstring jtoken = env->NewStringUTF(utf8_buf.c_str());
            if (jtoken) {
                env->CallVoidMethod(callback, onTokenMethod, jtoken);
                env->DeleteLocalRef(jtoken);
            }
        }
    }

    llama_sampler_free(smpl);
}
