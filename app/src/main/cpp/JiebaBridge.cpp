#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "cppjieba/Jieba.hpp"

namespace {
std::mutex g_mutex;
std::unique_ptr<cppjieba::Jieba> g_jieba;

std::string toUtf8(JNIEnv* env, jstring value) {
  if (value == nullptr) return "";
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars == nullptr ? "" : chars);
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return result;
}

void logError(const char* message) {
  __android_log_print(ANDROID_LOG_ERROR, "WeikeJieba", "%s", message);
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_weike_ime_text_JiebaNative_nativeInitialize(
    JNIEnv* env, jobject /* thiz */, jstring dict_dir, jstring user_dict) {
  std::lock_guard<std::mutex> lock(g_mutex);
  try {
    const std::string root = toUtf8(env, dict_dir);
    const std::string user = toUtf8(env, user_dict);
    g_jieba = std::make_unique<cppjieba::Jieba>(
        root + "/jieba.dict.utf8",
        root + "/hmm_model.utf8",
        user,
        root + "/idf.utf8",
        root + "/stop_words.utf8");
    return JNI_TRUE;
  } catch (const std::exception& error) {
    logError(error.what());
  } catch (...) {
    logError("Unable to initialize Jieba");
  }
  g_jieba.reset();
  return JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_weike_ime_text_JiebaNative_nativeSegment(
    JNIEnv* env, jobject /* thiz */, jstring text, jboolean search_mode) {
  std::lock_guard<std::mutex> lock(g_mutex);
  jclass string_class = env->FindClass("java/lang/String");
  if (string_class == nullptr) return nullptr;
  if (!g_jieba) return env->NewObjectArray(0, string_class, nullptr);
  try {
    std::vector<std::string> words;
    const std::string source = toUtf8(env, text);
    if (search_mode == JNI_TRUE) {
      g_jieba->CutForSearch(source, words, true);
    } else {
      g_jieba->Cut(source, words, true);
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(words.size()), string_class, nullptr);
    for (size_t index = 0; index < words.size(); ++index) {
      jstring word = env->NewStringUTF(words[index].c_str());
      env->SetObjectArrayElement(result, static_cast<jsize>(index), word);
      env->DeleteLocalRef(word);
    }
    return result;
  } catch (const std::exception& error) {
    logError(error.what());
    return env->NewObjectArray(0, string_class, nullptr);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_weike_ime_text_JiebaNative_nativeRelease(
    JNIEnv* /* env */, jobject /* thiz */) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_jieba.reset();
}
