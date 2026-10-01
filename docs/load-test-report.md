# Báo cáo đo tải và diễn tập sự cố (giai đoạn 4 và 5)

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

## Diễn tập sự cố (giai đoạn 5)

Đo ngày 01/10/2026 trên cùng máy, lúc này mọi request đều mang JWT (dùng issuer load-test, xem `load-test/README.md`).

**Cách chạy:**
- Kịch bản `load-test/run-chaos.sh`: người mua giữ ghế với tốc độ 50 lần/giây trong 4 phút. Ai giữ được ghế thì mở trang thanh toán tối đa 4 phút, gặp 503/504 thì thử lại.
- 30 giây sau khi mở bán, dịch vụ mục tiêu bị tắt.
- Sau đó `scripts/reconcile.py` đọc cả bốn database và kiểm tra lặp lại cho tới khi mọi thứ khớp:
  - booking đã hết hạn giữ chỗ đều ở trạng thái cuối;
  - CONFIRMED ⇔ payment SUCCEEDED;
  - không booking CANCELLED nào giữ tiền khách;
  - mỗi booking CONFIRMED có đủ vé và email;
  - không ghế nào bán trùng;
  - không dòng outbox nào chưa được gửi.

| Kịch bản | Yêu cầu | Mục tiêu | Kết quả | Đạt |
|---|---|---|---|---|
| Tắt Payment Service 2 phút | NFR-AVAIL-01 | Giữ ghế vẫn chạy; booking về trạng thái cuối ≤ 60 s sau khi bật lại | Trong 2 phút sự cố: 0 request giữ ghế lỗi. 2.648 booking giữ trong lúc sự cố đều CONFIRMED, chậm nhất **45,8 s** sau khi bật lại (p95 45,1 s). Khoảng 10 s ngay sau khi bật lại có 4,4% request giữ ghế bị từ chối (xem "Còn tồn tại") | Đạt, kèm một điểm cần cải thiện |
| Tắt Kafka 60 giây | NFR-CORR-02 | 0 event mất | Mọi dòng outbox đều được gửi. 4.558/4.558 booking CONFIRMED đều có vé và email. 0/12.001 request giữ ghế lỗi (p99 102 ms), 1/88.361 request lỗi tổng. Mọi thứ khớp **169 s** sau khi Kafka bật lại, tính cả 2 phút tải vẫn chạy tiếp | Đạt |
| Đối soát cuối đợt | NFR-CORR-04 | 0 lệch giữa booking và payment | 0 lệch ở mọi lần diễn tập | Đạt |
| Xoá pod, kill pod, rolling restart trên kind | NFR-AVAIL-04, 06 | Lỗi không quá 1 s | **0/10.000** request lỗi, p99 22 ms | Đạt |

### Những gì diễn tập đã phát hiện và sửa

Lần chạy đầu của cả hai kịch bản đều **không đạt**, dù đối soát dữ liệu vẫn đúng. Từng nguyên nhân được tìm ra bằng số liệu, không đoán:

| # | Triệu chứng | Tìm ra bằng | Sửa |
|---|---|---|---|
| 1 | Tắt Payment: 77% request giữ ghế bị 503, dù booking-service vẫn khoẻ | Metric Resilience4j: breaker của booking-service ghi 275 lỗi nhưng từ chối 248.043 lời gọi. Có một bulkhead 25 lời gọi mà cấu hình không hề khai báo | Spring Cloud CircuitBreaker tự bật bulkhead 25 lời gọi đồng thời. Hàng nghìn khách cùng thăm dò booking vượt ngưỡng đó, bị tính là lỗi, rồi làm breaker mở. Đã tắt bulkhead (Gateway là non-blocking và API đặt vé đã có rate limit). `ResilienceTest` tái hiện lỗi này |
| 2 | Còn 4% request giữ ghế lỗi, do Gateway mở kết nối tới booking-service quá 1 s | Log lý do fallback: 30 lần `ConnectTimeoutException`. `/proc/net/netstat` trong container: `ListenOverflows 30` | Hàng đợi accept của Tomcat mặc định chỉ 100, nên kernel bỏ gói SYN khi Gateway mở hàng loạt kết nối mới. Nâng `server.tomcat.accept-count` lên 1024 |
| 3 | Tắt Kafka: 38% request giữ ghế bị timeout, dù giữ ghế không cần Kafka | Pool DB của booking đầy 50/50 với 110 request chờ. Postgres có nhiều kết nối "idle in transaction". Tắt Kafka lúc không có tải mà CPU booking-service vẫn lên 334% | Giao thức consumer mới của Kafka 4 (KIP-848) quay vòng liên tục khi mất broker. Giao thức này được bật trước đó để trị lỗi rebalance chập chờn trong test. Cùng lúc tắt Kafka, consumer classic chỉ dùng 8,6% CPU, consumer KIP-848 dùng 249%. Quay lại giao thức classic. Lỗi rebalance gốc được sửa tận gốc: mỗi listener phụ (danh mục sự kiện, email huỷ, ban tổ chức) có consumer group riêng, nên không nhóm nào có hai kiểu subscription |
| 4 | Sau khi Kafka bật lại, 3.710 booking CONFIRMED chưa có vé trong 5,5 phút | Script đối soát báo thiếu. `kafka-consumer-groups` cho thấy nhóm ticket-service kẹt ở PreparingRebalance, 8/12 partition không có ai đọc | Rebalance chờ thành viên không quay lại cho tới hết `max.poll.interval.ms` (mặc định 300 s). Hạ xuống 60 s vì mỗi batch chỉ mất vài giây |
| 5 | Kafka bật lại thì thanh toán bị timeout | Pool DB của payment-service có 184 request chờ, pool chỉ 10 kết nối | 12 luồng consumer, mỗi luồng giữ một kết nối trong transaction, đã chiếm hết pool. Pool giờ cấu hình được: 30 cho payment, 20 cho ticket và notification |

Một lỗi nữa lộ ra ngoài diễn tập, khi máy thức dậy sau một đêm ngủ: notification-service **đứng hẳn**, không gửi email nào cho tới khi khởi động lại.
- **Tìm ra bằng:**
  - Thread dump có cả virtual thread (`jcmd Thread.dump_to_file`): 12 consumer Kafka bị pin vào carrier, cùng chờ lock của logback.
  - `/proc/net/tcp`: 12 socket tới Postgres và Kafka có dữ liệu nằm sẵn trong kernel mà không thread nào đọc.
- **Nguyên nhân:**
  - kafka-clients ghi log ngay trong các method `synchronized`. Trên Java 21, việc đó pin virtual thread vào carrier.
  - Khi máy thức dậy, mọi consumer cùng hết poll timeout và cùng ghi log một lúc, nên số carrier bị pin vượt số CPU (10).
  - Lock của logback được trao cho một virtual thread không còn carrier nào để chạy, nên toàn bộ service bị deadlock.
- **Sửa:** consumer Kafka chạy trên platform thread; phần còn lại của service vẫn dùng virtual thread. `KafkaConsumerThreadsTest` kiểm tra điều này; trước khi sửa, test này đỏ vì một task giao cho executor của consumer chờ 5 s không có carrier.
- **Hướng khác:** Java 24 trở lên (JEP 491) không còn pin virtual thread trong `synchronized`.

**Còn tồn tại: hiệu ứng "thundering herd" lúc Payment hồi phục.** Khoảng 2.600 người mua đã chờ 2 phút cùng thanh toán và thăm dò mỗi giây, đúng lúc saga xử lý dồn khoảng 5.000 event.
- **Tác động:**
  - Pool DB của payment-service có tới 926 request chờ, của booking-service tới 629.
  - Khoảng 2.300 lần bấm thanh toán nhận 504 dù tiền đã được ghi nhận. Người bấm lại nhận 409 "đã thanh toán"; không ai bị trừ tiền hai lần.
  - Ở lần đo cuối, 36 lời gọi tới booking bị timeout liền nhau làm breaker mở 10 s, khiến 517 request giữ ghế (4,4% của cả đợt) bị từ chối. Lần đo trước đó với cùng kịch bản thì không có request nào bị từ chối.
- **Hướng xử lý (chưa làm):**
  - client thử lại với backoff có jitter, hoặc nhận trạng thái qua SSE thay vì thăm dò mỗi giây;
  - trang thanh toán nhận biết giao dịch đã xong;
  - dùng cửa sổ breaker theo thời gian thay vì 20 lời gọi, để một đợt nghẽn 1–2 s không làm mở breaker 10 s.

## Chạy lại

```bash
./scripts/init-dev-env.sh
TRACING_SAMPLING_PROBABILITY=0.01 docker compose up -d --build

docker compose -f docker-compose.yml -f load-test/compose.yml up -d --build   # tin token của k6

RATE=1000 DURATION=5m ./load-test/run-flash-sale.sh             # flash sale + đối soát
RATE=1000 DURATION=1m ./load-test/run-flash-sale.sh --flush-redis-at 8   # xoá Redis giữa đợt bán
k6 run load-test/read-paths.js
k6 run load-test/waiting-room.js
./load-test/run-chaos.sh payment                                 # tắt Payment 2 phút giữa lúc có tải
./load-test/run-chaos.sh kafka                                   # tắt Kafka 60 giây
./scripts/reconcile.py <eventId>                                 # đối soát một sự kiện bất kỳ
./deploy/kind/up.sh && ./deploy/kind/pod-drills.sh               # diễn tập pod trên Kubernetes
```

Nếu Gateway không ở cổng 8080 thì thêm `GATEWAY=http://localhost:<cổng>`. Kết quả k6 được lưu trong `load-test/results/` (không commit).
