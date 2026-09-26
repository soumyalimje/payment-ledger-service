import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Test double: a webhook endpoint that always fails FAST.
 *
 * Returns HTTP 500 to every POST and appends one line per REAL request
 * to a hit-counter file, so tests can assert exactly how many delivery
 * attempts were made. Bare TCP connections (readiness probes, port
 * scans) are served but NOT counted -- only requests that actually sent
 * a POST request line count as delivery attempts.
 *
 * Running on loopback means every attempt completes in milliseconds --
 * no dependence on OS TCP timeout behavior for unreachable external
 * hosts (macOS can block ~75s per connect).
 *
 * Launched via the single-file source launcher, no compile step needed:
 *   java tests/FailFastServer.java <port> <hitfile>
 */
public class FailFastServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path hitFile = Path.of(args[1]);
        byte[] response = "HTTP/1.1 500 Always Failing\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("FailFastServer listening on " + port);
            while (true) {
                try (Socket socket = server.accept()) {
                    // Drain request headers (bounded, sloppily -- it's a test double)
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[4096];
                    int total = 0;
                    boolean sawRequestLine = false;
                    while (total < 4096) {
                        int n = in.read(buf, 0, Math.min(1024, buf.length - total));
                        if (n < 0) break;
                        total += n;
                        String seen = new String(buf, 0, total, StandardCharsets.UTF_8);
                        if (seen.startsWith("POST ")) sawRequestLine = true;
                        if (seen.contains("\r\n\r\n")) break; // end of headers
                    }
                    if (sawRequestLine) {
                        Files.writeString(hitFile, "hit\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(response);
                    out.flush();
                } catch (Exception e) {
                    // keep serving -- a malformed probe must not kill the double
                }
            }
        }
    }
}
