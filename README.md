# Gemini Live Translate Native

Ứng dụng Android dịch giọng nói trực tiếp viết **100% bằng Kotlin**. Dự án tham khảo bố cục và luồng tính năng từ gói `.xpk`, nhưng không sử dụng Lua, Lua runtime hoặc bridge Lua.

## Trạng thái kiểm tra GitHub

Nhánh sửa chữa `fix/complete-v1.2.0` được dùng để chạy đối chiếu cấu trúc, unit test, Android lint và `assembleDebug` trước khi hợp nhất vào `main`.

## Điểm chính

- Dịch từ **tệp âm thanh/video**, **microphone** và **âm thanh nội bộ**.
- PCM đầu vào 16-bit mono 16 kHz; giọng dịch đầu ra 16-bit mono 24 kHz.
- Gemini Live WebSocket với backpressure hai tầng, session resumption và GoAway.
- Streaming resampler giữ trạng thái giữa các chunk.
- Foreground Service, WakeLock, thông báo điều khiển và tự kết nối lại.
- Phụ đề trực tiếp, xuất **SRT** hoặc **TXT**.
- Giọng AI trực tiếp và Android TTS dự phòng.
- Tua, tạm dừng, âm lượng gốc/dịch và auto-ducking.
- Ghi WAV gốc, WAV dịch hoặc WAV trộn theo timeline.
- Trình duyệt mini có điều khiển video HTML5.
- Nhiều API Key, mã hóa AES-GCM bằng Android Keystore.
- Nhật ký dùng chung toàn ứng dụng và báo cáo chẩn đoán ZIP.
- Cài đặt được chuẩn hóa và áp dụng theo đúng vòng đời phiên.

## Yêu cầu

- Android Studio và JDK 17.
- Android SDK 35.
- Thiết bị Android 8.0 trở lên, `minSdk 26`.
- Thu âm thanh nội bộ yêu cầu Android 10 trở lên.
- API Key có quyền truy cập model đã cấu hình.

## Mở và build

```bash
./gradlew clean test lint assembleDebug
```

APK debug:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Chẩn đoán

Mở **Cài đặt > Hệ thống > Mở nhật ký** để lọc, tìm kiếm, sao chép, xóa hoặc tạo báo cáo ZIP. Transcript không được ghi mặc định; API Key và token được che trước khi vào RAM, Logcat, file hoặc ZIP.

## Cài đặt

`SettingsPolicy` là nguồn duy nhất cho validation, profile và phân loại thay đổi. Các thay đổi an toàn áp dụng ngay; model, ngôn ngữ và API Key reconnect có kiểm soát; buffer nguồn, pacing, queue gửi và WAV áp dụng từ phiên mới.

## Kiến trúc và tài liệu

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md)
- [docs/BUILD_AND_RELEASE.md](docs/BUILD_AND_RELEASE.md)
- [CHANGELOG.md](CHANGELOG.md)
- [PROJECT_STATUS.md](PROJECT_STATUS.md)

## Kiểm tra dự án

```bash
python3 tools/verify_project.py
./gradlew clean test lint assembleDebug
```
