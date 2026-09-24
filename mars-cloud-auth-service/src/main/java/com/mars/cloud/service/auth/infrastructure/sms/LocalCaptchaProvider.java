package com.mars.cloud.service.auth.infrastructure.sms;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public final class LocalCaptchaProvider implements CaptchaProvider {
    private static final String ALPHABET="23456789ABCD";
    private static final String PUT = """
        redis.call('HSET', KEYS[1], 'purpose', ARGV[1], 'session', ARGV[2], 'hash', ARGV[3])
        redis.call('PEXPIRE', KEYS[1], 120000)
        return 1
        """;
    private static final String CONSUME = """
        if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
        local fields=redis.call('HMGET', KEYS[1], 'purpose', 'session', 'hash')
        redis.call('DEL', KEYS[1])
        if fields[1]==ARGV[1] and fields[2]==ARGV[2] and fields[3]==ARGV[3] then return 1 end
        return 0
        """;
    private final SmsRedis redis;
    private final SmsCrypto crypto;
    private final SmsRiskService risk;
    private final Map<Character,String[]> glyphs;
    public LocalCaptchaProvider(SmsRedis redis,SmsCrypto crypto,SmsRiskService risk) {
        this.redis=redis; this.crypto=crypto; this.risk=risk; this.glyphs=loadFont();
    }
    @Override public Image create(String purpose,String session,String ip) {
        risk.reserveCaptcha(session,ip);
        StringBuilder answer=new StringBuilder();
        for (int i=0;i<5;i++) answer.append(ALPHABET.charAt(SmsCrypto.random(ALPHABET.length())));
        String id=SmsCrypto.randomId();
        redis.run(PUT,List.of(key(id)),purpose,crypto.digest("captcha-session",session),
                crypto.digest("captcha-answer",id+":"+answer));
        return new Image(id,render(answer.toString()));
    }
    @Override public boolean consume(String purpose,String session,String id,String answer) {
        if (id==null || !id.matches("[A-Za-z0-9_-]{22}")) return false;
        String submitted=answer==null || answer.length()>32 ? "" : answer;
        return redis.run(CONSUME,List.of(key(id)),purpose,crypto.digest("captcha-session",session),
                crypto.digest("captcha-answer",id+":"+submitted))==1;
    }
    private static String key(String id) { return "mars:auth:captcha:challenge:"+id; }
    private static Map<Character,String[]> loadFont() {
        try (InputStream stream=LocalCaptchaProvider.class.getResourceAsStream("/captcha/font-5x7.txt")) {
            if (stream==null) throw new IllegalStateException("Bundled captcha font missing");
            Map<Character,String[]> result=new HashMap<>();
            for (String line:new String(stream.readAllBytes(),StandardCharsets.US_ASCII).split("\\R")) {
                if (line.isBlank()) continue;
                result.put(line.charAt(0),line.substring(2).split("/"));
            }
            for (char c:ALPHABET.toCharArray()) if (!result.containsKey(c) || result.get(c).length!=7)
                throw new IllegalStateException("Bundled captcha font incomplete");
            return Map.copyOf(result);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot load captcha font",ex); }
    }
    private byte[] render(String answer) {
        BufferedImage image=new BufferedImage(180,64,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();
        graphics.setColor(new Color(246,248,250)); graphics.fillRect(0,0,180,64);
        for (int i=0;i<5;i++) {
            String[] rows=glyphs.get(answer.charAt(i)); int x=8+i*34, y=8+SmsCrypto.random(7);
            graphics.setColor(new Color(25+SmsCrypto.random(80),35+SmsCrypto.random(80),50+SmsCrypto.random(80)));
            for (int row=0;row<7;row++) for (int col=0;col<5;col++)
                if (rows[row].charAt(col)=='1') graphics.fillRect(x+col*5,y+row*6,5,6);
        }
        for (int i=0;i<130;i++) {
            graphics.setColor(new Color(110+SmsCrypto.random(130),110+SmsCrypto.random(130),110+SmsCrypto.random(130)));
            graphics.fillRect(SmsCrypto.random(180),SmsCrypto.random(64),1+SmsCrypto.random(3),1+SmsCrypto.random(3));
        }
        for (int i=0;i<4;i++) {
            graphics.setColor(new Color(120+SmsCrypto.random(100),120+SmsCrypto.random(100),120+SmsCrypto.random(100)));
            graphics.drawLine(SmsCrypto.random(180),SmsCrypto.random(64),SmsCrypto.random(180),SmsCrypto.random(64));
        }
        graphics.dispose();
        try {
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            ImageIO.write(image,"png",output); return output.toByteArray();
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot render captcha",ex); }
    }
}
