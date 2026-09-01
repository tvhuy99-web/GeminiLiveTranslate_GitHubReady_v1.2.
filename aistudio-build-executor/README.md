# AI Studio Build Executor POC

Mục tiêu của POC là biến một app **Google AI Studio Build** thành executor do chúng ta kiểm soát:

`Android APK -> HTTPS/SSE -> AI Studio Build server -> Gemini -> response/stream -> APK`

Không dò DOM, không bấm Run của Playground và không phụ thuộc TalkBack/accessibility click.

## Vì sao khác các mẫu tham khảo

Các mẫu `dark-server.js` / `dark-browser.js` và `remix-build-executor` chứng minh mô hình browser executor + relay. POC này dùng khả năng full-stack mới của AI Studio Build: Gemini được gọi từ **Node.js server-side runtime** bằng `GEMINI_API_KEY` secret do AI Studio cấu hình, nên key không cần xuống browser.

## Route

- `GET /api/bridge/health`
- `POST /api/bridge/generate`
- `POST /api/bridge/stream` (SSE)

Request tối giản:

```json
{
  "request_id": "android_123",
  "model": "gemini-3.1-flash-live-preview",
  "prompt": "Chỉ trả lời đúng chuỗi AIS_BUILD_BRIDGE_OK_20260901"
}
```

`contents`, `systemInstruction`, `generationConfig`, `safetySettings`, `tools` và `toolConfig` cũng được chuyển tiếp có kiểm soát.

## Secrets

- `GEMINI_API_KEY`: AI Studio Build tự cấu hình cho app Gemini mới.
- `BRIDGE_TOKEN`: tùy chọn trong POC. Nếu đặt, client phải gửi `x-bridge-token`. Trước khi share/deploy thật nên cấu hình token hoặc auth mạnh hơn.
- `BRIDGE_MOCK=1`: chỉ dùng local test, trả `AIS_BUILD_BRIDGE_OK_20260901` mà không gọi Gemini.

## Chạy local

```bash
npm install
BRIDGE_MOCK=1 npm start
```

## Đưa vào Google AI Studio Build

Có thể dùng source trong thư mục này làm mẫu cho Build Agent hoặc upload/import các file. Yêu cầu Agent giữ toàn bộ Gemini call ở server side, không đưa `GEMINI_API_KEY` vào client.

Bài test đầu tiên trên thiết bị là lấy Shared/Integration URL của app Build, nhập URL đó vào launcher **AI Studio Build Executor Lab**, rồi thử Health -> Generate -> Stream.

Nếu direct HTTPS route không truy cập được do lớp auth/routing của Shared URL, bước kế tiếp sẽ là WebView chỉ tải app Build của chính chúng ta và dùng JS bridge nội bộ. Cách fallback này vẫn không điều khiển Playground UI.
