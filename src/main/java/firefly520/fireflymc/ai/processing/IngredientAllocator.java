package firefly520.fireflymc.ai.processing;

import java.util.*;

/** Bounded backtracking over server-captured matching facts; no registry/world access on the search worker. */
public final class IngredientAllocator {
    public record Stock(int count, String pool, long weight) {}
    private IngredientAllocator() {}
    /** Splits identical component identities across physical slots while retaining one concrete input per recipe position. */
    public static int[][] allocateBatch(List<int[]> candidates, List<Stock> stocks, List<String> fingerprints,
                                       Map<String, Long> poolUnits, int count, int nodeLimit) {
        int[] amounts = new int[candidates.size()]; Arrays.fill(amounts, count);
        return allocateBatchCounts(candidates, stocks, fingerprints, poolUnits, amounts, nodeLimit);
    }
    public static int[][] allocateBatchCounts(List<int[]> candidates, List<Stock> stocks, List<String> fingerprints,
                                             Map<String, Long> poolUnits, int[] amounts, int nodeLimit) {
        if (candidates.size() > 81 || stocks.size() > 16384 || fingerprints.size() != stocks.size()
                || amounts.length != candidates.size() || Arrays.stream(amounts).anyMatch(n -> n < 1 || n > 512)
                || Arrays.stream(amounts).sum() > 512 || nodeLimit < 1)
            throw new IllegalArgumentException("ALLOCATION_LIMIT");
        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) { order[i] = i; if (candidates.get(i).length == 0) throw new IllegalStateException("MATERIAL_MISSING"); }
        Arrays.sort(order, Comparator.comparingInt(i -> candidates.get(i).length));
        int[][] result = new int[candidates.size()][stocks.size()];
        int[] remaining = stocks.stream().mapToInt(Stock::count).toArray();
        if (!batch(0, 0, 0, null, order, candidates, stocks, fingerprints, new HashMap<>(poolUnits), remaining, result, amounts, new int[]{nodeLimit}))
            throw new IllegalStateException("MATERIAL_ASSIGNMENT_UNAVAILABLE_OR_SPLIT_BATCH_REQUIRED");
        return result;
    }
    private static boolean batch(int depth, int unit, int firstCandidate, String fingerprint, Integer[] order,
                                 List<int[]> candidates, List<Stock> stocks, List<String> fingerprints,
                                 Map<String, Long> pools, int[] remaining, int[][] result, int[] amounts, int[] budget) {
        if (--budget[0] < 0) throw new IllegalStateException("ALLOCATION_SEARCH_BUDGET");
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("ALLOCATION_CANCELLED");
        if (depth == order.length) return true;
        int ingredient = order[depth];
        if (unit == amounts[ingredient]) return batch(depth + 1, 0, 0, null, order, candidates, stocks, fingerprints, pools, remaining, result, amounts, budget);
        int[] options = candidates.get(ingredient);
        for (int option = firstCandidate; option < options.length; option++) {
            int index = options[option]; Stock stock = stocks.get(index);
            if (remaining[index] == 0 || fingerprint != null && !fingerprint.equals(fingerprints.get(index))) continue;
            long pool = pools.getOrDefault(stock.pool(), 0L);
            if (pool < stock.weight()) continue;
            remaining[index]--; result[ingredient][index]++; pools.put(stock.pool(), pool - stock.weight());
            if (batch(depth, unit + 1, option, fingerprints.get(index), order, candidates, stocks, fingerprints,
                    pools, remaining, result, amounts, budget)) return true;
            remaining[index]++; result[ingredient][index]--; pools.put(stock.pool(), pool);
        }
        return false;
    }
    public static int[] allocate(List<int[]> candidates, List<Stock> stock, Map<String, Long> poolUnits,
                                 int craftCount, int nodeLimit) {
        if (candidates.size() > 81 || stock.size() > 16384 || craftCount < 1 || craftCount > 64 || nodeLimit < 1)
            throw new IllegalArgumentException("ALLOCATION_LIMIT");
        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) { order[i] = i; if (candidates.get(i).length == 0) throw new IllegalStateException("MATERIAL_MISSING"); }
        Arrays.sort(order, Comparator.comparingInt(i -> candidates.get(i).length));
        int[] remaining = stock.stream().mapToInt(Stock::count).toArray(), answer = new int[candidates.size()];
        if (!search(0, order, candidates, stock, new HashMap<>(poolUnits), remaining, answer, craftCount, new int[]{nodeLimit}))
            throw new IllegalStateException("MATERIAL_ASSIGNMENT_UNAVAILABLE");
        return answer;
    }
    private static boolean search(int depth, Integer[] order, List<int[]> candidates, List<Stock> stocks,
                                  Map<String, Long> pools, int[] remaining, int[] answer, int count, int[] budget) {
        if (--budget[0] < 0) throw new IllegalStateException("ALLOCATION_SEARCH_BUDGET");
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("ALLOCATION_CANCELLED");
        if (depth == order.length) return true;
        int ingredient = order[depth];
        for (int index : candidates.get(ingredient)) {
            Stock stock = stocks.get(index);
            long units = Math.multiplyExact(stock.weight(), count), pool = pools.getOrDefault(stock.pool(), 0L);
            if (remaining[index] < count || pool < units) continue;
            remaining[index] -= count; pools.put(stock.pool(), pool - units); answer[ingredient] = index;
            if (search(depth + 1, order, candidates, stocks, pools, remaining, answer, count, budget)) return true;
            remaining[index] += count; pools.put(stock.pool(), pool);
        }
        return false;
    }
}
