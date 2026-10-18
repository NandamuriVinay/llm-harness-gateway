package gateway;

import gateway.cache.SemanticCache;
import gateway.cache.VectorUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SemanticCacheTest {

    @Test
    void testCosineSimilarityIdenticalAndOrthogonal() {
        float[] a = new float[]{1.0f, 0.0f, 0.0f};
        float[] b = new float[]{1.0f, 0.0f, 0.0f};
        float[] c = new float[]{0.0f, 1.0f, 0.0f};

        assertEquals(1.0, VectorUtils.cosineSimilarity(a, b), 0.0001);
        assertEquals(0.0, VectorUtils.cosineSimilarity(a, c), 0.0001);
    }

    @Test
    void testEmbeddingVectorSimilarity() {
        String prompt1 = "What is the capital of France?";
        String prompt2 = "what is the capital of france";
        String prompt3 = "How to bake a chocolate cake at home?";

        float[] v1 = VectorUtils.embed(prompt1);
        float[] v2 = VectorUtils.embed(prompt2);
        float[] v3 = VectorUtils.embed(prompt3);

        // Normalized vectors for identical text (ignoring case/punctuation) must have similarity 1.0
        double simIdentical = VectorUtils.cosineSimilarity(v1, v2);
        assertEquals(1.0, simIdentical, 0.0001);

        // Completely different topic should have low similarity
        double simDifferent = VectorUtils.cosineSimilarity(v1, v3);
        assertTrue(simDifferent < 0.30, "Different prompts should have low similarity, got: " + simDifferent);
    }

    @Test
    void testSemanticCacheHitAndMiss() {
        SemanticCache cache = new SemanticCache();

        String originalPrompt = "Explain quantum computing in simple terms";
        List<String> mockChunks = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Quantum\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\" computing\"}}]}\n\n",
                "data: [DONE]\n\n"
        );

        // Cache miss initially
        assertTrue(cache.findSimilar(originalPrompt, 0.95).isEmpty());

        // Put in cache
        cache.put(originalPrompt, mockChunks);
        assertEquals(1, cache.size());

        // Exact match -> Hit!
        Optional<SemanticCache.CacheHit> exactHit = cache.findSimilar("Explain quantum computing in simple terms", 0.95);
        assertTrue(exactHit.isPresent());
        assertEquals(1.0, exactHit.get().similarity(), 0.0001);
        assertEquals(3, exactHit.get().sseChunks().size());

        // Case-insensitive / minor punctuation variation -> Hit!
        Optional<SemanticCache.CacheHit> variationHit = cache.findSimilar("explain quantum computing in simple terms!", 0.95);
        assertTrue(variationHit.isPresent());
        assertTrue(variationHit.get().similarity() >= 0.95);

        // Completely different query -> Miss!
        Optional<SemanticCache.CacheHit> miss = cache.findSimilar("What is the recipe for chocolate brownies?", 0.95);
        assertTrue(miss.isEmpty());
    }
}
