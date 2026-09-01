# AI Studio Web Session Executor Lab

## Mục tiêu

Thử dùng chính phiên đăng nhập `https://aistudio.google.com` trong WebView, thay vì AI Studio Build server hoặc `GEMINI_API_KEY`.

R1 chưa cố replay request xác thực. Nó làm một bước hẹp nhưng quan trọng:

1. cài probe ở document-start, trước JavaScript của AI Studio;
2. giữ TalkBack có thể bật;
3. điền một prompt có marker cố định;
4. dùng Android `MotionEvent` đã được kiểm chứng để AI Studio tự tạo request thật;
5. hook `fetch`/`XMLHttpRequest` và bắt `MakerSuiteService/GenerateContent`;
6. lấy trạng thái và marker từ response network, không đọc DOM model response;
7. ghi call stack tạo request để tìm lớp/hàm nội bộ cho vòng thử nghiệm sau.

## Không làm

- Không có AI Studio Build executor.
- Không dùng Cloud Run proxy.
- Không dùng `GEMINI_API_KEY`.
- Không ghi giá trị `Authorization`, cookie, API key hay token vào log.
- Không tự động retry request.

## Cách thử

1. Cài APK debug của PR và mở launcher `AI Studio Web Session Lab`.
2. Đăng nhập Google/AI Studio nếu cần.
3. Mở `New Chat` và chờ trang ổn định.
4. Giữ TalkBack bật nếu đó là cấu hình thực tế cần dùng.
5. Bấm `Kiểm tra probe`. Trạng thái phải cho biết document-start probe đã được cài.
6. Bấm `1. Gửi thật + bắt network`.
7. Chờ `Network GenerateContent` trả HTTP 2xx và `markerFound=true`.
8. Bấm `3. Xem call stack` rồi `Chia sẻ log ZIP`.

## Tiêu chí thành công R1

- Probe `DOCUMENT_START_INSTALLED` xuất hiện trước request GenerateContent.
- `GENERATE_START` bắt được đường `MakerSuiteService/GenerateContent`.
- `GENERATE_RESULT` nhận HTTP thành công.
- `markerFound=true` với `AIS_WEB_SESSION_NETWORK_OK_20260901`.
- Call stack chỉ ra bundle/function path đã gọi GenerateContent.

Nếu R1 đạt đủ các điều trên, bước tiếp theo là nghiên cứu chính call site nội bộ đó để giảm hoặc loại bỏ hoàn toàn phụ thuộc vào nút Run. Không cần đọc DOM response nữa vì network response đã được bắt trực tiếp.
