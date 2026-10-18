package com.choppy.ultimateplayer;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/** Browser-only validation. Never derives episode URLs or rewrites opaque path/query IDs. */
final class BrowserUrlPolicy {
    private BrowserUrlPolicy() {}

    static String webUrl(String value) {
        if (value == null) return null;
        // Reject header controls before trimming so even trailing CR/LF cannot reach downloads.
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return null;
        }
        String url = value.trim();
        try {
            URI parsed = new URI(url);
            if (!("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))
                    || parsed.getHost() == null || parsed.getHost().isEmpty()
                    || parsed.getRawUserInfo() != null || parsed.getPort() > 65535) return null;
            return url;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    static boolean canRememberVisit(String callbackUrl, String currentMainUrl, boolean failed) {
        String url = webUrl(callbackUrl);
        return !failed && url != null && url.equals(webUrl(currentMainUrl));
    }

    static String downloadReferer(String pageUrl, String downloadUrl) {
        String page = webUrl(pageUrl);
        String download = webUrl(downloadUrl);
        if (page == null || download == null) return null;
        URI source = URI.create(page);
        URI target = URI.create(download);
        if ("https".equalsIgnoreCase(source.getScheme()) && "http".equalsIgnoreCase(target.getScheme())) return null;
        boolean sameOrigin = source.getScheme().equalsIgnoreCase(target.getScheme())
                && source.getHost().equalsIgnoreCase(target.getHost()) && effectivePort(source) == effectivePort(target);
        // Match strict-origin-when-cross-origin without leaking fragment/query tokens to another host.
        if (!sameOrigin) return source.getScheme() + "://" + source.getRawAuthority() + "/";
        int fragment = page.indexOf('#');
        return fragment < 0 ? page : page.substring(0, fragment);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    static boolean isSafeHeader(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    static String safeFileName(String suggested) {
        if (suggested == null) return "download";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < suggested.length(); i++) {
            char c = suggested.charAt(i);
            if (Character.getType(c) == Character.FORMAT) continue;
            clean.append(Character.isISOControl(c) || "\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String name = clean.toString().trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.trim().isEmpty()) return "download";
        if (name.length() > 120 || name.getBytes(StandardCharsets.UTF_8).length > 200) {
            int dot = name.lastIndexOf('.');
            String extension = dot >= 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            int end = Math.min(name.length() - extension.length(), 120 - extension.length());
            if (end > 0 && Character.isHighSurrogate(name.charAt(end - 1))) end--;
            String base = name.substring(0, end);
            // Android filesystem limits are in bytes; preserve the extension and whole code points.
            while ((base + extension).getBytes(StandardCharsets.UTF_8).length > 200) {
                base = base.substring(0, base.offsetByCodePoints(base.length(), -1));
            }
            name = base + extension;
        }
        return name;
    }
}
