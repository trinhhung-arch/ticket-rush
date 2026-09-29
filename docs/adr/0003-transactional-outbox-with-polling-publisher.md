# ADR 0003: Transactional Outbox với polling publisher, consumer idempotent

- Trạng thái: Đã chấp nhận
- Liên quan: NFR-CORR-02, NFR-CORR-03, NFR-CORR-06

## Bối cảnh

Khi Event Service công bố sự kiện, nó phải vừa cập nhật database vừa gửi `EventPublished` lên Kafka. Làm hai việc riêng rẽ (dual write) thì có lúc DB đã commit mà message chưa gửi, hoặc ngược lại.

## Quyết định

- Service ghi message vào bảng `outbox_message` trong **cùng transaction** với thay đổi dữ liệu (`OutboxWriter`, bắt buộc có transaction đang chạy).
- `OutboxRelay` chạy mỗi 200 ms: khoá một lô dòng chưa gửi bằng `FOR UPDATE SKIP LOCKED`, gửi lên Kafka, chờ Kafka xác nhận, rồi đánh dấu đã gửi. Nhiều instance chạy song song không gửi trùng một dòng.
- Kafka key = id của aggregate, để các message của cùng một booking/sự kiện đi vào cùng partition và giữ thứ tự.
- Mỗi message mang header `message-id`. Consumer ghi id vào bảng `processed_message` trong cùng transaction với việc xử lý (`IdempotentConsumer`); message lặp bị bỏ qua.
- Message lỗi được retry 3 lần, mỗi lần cách 1 giây, rồi chuyển sang topic `<topic>-dlt`.

## Lý do

- Polling publisher không cần thêm hạ tầng. Debezium (CDC) cho độ trễ thấp hơn nhưng cần Kafka Connect.
- Relay có thể gửi lặp (crash sau khi gửi, trước khi commit), nên idempotent consumer là bắt buộc chứ không phải tuỳ chọn.

## Đánh đổi

- Độ trễ thêm tối đa khoảng 200 ms.
- Bảng outbox cần dọn định kỳ; relay xoá dòng đã gửi quá 1 ngày.

## Tham khảo

- [debezium-examples/outbox](https://github.com/debezium/debezium-examples/tree/HEAD/outbox)
- [microservices.io: Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- [spring-kafka samples](https://github.com/spring-projects/spring-kafka/tree/HEAD/samples), sample-01 về dead-letter topic
