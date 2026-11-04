# JaecooVoice

App Android không có icon, làm bộ nhận dạng giọng nói offline (sherpa-onnx) cho JaecooApp trên head unit Jaecoo J7, và cho các app khác qua `RecognitionService` / `RECOGNIZE_SPEECH`. Repo JaecooApp nằm cạnh repo này (`../JaecooApp`).

- **Ranh giới:** app này giữ micro, model và nhận dạng; trả về chữ, không biết lệnh là gì. Setting, UI, hiểu lệnh nằm ở JaecooApp. Hai app chỉ nói chuyện theo [docs/jaecoovoice-contract.md](docs/jaecoovoice-contract.md). Không import code của JaecooApp; chỉ chép file `.aidl` (giống hệt từng byte) và hằng số. Đổi hợp đồng thì sửa cả hai repo.
- Code nhận dạng ở `engine/` (`EngineCore` là lõi, mỗi lúc một lượt nghe); service và activity ở package gốc `com.example.jaecoovoice`. Không đổi tên class khai báo trong manifest: JaecooApp và `voice_recognition_service` trỏ tới chúng theo tên.
- **Khoá ký:** dùng khoá của JaecooApp (`../JaecooApp/app/debug.keystore`). Không đổi khoá: quyền signature của hợp đồng và bước JaecooApp kiểm chữ ký trước khi cài đều dựa vào nó. **Không bao giờ commit khoá vào repo này** (repo public). Gradle tìm khoá theo `-PjaecooKeystore`, env `JAECOO_KEYSTORE`, `jaecoo.keystore` trong `local.properties`, rồi repo JaecooApp cạnh bên; CI giải mã từ secret `JAECOO_KEYSTORE_B64`.
- **Giấy phép GPL-3.0, repo public** (vì APK link espeak-ng, GPL-3.0). Mỗi APK phát hành phải có tag source tương ứng trên GitHub. Thư viện bên thứ ba mới ghi vào `NOTICE`, và phải tương thích GPL-3.0.
- Không thêm activity `LAUNCHER`.
- Chuỗi người dùng nhìn thấy có đủ 13 file `strings.xml` (cùng tập locale với JaecooApp). Câu báo lỗi cho tài xế do JaecooApp viết; `detail` ở đây chỉ để ghi log.
- Build và kiểm: `./gradlew check assembleDebug`. `ktlintCheck` và `detekt` chạy trong `check`, baseline rỗng, không thêm gì vào baseline.
- Phát hành: tag `vX.Y.Z` chạy `.github/workflows/voice-release.yml`, đẩy APK lên server japp.cloud qua repo JaecooApp (cần secret `JAECOOAPP_TOKEN`).
