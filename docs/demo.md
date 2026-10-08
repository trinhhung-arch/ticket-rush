# Kịch bản video demo (khoảng 4 phút)

Video demo là một điều kiện hoàn thành của giai đoạn 5. Kịch bản dưới đây đi qua những gì nhà tuyển dụng muốn thấy nhất, theo thứ tự: chạy được thật, không bán trùng, chịu được sự cố, và quan sát được.

## Chuẩn bị (không quay)

```bash
./scripts/init-dev-env.sh
docker compose -f docker-compose.yml -f load-test/compose.yml up -d --build
./load-test/preflight.sh http://localhost:$(grep GATEWAY_PORT .env | cut -d= -f2)
```

Mở sẵn các tab:
- Swagger UI `http://localhost:<GATEWAY_PORT>/swagger-ui.html`
- Grafana `http://localhost:3000`, dashboard "TicketRush: tổng quan"
- Jaeger `http://localhost:16686`
- Mailpit `http://localhost:8025`

Chia đôi màn hình: một bên là terminal, một bên là trình duyệt.

## Các cảnh

| # | Thời lượng | Làm gì | Nói gì |
|---|---|---|---|
| 1 | 0:00–0:20 | Mở README, lướt sơ đồ kiến trúc | "TicketRush là backend bán vé gồm 7 service Spring Boot, giao tiếp qua Kafka bằng Saga và Outbox. Mục tiêu là mở bán đột biến mà không bán trùng ghế." |
| 2 | 0:20–0:50 | Swagger UI → Authorize, đăng nhập `alice@ticketrush.dev` qua Keycloak → gọi `GET /api/bookings` | "Mỗi request mang JWT do Keycloak cấp. Gateway và từng service đều tự kiểm tra token và vai trò." |
| 3 | 0:50–1:40 | Chạy `./scripts/smoke-test.sh`, dừng ở các bước 8 (webhook giả bị 401), 11 (email vé trong Mailpit), 11b (quét QR lần 2 bị 409) | "Đây là trọn luồng: giữ ghế, thanh toán qua webhook ký HMAC, nhận vé QR qua email, rồi check-in ở cổng. Vé chỉ vào cửa được một lần." |
| 4 | 1:40–2:10 | Jaeger: mở trace của request checkout, chỉ các span đi qua outbox và Kafka tới notification | "Một lần thanh toán tạo ra một trace duy nhất đi qua 5 service, kể cả qua bảng outbox và Kafka." |
| 5 | 2:10–3:10 | Chạy `./load-test/run-chaos.sh kafka`, mở dashboard Grafana trong lúc Kafka tắt; tua nhanh đến kết quả đối soát | "Tắt hẳn Kafka giữa lúc mở bán. Người mua vẫn giữ được ghế, vì event nằm chờ trong outbox. Khi Kafka bật lại, script đối soát đọc bốn database: không mất event nào, không ghế nào bán trùng." |
| 6 | 3:10–3:40 | Mở `docs/load-test-report.md`, bảng "Những gì diễn tập đã phát hiện và sửa" | "Lần diễn tập đầu thật ra không đạt. Metric chỉ ra một bulkhead ngầm của Spring Cloud và một lỗi của consumer Kafka 4. Tôi sửa từng lỗi và đo lại." |
| 7 | 3:40–4:00 | `kubectl get pods -o wide` trên cụm kind (quay trước); kết quả `pod-drills.sh` | "Cùng hệ thống chạy trên Kubernetes bằng Helm. Mỗi service có 2 instance: xoá, kill hay rolling restart pod đều không làm request nào lỗi." |

## Mẹo quay

- Phóng to chữ terminal lên cỡ 18 trở lên. Ẩn tab và bookmark cá nhân của trình duyệt.
- Cảnh 5 chạy mất khoảng 6 phút, nên quay trọn rồi cắt còn khoảng 1 phút.
- Không để lộ file `.env`: mật khẩu demo chỉ gõ trong ô đăng nhập Keycloak.
- Xuất video 1080p, đăng lên YouTube chế độ "không công khai" rồi gắn link vào đầu README.
