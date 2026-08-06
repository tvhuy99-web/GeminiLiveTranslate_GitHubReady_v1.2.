# Đăng dự án lên GitHub và build APK

## Cách dễ nhất: tạo repository mới trên GitHub

1. Vào GitHub và tạo một repository trống, không thêm README, `.gitignore` hoặc license.
2. Giải nén gói này.
3. Mở repository mới, chọn **Add file → Upload files**.
4. Kéo toàn bộ nội dung bên trong thư mục `GeminiLiveTranslate_GitHubReady_v1.2.0` vào trang upload. Phải thấy cả thư mục `.github`, `app`, `gradle`, `docs` và tệp `settings.gradle.kts`.
5. Chọn **Commit changes**.
6. Mở tab **Actions**, chọn workflow **Build Android APK**.
7. Khi workflow có dấu xanh, mở run, tải artifact **GeminiLiveTranslate-APK**.

## Dùng Git command line

Từ bên trong thư mục dự án:

```bash
git init
git add .
git commit -m "Import complete Gemini Live Translate Android project"
git branch -M main
git remote add origin https://github.com/USERNAME/REPOSITORY.git
git push -u origin main
```

Không đưa API Key, `local.properties`, keystore hoặc mật khẩu vào repository.

## Thay thế repository cũ đang bị lỗi

Chỉ dùng khi chắc chắn muốn thay toàn bộ lịch sử của repository cũ:

```bash
git init
git add .
git commit -m "Replace broken bootstrap with complete Android source"
git branch -M main
git remote add origin https://github.com/tvhuy99-web/Huytvtvhuy.git
git push --force -u origin main
```

Lệnh `--force` sẽ thay lịch sử nhánh `main` hiện tại.
