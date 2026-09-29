# ADR 0001: Saga đặt vé dạng điều phối (orchestration), Booking Service làm điều phối

- Trạng thái: Đã chấp nhận
- Liên quan: FR-BKG-09, NFR-CORR-04

## Bối cảnh

Một lần đặt vé chạm vào 3 service: Booking giữ ghế, Payment thu tiền, Ticket phát vé. Không có transaction chung giữa các database, nên cần Saga: chuỗi transaction cục bộ, bước nào lỗi thì chạy bước bù trừ.

Có hai kiểu Saga:

- **Choreography**: các service tự phản ứng với event của nhau, không ai điều phối.
- **Orchestration**: một service giữ trạng thái saga và gửi lệnh cho các service khác.

## Quyết định

Dùng orchestration, Booking Service là điều phối viên. Booking gửi lệnh (`CreatePayment`, `CancelPayment`, `RefundPayment`) và nghe event (`PaymentSucceeded`, `PaymentFailed`...). Toàn bộ trạng thái nằm trong bảng `booking`: `PENDING → AWAITING_PAYMENT → CONFIRMED | CANCELLED`.

## Lý do

- Luồng có nhiều nhánh bù trừ (hết hạn, thanh toán lỗi, tiền về trễ, ghế xung đột). Gom về một chỗ thì dễ đọc, dễ test hơn là rải logic qua nhiều service.
- Trả lời được ngay câu "booking này đang ở bước nào" bằng một câu SQL.
- Payment Service không cần biết khái niệm ghế hay booking, chỉ nhận lệnh.

## Đánh đổi

- Booking Service phụ thuộc vào contract lệnh của Payment.
- Điều phối viên có thể phình to; nếu thêm nhiều bước, tách logic saga thành class riêng.

## Tham khảo

- [ftgo-application: CreateOrderSaga](https://github.com/microservices-patterns/ftgo-application/tree/HEAD/ftgo-order-service/src/main/java/net/chrisrichardson/ftgo/orderservice/sagas)
- [eventuate-tram-sagas-examples-customers-and-orders](https://github.com/eventuate-tram/eventuate-tram-sagas-examples-customers-and-orders)
