# Nhật ký và báo cáo chẩn đoán

## Mục tiêu

Khi ứng dụng có lỗi, người dùng chỉ cần tái hiện lỗi rồi gửi **một tệp ZIP chẩn đoán**. Gói này đủ để đối chiếu vòng đời phiên, cấu hình, WebSocket, audio source, queue, player và stack trace mà không cần yêu cầu người dùng sao chép nhiều màn hình.

## Cách tạo báo cáo

1. Mở **Cài đặt > Hệ thống**.
2. Giữ **Ghi log xoay vòng ra file** ở trạng thái bật.
3. Chọn mức **Thông thường**. Chỉ chuyển sang **Chi tiết** khi cần tái hiện lỗi khó.
4. Thực hiện lại thao tác gây lỗi.
5. Mở **Nhật ký** để kiểm tra thời điểm xảy ra lỗi.
6. Nhấn **Gửi báo cáo** hoặc **Tạo và gửi báo cáo chẩn đoán**.
7. Gửi tệp `GeminiLiveTranslate_diagnostics_YYYYMMDD_HHMMSS.zip`.

Không cần chụp màn hình log nếu đã gửi ZIP.

## Nội dung gói ZIP

- `diagnostic-summary.txt`
  - phiên bản ứng dụng;
  - Android/API, thiết bị, ABI và locale;
  - bộ nhớ JVM và dung lượng trống;
  - cài đặt đã khử secret;
  - trạng thái phiên gần nhất;
  - queue ứng dụng/WebSocket, drop, backpressure, reconnect, resumption và GoAway.
- `memory-log.txt`: timeline log đang còn trong process.
- `logs/diagnostic-current.log`: file hiện tại.
- `logs/diagnostic-1.log` đến `diagnostic-4.log`: lịch sử xoay vòng nếu có.

## Bảo vệ riêng tư

- API Key đã lưu được thay bằng `[REDACTED_API_KEY]`.
- Query `key`, `api_key`, `token`, `access_token` được che.
- Header Authorization, Bearer token và trường token JSON được che.
- Nội dung transcript không được ghi mặc định.
- Tùy chọn **Cho phép ghi nội dung hội thoại** chỉ nên bật tạm thời khi lỗi nằm ở việc ghép transcript.
- Tắt tùy chọn này và xóa log sau khi hoàn tất kiểm tra.

Không thể bảo đảm che mọi dữ liệu do thư viện hoặc ROM tùy biến tự đưa vào exception. Người dùng vẫn nên xem nhanh `diagnostic-summary.txt` và log trước khi gửi cho bên không tin cậy.

## Mức nhật ký

| Mức | Nội dung | Dùng khi |
|---|---|---|
| Chỉ lỗi | Crash và lỗi làm hỏng thao tác | Muốn tối thiểu dữ liệu |
| Lỗi + cảnh báo | Thêm reconnect, drop, backpressure và fallback | Kiểm tra mạng/audio nhẹ |
| Thông thường | Thêm vòng đời phiên và cấu hình hoạt động | Mặc định khuyến nghị |
| Chi tiết | Thêm sự kiện tần suất cao, không gồm transcript nếu chưa bật riêng | Tái hiện lỗi khó |

## Tag chính

- `Session`, `Service`, `UI`, `Permission`
- `Settings`, `ApiKey`, `Diagnostics`, `Crash`
- `Gemini`, `GeminiWS`, `GeminiInput`
- `InputQueue`, `AudioSource`, `MicAudio`, `InternalAudio`, `FileAudio`
- `AudioPlayer`, `Recorder`, `TTS`, `MediaProjection`, `Export`

## Giới hạn dung lượng

- RAM: tối đa 5.000 entry.
- Ổ đĩa: file hiện tại và 4 file lịch sử.
- Mỗi file khoảng 2 MB.
- Tối đa 5 báo cáo ZIP gần nhất trong cache; báo cáo cũ tự bị dọn.

## Quy trình phân tích đề xuất

1. Đọc `diagnostic-summary.txt` để xác định phiên bản, nguồn, model và trạng thái cuối.
2. Tìm tag `Crash`, `GeminiWS`, `AudioSource` hoặc `MediaProjection` ở thời điểm lỗi.
3. Đối chiếu `session.setupLatencyMs`, `session.inputQueue`, `session.websocketQueuedBytes` và `session.backpressureEvents`.
4. Kiểm tra sự kiện `sessionResumptionUpdate`, `GoAway`, reconnect và setup mới.
5. Với audio giật, đối chiếu player underrun/drop, input queue và sample-rate được chọn.
6. Với lỗi cài đặt, tìm log `Settings` để biết mục nào áp dụng ngay, reconnect hoặc chờ phiên mới.
