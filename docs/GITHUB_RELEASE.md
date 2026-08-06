# Build và phát hành bằng GitHub Actions

## Debug APK tự động

Workflow `Android CI` chạy khi push, pull request hoặc chạy thủ công. Job thực hiện:

1. quét secret;
2. kiểm tra cấu trúc dự án;
3. chạy unit test;
4. chạy Android Lint;
5. build debug APK;
6. kiểm tra APK là ZIP hợp lệ và xác minh chữ ký debug;
7. tải artifact `GeminiLiveTranslate-v1.2.0-debug` kèm SHA-256.

## Release ký chính thức

Tạo bốn repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Mã hóa keystore thành Base64, ví dụ:

```bash
base64 -w 0 release-keystore.jks
```

Trên macOS dùng:

```bash
base64 < release-keystore.jks | tr -d '\n'
```

Sau đó tạo tag:

```bash
git tag v1.2.0
git push origin v1.2.0
```

Workflow `Android Signed Release` sẽ test, lint, build APK/AAB đã ký, xác minh chữ ký, tạo SHA-256 và phát hành GitHub Release. Có thể chạy thủ công để chỉ nhận artifact mà không tạo Release.

## Nguyên tắc

- Không commit keystore hoặc mật khẩu.
- Không coi job đỏ là thành công.
- Chỉ phát hành APK sau khi `apksigner verify` đạt.
- Artifact debug dùng để thử nghiệm; bản phân phối chính thức phải dùng workflow release đã ký.
