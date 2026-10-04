package dev.linjian.peek;

public class StatusOverlayConfigTest {
    public static void main(String[] args) {
        StatusOverlayConfig c = new StatusOverlayConfig(" https://example.com/mcp/abcdefgh12345678/ ");
        if (!c.endpoint.equals("https://example.com/mcp/abcdefgh12345678/status")) throw new AssertionError();
        if (!c.token.equals("abcdefgh12345678")) throw new AssertionError();
        String[] bad = { "http://example.com/mcp/abcdefgh12345678", "https://u:p@example.com/mcp/abcdefgh12345678",
            "https://example.com/mcp/short", "https://example.com/mcp/abcdefgh12345678?secret=1",
            "https://example.com/mcp/abcdefgh12345678#fragment", "https://example.com/mcp/abcdefgh12345678/status",
            "https://example.com/mcp/abcd%65fgh12345678", "", "file:///mcp/abcdefgh12345678" };
        for (String input : bad) {
            boolean rejected = false;
            try { new StatusOverlayConfig(input); } catch (IllegalArgumentException e) { rejected = true; }
            if (!rejected) throw new AssertionError("Invalid input accepted");
        }
        if (StatusOverlayConfig.retryDelay(1) != 10000 || StatusOverlayConfig.retryDelay(10000) != 60000)
            throw new AssertionError("Backoff bounds");
        System.out.println("PASS: HTTPS URL validation, secret parsing and bounded reconnect delays");
    }
}
