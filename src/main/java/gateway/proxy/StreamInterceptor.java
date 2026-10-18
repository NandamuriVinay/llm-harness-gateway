package gateway.proxy;

import gateway.telemetry.StreamMetrics;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class StreamInterceptor {

    public record StreamResult(StreamMetrics metrics, List<String> capturedChunks) {
    }

    public static StreamResult pipeAndInstrument(
            InputStream upstreamIn,
            OutputStream clientOut,
            long requestStartTimeNanos) {

        List<String> capturedChunks = new ArrayList<>();

        try (upstreamIn; clientOut) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            long firstTokenTimeNanos = -1;
            int totalTokens = 0;

            while ((bytesRead = upstreamIn.read(buffer)) != -1) {
                // 1. Immediately push bytes to client without buffering
                clientOut.write(buffer, 0, bytesRead);
                clientOut.flush();

                // 2. Capture chunk for semantic cache
                String chunkText = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                capturedChunks.add(chunkText);

                // 3. Inspect chunk on the fly for SSE token events
                if (chunkText.contains("data:") && !chunkText.contains("[DONE]")) {
                    if (firstTokenTimeNanos == -1) {
                        firstTokenTimeNanos = System.nanoTime();
                    }

                    // Count "data:" occurrences in this buffer segment
                    int idx = 0;
                    while ((idx = chunkText.indexOf("data:", idx)) != -1) {
                        int lineEnd = chunkText.indexOf("\n", idx);
                        String line = (lineEnd == -1) ? chunkText.substring(idx) : chunkText.substring(idx, lineEnd);
                        if (!line.contains("[DONE]")) {
                            totalTokens++;
                        }
                        idx += 5;
                    }
                }
            }

            long endTimeNanos = System.nanoTime();

            // Total request duration
            double totalDurationMs = (endTimeNanos - requestStartTimeNanos) / 1_000_000.0;

            // TTFT: from request start to first token arrival
            double ttftMs = (firstTokenTimeNanos > 0)
                    ? (firstTokenTimeNanos - requestStartTimeNanos) / 1_000_000.0
                    : totalDurationMs;

            // Generation duration (seconds): from first token until the stream finishes
            double generationDurationSec = (firstTokenTimeNanos > 0)
                    ? (endTimeNanos - firstTokenTimeNanos) / 1_000_000_000.0
                    : 0.0;

            // Tokens per second
            double tps = (generationDurationSec > 0)
                    ? (totalTokens / generationDurationSec)
                    : 0.0;

            StreamMetrics metrics = new StreamMetrics(ttftMs, totalDurationMs, totalTokens, tps);
            return new StreamResult(metrics, capturedChunks);

        } catch (Exception e) {
            System.err.println("[StreamInterceptor] Error while streaming: " + e.getMessage());
            StreamMetrics metrics = new StreamMetrics(0.0, 0.0, 0, 0.0);
            return new StreamResult(metrics, capturedChunks);
        }
    }
}
