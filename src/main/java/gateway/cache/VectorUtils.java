package gateway.cache;

import java.util.ArrayList;
import java.util.List;

public class VectorUtils {

    public static final int DIMENSIONS = 128;

    /**
     * Converts a text string into a normalized 128-dimensional embedding vector
     * using character/word n-gram hashing (the hashing trick).
     * High-speed, deterministic, and requires zero external model dependencies.
     */
    public static float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }

        // 1. Normalize text: lowercase and strip punctuation
        String normalized = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").trim();
        String[] words = normalized.split("\\s+");
        if (words.length == 0 || (words.length == 1 && words[0].isEmpty())) {
            return vector;
        }

        // 2. Extract unigrams and bigrams
        List<String> ngrams = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            ngrams.add(words[i]);
            if (i < words.length - 1) {
                ngrams.add(words[i] + "_" + words[i + 1]);
            }
        }

        // 3. Hash n-grams into vector dimensions
        for (String ngram : ngrams) {
            int hash = Math.abs(ngram.hashCode()) % DIMENSIONS;
            vector[hash] += 1.0f;
        }

        // 4. L2 Normalize vector so its magnitude is 1.0
        double sumSq = 0.0;
        for (float val : vector) {
            sumSq += val * val;
        }

        if (sumSq > 0.0) {
            float norm = (float) Math.sqrt(sumSq);
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] /= norm;
            }
        }

        return vector;
    }

    /**
     * Calculates cosine similarity between two unit-normalized vectors.
     * Since vectors are L2-normalized, cosine similarity is simply the dot product.
     * Returns a score between 0.0 (orthogonal) and 1.0 (identical direction).
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
        }

        return Math.max(0.0, Math.min(1.0, dotProduct));
    }
}
