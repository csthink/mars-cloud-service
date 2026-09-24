package com.mars.cloud.service.auth.interfaces;

import com.mars.cloud.mvc.annotation.IgnoreResponseAnnotation;
import com.mars.cloud.service.auth.configuration.AuthProperties;
import com.mars.cloud.service.auth.domain.ClientPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** A test-only consent form; product login and consent pages are separate application features. */
@RestController
public class LocalConsentController {
    private final AuthProperties properties;
    private final org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients;
    public LocalConsentController(AuthProperties properties,org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients) { this.properties=properties;this.clients=clients; }
    @GetMapping(value="/login/consent",produces=MediaType.TEXT_HTML_VALUE)
    @IgnoreResponseAnnotation
    public ResponseEntity<String> consent(@RequestParam("client_id") String clientId,@RequestParam String state,
            Authentication authentication,HttpServletRequest request) {
        if (!properties.getLocalLogin().isEnabled() || authentication==null || !authentication.isAuthenticated()
                || !ClientPolicy.nativeClient(clientId)) return ResponseEntity.notFound().build();
        var client=clients.findByClientId(clientId);
        if(client==null)return ResponseEntity.notFound().build();
        var csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        String html="<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Local authorization test</title>"
                +"<main><h1>Authorize local test client</h1><p>"+HtmlUtils.htmlEscape(clientId)+"</p><form method=\"post\" action=\"/oauth2/authorize\">"
                +hidden("client_id",clientId)+hidden("state",state)+hidden(csrf.getParameterName(),csrf.getToken())
                +"<button type=\"submit\" name=\"scope\" value=\"openid\">Authorize</button> <button type=\"submit\">Cancel</button></form></main></html>";
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Security-Policy","default-src 'none'; form-action 'self' "+String.join(" ",client.getRedirectUris())+"; frame-ancestors 'none'")
                .header("Referrer-Policy","no-referrer").body(html);
    }
    private static String hidden(String name,String value) {
        return "<input type=\"hidden\" name=\""+HtmlUtils.htmlEscape(name)+"\" value=\""+HtmlUtils.htmlEscape(value)+"\">";
    }
}
