# Đóng góp

1. Tạo nhánh từ `main`.
2. Không commit API Key, token, keystore hoặc tệp chẩn đoán riêng tư.
3. Chạy các kiểm tra trong pull request template.
4. Với thay đổi audio/network, bổ sung log có cấu trúc nhưng không log từng chunk ở mức thông thường.
5. Với thay đổi cài đặt, cập nhật `SettingsPolicy`, test và ghi rõ áp dụng ngay, reconnect hay phiên sau.
6. Pull request chỉ được merge khi workflow **Android CI** xanh.
