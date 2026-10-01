# ADR 0006: Keycloak cấp JWT, Gateway và từng service đều kiểm tra

- Trạng thái: Đã chấp nhận
- Liên quan: FR-IAM-01, NFR-SEC-01, NFR-SEC-05, NFR-SEC-06

## Bối cảnh

Từ giai đoạn 1 đến 4, danh tính người dùng lấy từ header `X-User-Id`, và client nào cũng có thể tự điền header này. Giai đoạn 5 cần đăng nhập thật với ba vai trò CUSTOMER, ORGANIZER, ADMIN. Gọi sai vai trò phải nhận 403.

## Quyết định

- **Keycloak 26** là nhà cung cấp định danh (OIDC). Realm `ticketrush` được nạp từ `infra/keycloak/ticketrush-realm.json`:
  - Vai trò realm nằm phẳng trong claim `roles`.
  - Token có `aud` là `ticketrush-api`.
  - Tài khoản mới tự nhận CUSTOMER qua default role.
  - Mật khẩu tài khoản demo lấy từ biến môi trường, không nằm trong Git.
- **Hai client:**
  - `ticketrush-web`: authorization code + PKCE, dùng cho trình duyệt và Swagger UI.
  - `ticketrush-cli`: password grant, chỉ dùng cho script dev như smoke test.
- **Gateway kiểm tra chữ ký** và từ chối sớm request không có token hợp lệ. Nếu hợp lệ, Gateway chuyển nguyên token xuống service.
- **Mỗi service cũng là resource server:**
  - Tự kiểm tra chữ ký, `iss`, `aud` và `exp`, dùng module `security`.
  - Tự kiểm tra vai trò ở từng endpoint bằng `@CustomerOnly` và `@OrganizerOnly`.
  - Controller nhận danh tính qua tham số `Caller`, lấy từ token đã xác thực.
- **Issuer công khai khác địa chỉ lấy khoá.** Token luôn mang issuer `http://localhost:8180/realms/ticketrush`, còn service lấy khoá qua địa chỉ nội bộ `http://keycloak:8080` (`KC_HOSTNAME` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`).
- **401 và 403 trả về dạng `application/problem+json`**, giống mọi lỗi khác (NFR-SEC-05).
- **Kiểm tra quyền sở hữu dữ liệu vẫn nằm trong nghiệp vụ,** ví dụ booking của người khác trả 404. Check-in chỉ dành cho đúng ban tổ chức của sự kiện: Ticket Service biết ai tổ chức sự kiện nhờ `EventPublished.organizerId`.
- **Rate limit** lấy khoá theo `sub` của token đã xác thực, thay cho header.

## Lý do

- **Không tin mạng nội bộ.** Một service bị gọi vòng qua Gateway, qua port-forward hay từ một pod khác vẫn chỉ nhận token hợp lệ. Cách chỉ kiểm tra ở Gateway rồi truyền header danh tính xuống sẽ mất tính chất này.
- **Kiểm tra chữ ký rẻ.** JWKS được cache, mỗi request chỉ tốn một phép kiểm RS256 cỡ micro giây. Keycloak không nằm trên đường đi của request.
- **Vai trò nằm cạnh endpoint** nên đọc controller là biết ai được gọi gì.

## Đánh đổi

- **Mỗi service thêm một phụ thuộc** vào cấu hình issuer và JWKS. Module `security` gom phần này lại, và mỗi service chỉ khai báo các đường dẫn công khai của mình.
- **Không thu hồi được token trước hạn.** Access token sống 15 phút. Muốn thu hồi ngay thì phải dùng token introspection, đổi lại mỗi request phải gọi Keycloak.
- **Issuer thứ hai cho load test.** k6 cần đóng vai 20.000 người dùng khác nhau. Tạo từng ấy tài khoản Keycloak rồi đăng nhập thì thứ được đo sẽ là Keycloak chứ không phải TicketRush. Vì vậy có issuer `ticketrush-load-test`:
  - Token ký HS256 bằng `LOAD_TEST_JWT_SECRET`.
  - Chỉ được tin khi chạy kèm `load-test/compose.yml`. Compose mặc định và Helm chart không bao giờ bật.
  - Issuer trong token chỉ dùng để chọn bộ giải mã. Bộ giải mã đó vẫn kiểm chữ ký, issuer, audience và hạn như với token Keycloak.
- **Password grant** đã bị bỏ trong OAuth 2.1, nên chỉ bật cho client `ticketrush-cli` để chạy script ở máy dev.

## Tham khảo

- [keycloak/keycloak-quickstarts](https://github.com/keycloak/keycloak-quickstarts)
- [Spring Security: OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Keycloak: Configuring the hostname (v2)](https://www.keycloak.org/server/hostname)
