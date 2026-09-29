# ADR 0002: Giữ ghế bằng Redis Lua, database là lớp chặn cuối

- Trạng thái: Đã chấp nhận
- Liên quan: FR-BKG-02, FR-BKG-03, NFR-CORR-01, NFR-AVAIL-05

## Bối cảnh

Khi mở bán, hàng nghìn request tranh cùng vài ghế trong vài giây. Nếu mỗi request khoá dòng trong Postgres, connection pool cạn rất nhanh. Nhưng yêu cầu "không bán trùng" thì tuyệt đối.

## Quyết định

Hai lớp:

1. **Redis (đường nhanh):** script `hold-seats.lua` kiểm tra rồi đặt khoá cho mọi ghế trong một lần chạy nguyên khối, kiểu tất cả hoặc không. Khoá có TTL = thời gian giữ ghế (10 phút) + 60 giây dự phòng. Key có dạng `hold:{eventId}:VIP-A-01`; phần `{eventId}` là hash tag để mọi ghế của một sự kiện nằm cùng slot khi chạy Redis Cluster.
2. **Postgres (lớp chặn cuối):** ghế chỉ chuyển sang `SOLD` bằng update có điều kiện `status = 'AVAILABLE'` khi xác nhận thanh toán. Nếu Redis mất dữ liệu và hai booking cùng trả tiền cho một ghế, chỉ booking đầu tiên thắng; booking sau bị huỷ và được hoàn tiền.

Nhả ghế dùng `release-seats.lua`: chỉ xoá khoá khi giá trị vẫn là bookingId của mình, để không xoá nhầm khoá người khác vừa giữ sau khi khoá cũ hết hạn.

## Lý do

- Request thua cuộc chỉ tốn một lần đọc Postgres và một script Redis, không giữ transaction.
- Test 1.000 luồng cùng giữ một ghế: đúng 1 thành công, khoảng 0,5 giây trên máy dev.

## Đánh đổi

- Có hai nguồn trạng thái (Redis cho HELD, Postgres cho SOLD); sơ đồ ghế phải ghép cả hai.
- Redis là single point of failure của đường nhanh; khi Redis sập thì không giữ được ghế mới, nhưng không bán trùng.

## Tham khảo

- [Hello Interview: Design Ticketmaster](https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster), phần giữ ghế bằng khoá phân tán có TTL
- [redisson](https://github.com/redisson/redisson), cách khoá phân tán được làm trong thư viện production
