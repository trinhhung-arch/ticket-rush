# ADR 0007: Circuit breaker ở Gateway, webhook ký HMAC

- Trạng thái: Đã chấp nhận
- Liên quan: NFR-AVAIL-02, NFR-AVAIL-06, FR-PAY-04, NFR-SEC-02

## Bối cảnh

- **Lời gọi đồng bộ duy nhất** trong hệ thống là từ Gateway xuống service; giữa các service với nhau chỉ đi qua Kafka. Khi một service treo, request dồn ứ ở Gateway và chiếm kết nối của các route khác.
- **Webhook thanh toán không cần token người dùng.** Nếu không có cơ chế nào khác, ai biết `paymentId` cũng có thể tự báo "đã trả tiền".

## Quyết định

**Resilience4j ở Gateway,** mỗi service một circuit breaker:
- Timeout 2 giây. Kết nối phải mở được trong 1 giây.
- Breaker mở khi ít nhất 50% trong 20 lời gọi gần nhất bị lỗi. Lỗi gồm 5xx, timeout và không kết nối được; 4xx là lỗi của client nên không tính.
- Khi mở, breaker chặn 10 giây rồi thử lại 5 lời gọi.

**Khi breaker chặn hoặc hết giờ,** Gateway trả problem+json:
- 503 kèm `Retry-After` khi breaker đang mở.
- 504 khi hết giờ. Client gửi lại với cùng Idempotency-Key thì vẫn an toàn.

**Luồng SSE `/api/queue/.../stream` không qua breaker,** vì nó được thiết kế để mở nhiều phút.

**Tắt bulkhead mặc định của Spring Cloud CircuitBreaker.** Thư viện này ngầm thêm bulkhead 25 lời gọi đồng thời cho mỗi service, và mỗi lời gọi bị bulkhead từ chối lại bị tính là một lỗi của breaker.
- Lần diễn tập tắt Payment đầu tiên: hàng nghìn khách cùng thăm dò booking mỗi giây, vượt 25 lời gọi đồng thời và làm breaker của booking-service mở.
- Kết quả: Gateway tự trả 503 cho khoảng 250.000 request, trong khi booking-service vẫn khoẻ.
- Gateway là non-blocking, và API đặt vé đã có rate limit theo người dùng, nên bulkhead ở đây chỉ gây hại. `ResilienceTest` giữ lại kịch bản này: 80 lời gọi đồng thời tới một service chậm nhưng khoẻ đều phải thành công.

**Webhook ký theo cách của Stripe:**
- Header `X-Webhook-Signature: t=<unix>,v1=<hex>`, với `v1 = HMAC-SHA256(secret, "<t>.<body gốc>")`.
- Service kiểm tra chữ ký trên đúng các byte nhận được, trước khi parse JSON. So sánh chữ ký theo thời gian hằng.
- Webhook ký quá 5 phút bị từ chối.
- Mọi trường hợp sai đều trả 401.

**Cổng thanh toán giả nằm trong Payment Service.** Nó ký webhook bằng cùng secret rồi đẩy vào đúng hàm nhận webhook, nên cũng đi qua bước kiểm chữ ký như webhook từ ngoài.

**Tắt service êm** (`server.shutdown=graceful`):
- Request đang xử lý có tối đa 30 giây để xong.
- Docker và Kubernetes cho 35 giây trước khi kill.
- Trên Kubernetes có thêm `preStop` 5 giây, để endpoint bị gỡ khỏi Service trước khi app ngừng nhận request.

## Lý do

- **Đặt breaker ở Gateway** là đủ, vì không service nào gọi đồng bộ sang service khác.
- **Timestamp nằm trong phần được ký,** nên không thể lấy một webhook cũ rồi gắn timestamp mới. Webhook trong cửa sổ 5 phút có bị gửi lại thì cũng chỉ được xử lý một lần (FR-PAY-03).

## Đánh đổi

- **Khi breaker mở, người dùng thấy 503 ngay** thay vì chờ, dù service có thể đã hồi phục trước khi hết 10 giây.
- **Secret webhook dùng chung giữa hai bên,** nên đổi secret phải làm cùng lúc ở cả hai. Nhà cung cấp thật thường cho phép hai secret song song trong lúc chuyển đổi; bản mock chưa làm việc này.
