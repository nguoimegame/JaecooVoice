// JNI for EspeakNative: text -> espeak-ng IPA clauses, the same loop as phonemize_eSpeak in
// piper-phonemize (src/phonemize.cpp). Joining clauses into sentences, punctuation, language
// flags and NFD are done in Kotlin (PiperPhonemes). espeak-ng keeps global state: the Kotlin side
// serialises every call.
#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include <espeak-ng/speak_lib.h>

namespace {

constexpr const char *kTag = "EspeakNative";
constexpr int kPhonemesIpa = 0x02;

std::string ToUtf8(JNIEnv *env, jstring s) {
  if (s == nullptr) return {};
  const char *chars = env->GetStringUTFChars(s, nullptr);
  std::string out = chars ? chars : "";
  if (chars) env->ReleaseStringUTFChars(s, chars);
  return out;
}

// NewStringUTF takes modified UTF-8, which breaks on 4-byte sequences; build from UTF-16 instead.
jstring FromUtf8(JNIEnv *env, const std::string &s) {
  std::u16string out;
  out.reserve(s.size());
  size_t i = 0;
  while (i < s.size()) {
    auto c = static_cast<unsigned char>(s[i]);
    char32_t cp;
    size_t len;
    if (c < 0x80) {
      cp = c;
      len = 1;
    } else if ((c >> 5) == 0x6) {
      cp = c & 0x1F;
      len = 2;
    } else if ((c >> 4) == 0xE) {
      cp = c & 0x0F;
      len = 3;
    } else if ((c >> 3) == 0x1E) {
      cp = c & 0x07;
      len = 4;
    } else {
      cp = 0xFFFD;
      len = 1;
    }
    if (i + len > s.size()) {
      cp = 0xFFFD;
      len = s.size() - i;
    } else {
      for (size_t k = 1; k < len; ++k) cp = (cp << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
    }
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
      out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    } else {
      out.push_back(static_cast<char16_t>(cp));
    }
    i += len;
  }
  return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_example_jaecoovoice_engine_EspeakNative_nativeInit(JNIEnv *env, jobject, jstring dataDir) {
  std::string path = ToUtf8(env, dataDir);
  int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path.c_str(), 0);
  if (rate <= 0) __android_log_print(ANDROID_LOG_WARN, kTag, "espeak_Initialize(%s) = %d", path.c_str(), rate);
  return rate;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_jaecoovoice_engine_EspeakNative_nativeClauses(JNIEnv *env, jobject, jstring text, jstring voice) {
  jclass stringClass = env->FindClass("java/lang/String");
  std::string voiceName = ToUtf8(env, voice);
  if (espeak_SetVoiceByName(voiceName.c_str()) != EE_OK) {
    return env->NewObjectArray(0, stringClass, nullptr);
  }
  // espeak-ng moves the pointer through the text; keep our own copy alive for the whole loop.
  std::string input = ToUtf8(env, text);
  const char *pointer = input.c_str();
  std::vector<std::string> clauses;
  while (pointer != nullptr) {
    int terminator = 0;
    const char *phonemes = espeak_TextToPhonemesWithTerminator(
        reinterpret_cast<const void **>(&pointer), espeakCHARS_AUTO, kPhonemesIpa, &terminator);
    clauses.push_back(std::to_string(terminator) + "|" + (phonemes ? phonemes : ""));
  }
  jobjectArray out = env->NewObjectArray(static_cast<jsize>(clauses.size()), stringClass, nullptr);
  for (size_t i = 0; i < clauses.size(); ++i) {
    jstring s = FromUtf8(env, clauses[i]);
    env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_jaecoovoice_engine_EspeakNative_nativeTerminate(JNIEnv *, jobject) {
  espeak_Terminate();
}
