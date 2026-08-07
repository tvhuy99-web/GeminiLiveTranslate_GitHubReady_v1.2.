# Ký APK để cài đè

Android chỉ cho phép APK mới cập nhật APK cũ khi đồng thời thỏa mãn:

1. cùng `applicationId`;
2. `versionCode` mới không thấp hơn yêu cầu cập nhật của Android;
3. cùng chứng thư ký.

Bản debug dùng package cố định:

```text
com.oai.geminilivetranslate.debug
```

## Khóa ổn định cho bản debug

Các máy GitHub-hosted runner không còn tự tạo debug key ngẫu nhiên. Mọi bản debug mặc định dùng khóa kiểm thử cố định tại:

```text
.github/signing/stable-debug.keystore.b64
```

Fingerprint SHA-256 bắt buộc:

```text
85e27156c4557de4f35b6ebe771dd426f108182894219ff3b6dbed607a230c95
```

`app/build.gradle.kts`, `tools/verify_github_ready.py` và workflow `Android CI` cùng khóa fingerprint này. Build sẽ thất bại nếu tệp ký hoặc chứng thư APK bị thay đổi ngoài ý muốn.

Khóa này chỉ dành cho package debug trong repository riêng tư. Bất kỳ ai có quyền đọc repository đều có thể dùng nó để ký bản debug, vì vậy tuyệt đối không dùng nó cho package phát hành chính thức. Bản release tiếp tục dùng keystore bảo mật riêng qua GitHub Actions Secrets.

## Lần chuyển đổi duy nhất

APK đang có trên thiết bị được ký bằng một debug key tạm thời trước đây. Khóa bí mật đó không thể được khôi phục từ APK, nên Android không cho bản dùng khóa mới cài đè lên bản cũ.

Cần thực hiện đúng một lần:

1. ghi lại API key hoặc cài đặt quan trọng;
2. gỡ bản `com.oai.geminilivetranslate.debug` đang có;
3. cài APK chuyển đổi được ký bằng fingerprint cố định ở trên.

Sau lần này, không thay đổi hoặc tạo lại `stable-debug.keystore.b64`. Mỗi bản mới chỉ cần tăng `versionCode` và có thể cài đè trực tiếp.

## Kiểm tra trước khi phát hành

Workflow `Android CI` thực hiện:

- unit test;
- Android Lint;
- kiểm tra cấu trúc APK;
- kiểm tra package và phiên bản;
- xác minh chữ ký bằng `apksigner`;
- so sánh fingerprint thực tế với fingerprint cố định.

APK mới nhất được cập nhật tại prerelease `debug-latest` với tên:

```text
GeminiLiveTranslate-debug-latest.apk
```
