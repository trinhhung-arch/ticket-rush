package com.ticketrush.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import com.ticketrush.contracts.ticket.TicketEvents.IssuedTicket;
import com.ticketrush.contracts.ticket.TicketEvents.TicketsIssued;

/** Sends the confirmation email with one inline QR code per ticket (FR-NTF-01). */
@Service
class TicketEmailSender {

    private static final Logger log = LoggerFactory.getLogger(TicketEmailSender.class);

    static final String KIND = "TICKETS";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter STARTS_AT = DateTimeFormatter.ofPattern("HH:mm, dd/MM/yyyy").withZone(ZONE);
    private static final int QR_SIZE = 240;

    private final JavaMailSender mailSender;
    private final SentEmailRepository sentEmails;
    private final NotificationProperties properties;
    private final Counter sent;

    TicketEmailSender(JavaMailSender mailSender, SentEmailRepository sentEmails, NotificationProperties properties,
                      MeterRegistry meters) {
        this.mailSender = mailSender;
        this.sentEmails = sentEmails;
        this.properties = properties;
        this.sent = Counter.builder("ticketrush.emails.sent").tag("kind", KIND).description("Emails sent").register(meters);
    }

    /**
     * Sends inside the caller's transaction and records it after: a failed send rolls back and is retried.
     * The one gap is a crash between sending and committing, which can repeat an email; that is the
     * at-least-once trade-off every consumer here accepts.
     */
    @Transactional
    public void send(TicketsIssued tickets) {
        if (sentEmails.existsByBookingIdAndKind(tickets.bookingId(), KIND)) {
            return;
        }
        String subject = "Vé của bạn: " + tickets.eventName();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(tickets.email());
            helper.setSubject(subject);
            helper.setText(html(tickets), true);
            for (IssuedTicket ticket : tickets.tickets()) {
                helper.addInline(contentId(ticket), new ByteArrayResource(QrCodes.png(ticket.qrToken(), QR_SIZE)), "image/png");
            }
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Could not build the ticket email for booking " + tickets.bookingId(), e);
        }
        sentEmails.save(new SentEmail(tickets.bookingId(), KIND, tickets.email(), subject, Instant.now()));
        sent.increment();
        log.info("Sent {} tickets for booking {} by email", tickets.tickets().size(), tickets.bookingId());
    }

    private static String html(TicketsIssued tickets) {
        StringBuilder html = new StringBuilder()
                .append("<p>Xin chào,</p>")
                .append("<p>Đơn đặt vé <b>").append(tickets.bookingId()).append("</b> đã được xác nhận.</p>")
                .append("<p><b>").append(escape(tickets.eventName())).append("</b><br>")
                .append(escape(tickets.venue())).append("<br>")
                .append(STARTS_AT.format(tickets.startsAt())).append("</p>")
                .append("<p>Mỗi ghế có một mã QR riêng. Hãy xuất trình mã tại cổng vào.</p>");
        for (IssuedTicket ticket : tickets.tickets()) {
            html.append("<div style=\"margin:16px 0\"><p>Ghế <b>").append(escape(ticket.seatCode())).append("</b></p>")
                    .append("<img src=\"cid:").append(contentId(ticket)).append("\" width=\"").append(QR_SIZE)
                    .append("\" height=\"").append(QR_SIZE).append("\" alt=\"Mã QR ghế ")
                    .append(escape(ticket.seatCode())).append("\"></div>");
        }
        return html.toString();
    }

    private static String contentId(IssuedTicket ticket) {
        return "ticket-" + ticket.ticketId();
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text);
    }
}
