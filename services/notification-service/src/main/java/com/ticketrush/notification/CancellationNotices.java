package com.ticketrush.notification;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketrush.contracts.booking.BookingEvents.BookingCancelled;
import com.ticketrush.contracts.payment.PaymentEvents.PaymentRefunded;

/**
 * FR-NTF-02: tells the customer why a booking was cancelled and, when money had already arrived, that
 * it was refunded. The refund email joins two topics that Kafka may deliver in either order, so both
 * handlers record what they learned and whichever sees both facts sends it. They take the same
 * per-booking lock first, so the second always sees what the first committed.
 */
@Service
class CancellationNotices {

    private static final Logger log = LoggerFactory.getLogger(CancellationNotices.class);

    static final String CANCELLED = "CANCELLED";
    static final String REFUNDED = "REFUNDED";

    private static final Map<String, String> REASONS = Map.of(
            "HOLD_EXPIRED", "Đã hết 10 phút giữ chỗ mà đơn chưa được thanh toán.",
            "PAYMENT_FAILED", "Thanh toán không thành công: ngân hàng hoặc cổng thanh toán đã từ chối giao dịch.",
            "USER_CANCELLED", "Bạn đã huỷ đơn này.",
            "SEAT_CONFLICT", "Ghế bạn chọn đã được bán cho một đơn khác ngay trước khi thanh toán của bạn được xác nhận.");

    private record Cancellation(String recipient, String reason) {
    }

    private final JdbcClient jdbc;
    private final JavaMailSender mailSender;
    private final SentEmailRepository sentEmails;
    private final NotificationProperties properties;
    private final MeterRegistry meters;

    CancellationNotices(JdbcClient jdbc, JavaMailSender mailSender, SentEmailRepository sentEmails,
                        NotificationProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.sentEmails = sentEmails;
        this.properties = properties;
        this.meters = meters;
    }

    @Transactional
    public void cancelled(BookingCancelled event) {
        lock(event.bookingId());
        jdbc.sql("""
                        insert into booking_cancellation (booking_id, recipient, reason, cancelled_at)
                        values (:bookingId, :recipient, :reason, :now) on conflict do nothing
                        """)
                .param("bookingId", event.bookingId())
                .param("recipient", event.email())
                .param("reason", event.reason())
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        if (!sentEmails.existsByBookingIdAndKind(event.bookingId(), CANCELLED)) {
            send(event.bookingId(), CANCELLED, event.email(), "Đơn đặt vé đã bị huỷ",
                    "<p>Xin chào,</p><p>Đơn đặt vé <b>%s</b> đã bị huỷ.</p><p><b>Lý do:</b> %s</p><p>Ghế đã được mở bán lại.</p>"
                            .formatted(event.bookingId(), describe(event.reason())));
        }
        sendRefundIfComplete(event.bookingId());
    }

    @Transactional
    public void refunded(PaymentRefunded event) {
        lock(event.bookingId());
        jdbc.sql("""
                        insert into payment_refund (booking_id, amount_vnd, refunded_at)
                        values (:bookingId, :amount, :now) on conflict do nothing
                        """)
                .param("bookingId", event.bookingId())
                .param("amount", event.amountVnd())
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        sendRefundIfComplete(event.bookingId());
    }

    private void sendRefundIfComplete(UUID bookingId) {
        if (sentEmails.existsByBookingIdAndKind(bookingId, REFUNDED)) {
            return;
        }
        Optional<Long> amount = jdbc.sql("select amount_vnd from payment_refund where booking_id = :id")
                .param("id", bookingId).query(Long.class).optional();
        Optional<Cancellation> cancellation = jdbc.sql("select recipient, reason from booking_cancellation where booking_id = :id")
                .param("id", bookingId).query(Cancellation.class).optional();
        if (amount.isEmpty() || cancellation.isEmpty()) {
            return; // the other half has not arrived yet; its handler will send
        }
        send(bookingId, REFUNDED, cancellation.get().recipient(), "Đã hoàn tiền cho đơn đặt vé",
                ("<p>Xin chào,</p><p>Tiền thanh toán cho đơn <b>%s</b> đến sau khi đơn đã bị huỷ, nên chúng tôi đã hoàn lại"
                        + " <b>%s VND</b> về phương thức thanh toán của bạn.</p><p><b>Lý do huỷ:</b> %s</p>")
                        .formatted(bookingId, NumberFormat.getIntegerInstance(Locale.of("vi", "VN")).format(amount.get()),
                                describe(cancellation.get().reason())));
    }

    private void lock(UUID bookingId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "notice:" + bookingId).query((rs, row) -> 1).single();
    }

    private static String describe(String reason) {
        return REASONS.getOrDefault(reason, reason);
    }

    /** Sent inside the transaction and recorded after it, like the ticket email (at least once). */
    private void send(UUID bookingId, String kind, String recipient, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Could not build the %s email for booking %s".formatted(kind, bookingId), e);
        }
        sentEmails.save(new SentEmail(bookingId, kind, recipient, subject, Instant.now()));
        meters.counter("ticketrush.emails.sent", "kind", kind).increment();
        log.info("Sent {} email for booking {}", kind, bookingId);
    }
}
