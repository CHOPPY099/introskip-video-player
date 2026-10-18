package com.choppy.ultimateplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class BrowserUrlPolicyTest {
    @Test
    public void preservesOpaquePathAndSignedQueryExactly() {
        String url = "https://example.com/watch/aZ-42_ef?token=A%2FB%3D&id=opaque-19#player";
        assertEquals(url, BrowserUrlPolicy.webUrl(" " + url + " "));
        assertEquals("HTTP://example.com:8080/a", BrowserUrlPolicy.webUrl("HTTP://example.com:8080/a"));
        assertEquals("https://[::1]/a", BrowserUrlPolicy.webUrl("https://[::1]/a"));
    }

    @Test
    public void rejectsNonWebMalformedAndCredentialUrls() {
        for (String url : new String[]{null, "", "example.com", "/relative", "//example.com/a",
                "javascript:alert(1)", "intent://example.com/#Intent;end", "file:///sdcard/a",
                "content://media/1", "blob:https://example.com/id", "data:text/html,test", "about:blank",
                "mailto:person@example.com", "https:example.com", "https:///a", "https://example.com:65536/",
                "https://user:password@example.com/a", "https://example.com\\@evil.test/",
                "https://example.com/a\r\n", "https://example.com/a b", "https://example.com/%invalid"}) {
            assertNull(url, BrowserUrlPolicy.webUrl(url));
        }
    }

    @Test
    public void onlyRemembersSuccessfulCurrentMainPage() {
        String main = "https://example.com/watch/opaque-id";
        assertTrue(BrowserUrlPolicy.canRememberVisit(main, main, false));
        assertFalse(BrowserUrlPolicy.canRememberVisit("https://cdn.example.com/ad.js", main, false));
        assertFalse(BrowserUrlPolicy.canRememberVisit("https://example.com/old", main, false));
        assertFalse(BrowserUrlPolicy.canRememberVisit(main, main, true));
        assertFalse(BrowserUrlPolicy.canRememberVisit("file:///private", "file:///private", false));
        assertFalse(BrowserUrlPolicy.canRememberVisit(null, null, false));
    }

    @Test
    public void sameOriginRefererPreservesQueryButNeverFragment() {
        assertEquals("https://example.com/watch/id?session=signed",
                BrowserUrlPolicy.downloadReferer("https://example.com/watch/id?session=signed#private", "https://example.com/file"));
        assertEquals("https://example.com/watch/id",
                BrowserUrlPolicy.downloadReferer("https://example.com/watch/id", "https://example.com:443/file"));
    }

    @Test
    public void crossOriginRefererDoesNotLeakPathOrQuery() {
        assertEquals("https://example.com/",
                BrowserUrlPolicy.downloadReferer("https://example.com/watch/private?token=secret#fragment", "https://cdn.example.com/file"));
        assertEquals("https://example.com:8443/",
                BrowserUrlPolicy.downloadReferer("https://example.com:8443/watch/id?token=secret", "https://example.com/file"));
        assertNull(BrowserUrlPolicy.downloadReferer("https://example.com/watch/id", "http://example.com/file"));
        assertNull(BrowserUrlPolicy.downloadReferer("file:///private", "https://example.com/file"));
        assertNull(BrowserUrlPolicy.downloadReferer(null, "https://example.com/file"));
    }

    @Test
    public void rejectsHeaderInjection() {
        assertTrue(BrowserUrlPolicy.isSafeHeader("session=opaque; other=value"));
        assertTrue(BrowserUrlPolicy.isSafeHeader("Mozilla/5.0 UltimatePlayer"));
        assertFalse(BrowserUrlPolicy.isSafeHeader("session=value\r\nOther: injected"));
        assertFalse(BrowserUrlPolicy.isSafeHeader("token=\u0000bad"));
        assertFalse(BrowserUrlPolicy.isSafeHeader(" "));
        assertFalse(BrowserUrlPolicy.isSafeHeader(null));
    }

    @Test
    public void downloadNamesCannotEscapeDownloadsOrHideTheirExtension() {
        assertEquals("_.._video.mp4", BrowserUrlPolicy.safeFileName("../../video.mp4"));
        assertEquals("video_.mp4", BrowserUrlPolicy.safeFileName("video\n.mp4"));
        assertEquals("file.exe", BrowserUrlPolicy.safeFileName("file\u202E.exe"));
        assertEquals("download", BrowserUrlPolicy.safeFileName(".."));
        assertEquals("download", BrowserUrlPolicy.safeFileName(null));
        assertEquals("episode_0485.mp4", BrowserUrlPolicy.safeFileName("episode_0485.mp4"));
        String longName = new String(new char[200]).replace('\0', 'a') + ".mp4";
        assertEquals(120, BrowserUrlPolicy.safeFileName(longName).length());
        assertTrue(BrowserUrlPolicy.safeFileName(longName).endsWith(".mp4"));
    }

    @Test
    public void unicodeDownloadNamesFitFilesystemByteLimitWithoutSplittingCharacters() {
        String name = new String(new char[120]).replace('\0', '\u754C') + ".mp4";
        String safe = BrowserUrlPolicy.safeFileName(name);
        assertTrue(safe.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertTrue(safe.endsWith(".mp4"));
        String supplementary = new String(new char[120]).replace("\0", "\uD83C\uDFAC") + ".mkv";
        String safeSupplementary = BrowserUrlPolicy.safeFileName(supplementary);
        assertTrue(safeSupplementary.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertEquals(safeSupplementary, new String(safeSupplementary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertTrue(safeSupplementary.endsWith(".mkv"));
    }
}
