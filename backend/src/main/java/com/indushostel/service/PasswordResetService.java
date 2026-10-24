package com.indushostel.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Service
public class PasswordResetService {
    private static final Logger log=LoggerFactory.getLogger(PasswordResetService.class);
    private final JdbcTemplate db;private final PasswordEncoder encoder;private final ObjectProvider<JavaMailSender> mailProvider;private final String origin,from;
    public PasswordResetService(JdbcTemplate db,PasswordEncoder encoder,ObjectProvider<JavaMailSender> mailProvider,@Value("${app.frontend-origin}")String origin,@Value("${spring.mail.username:}")String from){this.db=db;this.encoder=encoder;this.mailProvider=mailProvider;this.origin=origin;this.from=from;}

    public void request(String email) {
        Map<String,Object> row=db.queryForList("SELECT id,email FROM users WHERE email=? AND enabled=TRUE",email.toLowerCase().trim()).stream().findFirst().orElse(null);
        JavaMailSender mail=mailProvider.getIfAvailable();if(row==null||mail==null)return;
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash=hash(token);long userId=((Number)row.get("id")).longValue();
        db.update("UPDATE password_reset_tokens SET used_at=CURRENT_TIMESTAMP WHERE user_id=? AND used_at IS NULL",userId);
        db.update("INSERT INTO password_reset_tokens(user_id,token_hash,expires_at) VALUES(?,?,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 30 MINUTE))",userId,hash);
        try {
            SimpleMailMessage message=new SimpleMailMessage();if(from!=null&&!from.isBlank())message.setFrom(from);message.setTo((String)row.get("email"));message.setSubject("Password reset request");message.setText("A password reset was requested for your INDUS HOSTEL SERVICE BADIN account. Use this link within 30 minutes:\n\n"+origin+"/reset-password?token="+token+"\n\nIf you did not request this, ignore this message.");mail.send(message);
        } catch(Exception e) { db.update("UPDATE password_reset_tokens SET used_at=CURRENT_TIMESTAMP WHERE token_hash=?",hash);log.warn("Password reset email delivery failed for user id {}",userId); }
    }

    @Transactional
    public void confirm(String token,String newPassword) {
        String hash=hash(token);Map<String,Object> row=db.queryForList("SELECT id,user_id AS userId FROM password_reset_tokens WHERE token_hash=? AND used_at IS NULL AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",hash).stream().findFirst().orElseThrow(()->new IllegalArgumentException("This password reset link is invalid or has expired"));
        long tokenId=((Number)row.get("id")).longValue(),userId=((Number)row.get("userId")).longValue();
        db.update("UPDATE users SET password_hash=? WHERE id=?",encoder.encode(newPassword),userId);
        db.update("UPDATE password_reset_tokens SET used_at=CURRENT_TIMESTAMP WHERE id=?",tokenId);
        db.update("INSERT INTO audit_logs(actor_user_id,action,entity_type,entity_id,reason) VALUES(?,'PASSWORD_RESET','user',?,'Password reset completed using single-use email token')",userId,userId);
    }

    private String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Password reset could not be processed");}}
}
