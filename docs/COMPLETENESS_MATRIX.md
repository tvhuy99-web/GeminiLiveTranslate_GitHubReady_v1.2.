# Ma trận đầy đủ v1.2.0

| Nhóm | Tiêu chí | Trạng thái | Vị trí kiểm chứng |
|---|---|---:|---|
| Nền tảng | 100% Kotlin, không Lua/runtime Lua | Đủ | `tools/verify_project.py` |
| Nguồn audio | Tệp, microphone, playback capture | Đủ | `audio/*AudioSource.kt` |
| Quyền audio | Kiểm tra `RECORD_AUDIO` tại nguồn, không chỉ ở UI | Đủ | `MicAudioSource.kt`, `InternalAudioSource.kt` |
| Tương thích API | Playback Capture chỉ tạo từ Android 10 | Đủ | `TranslationService.kt` |
| Audio | Resampler streaming, PCM 16 kHz, player jitter | Đủ | `StreamingPcmConverter.kt`, unit test |
| Mạng | WebSocket backpressure hai tầng | Đủ | `GeminiLiveClient.kt`, `TranslationService.kt` |
| Phiên | Session resumption và GoAway | Đủ | `GeminiLiveClient.kt` |
| Queue | `pacingMaxBuffer` điều khiển queue thật | Đủ | `TranslationService.kt`, `SettingsPolicy.kt` |
| MediaProjection | Callback thu hồi, giải phóng AudioRecord | Đủ | `InternalAudioSource.kt` |
| Nhật ký | Kho dùng chung, rotation, lọc, tìm kiếm, stack trace | Đủ | `AppLogRepository.kt`, `LogViewerActivity.kt` |
| Chẩn đoán | ZIP summary, memory/file log và che secret | Đủ | `docs/DIAGNOSTICS.md` |
| Riêng tư | Transcript log tắt mặc định | Đủ | `AppSettings`, `AppLogRepository` |
| Cài đặt | Sanitize tập trung, profile một nguồn | Đủ | `SettingsPolicy.kt`, unit test |
| Áp dụng | Ngay / tạo lại player / reconnect / phiên sau | Đủ | `TranslationService.kt` |
| Reset | Xóa theo phạm vi, không xóa mù `filesDir` | Đủ | `SettingsActivity.kt` |
| Wrapper | Bootstrap có source, checksum JAR và checksum Gradle | Đã kiểm chứng | `tools/verify_github_ready.py`, `gradle/wrapper` |
| Unit test | Resampler và SettingsPolicy | Đã chạy xanh | `app/src/test`, Android CI |
| CI | Secret scan, verify, test, lint, assemble APK | Đã chạy xanh | `.github/workflows/android-ci.yml` |
| APK | ZIP, DEX, manifest, resources và chữ ký | Đã kiểm chứng | `tools/verify_apk.py`, `apksigner` |
| APK debug | Khoảng 4,34 MB, chữ ký v2 hợp lệ | Đã tạo | Android CI ngày 06/08/2026 |
| Phân phối APK debug | GitHub prerelease `debug-latest` | Đã cấu hình, chờ run main | `.github/workflows/android-ci.yml` |
| Actions artifact | Không còn dùng | Đã loại bỏ | CI không chứa `actions/upload-artifact` |
| Release chính thức | APK + AAB ký bằng GitHub Secrets | Đủ cấu hình, chưa chạy | `.github/workflows/android-release.yml` |
| Chuỗi cung ứng | Dependabot và dependency graph | Đủ | `.github/dependabot.yml`, workflow |
| Hỗ trợ lỗi | Issue template yêu cầu gói chẩn đoán | Đủ | `.github/ISSUE_TEMPLATE/bug_report.yml` |
| Pháp lý | Privacy, Security, License | Đủ | tệp gốc repository |
| Thiết bị thật | Android 8 đến 15 và ba nguồn audio | Cần kiểm thử thủ công | `docs/BUILD_AND_RELEASE.md` |
| Google Play | Play App Signing, Data safety, phát hành store | Chưa thực hiện | Cần tài khoản Google Play |

## Cách hiểu trạng thái

- **Đủ**: mã hoặc tài liệu đã có và được kiểm tra tĩnh.
- **Đã chạy xanh / Đã kiểm chứng**: GitHub Actions đã thực thi thành công bước liên quan.
- **Đủ cấu hình, chưa chạy**: workflow hoàn chỉnh nhưng cần secret hoặc tài nguyên chưa được cung cấp.
- **Đã cấu hình, chờ run main**: thay đổi đã được CI trên pull request kiểm tra; bước phát hành chỉ chạy sau khi merge vào `main`.
- **Cần kiểm thử thủ công**: không thể chứng minh đầy đủ bằng runner Linux không có thiết bị Android thật.

Bản debug được phân phối bằng GitHub Release asset thay cho Actions artifact để không phụ thuộc quota artifact của tài khoản. Bản release ký chỉ được coi là hoàn thành sau khi thêm đủ bốn keystore Secrets và workflow `Android Signed Release` chạy xanh. Phát hành Google Play là phạm vi riêng, cần tài khoản nhà phát triển, Play App Signing và khai báo Data safety.
