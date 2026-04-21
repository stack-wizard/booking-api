package com.stackwizard.booking_api.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Shared SMTP send used by reservation confirmation / cancellation / amendment emails.
 */
public final class ReservationEmailDispatchHelper {

    private ReservationEmailDispatchHelper() {
    }

    public static JavaMailSenderImpl buildMailSender(TenantEmailConfigResolver.EmailResolvedConfig emailConfig) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(emailConfig.smtpHost());
        mailSender.setPort(emailConfig.smtpPort());
        if (StringUtils.hasText(emailConfig.smtpUsername())) {
            mailSender.setUsername(emailConfig.smtpUsername());
        }
        if (StringUtils.hasText(emailConfig.smtpPassword())) {
            mailSender.setPassword(emailConfig.smtpPassword());
        }

        Properties props = mailSender.getJavaMailProperties();
        props.setProperty("mail.transport.protocol", "smtp");
        props.setProperty("mail.smtp.auth", String.valueOf(emailConfig.smtpAuth()));
        props.setProperty("mail.smtp.starttls.enable", String.valueOf(emailConfig.smtpStarttlsEnabled()));
        props.setProperty("mail.smtp.ssl.enable", String.valueOf(emailConfig.smtpSslEnabled()));
        return mailSender;
    }

    public static void sendMime(
            TenantEmailConfigResolver.EmailResolvedConfig emailConfig,
            String toEmail,
            String subject,
            String plainText,
            String htmlBody) throws Exception {
        JavaMailSenderImpl mailSender = buildMailSender(emailConfig);
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        String trimmedTo = toEmail.trim();
        String senderEmail = emailConfig.emailFrom().trim();
        helper.setTo(trimmedTo);
        helper.setFrom(emailConfig.emailFrom());
        if (!senderEmail.equalsIgnoreCase(trimmedTo)) {
            helper.addBcc(senderEmail);
        }
        if (StringUtils.hasText(emailConfig.emailReplyTo())) {
            helper.setReplyTo(emailConfig.emailReplyTo());
        }
        helper.setSubject(subject);
        helper.setText(plainText, htmlBody);
        mailSender.send(message);
    }
}
