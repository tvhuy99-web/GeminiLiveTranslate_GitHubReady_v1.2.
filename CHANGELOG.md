# Changelog

## 1.2.0

### Nhật ký và chẩn đoán

- Thay các bộ đệm log riêng lẻ bằng `AppLogRepository` dùng chung cho toàn bộ process.
- Mọi `SessionLogger` từ Activity, Service, WebSocket và audio source cùng ghi vào một timeline.
- Mặc định lưu mức **Thông thường** ra file; nội dung hội thoại mặc định không được ghi.
- Bộ nhớ giữ tối đa 5.000 entry; file log dùng `BufferedWriter` và xoay vòng tối đa 5 file, khoảng 2 MB mỗi file.
- Ghi stack trace, thread, timestamp mili giây và tag thành phần.
- Che API Key, query credential, Bearer token, Authorization và các trường token dạng JSON.
- Cache redaction được làm mới ngay khi thêm, chọn, xóa hoặc đổi API Key.
- Thêm handler lỗi chưa bắt để ghi crash và flush log trước khi tiến trình kết thúc.
- Thêm màn hình Nhật ký có lọc mức, lọc tag, tìm kiếm, tự cuộn, sao chép và xóa.
- Thêm gói ZIP chẩn đoán gồm log trong RAM, các file xoay vòng, cấu hình đã khử secret, thiết bị và trạng thái phiên.
- Bổ sung log cho quyền, API Key, setup Gemini, reconnect, resumption, GoAway, backpressure, queue drop, nguồn audio, player, recorder, seek, export và reset.

### Cài đặt

- Gom chuẩn hóa, profile và quy tắc áp dụng vào `SettingsPolicy` duy nhất.
- Mọi lần đọc/lưu đều clamp số, sửa enum lỗi và chuẩn hóa ngôn ngữ.
- Bỏ bước “Áp dụng” riêng cho model/mã ngôn ngữ; nút **Lưu & áp dụng** đọc trực tiếp nội dung ô nhập.
- Phân loại thay đổi thành: áp dụng ngay, tạo lại bộ phát, nối lại Gemini và áp dụng từ phiên tiếp theo.
- Các giá trị dành cho phiên sau không còn làm thay đổi nửa pipeline của phiên đang chạy.
- Đổi model/ngôn ngữ tự reconnect; đổi API Key đang dùng cũng tự tạo kết nối mới.
- Cài đặt được giữ khi Activity xoay màn hình.
- Ẩn các thanh phụ thuộc khi tính năng cha đang tắt.
- Tách reset thành: mặc định, xóa log, xóa bản ghi, xóa API Key và xóa toàn bộ dữ liệu do ứng dụng quản lý.
- Không còn xóa đệ quy toàn bộ `filesDir`.

### Kiểm thử và tài liệu

- Thêm test cho phân loại cài đặt và việc giữ nguyên cấu hình audio dành cho phiên kế tiếp.
- Mở rộng `tools/verify_project.py` để kiểm tra logger dùng chung, redaction, diagnostic ZIP, settings policy và đường reset an toàn.
- Thêm `docs/DIAGNOSTICS.md` với quy trình tái hiện lỗi và gửi báo cáo.

## 1.1.0

### Độ bền mạng

- Thêm hàng đợi audio giới hạn bằng `pacingMaxBuffer`.
- Thêm backpressure hai tầng: hàng đợi ứng dụng và `OkHttp WebSocket.queueSize()`.
- Chế độ tệp chặn producer khi đầy; nguồn trực tiếp bỏ chunk cũ để giữ độ trễ thấp.
- Thêm số liệu queue, drop và backpressure vào health status.

### Phiên Gemini

- Gửi `sessionResumption` trong setup.
- Lưu `sessionResumptionUpdate.newHandle` và dùng lại khi reconnect.
- Xử lý `GoAway.timeLeft` để chủ động đổi kết nối.
- Giữ hàng đợi audio qua reconnect; xóa handle khi seek hoặc đổi ngôn ngữ tạo ngữ cảnh mới.

### Audio và MediaProjection

- Thêm `StreamingPcmConverter` có trạng thái cho cả ba nguồn.
- Giữ pha nội suy, mẫu biên và byte frame chưa hoàn chỉnh giữa các chunk.
- Reset đúng lúc đổi format, seek và resume.
- Xử lý `MediaProjection.Callback.onStop()` và trả lỗi rõ ràng về service.
