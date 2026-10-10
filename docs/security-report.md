# Báo cáo kiểm thử bảo mật

Thực hiện từ 01/10 đến 10/10/2026 theo kế hoạch kiểm thử bảo mật của dự án. Mỗi lỗi được tái hiện trước khi sửa, sửa kèm một test hoặc một script tái hiện, rồi chạy lại đúng đòn tấn công trên bản đã sửa.

## Cách làm

- **Môi trường:** stack Docker Compose trên máy local (17 container) và cụm kind 3 node chạy Helm chart (Pod Security "restricted", NetworkPolicy).
- **Cách tìm lỗi:** đọc code và cấu hình, tấn công thử bằng `curl`, `kafka-console-*`, `redis-cli` và Keycloak admin API. Sau đó là các công cụ quét trong CI: CodeQL, osv-scanner, Trivy, kubescape.
- **Mức độ:** điểm CVSS 3.1 base, tính cho một bản triển khai thật (Helm chart), tức là mạng nội bộ của cụm là `AV:A`. Hai lỗi lệch so với ước tính trong kế hoạch:
  - **#1:** kế hoạch ước Critical, CVSS cho 7,4. Lỗi chỉ phá tính toàn vẹn, và kẻ tấn công phải vào được mạng của Kafka trước. Đây vẫn là lỗi nặng nhất về hậu quả (vé miễn phí) và được sửa đầu tiên.
  - **#12:** kế hoạch ước Low, CVSS cho 5,3 (Medium), vì không cần đăng nhập mà lộ được số liệu vận hành.
- **"Chờ merge":** bản sửa nằm trong pull request đang mở (#15 đến #21), chưa vào `main`.

## Tóm tắt

16 nghi vấn trong kế hoạch, cộng các phát hiện của công cụ quét:
- **Đã sửa:** 14 nghi vấn; 9 đã vào `main`, 5 đang chờ merge.
- **Sửa một phần:** #11 (bảo vệ nhánh `main` phải do chủ repo bật).
- **Chấp nhận rủi ro:** 1 (#13).
- **Image hạ tầng:** CVE Critical đã được xử lý bằng cách nâng image, chờ merge #21. Riêng CVE trong `gosu` của Postgres được bỏ qua có lý do.

| # | Lỗi | Ca kiểm thử | CVSS 3.1 | Trạng thái | Sửa ở |
|---|---|---|---|---|---|
| 1 | Giả sự kiện thanh toán qua Kafka để lấy vé miễn phí | INF-03 | 7,4 High | Đã sửa | `0eb76a1`, `8f8dec6` |
| 2 | Cổng Docker mở ra mọi giao diện mạng; Redis không mật khẩu; Grafana cho khách quyền Admin | CFG-10, INF-02 | 8,8 High | Đã sửa | `1a293d9` |
| 3 | Đọc trộm Kafka lấy email khách và mã QR vào cửa | INF-04, QR-07 | 7,4 High | Đã sửa | `8f8dec6`, `7c30187` |
| 4 | Webhook công khai đọc hết body trước khi kiểm chữ ký | WH-05, RES-06 | 7,5 High | Đã sửa | `df616c4` |
| 5 | Email chưa xác minh vẫn nhận vé | BIZ-12, RES-10 | 6,5 Medium | Đã sửa | `df616c4` |
| 6 | Realm không có chính sách mật khẩu | AUTH-13 | 4,8 Medium | Đã sửa, chờ merge | #15 `c7d80d4` |
| 7 | Một tài khoản giữ mãi cùng một số ghế | BIZ-02 | 4,3 Medium | Đã sửa, chờ merge | #16 `893f9e4` |
| 8 | Không giới hạn luồng SSE của phòng chờ | RES-08 | 6,5 Medium | Đã sửa, chờ merge | #16 `1704682` |
| 9 | Keycloak chạy `start-dev` trong Helm chart | INF-05 | 5,6 Medium | Đã sửa | `7c30187` |
| 10 | Chart không có NetworkPolicy, seccomp, `capabilities.drop`; pod mang token service account | INF-06, 07, 08 | 5,5 Medium | Đã sửa | `7c30187` |
| 11 | Nhánh `main` chưa được bảo vệ; Dependabot tắt; Maven wrapper không kiểm checksum | SC-01, 03, 05 | 5,3 Medium | Một phần | `34f7190`; SC-01 chờ chủ repo |
| 12 | `/actuator/prometheus` công khai qua Gateway | CFG-02 | 5,3 Medium | Đã sửa | `1a293d9` |
| 13 | Biết `paymentId` là từ chối được thanh toán của người khác trên trang thanh toán giả | Ma trận phân quyền | 3,7 Low | Chấp nhận rủi ro | — |
| 14 | Check-in kiểm mã QR trước khi kiểm quyền, nên dò được mã nào là thật | QR-06 | 2,7 Low | Đã sửa, chờ merge | #15 `3437c8b` |
| 15 | Cùng `Idempotency-Key` nhưng body khác vẫn trả đơn cũ với 200 | BIZ-07 | 3,1 Low | Đã sửa, chờ merge | #15 `05464a5` |
| 16 | Kafka UI và Mailpit dùng tag `latest` | CFG-12 | 3,1 Low | Đã sửa | `92694ae` |
| — | Thư viện có CVE: Tomcat, Jackson, OpenTelemetry, lz4-java | SC-03 | tới 9,8 Critical | Đã sửa, chờ merge | #18 `a6deb93` |
| — | Pod không có CPU/memory limit; hạ tầng có root filesystem ghi được | INF-06 | 3,7 Low | Đã sửa, chờ merge | #19 `bfdf6c3` |
| — | CVE trong image hạ tầng (Kafka, Keycloak, Postgres) | SC-04 | tới Critical | Đã sửa, chờ merge | #21 `486d319` |
| — | Consumer Kafka dừng hẳn khi gặp lỗi phân quyền lúc ACL chưa có | Độ bền (phát hiện khi nâng Kafka) | — | Đã sửa, chờ merge | #21 `3b5d25d` |

## Chi tiết

### 1. Giả sự kiện thanh toán qua Kafka (INF-03)

- **Mức độ:** High, CVSS 3.1 7,4 (`AV:A/AC:L/PR:N/UI:N/S:C/C:N/I:H/A:N`)
- **Thành phần:** booking-service, saga nhận `PaymentSucceeded` từ `payment.events`
- **Tái hiện:**
  1. Giữ 1 ghế; booking ở trạng thái PENDING, chưa trả tiền.
  2. Ghi vào `payment.events` một `PaymentSucceeded` giả, với `paymentId` bịa và `amountVnd: 0`. Lúc đó Kafka không đòi đăng nhập.
- **Thực tế:**
  - Đơn chuyển sang CONFIRMED và 1 vé QR thật được phát.
  - `payment_db` vẫn ghi PENDING.
  - `scripts/reconcile.py` báo "CONFIRMED without a SUCCEEDED payment: 1".
- **Mong đợi:** chỉ xác nhận đơn khi payment-service thật sự ghi nhận đã trả tiền.
- **Ảnh hưởng:** ai vào được Kafka là lấy được vé miễn phí cho bất kỳ booking nào.
- **Cách sửa:**
  - `0eb76a1`: saga hỏi lại payment-service (`GET /internal/payments/{bookingId}`, Gateway không route ra ngoài) và chỉ xác nhận khi trạng thái là SUCCEEDED với `paymentId` khớp. Nếu không gọi được payment-service thì để Kafka thử lại, không tin suông sự kiện.
  - `8f8dec6`: đóng luôn gốc của lỗi bằng cách bắt Kafka đòi đăng nhập (xem #3).
  - Test tái hiện: `BookingSagaIntegrationTest.forgedPaymentSucceededIsRejected`.

### 2. Dịch vụ nền mở ra mạng (CFG-10, INF-02)

- **Mức độ:** High, CVSS 3.1 8,8 (`AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H`)
- **Thành phần:** `docker-compose.yml`, Redis, Grafana, Kafka UI
- **Tái hiện:**
  - Cả 12 cổng Docker lắng nghe trên `0.0.0.0`.
  - `redis-cli PING` trả `PONG` mà không cần mật khẩu.
  - Grafana `/api/org` và Kafka UI `/api/clusters` trả 200 khi chưa đăng nhập.
- **Mong đợi:** dịch vụ nền chỉ nghe trên máy local và đòi đăng nhập.
- **Ảnh hưởng:** ai cùng mạng LAN với máy dev cũng đọc, ghi hoặc xoá được chỗ giữ ghế, hàng chờ và dashboard.
- **Cách sửa (`1a293d9`):**
  - Mọi cổng bind về `127.0.0.1`.
  - Redis có `requirepass`, và mật khẩu cũng được thêm vào Helm Secret.
  - Khách vô danh trên Grafana chỉ còn quyền Viewer.
  - `scripts/init-dev-env.sh` sinh mật khẩu ngẫu nhiên.
- **Kiểm chứng lại (thử tay):**
  - `docker compose ps` không còn cổng nào nghe trên `0.0.0.0`.
  - `redis-cli PING` trả `NOAUTH`.
  - Khách vô danh gọi `/api/org/users` của Grafana nhận 403.

### 3. Đọc trộm Kafka (INF-04, QR-07)

- **Mức độ:** High, CVSS 3.1 7,4 (`AV:A/AC:L/PR:N/UI:N/S:C/C:H/I:N/A:N`)
- **Thành phần:** Kafka, các topic `ticket.events` và `booking.events`
- **Tái hiện:** đọc 1 message từ `ticket.events` mà không cần đăng nhập.
- **Thực tế:** message chứa email khách và mã QR đầy đủ, đủ để vào cửa.
- **Ảnh hưởng:** lộ dữ liệu cá nhân và vé hợp lệ của mọi khách.
- **Cách sửa:**
  - `8f8dec6`:
    - Mọi listener của Kafka đòi SASL/PLAIN, mỗi service một tài khoản, ACL theo từng topic.
    - Broker không tự tạo topic.
    - Kafka UI dùng tài khoản chỉ đọc.
  - `7c30187`: áp dụng giống như vậy trên Helm.
  - Lý do chọn cách này ghi ở [ADR 0009](adr/0009-infrastructure-hardening.md).
- **Kiểm chứng lại** (bằng `scripts/check-kafka-acls.sh`, 7/7 qua; và thử lại trên kind):
  - Không có mật khẩu: `TimeoutException`.
  - Sai mật khẩu: `Invalid username or password`.
  - Tài khoản booking-service đọc `ticket.events` hoặc ghi `payment.events`: `TopicAuthorizationException`.

### 4. Webhook đọc hết body trước khi kiểm chữ ký (WH-05)

- **Mức độ:** High, CVSS 3.1 7,5 (`AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H`)
- **Thành phần:** payment-service, `POST /api/payments/webhooks/mock-gateway` (công khai)
- **Tái hiện:** gửi body 1, 10 và 50 MB không có chữ ký.
- **Thực tế:** body được đọc hết vào bộ nhớ rồi mới trả 401. Thời gian xử lý tăng theo kích thước body.
- **Mong đợi:** từ chối với 413 trước khi đọc hết.
- **Ảnh hưởng:** nhiều request lớn chạy song song có thể làm cạn RAM của payment-service.
- **Cách sửa (`df616c4`):**
  - Body được đọc từ `HttpServletRequest` với trần 64 KB: kiểm `Content-Length` trước, rồi chỉ đọc tối đa 64 KB + 1 byte.
  - Test tái hiện: `PaymentIntegrationTest.aWebhookBodyOverTheCapIsRejected`.

### 5. Email chưa xác minh vẫn nhận vé (BIZ-12)

- **Mức độ:** Medium, CVSS 3.1 6,5 (`AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:L`)
- **Thành phần:** booking-service, `POST /api/bookings`
- **Tái hiện:** tạo user có `emailVerified=false`, đặt vé rồi thanh toán.
- **Thực tế:** đơn CONFIRMED, và vé kèm mã QR được gửi tới một địa chỉ mà người dùng chưa chứng minh mình sở hữu.
- **Ảnh hưởng:**
  - Kẻ tấn công có thể dùng địa chỉ email của người khác.
  - Vì realm cấm trùng email, chủ thật của địa chỉ đó sẽ không đăng ký được nữa.
- **Cách sửa (`df616c4`):**
  - Đặt vé khi `email_verified` khác `true` thì nhận 403.
  - Test tái hiện: `BookingIntegrationTest.bookingNeedsAVerifiedEmail`.

### 6. Realm không có chính sách mật khẩu (AUTH-13)

- **Mức độ:** Medium, CVSS 3.1 4,8 (`AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:L/A:N`)
- **Thành phần:** `infra/keycloak/ticketrush-realm.json` (cho phép tự đăng ký)
- **Tái hiện:** dùng admin API đặt mật khẩu 14 ký tự cho một user mới.
- **Thực tế:** 204, tức là mật khẩu nào cũng được chấp nhận.
- **Mong đợi:** mật khẩu ngắn hoặc trùng email bị từ chối.
- **Cách sửa (#15, `c7d80d4`):**
  - Thêm `passwordPolicy`: 15–128 ký tự, không trùng username hay email, không ép kiểu ký tự (theo NIST SP 800-63B-4). Chặn dò mật khẩu đã bật từ trước.
  - Test tái hiện, chạy trên Keycloak thật: `KeycloakRealmTest.weakPasswordsAreTurnedAway`.
  - Trên compose, mật khẩu 14 ký tự bị trả 400 `invalidPasswordMinLengthMessage`.
- **Lưu ý:** Keycloak chỉ import realm khi tạo realm lần đầu. Cụm Helm cài trước thay đổi này phải đặt chính sách bằng `kcadm.sh` (xem README).

### 7. Một tài khoản giữ mãi cùng một số ghế (BIZ-02)

- **Mức độ:** Medium, CVSS 3.1 4,3 (`AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:L`)
- **Thành phần:** booking-service, `BookingService.create`
- **Tái hiện:** giữ ghế, để booking kết thúc mà không trả tiền (tự huỷ, hết hạn, hoặc bị từ chối ở trang thanh toán giả mà ai cũng bấm được), rồi giữ lại ngay chính các ghế đó.
- **Thực tế:** giữ lại được ngay. Giới hạn 6 vé chỉ đếm các booking còn hiệu lực.
- **Ảnh hưởng:** một tài khoản chặn được những ghế cụ thể suốt đợt bán.
- **Cách sửa (#16, `893f9e4`):**
  - Ghế mà một khách nhả khi chưa trả tiền bị khoá với **chính khách đó** trong `ticketrush.booking.rehold-cooldown` (10 phút). Khách đó nhận 409 kèm `availableToYouAt`, còn người khác giữ được ngay. Booking bị hệ thống huỷ do `SEAT_CONFLICT` không tính.
  - Test tái hiện: `BookingSagaIntegrationTest.seatsLetGoUnpaidAreNotHeldAgainBySameCustomerAtOnce`.
- **Còn lại:** một người dùng nhiều tài khoản vẫn chuyền ghế cho nhau được. Rào chắn cho trường hợp này là bắt buộc xác minh email (#5) và rate limit.

### 8. Không giới hạn luồng SSE của phòng chờ (RES-08)

- **Mức độ:** Medium, CVSS 3.1 6,5 (`AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:H`)
- **Thành phần:** waiting-room-service `GET /api/queue/events/{id}/stream`, và route tương ứng ở Gateway
- **Tái hiện:** một user mở nhiều luồng cho cùng một sự kiện.
- **Thực tế:**
  - Luồng nào cũng sống.
  - Mỗi luồng giữ một thread và hỏi Redis 2 giây một lần, tối đa 30 phút.
  - Route ở Gateway không có rate limit: 10 request mở luồng cùng lúc đều qua.
- **Cách sửa (#16, `1704682`):**
  - Mỗi user mỗi sự kiện chỉ có 1 luồng trên mỗi instance; luồng mới (ví dụ tải lại tab) đóng luồng cũ.
  - Gateway giới hạn việc mở luồng: dồn được 3, sau đó 1 luồng mỗi giây.
  - Test tái hiện: `WaitingRoomIntegrationTest.aNewStreamEndsTheBuyersOlderOne` và `RateLimitTest.openingQueueStreamsIsCutAfterABurstOfThree`.
  - Trên compose, 10 lần mở cùng lúc: 3 lần nhận 200, 7 lần nhận 429.

### 9. Keycloak chạy `start-dev` trong Helm chart (INF-05)

- **Mức độ:** Medium, CVSS 3.1 5,6 (`AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:L/A:L`)
- **Thực tế:** chế độ dev dùng database nhúng, nới lỏng kiểm tra hostname và mở admin console ra cổng công khai.
- **Cách sửa (`7c30187`):** Keycloak chạy `start` với database `keycloak_db` riêng trên Postgres, hostname cố định, tắt admin console (`KC_FEATURES_DISABLED=admin`).
- **Kiểm chứng lại trên kind:** log ghi "Profile prod activated", `/admin/` trả 404, realm vẫn trả 200.

### 10. Pod chưa được siết (INF-06, 07, 08)

- **Mức độ:** Medium, CVSS 3.1 5,5 (`AV:A/AC:H/PR:L/UI:N/S:C/C:L/I:L/A:L`)
- **Thực tế:**
  - Không có NetworkPolicy, nên chiếm được một pod là đi tới được Postgres, Redis, Kafka và mọi service.
  - Thiếu `seccompProfile` và `capabilities.drop`.
  - Pod mang token service account.
- **Cách sửa (`7c30187`):**
  - Namespace bật `enforce=restricted`.
  - Mọi pod chạy uid không phải root, dùng seccomp `RuntimeDefault`, bỏ mọi capability, không leo quyền và không mang token.
  - NetworkPolicy chặn mặc định, chỉ mở đúng đường cần.
- **Kiểm chứng lại trên kind:**
  - Pod chạy root bị API server từ chối.
  - Từ một pod lạ, 11 cổng nội bộ bị chặn, chỉ 3 cổng công khai mở.
  - Pod không còn thư mục token.

### 11. Chuỗi cung ứng (SC-01, SC-03, SC-05)

- **Mức độ:** Medium, CVSS 3.1 5,3 (`AV:N/AC:H/PR:L/UI:N/S:U/C:N/I:H/A:N`)
- **Thực tế:** nhánh `main` chưa được bảo vệ, Dependabot chưa bật, và Maven wrapper tải Maven mà không kiểm checksum.
- **Cách sửa (`34f7190`):**
  - `.github/dependabot.yml` mở PR cập nhật cho Maven, GitHub Actions và image (SC-03).
  - Maven wrapper kiểm `distributionSha256Sum`; checksum sai thì `./mvnw` từ chối chạy (SC-05).
- **Còn mở:** bảo vệ nhánh `main` (SC-01) và Dependabot alerts/security updates là cài đặt của repo, chủ repo phải tự bật.

### 12. `/actuator/prometheus` công khai qua Gateway (CFG-02)

- **Mức độ:** Medium, CVSS 3.1 5,3 (`AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N`)
- **Thực tế:** gọi `/actuator/prometheus` qua cổng công khai của Gateway trả 200.
- **Cách sửa (`1a293d9`):** actuator của Gateway chuyển sang cổng quản trị 8090, cổng này không được publish ra ngoài.
- **Kiểm chứng lại (thử tay; chưa có test tự động):**
  - Qua cổng ngoài, `/actuator/*` trả 404 còn `/api` trả 200.
  - Prometheus vẫn scrape được `api-gateway:8090`.
- **Không phải lỗi (CFG-01):** `/actuator/env`, `heapdump`, `configprops`, `threaddump` qua Gateway vốn đã trả 404.

### 13. Từ chối thanh toán của người khác trên trang thanh toán giả (chấp nhận rủi ro)

- **Mức độ:** Low, CVSS 3.1 3,7 (`AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:L/A:N`)
- **Thực tế (xác nhận qua code):** `POST /api/payments/{id}/checkout` công khai, chỉ cần biết `paymentId`.
- **Lý do chấp nhận:**
  - Đây là trang của **cổng thanh toán giả lập**; khi tích hợp nhà cung cấp thật, trang này thuộc về nhà cung cấp.
  - `paymentId` là UUID ngẫu nhiên nên không đoán được, chỉ lộ khi chính link thanh toán bị lộ.
  - Hậu quả cao nhất là một booking bị huỷ. Ghế được nhả ra, không ai mất tiền.

### 14. Check-in dùng để dò mã QR thật (QR-06)

- **Mức độ:** Low, CVSS 3.1 2,7 (`AV:N/AC:L/PR:H/UI:N/S:U/C:L/I:N/A:N`)
- **Tái hiện:** organizer của một sự kiện khác gửi mã QR giả lên `/api/tickets/check-in`.
- **Thực tế:** nhận 422 cho mã giả nhưng 403 cho mã thật, nên phân biệt được mã nào là thật.
- **Cách sửa (#15, `3437c8b`):**
  - `CheckIn.scan` kiểm quyền trước, kiểm mã sau, nên cả hai trường hợp đều nhận 403.
  - Test tái hiện: `TicketIntegrationTest.aTicketGetsInOnceAndOnlyThroughItsOrganizer`.
- **Chấp nhận:** organizer quét mã thật của sự kiện khác ngay tại sự kiện của mình vẫn nhận 409 `WRONG_EVENT`. Nhân viên soát vé cần thông báo này để chỉ khách sang đúng sự kiện, và vai trò ORGANIZER do admin cấp.

### 15. Dùng lại `Idempotency-Key` với body khác (BIZ-07)

- **Mức độ:** Low, CVSS 3.1 3,1 (`AV:N/AC:H/PR:L/UI:N/S:U/C:N/I:L/A:N`)
- **Thực tế:** gửi cùng key nhưng ghế hoặc sự kiện khác vẫn nhận 200 kèm booking cũ, nên client tưởng mình đang giữ những ghế mà thực ra không giữ.
- **Cách sửa (#15, `05464a5`):**
  - Request gửi lại phải cùng sự kiện và cùng ghế (thứ tự không quan trọng); khác thì trả 422.
  - Test tái hiện: `BookingIntegrationTest.anIdempotencyKeyReusedWithAnotherBodyIsRefused`.

### 16. Image dùng tag `latest` (CFG-12)

- **Mức độ:** Low, CVSS 3.1 3,1 (`AV:N/AC:H/PR:N/UI:R/S:U/C:N/I:L/A:N`)
- **Cách sửa (`92694ae`):** ghim Kafka UI `v1.5.0` và Mailpit `v1.31.4`; Dependabot đề xuất bản mới.

## Phát hiện của công cụ quét

CI chạy CodeQL, osv-scanner, Trivy và kubescape trên mọi pull request (#17). Hiện ở chế độ chỉ báo cáo (`SECURITY_GATE: report`).

### CodeQL

Java và các workflow GitHub Actions được quét với bộ `security-extended`: **0 cảnh báo** trên #17 và #19.

### Thư viện (osv-scanner, đã sửa trong #18)

osv-scanner quét SBOM CycloneDX gồm 263 thư viện đi vào bản build. Lần quét đầu ra 25 advisory trong 8 gói:

| Thư viện | Trước → sau | Advisory | CVSS cao nhất |
|---|---|---|---|
| Tomcat | 11.0.24 → 11.0.26 | GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5 | 9,8 |
| Jackson 3 và 2 | 3.1.5 / 2.21.5 → 3.1.7 / 2.21.7 | 7 advisory (DoS khi parse JSON) | 7,5 |
| lz4-java | 1.10.1 → 1.11.4 | 6 advisory | 7,3 |
| OpenTelemetry | 1.55.0 → 1.62.0 | GHSA-rcgg-9c38-7xpx | 5,3 |

- **Tomcat:** cả ba CVE nằm ở phần xác thực DIGEST/FORM của Tomcat, mà TicketRush không dùng (xác thực bằng JWT qua Spring Security). Vẫn nâng cấp để không phải giữ ngoại lệ.
- **Sau #18:** osv-scanner báo "No issues found", `./mvnw verify` qua 144 test, smoke test qua.

### Cấu hình Kubernetes (Trivy config và kubescape, đã sửa trong #19)

- **Lần quét đầu:**
  - Trivy config: 6 cảnh báo High, đều là root filesystem ghi được ở pod hạ tầng.
  - kubescape: không có CPU limit (13 pod), hạ tầng thiếu memory limit, Keycloak chạy group 0, và một cảnh báo sai ở C-0012 (`JWT_ISSUER`, `JWT_JWK_SET_URI` là URL công khai).
- **Cách sửa (#19):**
  - Mọi pod có CPU và memory limit.
  - Cả 6 container hạ tầng chạy root filesystem chỉ đọc, kèm `emptyDir` cho chỗ cần ghi.
  - Keycloak chạy group 1000.
  - kubescape chỉ cho phép đúng hai tên biến `JWT_*`. Đã kiểm bằng một biến mật khẩu giả để chắc control vẫn bắt được mật khẩu thật.
- **Sau #19:** cả hai công cụ không còn phát hiện nào. Trên kind, smoke test qua và diễn tập pod lỗi 0/10.002 request.

### Image hạ tầng (đã sửa trong #21)

Lần quét đầu (09/10/2026), Trivy tìm thấy CVE Critical đã có bản vá trong ba image upstream:
- **Kafka 4.1.0:** `kafka-clients`, OpenSSL, GnuTLS.
- **Keycloak 26.7.4:** Netty, FreeMarker, Bouncy Castle.
- **Postgres 17-alpine:** thư viện chuẩn Go trong `gosu`.

| Image | Trước | Sau | CVE Critical có bản vá |
|---|---|---|---|
| Kafka | 4.1.0 | 4.2.2 | 6 → 0. Bản 4.1.2 vẫn còn 5, nên phải lên dòng 4.2 |
| Keycloak | 26.7.4 | 26.7.5 | 4 → 0 |
| Postgres | `17-alpine` (tag trôi) | `17.11-alpine3.24` | 1 → 0, nhờ bỏ qua có lý do (xem dưới) |
| Redis | `8-alpine` (tag trôi) | `8.8.3-alpine` | 0 → 0 |

- **Ghim tag:** Postgres và Redis giờ được ghim bản cụ thể. Bản build mới của upstream không còn lặng lẽ thay image, và Dependabot đề xuất từng lần nâng.
- **CVE-2025-68121 trong `gosu` của Postgres:** mọi image Postgres chính thức (17 và 18, Alpine và Debian) đều mang `gosu` build bằng Go 1.24.6. Lỗi nằm ở `crypto/tls`, nhưng `gosu` chỉ đổi user lúc khởi động và không mở kết nối TLS nào. Trên Kubernetes, pod chạy uid 70 nên `gosu` còn không được gọi. `.trivyignore.yaml` bỏ qua CVE này cho riêng đường dẫn `usr/local/bin/gosu`, kèm lý do và ngày hết hạn.
- **Kiểm chứng:**
  - Job quét image hạ tầng chạy ở chế độ `enforce` thì qua.
  - Trên compose, smoke test qua và `check-kafka-acls.sh` qua 7/7.
  - Trên kind, Kafka 4.2.2 khởi động được trên dữ liệu (PVC) của bản 4.1.0, và smoke test qua.

**Image của 7 service:** phần hệ điều hành (Ubuntu 26.04) và các jar không có lỗ hổng High hay Critical sau #18. Chỉ còn một lỗ hổng High trong thư viện chuẩn Go của `/usr/bin/pebble` thuộc base image temurin; lỗ hổng này hết khi Dependabot nâng base image.

### Phát hiện thêm: consumer Kafka dừng hẳn sau lỗi phân quyền (đã sửa trong #21)

- **Mức độ:** lỗi độ bền, không phải lỗ hổng, nên không tính CVSS.
- **Thành phần:** mọi service đọc Kafka.
- **Tái hiện:** tạo lại Kafka trong compose (Kafka không có volume) khi các service đang chạy.
- **Thực tế:**
  - ACL chỉ có lại sau khi `kafka-init` chạy xong. Consumer nào poll trong khoảng đó nhận `GroupAuthorizationException`, và Spring Kafka dừng container vĩnh viễn.
  - 7 consumer dừng như vậy. Service vẫn chạy và báo khoẻ nhưng không bao giờ đọc Kafka nữa, nên smoke test kẹt ở bước 3 cho tới khi restart service.
  - Lần cài Helm đầu tiên cũng gặp đúng khoảng trống này, cho tới khi Job `kafka-init` chạy xong.
- **Cách sửa (#21, `3b5d25d`):**
  - Thêm `spring.kafka.listener.auth-exception-retry-interval: 10s` vào cấu hình Kafka dùng chung.
  - Kiểm lại với cùng kịch bản: không consumer nào dừng, và smoke test qua mà không cần restart.
  - Chưa có test tự động, vì broker của Testcontainers không bật ACL.

## Rủi ro được chấp nhận

| Rủi ro | Lý do | Khi nào xem lại |
|---|---|---|
| #13: trang thanh toán giả cho từ chối thanh toán chỉ với `paymentId` | Chỉ có ở cổng giả lập; `paymentId` không đoán được; hậu quả chỉ là một booking bị huỷ | Khi tích hợp nhà cung cấp thanh toán thật |
| QR-06 còn lại: 409 `WRONG_EVENT` cho mã thật của sự kiện khác | Nhân viên soát vé cần thông báo này; ORGANIZER do admin cấp | Khi có vai trò soát vé riêng |
| Kafka dùng `SASL_PLAINTEXT`, chưa có TLS | Compose và kind là môi trường local; SASL và ACL đã chặn đọc, ghi trái phép | Trước khi lên môi trường thật: `SASL_SSL` ([ADR 0009](adr/0009-infrastructure-hardening.md)) |
| BIZ-02 với nhiều tài khoản | Mỗi tài khoản phải có email đã xác minh; có rate limit | Nếu thấy dấu hiệu bot giữ ghế |
| CVE-2025-68121 trong `gosu` của image Postgres | `gosu` không dùng TLS; trên Kubernetes nó không chạy | Khi image Postgres chính thức build lại `gosu`, hoặc tới ngày hết hạn 10/01/2027 trong `.trivyignore.yaml` |

## Việc chưa làm

- **SC-01:** bảo vệ nhánh `main` và bật Dependabot alerts/security updates (chủ repo bật trong Settings).
- **SC-02:** ghim mọi `uses:` trong workflow theo SHA. Hiện mới ghim `github/codeql-action`; các action còn lại để sau PR Dependabot #13.
- **Bật `enforce`:** khi #17–#21 đã được merge, đổi `SECURITY_GATE` sang `enforce` để các công cụ quét chặn PR.
- **DAST:** OWASP ZAP chạy mỗi đêm chưa có.
- **Phân quyền và xác thực:**
  - Ma trận phân quyền tự động (`scripts/authz-matrix.sh`) chưa có.
  - Chưa có test cho AUTH-05 (đổi thuật toán RS256 sang HS256, cần Keycloak thật).
  - Chưa có test cho AUTH-12, AUTH-14, AUTH-15 (dò mật khẩu, PKCE).
- **Lạm dụng nghiệp vụ:** kịch bản k6 trong `load-test/abuse/` chưa có.

## Đối chiếu tiêu chí đạt của kế hoạch

| Tiêu chí | Kết quả |
|---|---|
| Không còn lỗi Critical hoặc High nào đang mở | **Đạt** khi #15–#21 được merge. Ngoại lệ duy nhất là CVE trong `gosu` của Postgres, được chấp nhận có lý do |
| Mỗi lỗi Medium đã sửa, hoặc chấp nhận rủi ro có ghi lý do | **Đạt**, trừ SC-01 đang chờ chủ repo bật |
| Mỗi lỗi sửa có test tự động chạy trong CI | Phần lớn **đạt**. Ngoại lệ: #2, #9, #10 kiểm bằng tay trên compose và kind; #12 chưa có test tự động; #3 có `scripts/check-kafka-acls.sh` nhưng script này không chạy trong CI |
| CI có CodeQL, osv-scanner, Trivy và kubescape; ZAP chạy mỗi đêm không còn cảnh báo High | 4 công cụ quét: **có** (#17, chế độ báo cáo). ZAP: **chưa** |
| Có `docs/security-report.md` và mục Bảo mật trong README | **Đạt** (tài liệu này) |
