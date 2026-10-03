# ADR 0008: Cấu trúc source: services/ và libs/, một bộ package chung, kiểm bằng ArchUnit

- Trạng thái: Đã chấp nhận
- Liên quan: ADR 0003 (outbox nằm trong thư viện `messaging`)

## Bối cảnh

- **Thư viện được nối vào service bằng quét package.** Mọi class `*Application` nằm ở `com.ticketrush`, nên Spring quét luôn mọi bean của thư viện dùng chung. notification-service không ghi outbox nhưng vẫn chạy `OutboxRelay`. Service mới đặt Application sâu hơn một cấp sẽ mất bảo mật và xử lý lỗi mà không báo gì.
- **`common` gánh sáu việc:** hợp đồng message, Kafka, outbox, inbox, sơ đồ ghế, xử lý lỗi HTTP. Muốn dùng một record sự kiện là phải kéo cả webmvc, JPA và Kafka. Năm service còn nhận thư viện `security` gián tiếp qua `common` mà không khai báo.
- **Mỗi service một kiểu package.** Booking có 17 file phẳng cộng hai package con, các service khác phẳng hoàn toàn. Không có gì ngăn domain gọi ngược lên controller.
- **Test lặp:** năm bản `TestcontainersConfiguration`, ba chỗ tự dựng Redis.

## Quyết định

**Gốc repo chia hai:** `services/` chứa 7 ứng dụng deploy được, `libs/` chứa thư viện không có `main`. Tên artifact, image, topic Kafka và bảng DB giữ nguyên. Dockerfile chọn module bằng `-pl :<artifactId>`.

**`common` tách thành ba thư viện:**
- `contracts`: message và DTO giữa các service, chia package theo service phát hành (`contracts.booking`, `.payment`, ...). Java thuần; maven-enforcer cấm mọi phụ thuộc compile và runtime.
- `messaging`: cấu hình Kafka, transactional outbox, idempotent consumer.
- `web`: `ApiException`, handler problem+json, `PageResponse`.

**Thư viện tự khai báo bean** trong `META-INF/spring/...AutoConfiguration.imports`, như các starter của Spring Boot:
- `ResourceServerConfig` chạy trước auto-configuration bảo mật của Boot, nên filter chain, `JwtDecoder` và user mặc định của Boot lùi lại.
- `OutboxAutoConfiguration` đăng ký package của nó cho JPA trước khi Spring Data quét repository. Nó tắt được bằng `ticketrush.outbox.enabled=false`, và notification-service tắt nó.

**Mỗi service dùng chung một bộ package con** dưới `com.ticketrush.<service>`, và chỉ tạo package khi có class cho nó:

| Package | Chứa |
| --- | --- |
| `web` | controller và request body |
| `messaging` | Kafka listener |
| `domain` | entity, repository, service nghiệp vụ, view trả ra, port sang service khác |
| `saga` | điều phối có bù trừ (booking) |
| `client` | gọi service khác qua HTTP, cài port của domain (booking) |
| `psp` | cổng thanh toán giả và chữ ký webhook (payment) |
| `config` | `@ConfigurationProperties` |

Gateway giữ phẳng vì cả 8 class của nó đều là cấu hình.

**ArchUnit kiểm cấu trúc trong `./mvnw verify`.** Thư viện `test-support` chứa `TicketRushArchitecture`, và mỗi service có một `ArchitectureTest` chạy bộ luật đó:
- `domain` không phụ thuộc `web`, `messaging`, `saga`, `client`, `psp`.
- `web` và `messaging` không gọi nhau.
- Không có vòng phụ thuộc giữa các package.
- Controller nằm ở `web`, Kafka listener ở `messaging`, entity ở `domain`.
- Application nằm ở gốc package của service.

`test-support` cũng chứa Testcontainers dùng chung cho Postgres, Kafka và Redis.

## Lý do

- **Tham khảo các repo microservice lớn.** [microservices-demo](https://github.com/GoogleCloudPlatform/microservices-demo) và [eShop](https://github.com/dotnet/eShop) gom service vào một thư mục. eShop tách `EventBus` khỏi outbox. [ftgo-application](https://github.com/microservices-patterns/ftgo-application) chia service thành `domain`, `web`, `messaging`, `sagas` và có `ftgo-test-util`. [spring-petclinic-microservices](https://github.com/spring-petclinic/spring-petclinic-microservices) đặt Application ở gốc package của từng service.
- **Phụ thuộc chỉ đi vào trong:** domain không biết nó được gọi qua HTTP hay Kafka. Port chỉ dùng ở chỗ gọi service khác (`PaymentVerifier`), như [buckpal](https://github.com/thombergs/buckpal).
- **Luật nằm trong build** thì cấu trúc không mòn dần theo thời gian. Cả 8 luật đã được thử bằng vi phạm cố ý và đều làm build đỏ.

## Đánh đổi

- **32 khai báo chuyển sang `public`** (class, method, hằng) vì được dùng xuyên package. ArchUnit thay vai trò kiểm soát truy cập mà package-private từng làm.
- **Không dùng hexagonal đầy đủ** (port in/out cho mọi use case). Mỗi service chỉ có 6–25 class, nên thêm interface cho mọi thứ sẽ gần như nhân đôi số file.
- **Một module `contracts` cho cả hệ thống,** thay vì mỗi service một module `-api` như ftgo. Hợp đồng chỉ có 12 file; khi có nhiều team thì tách ra sau cũng dễ.
- **`TestJwts` ở lại test-jar của `security`.** Chính test của `security` dùng nó, nên chuyển sang `test-support` sẽ làm hai module phụ thuộc vòng.
- **Bảng `outbox_message` của notification-service vẫn còn** dù không dùng. Xoá nó cần một migration riêng.
- **Không chạy `dependency:analyze` trong CI.** Với các starter của Spring Boot, nó báo hàng loạt phụ thuộc "khai báo mà không dùng" không đúng.

## Tham khảo

- [Spring Boot: Creating your own auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
