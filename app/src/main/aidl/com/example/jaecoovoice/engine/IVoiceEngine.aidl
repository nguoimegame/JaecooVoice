// Contract with JaecooApp: docs/jaecoovoice-contract.md. Keep byte-identical in both repos.
package com.example.jaecoovoice.engine;

import android.os.Bundle;
import com.example.jaecoovoice.engine.IListenCallback;
import com.example.jaecoovoice.engine.IModelCallback;
import com.example.jaecoovoice.engine.ITtsCallback;

interface IVoiceEngine {
    int apiVersion();
    List<String> languages();
    Bundle modelStatus(String language);
    void downloadModel(String language, IModelCallback cb);
    void deleteModel(String language);
    void listen(String language, IListenCallback cb);
    void cancel();

    // Mới cho TTS:
    Bundle ttsModelStatus(String language);
    void downloadTtsModel(String language, IModelCallback cb);
    void deleteTtsModel(String language);
    void speak(String text, String language, ITtsCallback cb);
    void stopSpeaking();
    boolean isSpeaking();

    // Từ API 3:
    void warmUp(String listenLanguage, String speakLanguage);   // trả về ngay; chuỗi rỗng = bỏ qua phần đó
}
