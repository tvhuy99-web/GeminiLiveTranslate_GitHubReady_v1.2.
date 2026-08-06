# Chính sách bảo mật

## Báo cáo lỗ hổng

Không mở issue công khai nếu lỗi có thể làm lộ API Key, token, tệp chẩn đoán riêng tư hoặc cho phép thực thi mã trái phép. Hãy gửi báo cáo riêng cho chủ repository qua kênh liên hệ GitHub của tài khoản `tvhuy99-web`.

Báo cáo nên gồm phiên bản, thiết bị/Android, bước tái hiện, phạm vi ảnh hưởng và bản vá đề xuất nếu có. Không gửi khóa thật. Dùng khóa thử nghiệm đã thu hồi khi cần minh họa.

## Phạm vi bí mật

Repository không được chứa:

- Gemini API Key;
- keystore phát hành;
- mật khẩu keystore;
- access token GitHub;
- gói chẩn đoán có dữ liệu cá nhân chưa kiểm tra.

CI chạy `tools/check_no_secrets.py`. Keystore phát hành chỉ được khôi phục tạm thời từ GitHub Actions Secrets và bị xóa ở cuối job.
