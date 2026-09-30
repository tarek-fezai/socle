package eu.socle.document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Diff structurel JSONB pour Diff.dc.html — comparaison récursive Map/List/scalaires.
 */
public final class BodyDiff {

    private BodyDiff() {}

    public record Change(String path, String op, Object before, Object after) {}

    public record DiffResult(int fromVersion, int toVersion, List<Change> changes) {}

    public static DiffResult diff(int fromVersion, int toVersion, Object fromBody, Object toBody) {
        List<Change> changes = new ArrayList<>();
        walk("", fromBody, toBody, changes);
        return new DiffResult(fromVersion, toVersion, List.copyOf(changes));
    }

    @SuppressWarnings("unchecked")
    private static void walk(String path, Object before, Object after, List<Change> out) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before instanceof Map<?, ?> bm && after instanceof Map<?, ?> am) {
            Map<String, Object> beforeMap = (Map<String, Object>) bm;
            Map<String, Object> afterMap = (Map<String, Object>) am;
            for (String key : unionKeys(beforeMap, afterMap)) {
                String child = path.isEmpty() ? key : path + "." + key;
                boolean inBefore = beforeMap.containsKey(key);
                boolean inAfter = afterMap.containsKey(key);
                if (inBefore && !inAfter) {
                    out.add(new Change(child, "removed", beforeMap.get(key), null));
                } else if (!inBefore && inAfter) {
                    out.add(new Change(child, "added", null, afterMap.get(key)));
                } else {
                    walk(child, beforeMap.get(key), afterMap.get(key), out);
                }
            }
            return;
        }
        if (before instanceof List<?> bl && after instanceof List<?> al) {
            int max = Math.max(bl.size(), al.size());
            for (int i = 0; i < max; i++) {
                String child = path + "[" + i + "]";
                Object b = i < bl.size() ? bl.get(i) : null;
                Object a = i < al.size() ? al.get(i) : null;
                if (b == null && a != null) {
                    out.add(new Change(child, "added", null, a));
                } else if (b != null && a == null) {
                    out.add(new Change(child, "removed", b, null));
                } else {
                    walk(child, b, a, out);
                }
            }
            return;
        }
        // Types différents ou scalaires différents
        if (before == null) {
            out.add(new Change(path.isEmpty() ? "$" : path, "added", null, after));
        } else if (after == null) {
            out.add(new Change(path.isEmpty() ? "$" : path, "removed", before, null));
        } else {
            out.add(new Change(path.isEmpty() ? "$" : path, "modified", before, after));
        }
    }

    private static List<String> unionKeys(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Boolean> keys = new LinkedHashMap<>();
        a.keySet().forEach(k -> keys.put(k, true));
        b.keySet().forEach(k -> keys.put(k, true));
        return List.copyOf(keys.keySet());
    }
}
