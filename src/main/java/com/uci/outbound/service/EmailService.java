package com.uci.outbound.service;

import com.uci.outbound.entity.EmailDetails;

public interface EmailService {
    public void sendSimpleMail(EmailDetails details);

    public void sendMailWithAttachment(EmailDetails details);
}
