# Báo cáo đo tải (giai đoạn 4)

Đo ngày 30/09/2026. Mọi số liệu là số thật từ k6 và từ database, không làm tròn có lợi.

## Môi trường

- Một máy MacBook: 10 CPU, 64 GB RAM. Docker (OrbStack) được cấp 10 CPU và 16 GB.
- Cả 17 container (7 service, Postgres, Redis, Kafka, bộ quan sát…) và k6 **chạy chung trên máy này**, nên số liệu thấp hơn so với khi tách máy tạo tải ra riêng.
- Trace lấy mẫu 1% khi đo (`TRACING_SAMPLING_PROBABILITY=0.01`). Trước mỗi lần đo có 20 giây khởi động để JVM kịp tối ưu, phần này không tính.
- Sự kiện 5.000 ghế (5 khu, mỗi khu 10 hàng × 100 ghế). Mỗi người mua giữ 1 ghế ngẫu nhiên. Ai giữ được ghế thì mất 5–15 giây điền form rồi thanh toán qua cổng giả lập.

## Kết quả

| Kịch bản | Yêu cầu | Mục tiêu | Kết quả | Đạt |
|---|---|---|---|---|
| Flash sale 1.000 req/s trong 5 phút | NFR-PERF-01 | ≥ 1.000 req/s, lỗi 5xx < 0,1% | 300.001 request giữ ghế trong 5 phút, 0 lỗi | Đạt |
| Độ trễ giữ ghế ở 1.000 req/s | NFR-PERF-02 | p95 ≤ 200 ms, p99 ≤ 500 ms | p95 **10 ms**, p99 **31 ms**, max 299 ms | Đạt |
| Không bán trùng | NFR-CORR-01 | 0 ghế bán 2 lần | 5.000 ghế bán, 5.000 booking CONFIRMED, **0** ghế trùng | Đạt |
| Xoá Redis ở giây thứ 8 của đợt bán | NFR-AVAIL-05 | 0 bán trùng, người trả tiền thừa được hoàn | 7.616 người trả tiền cho 5.000 ghế: 5.000 CONFIRMED, 2.616 SEAT_CONFLICT, **2.616/2.616 được hoàn tiền**, 0 ghế trùng | Đạt |
| Từ lúc trả tiền tới CONFIRMED | NFR-PERF-04 | p95 ≤ 3 s | p95 **0,35 s**, p99 0,45 s | Đạt |
| Danh sách sự kiện, 200 req/s | NFR-PERF-03 | p95 ≤ 150 ms | p95 **6,3 ms** | Đạt |
| Sơ đồ 5.000 ghế, 100 req/s | NFR-PERF-03 | p95 ≤ 300 ms | p95 **10,8 ms** | Đạt |
| 50.000 người vào hàng trong 1 phút | NFR-PERF-05 | chịu được, Booking không nhận quá ngưỡng | 50.041 lượt, p95 **2,8 ms**, 0 lỗi; đúng **2.000** người được vào (bằng sức chứa), 48.041 xếp hàng | Đạt |
| Rate limit 10 req/s mỗi người | FR-GW-02 | request thứ 11 → 429 | Bot bắn 20 request cùng lúc: 10 qua, 10 nhận 429 | Đạt |
| 2 instance Booking | NFR-SCAL-01 | tải chia đều | 1.815 / 1.691 lượt giữ ghế trên 2 instance | Chia đều; chưa đo được hệ số tăng thông lượng (xem dưới) |

NFR-SCAL-01 yêu cầu tăng 2 → 4 instance thì thông lượng tăng ≥ 1,6 lần. Trên một laptop mà k6 và mọi container dùng chung CPU, thêm instance không thêm CPU, nên phép đo này không có ý nghĩa. Ở 1.000 req/s, một instance Booking chỉ dùng khoảng 3 nhân và vẫn giữ p95 10 ms. Việc chia tải và các cơ chế cho nhiều instance (Redis, `SKIP LOCKED`, consumer group) đã được kiểm chứng; hệ số thông lượng cần đo trên cluster thật.

## Những gì đo tải đã phát hiện và sửa

Lần chạy đầu ở 1.000 req/s: giữ ghế p95 **227 ms**, thanh toán tới CONFIRMED p95 **21 giây**. Năm thay đổi dưới đây đưa về số liệu ở bảng trên.

| # | Phát hiện | Bằng chứng | Sửa |
|---|---|---|---|
| 1 | Gateway gọi vào IP cũ sau khi container Booking được tạo lại | `Connection refused: booking-service/192.168.107.12` | Reactor Netty cache DNS tối đa 10 s và chọn địa chỉ xoay vòng (`HttpClientConfig`); cũng là điều kiện để scale ngang |
| 2 | Pool 20 connection của Booking cạn: request HTTP và listener Kafka của saga tranh nhau | `hikaricp_connections_pending` đỉnh 571; mỗi connection chỉ giữ 4,4 ms trung bình | Pool 50 cho Booking, Postgres `max_connections=200` |
| 3 | Listener xử lý không kịp đợt dồn 10.000 event thanh toán; lag báo thấp vì message đã được kéo về nhưng chưa xử lý | Đo bằng timestamp trong DB: outbox → Kafka p95 0,4 s, nhưng Kafka → xử lý xong p95 10 s | 12 partition, 12 luồng đọc mỗi instance |
| 4 | Mỗi message tốn ~9,5 ms dù Postgres commit chỉ 1,4 ms (pgbench) | `spring_kafka_listener_seconds` | Idempotent consumer còn 1 câu `INSERT … ON CONFLICT DO NOTHING`; bước `PaymentCreated` thành 1 câu update có điều kiện, không khoá dòng, không tải entity |
| 5 | Kịch bản ban đầu cho 5.000 người trả tiền cùng một giây sau khi giữ ghế và thăm dò 2 lần/giây, sinh bão request thăm dò | 3.190 req/s tổng, 155 nghìn request thăm dò | Người mua mất 5–15 s điền form, thăm dò 1 lần/giây: mô phỏng sát thực tế hơn |

Không thêm cache Redis cho danh sách sự kiện: p95 đã là 6,3 ms, thêm cache chỉ thêm độ phức tạp (NFR-PERF-03 để cache là phương án, không bắt buộc).

## Chạy lại

```bash
./scripts/init-dev-env.sh
TRACING_SAMPLING_PROBABILITY=0.01 docker compose up -d --build

RATE=1000 DURATION=5m ./load-test/run-flash-sale.sh             # flash sale + đối soát
RATE=1000 DURATION=1m ./load-test/run-flash-sale.sh --flush-redis-at 8   # xoá Redis giữa đợt bán
k6 run load-test/read-paths.js
k6 run load-test/waiting-room.js
./scripts/check-oversell.sh <eventId>                            # đối soát một sự kiện bất kỳ
```

Nếu Gateway không ở cổng 8080 thì thêm `GATEWAY=http://localhost:<cổng>`. Kết quả k6 được lưu trong `load-test/results/` (không commit).
