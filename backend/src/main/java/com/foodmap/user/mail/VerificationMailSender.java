package com.foodmap.user.mail;

import com.foodmap.user.config.VerificationCodeProperties;
import com.foodmap.user.config.VerificationMailProperties;
import com.foodmap.user.dto.VerificationCodeReservation;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 邮件适配器只负责模板与SMTP投递，不管理验证码额度、激活或消费状态，禁止记录敏感内容。 */
@Component
@RequiredArgsConstructor
public class VerificationMailSender {
    private final JavaMailSender mailSender;
    private final VerificationCodeProperties properties;
    private final VerificationMailProperties mailProperties;

    /** 生成并发送邮件，异常交由业务服务取消预留资格，保持既有失败语义。 */
    public void send(VerificationCodeReservation reservation) {
        try { mailSender.send(createMailMessage(reservation)); }
        catch(org.springframework.mail.MailException e) {
            throw new com.foodmap.common.exception.BusinessException(
                com.foodmap.user.exception.VerificationCodeErrorCode.MAIL_DELIVERY_FAILED);
        }
    }

    private SimpleMailMessage createMailMessage(VerificationCodeReservation reservation) {
        String operationName = switch (reservation.getPurpose()) {
            case REGISTER -> "注册";
            case PASSWORD_RESET -> "密码重置";
        };
        long validMinutes = Math.max(1, properties.getTimeToLive().toMinutes());

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailProperties.getFrom());
        message.setTo(reservation.getRecipientEmail());
        message.setSubject(mailProperties.getSubjectPrefix() + operationName + "验证码");
        message.setText("""
                您好：

                您正在进行%s操作，本次验证码为：%s

                验证码在%d分钟内有效，请勿向任何人泄露。如非本人操作，请忽略本邮件。
                """.formatted(operationName, reservation.getCode(), validMinutes));
        return message;
    }

}
