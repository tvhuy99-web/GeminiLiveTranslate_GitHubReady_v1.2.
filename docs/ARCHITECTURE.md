# Kiến trúc kỹ thuật

## Mục tiêu

- UI rõ ràng và thuận tiện hơn bản tham khảo, không ép pixel-copy.
- Tách UI khỏi audio/network để dễ test và thay model.
- Không phụ thuộc Lua.
- Duy trì phiên khi Activity bị đưa xuống nền.
- Giữ độ trễ có giới hạn khi mạng chậm.
- Khôi phục phiên Gemini khi socket bị luân chuyển.
- Không giữ secret ở dạng rõ trên ổ đĩa.

## Luồng dữ liệu

```text
File / Microphone / Playback Capture
             |
             v
 StreamingPcmConverter
 mono PCM16 16 kHz liên tục
             |
             +-----------------------> Original WAV / original player
             |
             v
 bounded app queue (pacingMaxBuffer)
             |
             v
 OkHttp WebSocket queue guard
             |
             v
      Gemini Live WebSocket
             |
       +-----+------------------+
       |                        |
       v                        v
translated PCM16 24 kHz     transcript text
       |                        |
       v                        v
AudioTrack / WAV / mixer     UI / SRT / TXT / TTS fallback
```

## Phiên dịch

`TranslationService` là chủ sở hữu duy nhất của tài nguyên phiên:

- `GeminiLiveClient`
- `AudioSource`
- hàng đợi `InputFrame`
- resumption handle mới nhất
- `AudioTrack` gốc và dịch
- `MediaProjection`
- `WakeLock`
- bộ ghi WAV
- reconnect/GoAway jobs
- timeline phụ đề

Activity chỉ bind để điều khiển và quan sát `StateFlow<SessionUiState>`. Việc xoay màn hình hoặc mở trình duyệt mini không làm mất phiên đang chạy.

## Backpressure hai tầng

### Hàng đợi ứng dụng

`pacingMaxBuffer` là sức chứa của `LinkedBlockingDeque<InputFrame>` trong service.

- **Tệp:** `offerLast` có chờ, vì mất chunk sẽ làm thiếu nội dung bản dịch.
- **Microphone/Playback Capture:** khi đầy, bỏ `Audio` cũ nhất rồi chèn chunk mới để giới hạn độ trễ.
- `StreamEnd` không bị đẩy ra khỏi queue và luôn đứng sau phần audio đã nhận.
- Mỗi frame mang `epoch`; seek, đổi ngôn ngữ hoặc stop tăng epoch để frame của timeline cũ tự vô hiệu.

### Hàng đợi OkHttp

Trước khi gửi, `GeminiLiveClient` đọc `WebSocket.queueSize()`. Khi số byte chờ vượt ngưỡng tính từ kích thước chunk và `pacingMaxBuffer`, client trả `BACKPRESSURED`. Sender giữ nguyên frame đầu và thử lại, không nhân bản payload.

Health status hiển thị:

- số chunk đang chờ/sức chứa
- số KB đang chờ ở WebSocket
- số chunk realtime đã bỏ
- số backpressure event
- số lần resume và GoAway

## WebSocket và session resumption

Setup gồm:

- model có tiền tố `models/`
- `responseModalities = ["AUDIO"]`
- `inputAudioTranscription = {}`
- `outputAudioTranscription = {}`
- `translationConfig.targetLanguageCode`
- `translationConfig.echoTargetLanguage`
- `sessionResumption`, có `handle` khi đang nối lại phiên cũ

Khi nhận `sessionResumptionUpdate` có `resumable=true`, service lưu `newHandle`. Handle chỉ sống trong phiên service hiện tại và được dùng cho lần reconnect kế tiếp.

Khi nhận `goAway.timeLeft`:

1. Tạm dừng nguồn và player.
2. Giữ nguyên queue ứng dụng và resumption handle.
3. Chờ một khoảng an toàn ngắn hơn `timeLeft`.
4. Tạo client mới và gửi handle trong setup.
5. Resume nguồn sau `setupComplete`.

Seek tệp hoặc đổi ngôn ngữ microphone xóa queue, tăng epoch và xóa handle vì đây là ngữ cảnh mới.

## Audio

### Streaming conversion

`StreamingPcmConverter` thực hiện ba việc trong một pipeline:

1. Giữ byte dư chưa đủ một frame PCM.
2. Downmix nhiều kênh về mono.
3. Resample tuyến tính, giữ `sourcePosition` và mẫu biên qua các lần gọi.

`flush()` phát nốt tail khi tệp kết thúc hoặc format đổi. `reset()` bỏ trạng thái khi seek hoặc khi cần loại audio tích lũy trong lúc pause.

### Microphone

Ưu tiên 16 kHz mono. Nếu audio HAL từ chối, thử 48 kHz hoặc 44,1 kHz mono rồi streaming-resample. Acoustic Echo Canceler và Noise Suppressor được bật khi thiết bị hỗ trợ. Sau reconnect/pause, buffer cũ được drain bằng chế độ non-blocking trước khi gửi tiếp.

### Playback Capture

Ưu tiên 48 kHz stereo, sau đó 44,1 kHz stereo, 48 kHz mono và 16 kHz mono. `MediaProjection.Callback.onStop()` đánh dấu projection bị thu hồi, ngắt `AudioRecord` và trả lỗi về service. Dừng chủ động không bị báo thành lỗi.

### Tệp

`MediaExtractor` chọn track audio; `MediaCodec` decode về PCM. Pacing dựa trên presentation timestamp. Khi output format thay đổi, converter flush tail rồi reconfigure. Seek flush decoder, reset converter và tạo phiên Gemini mới.

### Output

Giọng AI dùng AudioTrack 24 kHz mono với queue hữu hạn và chính sách bỏ chunk cũ khi quá tải. Cơ chế này ưu tiên độ trễ thấp hơn phát lại một hàng đợi đã lỗi thời.

## Reconnect

- Lỗi xác thực và model: dừng ngay.
- Quota/rate limit: backoff dài.
- Lỗi mạng: backoff tuyến tính có giới hạn.
- GoAway: chủ động luân chuyển socket, không tính là một lần thất bại.
- Queue audio thuộc service nên không bị mất khi client WebSocket được thay mới.
- Nguồn và player chỉ resume sau `setupComplete`.

## Secret

`ApiKeyStore` tạo khóa AES trong `AndroidKeyStore`, mã hóa payload danh sách key bằng AES/GCM/NoPadding. IV ngẫu nhiên được lưu cùng ciphertext. App backup bị tắt trong manifest.

## Khả năng mở rộng

Có thể thay API Key trực tiếp bằng gateway cấp ephemeral token mà không đổi audio pipeline. Điểm thay chủ yếu là credential provider và `GeminiLiveClient`.

## Diagnostics dùng chung

`AppLogRepository` là singleton theo process. Mọi `SessionLogger` chỉ là facade nên Activity, Service và client mạng không còn giữ các bộ đệm riêng.

```text
UI / Settings / Service / WebSocket / Audio
                 |
                 v
          AppLogRepository
       +---------+----------+
       |                    |
       v                    v
memory ring (5000)   buffered rotating files
       |                    |
       +---------+----------+
                 v
         diagnostic ZIP export
```

Trước khi lưu, entry đi qua redaction theo hai lớp:

1. So khớp chính xác với danh sách API Key đang được mã hóa trong `ApiKeyStore`.
2. Regex cho query credential, Authorization, Bearer và trường token JSON.

`DiagnosticContext` giữ snapshot trạng thái không nhạy cảm của ứng dụng và phiên. Health monitor cập nhật queue, wire bytes, drop, backpressure, reconnect, resumption và GoAway để bundle phản ánh cả trạng thái cuối, không chỉ dòng lỗi.

Application-level uncaught exception handler ghi tag `Crash`, kèm stack trace, rồi flush writer trước khi chuyển lỗi cho handler mặc định.

## Quy tắc áp dụng cài đặt

`SettingsPolicy` là nguồn duy nhất cho:

- `sanitize()` khi đọc và lưu;
- profile `realtime`, `balanced`, `stable`, `custom`;
- `diff()` giữa cấu hình active và persisted;
- `activeSessionSettings()` để giữ nguyên các giá trị không được phép đổi giữa phiên.

Bốn nhóm thay đổi:

1. **Immediate:** volume, ducking, TTS, log, UI và lựa chọn phụ trợ.
2. **Playback rebuild:** buffer/queue/jitter của giọng dịch.
3. **Reconnect:** model, ngôn ngữ đích và echo target.
4. **Next session:** quality/input buffer, file sync, pacing, input queue và recorder mode.

Preferences luôn chứa lựa chọn mới của người dùng. Khi service đang chạy, `settings` chỉ nhận phần an toàn; các giá trị next-session được giữ từ cấu hình active. Lần `startTranslation()` kế tiếp tải toàn bộ persisted settings.

Đổi API Key đang chọn đi qua `ACTION_REFRESH_API_KEY`, xóa resumption handle và audio chờ không còn hợp lệ rồi tạo socket mới. Xóa hết key dừng phiên.
