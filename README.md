# Gemini Live Translate Native

Ứng dụng Android dịch giọng nói trực tiếp viết **100% bằng Kotlin**. Dự án tham khảo bố cục và luồng tính năng từ gói `.xpk`, nhưng không sử dụng Lua, Lua runtime hoặc bridge Lua.

## Điểm chính

- Dịch từ **tệp âm thanh/video**, **microphone** và **âm thanh nội bộ**.
- PCM đầu vào 16-bit mono 16 kHz; giọng dịch đầu ra 16-bit mono 24 kHz.
- Gemini Live WebSocket với backpressure hai tầng, session resumption và GoAway.
- Streaming resampler giữ trạng thái giữa các chunk.
- Foreground Service, WakeLock, thông báo điều khiển và tự kết nối lại.
- Phụ đề trực tiếp, xuất **SRT** hoặc **TXT**.
- Giọng AI trực tiếp và Android TTS dự phòng.
- Tua, tạm dừng, âm lượng gốc/dịch và auto-ducking.
- Ghi WAV gốc, WAV dịch hoặc WAV trộn theo timeline.
- Trình duyệt mini có điều khiển video HTML5.
- Nhiều API Key, mã hóa AES-GCM bằng Android Keystore.
- Nhật ký dùng chung toàn ứng dụng và báo cáo chẩn đoán ZIP.
- Cài đặt được chuẩn hóa và áp dụng theo đúng vòng đời phiên.

## Yêu cầu

- Android Studio và JDK 17.
- Android SDK 35.
- Thiết bị Android 8.0 trở lên, `minSdk 26`.
- Thu âm thanh nội bộ yêu cầu Android 10 trở lên.
- API Key có quyền truy cập model đã cấu hình.

## Mở và build

1. Giải nén dự án.
2. Mở thư mục dự án trong Android Studio.
3. Chờ Gradle Sync hoàn tất.
4. Chạy:

```bash
./gradlew clean test lint assembleDebug
```

APK debug:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Trên Windows dùng `gradlew.bat`.

## Thay đổi chính ở 1.2.0

### Nhật ký dùng chung

`AppLogRepository` là kho log duy nhất của process. Log từ MainActivity, Settings, TranslationService, WebSocket, MediaProjection và các nguồn audio nằm trên cùng một timeline.

Mỗi entry có:

- thời gian đến mili giây;
- mức `E/W/I/D`;
- tag thành phần;
- thread;
- thông điệp đã che secret;
- stack trace nếu có exception.

Mặc định:

- mức log là **Thông thường**;
- ghi file được bật;
- transcript không được ghi;
- RAM giữ tối đa 5.000 entry;
- ổ đĩa giữ tối đa 5 file, khoảng 2 MB mỗi file.

Mở **Cài đặt > Hệ thống > Mở nhật ký** để:

- lọc theo mức và tag;
- tìm kiếm;
- bật/tắt tự cuộn;
- sao chép phần đang hiển thị;
- xóa log;
- tạo báo cáo ZIP.

Khi có lỗi, hãy tái hiện lỗi rồi nhấn **Gửi báo cáo**. Xem quy trình đầy đủ tại [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md).

### Báo cáo ZIP

Gói chẩn đoán bao gồm:

- thông tin phiên bản, Android, thiết bị, ABI và bộ nhớ;
- cấu hình đã khử dữ liệu nhạy cảm;
- trạng thái phiên gần nhất;
- queue, backpressure, drop, reconnect, resumption và GoAway;
- log trong RAM;
- các file log xoay vòng;
- stack trace của lỗi và crash chưa bắt.

API Key, Bearer token, Authorization và các trường token phổ biến được che. Nội dung hội thoại chỉ xuất hiện khi người dùng bật riêng **Cho phép ghi nội dung hội thoại**.

### Cài đặt nhất quán

`SettingsPolicy` quản lý toàn bộ:

- kiểm tra và sửa dữ liệu cũ;
- giới hạn giá trị số;
- chuẩn hóa mã ngôn ngữ;
- ba profile hiệu năng;
- phân loại thay đổi.

Khi nhấn **Lưu & áp dụng**:

- âm lượng, TTS, ducking, log và giao diện được áp dụng ngay;
- buffer phát được tạo lại khi cần;
- model, ngôn ngữ hoặc API Key mới tự tạo kết nối Gemini mới;
- buffer nguồn, pacing, queue gửi và ghi WAV được lưu cho phiên tiếp theo, không làm đổi nửa pipeline đang chạy.

Không còn nút “Áp dụng model” hoặc “Áp dụng mã” riêng. Nội dung ô nhập được đọc trực tiếp khi lưu. Draft cài đặt cũng được giữ khi xoay màn hình.

### Reset có phạm vi

Màn hình Hệ thống tách riêng:

- khôi phục cài đặt mặc định;
- xóa nhật ký;
- xóa bản ghi audio;
- xóa toàn bộ API Key;
- xóa toàn bộ dữ liệu do ứng dụng quản lý.

Ứng dụng không còn xóa mù toàn bộ `filesDir`.

## Cách dùng

1. Mở **API Key**, thêm key rồi chọn key cần dùng.
2. Nhấn **Kiểm tra API và kết nối**.
3. Chọn nguồn:
   - **Tệp âm thanh/video**: chọn tệp rồi bắt đầu.
   - **Microphone**: cấp quyền microphone và chọn ngôn ngữ đích.
   - **Ghi âm nội bộ**: cấp quyền microphone và MediaProjection.
4. Bật **Giọng AI** để phát audio do model trả về.
5. Dùng **Xuất phụ đề** để lưu SRT/TXT.

Nếu đổi ngôn ngữ hoặc API Key trong khi chạy, service sẽ tạm dừng nguồn, làm sạch audio chờ không còn hợp lệ và kết nối lại bằng cấu hình mới.

## Hàng đợi và độ bền mạng

Tùy chọn **Hàng đợi gửi tối đa** giới hạn trực tiếp số chunk PCM chờ gửi:

- Với tệp, decoder chờ khi queue đầy để không mất nội dung.
- Với microphone và âm thanh nội bộ, chunk cũ nhất bị bỏ khi nghẽn để giữ độ trễ có giới hạn.
- Client kiểm tra `WebSocket.queueSize()` trước khi gửi payload.

Health line hiển thị queue ứng dụng, queue WebSocket, drop, resumption, GoAway và backpressure.

## Audio liên tục

`StreamingPcmConverter` giữ:

- byte chưa đủ frame;
- mẫu biên cuối;
- pha nội suy;
- vị trí nguồn qua nhiều chunk.

Cả File, Microphone và Playback Capture đều dùng pipeline này. Seek và resume reset đúng phần timeline cần bỏ.

## MediaProjection

Nếu người dùng dừng chia sẻ từ system UI hoặc hệ thống thu hồi projection:

- `AudioRecord` được giải phóng;
- nguồn trả lỗi rõ ràng về service;
- notification và UI không tiếp tục báo trạng thái đang chạy giả.

Không phải ứng dụng nguồn nào cũng cho phép Playback Capture. Nội dung DRM, cuộc gọi và một số ứng dụng có thể chủ động chặn capture.

## Bảo mật API Key

Danh sách API Key được mã hóa bằng AES-GCM. Khóa mã hóa nằm trong Android Keystore, và backup ứng dụng bị tắt.

Logger làm mới danh sách secret ngay khi key thay đổi để che cả key vừa thêm. Tuy nhiên, trước khi gửi báo cáo cho bên không tin cậy, vẫn nên xem nhanh nội dung ZIP.

## Thư mục dữ liệu

Bản ghi audio:

```text
Android/data/com.oai.geminilivetranslate/files/Music/GeminiLiveTranslate/
```

Log nội bộ:

```text
files/diagnostics/
```

Báo cáo chẩn đoán tạm thời:

```text
cache/diagnostic-share/
```

## Kiến trúc

| Khối | Vai trò |
|---|---|
| `GeminiTranslateApp` | Khởi tạo diagnostics và bắt crash chưa xử lý |
| `MainActivity` | UI chính, quyền, chọn tệp, API Key và phụ đề |
| `SettingsActivity` | Cài đặt theo tab, validation và thao tác dữ liệu |
| `LogViewerActivity` | Lọc, tìm, sao chép, xóa và chia sẻ diagnostics |
| `AppLogRepository` | Timeline log dùng chung, rotation, redaction và ZIP |
| `SettingsPolicy` | Validation, profile, diff và quy tắc áp dụng phiên |
| `TranslationService` | Vòng đời phiên, queue, audio routing và live settings |
| `GeminiLiveClient` | WebSocket, setup, backpressure, resume và GoAway |
| `StreamingPcmConverter` | Downmix/resample có trạng thái |
| `FileAudioSource` | MediaExtractor/MediaCodec, seek và pacing |
| `MicAudioSource` | AudioRecord, AEC/NS và fallback sample rate |
| `InternalAudioSource` | Playback Capture và MediaProjection lifecycle |
| `StreamingPcmPlayer` | AudioTrack streaming và jitter queue |
| `SubtitleStore` | Timeline cue, SRT/TXT |
| `WavWriter` / `TimelineWavMixer` | Ghi và trộn WAV |
| `ApiKeyStore` | Android Keystore và AES-GCM |

Xem thêm:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md)
- [docs/BUILD_AND_RELEASE.md](docs/BUILD_AND_RELEASE.md)
- [CHANGELOG.md](CHANGELOG.md)

## Kiểm tra dự án

Kiểm tra cấu trúc không cần Android SDK:

```bash
python3 tools/verify_project.py
```

Kiểm tra đầy đủ trên máy phát triển:

```bash
./gradlew clean test lint assembleDebug
```

Môi trường tạo ZIP không có Android SDK và không tải được Gradle dependency qua DNS ngoài, nên APK chưa được build trong môi trường này.
