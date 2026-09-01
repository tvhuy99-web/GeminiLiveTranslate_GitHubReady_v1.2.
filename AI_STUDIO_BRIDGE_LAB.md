# AI Studio Browser Bridge Lab

Nhánh thử nghiệm: `experiment/aistudio-browser-bridge-poc`

Mục tiêu của phòng lab là trả lời bằng dữ liệu thực tế trên Android:

1. Google AI Studio có tải và đăng nhập được trong Android WebView hay không.
2. JavaScript từ Kotlin có đọc/điều khiển được DOM của AI Studio hay không.
3. Cách nào tìm được ô prompt ổn định nhất: semantic DOM, `contenteditable`, `textarea`, `role=textbox` hay `execCommand`.
4. Cách nào gửi được prompt: click nút, submit form hay Enter.
5. Có lấy được response/streaming từ DOM bằng MutationObserver hay không.
6. Khi thao tác thủ công, AI Studio dùng fetch, XHR hay WebSocket nào để giao tiếp.
7. File chooser ảnh/video, popup đăng nhập, cookie state, WebView renderer, HTTP/JS errors và background lifecycle hoạt động đến đâu.

## Cách mở

APK thử nghiệm có **hai launcher**:

- `Gemini Live Translate`: ứng dụng chính, không thay đổi.
- `AI Studio Bridge Lab`: phòng thử nghiệm riêng.

## Trình tự thử đề nghị

### A. Kiểm tra nền tảng

1. Mở `AI Studio Bridge Lab`.
2. Chờ trang AI Studio tải.
3. Đăng nhập nếu cần.
4. Nhấn `Test cầu JS`.
5. Nhấn `Quét DOM`.
6. Nhấn `Cookie trạng thái`.

Nếu lỗi ở đây, chưa cần thử gửi prompt.

### B. Thử DOM automation

1. Nhấn `Cài probe JS`.
2. Nhấn `Highlight` để đánh dấu các candidate mà probe tìm thấy.
3. Nhấn `Điền A: semantic`.
4. Nếu không điền được, thử `Điền B: execCommand`.
5. Thử lần lượt `Gửi A: nút`, `Gửi B: form`, `Gửi C: Enter`.
6. Nhấn `Đọc response`.

### C. Thử tự động

- `AUTO A`: semantic fill + button click.
- `AUTO B`: execCommand + form submit + Enter fallback.

Cả hai tự cài observer, network hooks và quét response nhiều lần trong khoảng 25 giây.

### D. Quan sát thủ công, fallback quan trọng nhất

Nhấn `Chỉ quan sát thủ công`, sau đó tự thao tác trực tiếp trên trang AI Studio.

Chế độ này không tự điền hay bấm gì. Nó chỉ:

- cài MutationObserver;
- hook `fetch`;
- hook `XMLHttpRequest`;
- hook WebSocket mới được tạo;
- ghi resource timing;
- ghi console và WebView network metadata.

Nếu DOM automation thất bại nhưng thao tác tay vẫn gọi model được, log này sẽ cho biết hướng browser bridge cấp mạng có khả thi hay không.

## User-Agent

Có ba chế độ để so sánh:

1. WebView mặc định.
2. Chrome Android-like.
3. Chrome Desktop-like.

Mỗi lần đổi, nhấn `Áp dụng + reload` và thử lại từ đầu.

## File upload và Live

WebChromeClient hỗ trợ `ACTION_OPEN_DOCUMENT`, multiple file, MIME accept types, popup window, yêu cầu microphone/camera và ghi lại toàn bộ trạng thái liên quan. Nút `Xin mic/camera` cho phép chuẩn bị trước quyền Android trước khi thử tính năng Live.

## Nhật ký

Phòng lab có logger riêng **luôn ghi file**, không phụ thuộc cấu hình log của ứng dụng chính.

Log bao gồm:

- lifecycle Activity/WebView;
- WebView package/version/user-agent;
- page load/progress/title/navigation;
- HTTP/WebResource errors;
- renderer process gone;
- console JavaScript;
- DOM candidate scoring;
- MutationObserver output;
- fetch/XHR/WebSocket metadata và preview text giới hạn;
- resource timing;
- file chooser metadata;
- permission requests;
- cookie **tên + độ dài giá trị**, không lưu giá trị cookie;
- memory trim/low-memory;
- snapshot Android và JavaScript.

Nhấn `Chia sẻ log ZIP` để xuất toàn bộ session thử nghiệm hiện tại.

## Nguyên tắc thử nghiệm

- Không merge nhánh này vào `main` khi chưa có kết quả thực tế trên điện thoại.
- Không kết luận một phương án thất bại chỉ vì AUTO A lỗi; phải thử AUTO B và manual observation.
- Giá trị lớn nhất của bản POC là xác định chính xác lớp nào thất bại: login/WebView, DOM, click, response extraction, network, WebSocket, background hay renderer.
