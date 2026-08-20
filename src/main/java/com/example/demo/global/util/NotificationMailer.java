package com.example.demo.global.util;

import org.slf4j.Logger;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

public final class NotificationMailer {

    private NotificationMailer() {
    }

    public static void sendQuietly(MailSender mailSender, Logger log, String mailUsername,
                                    String[] to, String subject, String text, String failureLogMessage) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("SolarAivle <" + mailUsername + ">");
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.warn(failureLogMessage, e);
        }
    }
}
