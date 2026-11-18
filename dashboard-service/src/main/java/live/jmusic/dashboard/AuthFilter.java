package live.jmusic.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.annotation.PostConstruct;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Every /api call needs the dashboard token (cookie set by /api/login, or X-Auth-Token header).
 * Token: property dashboard.token, else the file dashboard.token in the working dir (created on first start).
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);
    public static final String COOKIE = "dash_token";

    @Value("${dashboard.token:}")
    private String configured;

    private String token;

    @PostConstruct
    public void init() {
        try {
            loadToken();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read/create dashboard.token", e);
        }
    }

    private void loadToken() throws IOException {
        if (configured != null && !configured.isBlank()) {
            token = configured.trim();
            return;
        }
        File f = new File("dashboard.token");
        if (f.isFile()) {
            token = Files.readString(f.toPath()).trim();
        } else {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            Files.writeString(f.toPath(), token + "\n");
            try {
                Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
            }
            LOG.warn("Dashboard token generated and written to {}", f.getAbsolutePath());
        }
    }

    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(candidate.trim().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/login")) {
            chain.doFilter(req, res);
            return;
        }
        String candidate = req.getHeader("X-Auth-Token");
        if (candidate == null && req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (COOKIE.equals(c.getName())) candidate = c.getValue();
            }
        }
        if (!matches(candidate)) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
