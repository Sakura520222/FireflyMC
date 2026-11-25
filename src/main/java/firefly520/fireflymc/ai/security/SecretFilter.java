package firefly520.fireflymc.ai.security;

import java.util.List;

/** Defense in depth. Config objects/credentials are never offered as model context. */
public final class SecretFilter {
    private final List<String> secrets;
    public SecretFilter(List<String> secrets) {
        this.secrets = secrets.stream().filter(s -> s != null && !s.isBlank()).toList();
    }
    public String redact(String value) {
        String result = value;
        for (String secret : secrets) result = result.replace(secret, "[REDACTED]");
        return result.replaceAll("(?i)(Bearer\\s+)[^\\s\"']+", "$1[REDACTED]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{12,}", "[REDACTED]");
    }
    /** Delay an uncommitted suffix so a credential split across provider chunks is never exposed. */
    public String streamingPrefix(String value) {
        int held = Math.max(64, secrets.stream().mapToInt(String::length).max().orElse(0) + 16);
        String safe = redact(value);
        int end = Math.max(0, safe.length() - held);
        if (end > 0 && Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        return safe.substring(0, end);
    }
}
