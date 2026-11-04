# Hợp đồng JaecooApp ↔ JaecooVoice

JaecooApp và JaecooVoice là **hai app riêng**, mỗi app một repo. JaecooVoice giữ micro và nhận dạng giọng nói (sherpa-onnx, model); JaecooApp giữ mọi thứ còn lại, kể cả phần hiểu câu và chạy lệnh. Hai app chỉ nói chuyện qua Binder (AIDL), không dùng chung thư viện hay module Gradle. Tài liệu này có ở cả hai repo với nội dung giống nhau; đổi hợp đồng thì sửa cả hai bên cùng lúc.

Thiết kế: `docs/superpowers/specs/2026-10-30-voice-apk-split-design.md` (repo JaecooApp).

## Quy tắc

- Không bên nào import code của bên kia. Hai bên chỉ chép **file `.aidl`** (giống hệt từng byte, cùng package `com.example.jaecoovoice.engine`) và các hằng số dưới đây.
- Giá trị qua kênh: `String`, `int`, `long`, `float`, `boolean`, `Bundle`, `List` của chúng, và interface callback.

## Package, service, quyền

| | JaecooApp | JaecooVoice |
|---|---|---|
| package | `com.example.jaecooapp` | `com.example.jaecoovoice` |
| service cho JaecooApp | — | `com.example.jaecoovoice.VoiceEngineService`, action `com.example.jaecoovoice.action.BIND_ENGINE` |
| service cho app khác | — | `com.example.jaecoovoice.JaecooRecognitionService` (`android.speech.RecognitionService`) |
| service TTS cho app khác | — | `com.example.jaecoovoice.JaecooTtsService` (`android.speech.tts.TextToSpeechService`) |
| activity cho app khác | — | `com.example.jaecoovoice.RecognizeSpeechActivity` (`android.speech.action.RECOGNIZE_SPEECH`) |
| quyền | xin `com.example.jaecoovoice.permission.ENGINE` | bảo vệ `VoiceEngineService` |

- Quyền `protectionLevel="signature"`, **cả hai app đều khai báo**, để cài app nào trước cũng được.
- Hai APK ký cùng một khoá: khoá của JaecooApp, SHA-256 chứng chỉ `139f1d19f55e5f9b6cb5bd510df1e1266128463e41da59ef4540295178dab85a`.
- Mọi hàm của `VoiceEngineService` kiểm tra package của `Binder.getCallingUid()` phải là `com.example.jaecooapp`, sai thì ném `SecurityException`.
- Mỗi app khai báo package của app kia trong `<queries>`.
- JaecooApp bind bằng `BIND_AUTO_CREATE | BIND_INCLUDE_CAPABILITIES`. Dịch vụ nền trước loại `microphone` của JaecooApp nhờ đó truyền quyền dùng mic sang tiến trình JaecooVoice.

## AIDL

```aidl
interface IVoiceEngine {
    int apiVersion();                                        // 2
    List<String> languages();                                // mã ngôn ngữ có trong danh mục model, vd ["vi"]
    Bundle modelStatus(String language);                     // chặn luồng, nhanh (chỉ đọc đĩa)
    void downloadModel(String language, IModelCallback cb);  // trả về ngay
    void deleteModel(String language);
    void listen(String language, IListenCallback cb);        // trả về ngay: một lượt nghe
    void cancel();                                           // huỷ lượt đang chạy, nếu có

    // TTS:
    Bundle ttsModelStatus(String language);
    void downloadTtsModel(String language, IModelCallback cb);
    void deleteTtsModel(String language);
    void speak(String text, String language, ITtsCallback cb);
    void stopSpeaking();
    boolean isSpeaking();
}

oneway interface IListenCallback {
    void onState(int state);          // 0 đang nghe, 1 đang nhận dạng
    void onLevel(float rms);          // 0–1, mỗi khung 100 ms
    void onResult(String text);       // đã trim, không rỗng
    void onError(int code, String detail);
}

oneway interface IModelCallback {
    void onProgress(int percent);     // 0–100
    void onDone();
    void onError(String message);
}

oneway interface ITtsCallback {
    void onStart();
    void onDone();
    void onError(String message);
}
```

**Mã ngôn ngữ** theo BCP-47, chỉ phần ngôn ngữ: `vi`, `en`, `zh`…

**`modelStatus(language)`** trả về một `Bundle`:

| key | kiểu | nghĩa |
|---|---|---|
| `language` | String | mã ngôn ngữ đã hỏi |
| `supported` | boolean | danh mục có model cho ngôn ngữ này |
| `installed` | boolean | model đã tải và giải nén đủ file |
| `downloading` | boolean | đang tải |
| `label` | String | tên model, vd "Zipformer tiếng Việt 30M" |
| `sizeBytes` | long | dung lượng gói tải về |
| `loadError` | String | chỉ có khi model đã cài mà lần nạp gần nhất lỗi |

`label` và `sizeBytes` chỉ có khi `supported` là true.

**`ttsModelStatus(language)`** trả về một `Bundle`:

| key | kiểu | nghĩa |
|---|---|---|
| `tts_installed` | boolean | model TTS đã tải và giải nén đủ file |
| `tts_downloading` | boolean | đang tải model TTS |
| `tts_label` | String | tên model TTS |

**Mỗi lượt `listen` kết thúc bằng đúng một `onResult` hoặc `onError`.** Mã lỗi:

| code | tên | nghĩa | `detail` |
|---|---|---|---|
| 1 | `NO_SPEECH` | không nghe thấy tiếng nói | `source=<nguồn> peak=<mức>` |
| 2 | `CANCELLED` | đã huỷ | rỗng |
| 3 | `MODEL_MISSING` | chưa tải model cho ngôn ngữ này | rỗng |
| 4 | `MODEL_LOAD_FAILED` | model có nhưng nạp lỗi | lớp lỗi và thông báo |
| 5 | `NO_MIC_PERMISSION` | JaecooVoice chưa có `RECORD_AUDIO` | rỗng |
| 6 | `MIC_UNAVAILABLE` | không mở được mic, hoặc mic đang bị app khác giữ | lý do |
| 7 | `MIC_SILENT` | mic chỉ trả số 0 | `source=<nguồn> peak=<mức>` |
| 8 | `RECOGNIZE_FAILED` | lỗi khi nhận dạng | lý do |
| 9 | `BUSY` | đang có lượt khác (có thể từ app khác) | rỗng |
| 10 | `LANGUAGE_UNSUPPORTED` | danh mục không có model cho ngôn ngữ này | rỗng |

`detail` dùng để ghi log, không hiện cho tài xế. JaecooApp tự viết câu báo lỗi và ghi `AppLog.w` cho mọi lỗi trừ `CANCELLED`, nên lỗi của JaecooVoice cũng lên log server.

## Quy tắc chạy

- Cùng lúc chỉ có **một lượt nghe** trên toàn JaecooVoice, dù đến từ `IVoiceEngine`, `JaecooRecognitionService` hay `RecognizeSpeechActivity`. Ai đến trước được, đến sau nhận `BUSY`.
- **Xoay nguồn mic** (`VOICE_RECOGNITION` → `MIC` → `VOICE_COMMUNICATION` → `CAMCORDER`) nằm trong JaecooVoice. Gặp `NO_SPEECH` hay `MIC_SILENT` mà mức đỉnh dưới 0,002 thì lượt sau thử nguồn kế tiếp.
- Engine nạp lười, chỉ giữ engine của một ngôn ngữ, thả sau 5 phút không dùng.
- **Danh mục model nằm cứng trong JaecooVoice.** Thêm ngôn ngữ thì phát hành bản JaecooVoice mới, không đổi hợp đồng.
- **TTS (Text-To-Speech):**
  - `speak(text, language, cb)`: tổng hợp và phát âm thanh phản hồi qua AudioTrack offline bằng model VITS/Piper của sherpa-onnx. Khi bắt đầu phát, engine gọi `cb.onStart()`, phát xong gọi `cb.onDone()`. Nếu lỗi gọi `cb.onError(message)`.
  - Audio Focus: Khi phát âm, engine xin quyền `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` stream `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` để hạ âm lượng nhạc nền xe hơi; phát xong hoặc bị dừng thì nhả Audio Focus.
  - `stopSpeaking()`: ngắt lời phát âm lập tức (< 20ms) và nhả Audio Focus.
  - `isSpeaking()`: trả về true nếu đang trong quá trình phát âm.
- **Đổi phiên bản:** `apiVersion()` hiện là `2`. Khi thêm hàm hay đổi nghĩa thì tăng số, bên gọi kiểm tra trước khi dùng. JaecooApp gặp bản thấp hơn bản nó cần thì coi như JaecooVoice quá cũ và đề nghị cập nhật.

## App khác

- `JaecooRecognitionService` đổi mã lỗi sang `SpeechRecognizer.ERROR_*`:

  | Mã của hợp đồng | Mã của Android |
  |---|---|
  | `NO_SPEECH` | `ERROR_SPEECH_TIMEOUT` |
  | `MIC_UNAVAILABLE`, `MIC_SILENT` | `ERROR_AUDIO` |
  | `NO_MIC_PERMISSION` | `ERROR_INSUFFICIENT_PERMISSIONS` |
  | `BUSY` | `ERROR_RECOGNIZER_BUSY` |
  | `MODEL_MISSING`, `MODEL_LOAD_FAILED`, `RECOGNIZE_FAILED`, `LANGUAGE_UNSUPPORTED` | `ERROR_SERVER` |
  | `CANCELLED` | không gọi callback nào |

  Không có kết quả từng phần. `rmsChanged` tính bằng dB, kẹp trong −2..10.
- **Chọn ngôn ngữ:** lấy `EXTRA_LANGUAGE`, không có thì lấy ngôn ngữ máy. Chưa có model cho ngôn ngữ đó thì dùng model đã cài; không cài model nào thì báo lỗi.
- JaecooApp ghi `Settings.Secure.voice_recognition_service = com.example.jaecoovoice/.JaecooRecognitionService` và `Settings.Secure.tts_default_synth = com.example.jaecoovoice` (`Settings.Secure.TTS_DEFAULT_SYNTH`) khi ô đang trống hoặc trỏ tới app đã gỡ, nếu JaecooApp có `WRITE_SECURE_SETTINGS`.

## Phát hành

JaecooVoice không có trong `apps.json`. JaecooApp cài và cập nhật nó từ server của chính mình:

- `<server>/api/voice.json`, cùng dạng với `/api/version.json`. Gồm `versionCode`, `versionName`, `packageName`, `releaseDate`, `apkUrl` (mặc định `/apks/jaecoo-voice.apk`), `fileSize`, và `sha256` do server tự tính lúc build.
- Hai kênh như OTA: `main` ở `https://japp.cloud/`, `beta` ở `https://japp.cloud/beta/`.
- JaecooApp kiểm sha256, rồi kiểm package và chứng chỉ ký trùng với của chính mình, trước khi cài.
