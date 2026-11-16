# Hợp đồng nhận log: app đồng hành → JaecooApp

JaecooVoice và jstore không có khoá server, không gọi mạng để gửi log và không giữ hàng đợi trên đĩa. Chúng **đẩy** cảnh báo, lỗi và crash vào cửa nhận log của JaecooApp; từ đó mọi thứ (che dữ liệu, hàng đợi, gửi, hạn mức) đi chung đường với log của JaecooApp. Xem [remote-logging.md](remote-logging.md).

File này giống hệt nhau trong ba repo (JaecooApp, JaecooVoice, JStore); đổi hợp đồng thì sửa cả ba. Các hợp đồng tính năng ([jaecoovoice-contract.md](jaecoovoice-contract.md), [jstore-contract.md](jstore-contract.md)) không đổi vì log.

## Cửa nhận

- ContentProvider `com.example.jaecooapp.LogSinkProvider`, authority `com.example.jaecooapp.logsink`, `exported="true"`.
- Gọi: `ContentResolver.call(Uri.parse("content://com.example.jaecooapp.logsink"), "log", "1", extras)`. `arg` là phiên bản hợp đồng (`"1"`).
- Cuộc gọi tự khởi động process JaecooApp nếu nó đang không chạy.
- Người gọi được kiểm bằng uid, như `NavInfoProvider`: uid có package `com.example.jaecoovoice` (JaecooApp ghi là `jaecoovoice`), `com.example.jstore` (`jstore`) hoặc `com.example.jaecooassistant` (`jaecooassistant`). Người khác nhận `accepted = 0`, mỗi uid được ghi một dòng `W` tag `LogSink`.

## Dữ liệu

`extras` (Bundle):

| Khoá | Kiểu | Ý nghĩa |
|---|---|---|
| `version` | String | `versionName` của app gọi |
| `versionCode` | Long | `versionCode` của app gọi |
| `run` | String | 8 ký tự hex, mỗi process một mã mới |
| `events` | `ArrayList<Bundle>` | tối đa 20 sự kiện mỗi lần gọi |

Mỗi sự kiện (Bundle):

| Khoá | Kiểu | Ý nghĩa |
|---|---|---|
| `ts` | Long | `System.currentTimeMillis()` |
| `up` | Long | `SystemClock.elapsedRealtime()` |
| `level` | String | `I`, `W`, `E` hoặc `F` |
| `tag` | String | ≤ 64 ký tự |
| `msg` | String | ≤ 4096 ký tự |
| `stack` | String, tuỳ chọn | ≤ 16384 ký tự |
| `trail` | String, tuỳ chọn | ≤ 4096 ký tự |
| `run`, `version`, `versionCode` | tuỳ chọn | ghi đè ngữ cảnh của lô, cho sự kiện về **lần chạy trước** (ví dụ báo cáo process chết) |

JaecooApp cắt các trường dài, bỏ sự kiện sai kiểu hoặc thiếu trường bắt buộc (và chỉ bỏ sự kiện đó, lấy phần còn lại), bỏ cả lô nếu không có `run`. `boot` do JaecooApp điền (cùng máy nên cùng số).

Trả về Bundle: `accepted` (Int, số sự kiện đã nhận) và `contract` (Int, phiên bản hợp đồng JaecooApp hiểu). Người gọi lạ, `method` lạ hay `arg` khác `"1"`: `accepted = 0` hoặc `null`; không bao giờ ném exception.

## Quy tắc cho app gọi

- Gọi trên một thread gửi riêng, không bao giờ trên thread chính hay thread âm thanh; riêng crash handler gửi đồng bộ.
- Giữ tối đa 200 sự kiện chờ gửi trong bộ nhớ; đầy thì bỏ dòng cũ nhất và gửi kèm một dòng `W` tag `LogSink` `"<n> lines dropped"`.
- JaecooApp chưa cài, quá cũ hay không trả lời: giữ sự kiện chờ và thử lại ở lần sau; **không** tự ghi log về việc đó (sẽ thành vòng lặp).
- Không log nội dung câu nói, hotwords, token. JaecooApp vẫn che URL query, mật khẩu và toạ độ khi nhận.
- Khai báo `<queries><package android:name="com.example.jaecooapp" /></queries>` để thấy provider.

## Đổi phiên bản

Thêm trường tuỳ chọn thì giữ `VERSION = 1`; JaecooApp bỏ qua khoá lạ. Đổi nghĩa trường hoặc bỏ trường thì tăng `VERSION`; JaecooApp nhận cả bản cũ trong ít nhất một chu kỳ phát hành, và app gọi đọc `contract` trong câu trả lời để biết JaecooApp hiểu bản nào.
