# Quyền riêng tư

Gemini Live Translate xử lý âm thanh theo lựa chọn của người dùng. Khi bắt đầu một phiên, audio và cấu hình dịch cần thiết được gửi tới dịch vụ Gemini qua kết nối TLS để tạo transcript, bản dịch và có thể cả audio đầu ra.

## Dữ liệu lưu trên thiết bị

- API Key được mã hóa AES-GCM bằng khóa trong Android Keystore.
- Nhật ký chẩn đoán được lưu trong vùng riêng của ứng dụng khi tùy chọn ghi file bật.
- Bản ghi WAV và phụ đề chỉ được tạo khi người dùng bật hoặc chủ động xuất.
- Backup ứng dụng bị tắt trong manifest.

## Nhật ký

Nội dung hội thoại không được ghi mặc định. Tùy chọn ghi transcript phải được bật riêng. Logger che các mẫu API Key, Bearer token và trường xác thực phổ biến, nhưng người dùng vẫn nên xem gói chẩn đoán trước khi chia sẻ.

## Xóa dữ liệu

Màn hình Cài đặt cho phép xóa riêng nhật ký, bản ghi, API Key hoặc toàn bộ dữ liệu do ứng dụng quản lý. Gỡ cài đặt cũng xóa dữ liệu trong vùng riêng của ứng dụng theo cơ chế Android.

Repository này không cung cấp máy chủ trung gian. Chính sách và thời hạn lưu dữ liệu phía Gemini phụ thuộc tài khoản/dịch vụ API mà người dùng sử dụng.
