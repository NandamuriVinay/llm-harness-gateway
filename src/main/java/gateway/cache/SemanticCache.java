package gateway.cache;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class SemanticCache {

    public record CacheHit(String matchedPrompt, double similarity, List<String> sseChunks) {}

    private record CacheEntry(String prompt, float[] vector, List<String> sseChunks) {}

    private final List<CacheEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * Searches the cache for the most semantically similar prompt.
     * If the highest cosine similarity is >= threshold, returns the CacheHit.
     */
    public Optional<CacheHit> findSimilar(String prompt, double threshold) {
        if (entries.isEmpty() || prompt == null || prompt.isBlank()) {
            return Optional.empty();
        }

        float[] queryVector = VectorUtils.embed(prompt);

        CacheEntry bestMatch = null;
        double highestSimilarity = -1.0;

        for (CacheEntry entry : entries) {
            double similarity = VectorUtils.cosineSimilarity(queryVector, entry.vector());
            if (similarity > highestSimilarity) {
                highestSimilarity = similarity;
                bestMatch = entry;
            }
        }

        if (bestMatch != null && highestSimilarity >= threshold) {
            return Optional.of(new CacheHit(bestMatch.prompt(), highestSimilarity, bestMatch.sseChunks()));
        }

        return Optional.empty();
    }

    /**
     * Stores a prompt and its streamed SSE response chunks into the cache.
     */
    public void put(String prompt, List<String> sseChunks) {
        if (prompt == null || prompt.isBlank() || sseChunks == null || sseChunks.isEmpty()) {
            return;
        }

        float[] vector = VectorUtils.embed(prompt);
        entries.add(new CacheEntry(prompt, vector, List.copyOf(sseChunks)));
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
