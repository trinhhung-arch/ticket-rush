# ADR 0004: Quan sát hệ thống bằng OpenTelemetry, trace đi xuyên qua outbox

- Trạng thái: Đã chấp nhận
- Liên quan: NFR-OBS-01, NFR-OBS-02, NFR-OBS-03, NFR-OBS-04, NFR-PERF-04

## Bối cảnh

Một lần đặt vé đi qua 5 service và 3 topic Kafka. Khi có sự cố, phải trả lời được: request này đã đi qua đâu, chậm ở bước nào, log nào thuộc về nó.

Khó nhất là outbox: message không được gửi trong request, mà được relay gửi sau đó trong một luồng khác. Nếu chỉ bật tracing cho Kafka, trace sẽ đứt ở bảng outbox.

## Quyết định

- **Trace và log** gửi qua OTLP tới OpenTelemetry Collector. Collector chuyển trace sang Jaeger và log sang Loki. Metric thì Prometheus kéo từ `/actuator/prometheus`.
- **Trace xuyên outbox:** `OutboxWriter` lưu `traceparent` (W3C) của span đang chạy vào cột `trace_parent`. `OutboxRelay` khôi phục ngữ cảnh đó, mở span `outbox publish <type>`, rồi gửi. KafkaTemplate gắn `traceparent` vào header, và listener ở service bên kia nối tiếp trace.
- **Log** ra console như cũ, đồng thời gửi qua OTLP bằng Logback appender của OpenTelemetry. Mỗi dòng log mang `trace_id`, và log nghiệp vụ ghi kèm `bookingId`.
- **Metric nghiệp vụ:** số lần giữ ghế theo kết quả, số booking kết thúc theo lý do, thời gian từ lúc trả tiền tới CONFIRMED (histogram), số thanh toán theo kết quả, số vé đã phát, số email đã gửi, độ trễ và số message tồn của outbox.
- **Lọc nhiễu:** không tạo trace cho request tới `/actuator` (Prometheus scrape mỗi 5 giây) và cho tác vụ định kỳ (relay chạy mỗi 200 ms).
- Cấu hình dùng chung nằm trong module `observability`, được mọi service import. Export mặc định tắt, Docker Compose bật lên, nên test và lúc chạy từ IDE không bị log lỗi vì thiếu collector.

## Kết quả đo được

Sau `./scripts/smoke-test.sh`, trace thanh toán có 12 span qua 5 service:
`api-gateway → payment-service → Kafka → booking-service → Kafka → ticket-service → Kafka → notification-service`.
Loki trả về mọi dòng log của một booking khi tìm theo `bookingId`. Thời gian từ lúc trả tiền tới CONFIRMED khoảng 70–200 ms, trong khi mục tiêu p95 là 3 s.

## Đánh đổi

- Thêm 5 container (collector, Jaeger, Loki, Prometheus, Grafana), khoảng 1 GB RAM.
- Lấy mẫu 100% trace chỉ hợp với môi trường dev. Production nên hạ xuống, hoặc lấy mẫu ở collector (tail sampling).
- Logback appender của OpenTelemetry phải khớp phiên bản SDK mà Spring Boot quản lý; được ghim trong `pom.xml` gốc.

## Tham khảo

- [open-telemetry/opentelemetry-demo](https://github.com/open-telemetry/opentelemetry-demo): collector nhận OTLP, trace đi qua Kafka
- [spring-kafka samples/sample-08](https://github.com/spring-projects/spring-kafka/tree/HEAD/samples): truyền observation từ `KafkaTemplate` sang `@KafkaListener`
