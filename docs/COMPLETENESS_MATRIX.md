# Ma trận đầy đủ v1.2.0

| Nhóm | Tiêu chí | Trạng thái | Vị trí kiểm chứng |
|---|---|---:|---|
| Nền tảng | 100% Kotlin, không Lua/runtime Lua | Đủ | `tools/verify_project.py` |
| Nguồn audio | Tệp, microphone, playback capture | Đủ | `audio/*AudioSource.kt` |
| Audio | Resampler streaming, PCM 16 kHz, player jitter | Đủ | `StreamingPcmConverter.kt`, test |
| Mạng | WebSocket backpressure hai tầng | Đủ | `GeminiLiveClient.kt`, `TranslationService.kt` |
| Phiên | Session resumption và GoAway | Đủ | `GeminiLiveClient.kt` |
| Queue | `pacingMaxBuffer` điều khiển queue thật | Đủ | `TranslationService.kt`, `SettingsPolicy.kt` |
| MediaProjection | Callback thu hồi, giải phóng AudioRecord | Đủ | `InternalAudioSource.kt` |
| Nhật ký | Kho dùng chung, rotation, lọc, tìm kiếm, stack trace | Đủ | `AppLogRepository.kt`, `LogViewerActivity.kt` |
| Chẩn đoán | ZIP summary + memory log + file log, che secret | Đủ | `docs/DIAGNOSTICS.md` |
| Riêng tư | Transcript log tắt mặc định | Đủ | `AppSettings`, `AppLogRepository` |
| Cài đặt | Sanitize tập trung, profile một nguồn | Đủ | `SettingsPolicy.kt`, test |
| Áp dụng | Ngay / tạo lại player / reconnect / phiên sau | Đủ | `TranslationService.kt` |
| Reset | Xóa theo phạm vi, không xóa mù `filesDir` | Đủ | `SettingsActivity.kt` |
| Unit test | Resampler và SettingsPolicy | Đủ cơ bản | `app/src/test` |
| CI | Secret scan, verify, test, lint, assemble APK | Đủ cấu hình | `.github/workflows/android-ci.yml` |
| APK | Kiểm tra ZIP, DEX, manifest, resources và chữ ký | Đủ cấu hình | `tools/verify_apk.py`, CI |
| Release | APK + AAB ký bằng GitHub Secrets | Đủ cấu hình | `.github/workflows/android-release.yml` |
| Chuỗi cung ứng | Dependabot và dependency graph | Đủ | `.github/dependabot.yml`, workflow |
| Hỗ trợ lỗi | Issue template yêu cầu gói chẩn đoán | Đủ | `.github/ISSUE_TEMPLATE/bug_report.yml` |
| Pháp lý | Privacy, Security, License | Đủ | tệp gốc repository |
| Thiết bị thật | Ma trận Android 8 đến 15 và nguồn audio | Cần kiểm thử thủ công | `docs/BUILD_AND_RELEASE.md` |
| Store | Play Console, app signing, Data safety | Chưa thực hiện | Cần tài khoản Google Play |

“Đủ cấu hình” nghĩa là mã và workflow đã có, nhưng chỉ được đổi thành “đã kiểm chứng” sau khi workflow liên quan chạy xanh. Bản release ký chỉ được tạo khi chủ repository cung cấp keystore qua GitHub Secrets. Kiểm thử thiết bị thật và quy trình Google Play không thể được chứng minh hoàn toàn bằng CI không có thiết bị Android thật.
