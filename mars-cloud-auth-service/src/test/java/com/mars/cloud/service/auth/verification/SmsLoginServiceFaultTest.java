package com.mars.cloud.service.auth.verification;

import com.mars.cloud.service.auth.application.AccountService;
import com.mars.cloud.service.auth.application.SmsLoginService;
import com.mars.cloud.service.auth.infrastructure.sms.CaptchaProvider;
import com.mars.cloud.service.auth.infrastructure.sms.SmsChallengeStore;
import com.mars.cloud.service.auth.infrastructure.sms.SmsCrypto;
import com.mars.cloud.service.auth.infrastructure.sms.SmsFailure;
import com.mars.cloud.service.auth.infrastructure.sms.SmsRiskService;
import com.mars.cloud.service.auth.infrastructure.sms.SmsSender;
import com.mars.cloud.mvc.util.I18nUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsLoginServiceFaultTest {
    private static final String PHONE="+15551234567";
    private org.mockito.MockedStatic<I18nUtil> messages;

    @BeforeEach void setUp() { messages=mockStatic(I18nUtil.class); }
    @AfterEach void tearDown() { messages.close(); }

    @Test void redisFailureMustRejectSendBeforeSenderRuns() {
        var crypto=mock(SmsCrypto.class);
        var sender=mock(SmsSender.class);
        var risk=mock(SmsRiskService.class);
        var challenges=mock(SmsChallengeStore.class);
        when(crypto.enabled()).thenReturn(true);
        when(sender.available()).thenReturn(true);
        when(risk.reserve(eq(PHONE),anyString(),eq(false)))
                .thenThrow(new DataAccessResourceFailureException("Redis unavailable"));
        var service=new SmsLoginService(crypto,sender,risk,challenges,mock(CaptchaProvider.class),mock(AccountService.class));
        var request=new MockHttpServletRequest();
        request.getSession(true);
        SmsFailure failure=assertThrows(SmsFailure.class,() -> service.send(PHONE,null,null,request));
        assertEquals(503,failure.getHttpStatusCode());
        verify(sender,never()).send(anyString(),anyString(),anyString());
    }

    @Test void accountFailureAfterCodeConsumptionMustRejectLogin() {
        var crypto=mock(SmsCrypto.class);
        var sender=mock(SmsSender.class);
        var risk=mock(SmsRiskService.class);
        var challenges=mock(SmsChallengeStore.class);
        var accounts=mock(AccountService.class);
        when(crypto.enabled()).thenReturn(true);
        when(sender.available()).thenReturn(true);
        when(challenges.verify(PHONE,"LOGIN","session","abcdefghijklmnopqrstuv","123456")).thenReturn(1);
        when(accounts.registerVerifiedPhone(PHONE))
                .thenThrow(new DataAccessResourceFailureException("MySQL unavailable"));
        var service=new SmsLoginService(crypto,sender,risk,challenges,mock(CaptchaProvider.class),accounts);
        SmsFailure failure=assertThrows(SmsFailure.class,() -> service.authenticate(
                PHONE,"abcdefghijklmnopqrstuv","123456",null,null,"session"));
        assertEquals(503,failure.getHttpStatusCode());
        verify(accounts).registerVerifiedPhone(PHONE);
    }
}
