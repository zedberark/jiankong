package org.ops.netpulse.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class AuthTokenService {

    private static final long EXPIRE_SECONDS = 8 * 60 * 60;

    private final byte[] secret;

    public AuthTokenService(@Value("${auth.token-secret}") String secret) {
        if (secret == null || secret.isBlank() || secret.length() < 16) {
            throw new IllegalStateException("缺少安全配置 auth.token-secret（至少16位）");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issueToken(String username) {
        long exp = Instant.now().getEpochSecond() + EXPIRE_SECONDS;
        String payload = username + ":" + exp;
        String sig = sign(payload);
        String raw = payload + ":" + sig;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public String verifyAndGetUsername(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] arr = raw.split(":");
            if (arr.length != 3) return null;
            String username = arr[0];
            long exp = Long.parseLong(arr[1]);
            String sig = arr[2];
            String payload = username + ":" + exp;
            if (!sign(payload).equals(sig)) return null;
            if (Instant.now().getEpochSecond() > exp) return null;
            return username;
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("token签名失败", e);
        }
    }
}
