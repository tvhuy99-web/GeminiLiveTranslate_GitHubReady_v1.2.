# Ký APK để cài đè

Android chỉ cho phép APK mới cập nhật APK cũ khi đồng thời thỏa mãn:

1. cùng `applicationId`;
2. `versionCode` mới lớn hơn bản đang cài;
3. cùng chứng thư ký.

Bản debug dùng package cố định `com.oai.geminilivetranslate.debug`. CI không dùng debug key ngẫu nhiên của runner cho APK phát hành nữa. Keystore cập nhật phải được lưu bằng bốn GitHub Actions Secrets:

- `ANDROID_UPDATE_KEYSTORE_BASE64`
- `ANDROID_UPDATE_KEYSTORE_PASSWORD`
- `ANDROID_UPDATE_KEY_ALIAS`
- `ANDROID_UPDATE_KEY_PASSWORD`

Fingerprint được CI khóa tại:

```text
46e4178b4f1b8ca9a0b480db261ed31ca53dbc2a0a62225d3a97a0ecf2cb034b
```

## Thiết lập

1. Mở repository trên GitHub.
2. Chọn **Settings > Secrets and variables > Actions**.
3. Tạo bốn repository secrets bằng dữ liệu trong gói ký được cung cấp riêng.
4. Chạy workflow **Android CI** trên nhánh `main`.
5. Tải `GeminiLiveTranslate-debug-latest.apk` trong prerelease `debug-latest`.

Không commit keystore hoặc file chứa secret vào repository.

## Lần chuyển đổi đầu tiên

Các APK 1.2.0 trước đây được GitHub-hosted runner ký bằng debug key tạm thời. Fingerprint thực tế đã thay đổi giữa các lần chạy, nên khóa đã ký bản đang cài không thể được tái tạo. Vì thế cần gỡ bản 1.2.0 cũ đúng một lần trước khi cài 1.2.1 dùng khóa ổn định. Từ 1.2.1 trở đi, APK mới có thể cài đè trực tiếp miễn là giữ nguyên keystore này và tăng `versionCode`.

## Asset trên GitHub Release

Workflow dùng một tên cố định:

```text
GeminiLiveTranslate-debug-latest.apk
```

Mỗi lần phát hành, workflow xóa asset cũ trong prerelease `debug-latest` rồi tải asset mới lên. Vì vậy trang Release không tích lũy nhiều APK debug cũ.
