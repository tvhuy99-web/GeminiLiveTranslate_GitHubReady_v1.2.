# Gemini Live Translate Native v1.2.2

Ứng dụng Android dịch âm thanh trực tiếp viết **100% bằng Kotlin**, không dùng Lua, Lua runtime hoặc bridge Lua.

## Tính năng chính

- Dịch từ tệp âm thanh/video, microphone và âm thanh nội bộ.
- Gemini Live WebSocket có backpressure hai tầng, session resumption và GoAway.
- Streaming PCM converter giữ trạng thái qua các chunk.
- Phụ đề trực tiếp, xuất SRT/TXT và ghi WAV gốc, dịch hoặc trộn.
- AI voice, Android TTS dự phòng, auto-ducking và điều khiển âm lượng.
- Chọn luồng phát giọng AI (`aiAudioStreamType`) và lưu WAV công khai trong `Music/GeminiLiveTranslate`.
- API Key được mã hóa AES-GCM bằng Android Keystore.
- Nhật ký dùng chung toàn ứng dụng, xoay file, che secret và tạo ZIP chẩn đoán.
- Cài đặt được sanitize tập trung và phân loại áp dụng ngay, reconnect hoặc phiên sau.

## Yêu cầu

- JDK 17.
- Android SDK 35.
- Android 8.0 trở lên, `minSdk 26`.
- Playback Capture yêu cầu Android 10 trở lên.
- Gemini API Key có quyền truy cập model được cấu hình.

## Build

```bash
chmod +x gradlew
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

APK debug được tạo tại:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Trên Windows dùng `gradlew.bat`.

## Gradle bootstrap đã xác minh

Repository dùng bootstrap JAR có mã nguồn tại `gradle/wrapper/bootstrap-src`. Bootstrap tải đúng Gradle **8.10.2**, bắt buộc xác minh SHA-256 của distribution và chống Zip Slip trước khi chạy Gradle.

- SHA-256 của `gradle-wrapper.jar`: `44afcdcadc571c1a83763fc68e95ffaea07429f9ea0c473978e6052d1b7ec174`
- SHA-256 của Gradle 8.10.2 distribution: `31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26`

`tools/verify_github_ready.py` kiểm tra byte-for-byte bootstrap JAR, class `GradleWrapperMain`, URL và checksum distribution trước mỗi build CI.

## Kết quả GitHub Actions đã kiểm chứng

Workflow **Android CI** thực hiện:

1. quét API Key, token và private key;
2. kiểm tra cấu trúc dự án và GitHub readiness;
3. chạy unit test;
4. build debug APK;
5. kiểm tra APK có manifest, resources và DEX hợp lệ;
6. xác minh chữ ký bằng `apksigner`;
7. chạy Android Lint nghiêm ngặt;
8. đăng APK và SHA-256 lên GitHub prerelease `debug-latest`.

APK debug đã kiểm chứng ngày **06/08/2026**:

- Kích thước: khoảng **4,34 MB**.
- Chữ ký debug: APK Signature Scheme v2 hợp lệ.

## Tải APK mới nhất

Sau khi workflow trên nhánh `main` chạy xanh, mở:

```text
Releases → Gemini Live Translate v1.2.0 Debug (latest)
```

Hoặc truy cập release tag:

```text
https://github.com/tvhuy99-web/GeminiLiveTranslate_GitHubReady_v1.2./releases/tag/debug-latest
```

Release chứa:

- `GeminiLiveTranslate-v1.2.0-debug.apk`
- `GeminiLiveTranslate-v1.2.0-debug.apk.sha256`

CI không còn dùng Actions artifact storage, vì quota artifact của tài khoản có thể bị chia sẻ với GitHub Packages hoặc bị giới hạn bởi billing/budget.

## Release ký chính thức

Workflow **Android Signed Release** tạo cả APK và AAB đã ký. Cần bốn GitHub Actions Secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Xem hướng dẫn tại [docs/GITHUB_RELEASE.md](docs/GITHUB_RELEASE.md).

## Nhật ký và báo cáo lỗi

Mở **Cài đặt > Hệ thống > Mở nhật ký** để lọc, tìm kiếm, sao chép, xóa hoặc tạo ZIP chẩn đoán. Nội dung hội thoại không được ghi mặc định. Trước khi mở issue, tạo gói chẩn đoán và kiểm tra không có dữ liệu riêng tư không cần thiết.

Xem [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md) và dùng mẫu [Báo lỗi ứng dụng](.github/ISSUE_TEMPLATE/bug_report.yml).

## Tài liệu

- [Ma trận đầy đủ](docs/COMPLETENESS_MATRIX.md)
- [Kiến trúc](docs/ARCHITECTURE.md)
- [Build và release](docs/BUILD_AND_RELEASE.md)
- [GitHub Release](docs/GITHUB_RELEASE.md)
- [Quyền riêng tư](PRIVACY.md)
- [Bảo mật](SECURITY.md)
- [Changelog](CHANGELOG.md)

## Trạng thái phạm vi

Phần mã nguồn, CI debug, kiểm tra APK, bảo mật repository và phát hành APK debug qua GitHub Releases đã hoàn thiện. Bản release ký chưa thể được tạo cho đến khi chủ repository cung cấp keystore qua Secrets. Kiểm thử trên thiết bị Android thật và phát hành Google Play vẫn là các bước thủ công cần tài khoản và thiết bị tương ứng.
