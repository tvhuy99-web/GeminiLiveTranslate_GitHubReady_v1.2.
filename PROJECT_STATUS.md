# Trạng thái bàn giao

## Phiên bản

- `versionName`: **1.2.0**
- `versionCode`: **10200**
- Kotlin native, không Lua và không Lua runtime.

## Phạm vi đã triển khai

- UI chính, cài đặt nhiều tab, màn hình Nhật ký và mini browser.
- Dịch từ tệp, microphone và Playback Capture.
- Gemini Live WebSocket, backpressure, session resumption và GoAway.
- Streaming resampler cho cả ba nguồn audio.
- Foreground Service, WakeLock, notification actions và MediaProjection lifecycle.
- Seek, pause/resume, volume, ducking, SRT/TXT, WAV và Android TTS dự phòng.
- Nhiều API Key được mã hóa bằng Android Keystore.

## Nhật ký và chẩn đoán 1.2.0

- Một repository dùng chung cho toàn ứng dụng, không còn log bị chia theo Activity/Service.
- Kho RAM tối đa 5.000 entry.
- File log bật mặc định, xoay vòng tối đa 5 file x khoảng 2 MB.
- Ghi timestamp, mức, tag, thread và stack trace.
- Che API Key/token trước khi vào RAM, Logcat, file hoặc ZIP.
- Transcript mặc định không vào log; chỉ ghi khi người dùng bật rõ ràng.
- Màn hình log có lọc, tìm kiếm, tự cuộn, sao chép, xóa và chia sẻ.
- Báo cáo ZIP chứa summary thiết bị/cấu hình/trạng thái, memory log và các file log xoay vòng.
- Crash chưa bắt được ghi và flush qua application-level handler.

## Cài đặt 1.2.0

- `SettingsPolicy` là nguồn duy nhất cho validation, profile và phân loại thay đổi.
- Model/ngôn ngữ/API Key đang dùng có thể được áp dụng bằng reconnect tự động.
- Buffer phát được tạo lại có kiểm soát.
- Buffer nguồn, pacing, queue đầu vào và ghi WAV được giữ nguyên trong phiên đang chạy, rồi áp dụng ở phiên mới.
- Cài đặt lỗi hoặc dữ liệu cũ được chuẩn hóa mỗi lần load.
- Draft không mất khi xoay màn hình.
- Reset chỉ xóa các vùng dữ liệu ứng dụng biết rõ, không xóa mù toàn bộ `filesDir`.

## Đã kiểm tra trong môi trường tạo dự án

- Tất cả XML parse thành công.
- ID ViewBinding được đối chiếu với mã Kotlin.
- Bộ kiểm tra xác nhận logger dùng chung, redaction, diagnostic ZIP và settings policy được nối vào ứng dụng.
- Bộ kiểm tra xác nhận backpressure, resumption, GoAway, streaming resampler và MediaProjection lifecycle vẫn còn nguyên.
- Không có tệp Lua.
- Test lõi độc lập kiểm tra streaming PCM và quy tắc cài đặt.
- ZIP phát hành được giải nén lại và chạy `tools/verify_project.py` trước khi bàn giao.

## Giới hạn kiểm tra tại đây

Không thể chạy Android Gradle build, Android Lint hoặc instrumentation test trong môi trường tạo dự án vì không có Android SDK và Gradle không thể tải dependency qua DNS ngoài. Cần chạy `./gradlew clean test lint assembleDebug` trên Android Studio hoặc máy có Android SDK trước khi phát hành APK.
