# Build và phát hành

## Cấu hình dự án

- Namespace/application ID: `com.oai.geminilivetranslate`
- Min SDK: 26
- Target/Compile SDK: 35
- JVM target: 17
- Gradle: 8.10.2
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.0.21

Có thể đổi `applicationId`, `namespace`, tên package Kotlin và FileProvider authority trước khi phát hành dưới thương hiệu riêng.

## Debug APK

```bash
./gradlew clean assembleDebug
```

Kết quả:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release APK/AAB

Không đưa keystore ký phát hành vào repository hoặc ZIP chia sẻ.

Tạo keystore riêng, sau đó cấu hình signing bằng biến môi trường hoặc file `keystore.properties` nằm ngoài Git. Build:

```bash
./gradlew assembleRelease
./gradlew bundleRelease
```

## Checklist thiết bị thật

1. Android 8/9: tệp và microphone.
2. Android 10+: Playback Capture với ứng dụng cho phép capture.
3. Android 13+: quyền thông báo.
4. Android 14/15: Foreground Service microphone/mediaProjection.
5. Tai nghe Bluetooth và loa ngoài.
6. Tệp AAC, MP3, Opus/WebM và video MP4 có track audio.
7. Mất Wi-Fi giữa phiên và phục hồi bằng session handle.
8. Chạy phiên đủ lâu để nhận GoAway và xác nhận socket mới tiếp tục dịch.
9. Giới hạn mạng để kiểm tra app queue, WebSocket queue và số chunk realtime bị bỏ.
10. Pause/resume microphone, xác nhận không phát lại âm thanh cũ.
11. Dừng MediaProjection từ system chip và khóa màn hình, xác nhận service/UI dừng đúng.
12. API 401/403/404/429.
13. Xoay màn hình và chuyển giữa Main/Mini Browser.
14. Xuất SRT/TXT và mở lại tệp WAV đã lưu.
15. Tệp 44,1/48 kHz với output chunk lẻ; so thời lượng PCM đầu ra để phát hiện drift.

## Bảo mật phát hành

Đối với bản công khai, không nên yêu cầu người dùng nhập key dùng chung của nhà phát hành. Thiết kế khuyến nghị:

```text
Android app -> backend của bạn -> ephemeral Gemini token
Android app -> Gemini Live bằng token ngắn hạn
```

Backend cần xác thực người dùng, giới hạn quota và không log secret/token.

## Checklist Nhật ký và Cài đặt

1. Mở app, tạo log từ Main, Settings và một phiên dịch; xác nhận tất cả xuất hiện trong cùng Log Viewer.
2. Chuyển mức log từ Thông thường sang Chỉ lỗi và xác nhận entry info mới không được lưu.
3. Bật/tắt ghi file, khởi động lại app và xác nhận file log được giữ hoặc dừng đúng lựa chọn.
4. Thêm một API Key thử nghiệm, gây lỗi có URL chứa `key=...`, tạo bundle và xác nhận secret đã được che.
5. Xác nhận transcript không xuất hiện khi `logIncludeTranscript=false`.
6. Bật transcript tạm thời, tái hiện một câu, xác nhận chỉ tag `GeminiInput` chứa nội dung rồi tắt lại.
7. Tạo đủ dữ liệu để rotation; xác nhận không vượt quá current + 4 file lịch sử.
8. Dùng lọc mức, tag, tìm kiếm, sao chép, xóa và chia sẻ ZIP.
9. Đổi model/ngôn ngữ giữa phiên; xác nhận log cho reconnect và setup generation mới.
10. Đổi `pacingMaxBuffer` giữa phiên; xác nhận summary báo áp dụng từ phiên sau và queue active không đổi.
11. Đổi buffer phát; xác nhận player được tạo lại và service không bị dừng.
12. Xoay SettingsActivity khi có draft chưa lưu; xác nhận giá trị vẫn còn.
13. Thử từng nút reset và xác nhận chỉ vùng dữ liệu được mô tả bị xóa.
14. Gây crash trong debug build; mở lại app và kiểm tra tag `Crash` trong file/bundle.
15. Giải nén diagnostic ZIP và kiểm tra `diagnostic-summary.txt`, `memory-log.txt` và thư mục `logs/`.
