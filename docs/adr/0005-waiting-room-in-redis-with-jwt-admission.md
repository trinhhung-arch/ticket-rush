# ADR 0005: Waiting room bằng Redis sorted set, vé vào cửa là JWT

- Trạng thái: Đã chấp nhận
- Liên quan: FR-WR-01, FR-WR-02, FR-WR-03, NFR-PERF-05

## Bối cảnh

Với sự kiện hot, hàng chục nghìn người vào cùng lúc. Nếu tất cả đều gọi thẳng API đặt vé, Booking Service và database sẽ quá tải, và người vào trước chưa chắc được phục vụ trước.

## Quyết định

- **Bật theo từng sự kiện:** ban tổ chức đặt `waitingRoom: true`. Cờ này đi theo `EventPublished` sang Booking, sự kiện bình thường không phải đi qua hàng đợi.
- **Hàng đợi trong Redis**, mỗi sự kiện có:
  - `wr:{eventId}:queue`: sorted set, điểm là số thứ tự lúc vào hàng, đảm bảo FIFO.
  - `wr:{eventId}:active`: sorted set, điểm là thời điểm hết hạn vào cửa.
  - Ba Lua script: vào hàng, xem trạng thái, cho người kế tiếp vào. Mỗi script chạy nguyên khối nên không ai chen hàng hoặc bị cấp chỗ hai lần.
- **Phòng còn chỗ** (số người đang active ít hơn sức chứa, mặc định 2.000) và không ai đang chờ thì cho vào ngay. Ngược lại thì xếp hàng. Mỗi giây, vòng lặp cho vào số người bằng số chỗ vừa trống. Mọi instance đều chạy vòng lặp này một cách an toàn.
- **Vé vào cửa là JWT HS256** gồm `sub` là người dùng, `evt` là sự kiện, `exp` là thời điểm hết hạn vào cửa (10 phút). Booking Service tự kiểm chữ ký bằng khoá dùng chung nên không phải gọi sang Waiting Room.
- **Vị trí** được đẩy qua SSE mỗi 2 giây cho tới khi vào được.

## Lý do

- Sorted set cho cả thứ tự lẫn thời hạn trong một cấu trúc. Lua giữ mọi bước nguyên tử mà không cần khoá phân tán.
- JWT giúp Booking không phụ thuộc vào Waiting Room lúc chạy.
- Đo được: 50.041 lượt vào hàng trong 60 giây, p95 2,8 ms, và đúng 2.000 người được vào.

## Đánh đổi

- **Thời gian chờ ước tính** là cận trên: coi như mọi người đang ở trong phòng đều dùng hết 10 phút.
- **Chỉ mục sự kiện có hàng đợi** (`wr:events`) nằm ở slot Redis khác các key của sự kiện, nên không đưa được vào cùng một Lua script. Mỗi lần người dùng xem trạng thái sẽ tự đăng ký lại sự kiện vào chỉ mục, để lỡ bị xoá nhầm cũng tự hồi phục.
- **Khoá HS256 dùng chung** giữa hai service. Khi có Keycloak (giai đoạn 5) có thể chuyển sang ký bất đối xứng.

## Tham khảo

- [Hello Interview: Design Ticketmaster](https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster), phần virtual waiting queue
