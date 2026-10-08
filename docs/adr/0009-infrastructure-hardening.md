# ADR 0009: Siết hạ tầng: Kafka đăng nhập và ACL theo service, Keycloak production, pod "restricted", NetworkPolicy

- Trạng thái: Đã chấp nhận
- Liên quan: INF-03 đến INF-09, CFG-12, SC-03, SC-05 trong kế hoạch kiểm thử bảo mật; ADR 0006

## Bối cảnh

- **Kafka không đòi đăng nhập.** Khi kiểm thử, đọc một message của `ticket.events` là lấy được email khách và mã QR vào cửa được. Bơm một `PaymentSucceeded` giả vào `payment.events` từng tạo ra vé miễn phí. INF-03 đã được chặn ở saga bằng cách hỏi lại payment-service, nhưng gốc của lỗ hổng vẫn mở.
- **Helm chart chưa sẵn sàng cho môi trường thật:**
  - Keycloak chạy `start-dev`.
  - Pod chạy bằng root và mang token service account.
  - Không có NetworkPolicy, nên lọt vào một pod là đi được tới Postgres, Redis, Kafka và mọi service.
- **Chuỗi cung ứng:**
  - Kafka UI và Mailpit dùng tag `latest`.
  - Maven wrapper tải Maven mà không kiểm checksum.
  - Dependabot chưa bật.

## Quyết định

**Kafka: SASL/PLAIN, mỗi service một tài khoản, ACL theo topic.**
- Mọi listener đều đòi đăng nhập, gồm listener cho client, listener của controller và cổng 9094 mở ra máy host.
- Kafka dùng `StandardAuthorizer`. Chỉ `admin` là super user, và broker không tự tạo topic.
- `infra/kafka/init-kafka.sh` tạo topic và ACL. Trong compose, script chạy ở service `kafka-init`; trên Helm, nó chạy trong một Job ứng với mỗi revision.
- Quyền của mỗi service:
  - Ghi vào topic mà service đó phát.
  - Đọc topic mà service đó tiêu thụ, và ghi vào topic `-dlt` tương ứng.
  - Dùng các consumer group có tên bắt đầu bằng tên service.
  - Mọi service đều xem được metadata của topic.
- Kết quả: chỉ notification-service đọc được `ticket.events`, chỉ payment-service ghi được `payment.events`.
- Kafka UI dùng một tài khoản chỉ đọc và có trang đăng nhập riêng.
- Client đọc `ticketrush-messaging.yml` từ thư viện `messaging`, với user là `spring.application.name`. Test ép về `PLAINTEXT` vì broker của Testcontainers không bật SASL.

**Keycloak trên Helm chạy `start`:**
- Dùng database `keycloak_db` riêng trên Postgres.
- Hostname cố định.
- Chạy HTTP, vì cụm local không có TLS.
- Tắt admin console (`KC_FEATURES_DISABLED=admin`), nên `/admin` trả 404 trên cổng công khai.

**Pod đạt chuẩn Pod Security "restricted":**
- Namespace bật `enforce=restricted`, nên API server từ chối mọi pod không đạt.
- Mọi pod chạy bằng uid không phải root của chính image, dùng seccomp `RuntimeDefault`, bỏ mọi Linux capability, không leo quyền và không mang token service account.
- Các service Java chạy với root filesystem chỉ đọc, kèm `/tmp` là `emptyDir`.

**NetworkPolicy:** mặc định chặn mọi kết nối vào, rồi thêm 12 luật mở theo đúng bên gọi. Chỉ gateway, Keycloak và giao diện Mailpit nhận kết nối từ ngoài.

**Chuỗi cung ứng:**
- Ghim Kafka UI `v1.5.0` và Mailpit `v1.31.4`.
- Maven wrapper kiểm `distributionSha256Sum`.
- `.github/dependabot.yml` mở PR cập nhật cho Maven, Actions và image.

## Lý do

- **Chọn PLAIN thay vì SCRAM:** broker giữ danh sách user ngay trong cấu hình, không cần thêm bước tạo credential sau khi format KRaft. Khi chưa có TLS, SCRAM cũng không giấu được nội dung message trên đường truyền, nên dùng nó chỉ được thêm rất ít.
- **Service không được quyền tạo topic:** script init tạo topic. Nhờ vậy, một service bị chiếm cũng không tạo được topic lạ, và số partition chỉ đặt ở một chỗ.
- **Kiểm chứng:** mỗi biện pháp được thử lại bằng chính đòn tấn công trong kế hoạch.
  - Trên compose: `scripts/check-kafka-acls.sh`.
  - Trên kind:
    - Pod chạy root bị từ chối.
    - Một pod lạ không nối được tới 11 cổng nội bộ.
    - Pod service không có token.
    - Tài khoản booking-service đọc `ticket.events` hoặc ghi `payment.events` đều bị `TopicAuthorizationException`.
  - Smoke test end-to-end qua trên cả compose lẫn kind.

## Đánh đổi

- **Chưa có TLS** (dùng `SASL_PLAINTEXT`). Ai bắt được gói tin trong mạng nội bộ vẫn đọc được message và mật khẩu PLAIN. Trên môi trường thật cần dùng `SASL_SSL`.
- **Danh sách topic nằm ở hai chỗ:** `Topics.java` và `init-kafka.sh`.
- **Trên Helm, service có thể log lỗi Kafka trong vài giây đầu,** cho tới khi Job `kafka-init` chạy xong. Compose thì đợi `kafka-init` xong mới khởi động service.
- **Cụm Helm cài trước thay đổi này không tự có `keycloak_db`,** vì script init của Postgres chỉ chạy khi tạo volume mới. Cần tạo database bằng tay hoặc cài lại cụm.
- **Không còn admin console trên kind.** Muốn quản trị thì dùng `kcadm.sh` hoặc chạy bằng compose.
- **NetworkPolicy cần CNI có hỗ trợ** (kindnet có). Prometheus đặt ngoài namespace cần một luật riêng.
- **Bảo vệ nhánh `main`** và việc bật Dependabot alerts cùng security updates là cài đặt của repo trên GitHub, chủ repo phải tự bật.

## Tham khảo

- [Kafka: Authentication using SASL/PLAIN](https://kafka.apache.org/documentation/#security_sasl_plain)
- [Kafka: Authorization and ACLs](https://kafka.apache.org/documentation/#security_authz)
- [Keycloak: Configuring Keycloak for production](https://www.keycloak.org/server/configuration-production)
- [Kubernetes: Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/)
- [Kubernetes: Network Policies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
