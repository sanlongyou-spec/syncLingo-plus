package com.si.backend.service;

import com.si.backend.entity.SystemUserInfo;
import com.si.backend.mapper.SystemUserInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Match a 会议安排 participant name (possibly partial, ordered differently per nationality) to a user
 * in the directory (system_user_info).
 *
 * <p>Key insight: all three nationalities carry a Chinese name (中国人=纯中文, 华人=中文+外文,
 * 印尼人=印尼+中文), so the <b>Chinese name is the universal, order-independent anchor</b>. We extract
 * the Han characters from both sides and match on them; the Latin part only disambiguates same-Chinese-name
 * people. This is robust to the name ordering without needing per-nationality parsing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NameMatchService {

    private final SystemUserInfoMapper userInfoMapper;

    public enum Status { MATCHED, AMBIGUOUS, UNMATCHED }

    public record MatchResult(String scheduleName, SystemUserInfo user, Status status) {}

    /** A directory candidate reduced to its match keys. */
    record Entry(long id, String chineseName, Set<String> latinTokens) {}

    record Outcome(Status status, Long id) {}

    public List<MatchResult> match(List<String> scheduleNames) {
        List<SystemUserInfo> all = userInfoMapper.findAll("");
        List<Entry> entries = new ArrayList<>(all.size());
        java.util.Map<Long, SystemUserInfo> byId = new java.util.HashMap<>();
        for (SystemUserInfo u : all) {
            if (u.getId() == null || u.getPersonName() == null) continue;
            entries.add(new Entry(u.getId(), chinesePart(u.getPersonName()), latinTokens(u.getPersonName())));
            byId.put(u.getId(), u);
        }
        List<MatchResult> out = new ArrayList<>();
        for (String name : scheduleNames) {
            if (name == null || name.isBlank()) continue;
            Outcome o = resolve(name, entries);
            out.add(new MatchResult(name.trim(), o.id() != null ? byId.get(o.id()) : null, o.status()));
        }
        return out;
    }

    // ── pure matching core (testable) ───────────────────────────────────────

    static Outcome resolve(String scheduleName, List<Entry> entries) {
        String zh = chinesePart(scheduleName);
        Set<String> latin = latinTokens(scheduleName);

        if (!zh.isBlank()) {
            List<Entry> byZh = entries.stream().filter(e -> zh.equals(e.chineseName())).toList();
            if (byZh.size() == 1) return new Outcome(Status.MATCHED, byZh.get(0).id());
            if (byZh.size() > 1) {
                Entry best = bestByLatinOverlap(byZh, latin);   // distinguishing token wins, not shared surname
                return best != null ? new Outcome(Status.MATCHED, best.id()) : new Outcome(Status.AMBIGUOUS, null);
            }
            // no Chinese-name candidate — fall through to Latin
        }
        if (!latin.isEmpty()) {
            Entry best = bestByLatinOverlap(entries, latin);
            if (best != null) return new Outcome(Status.MATCHED, best.id());
            boolean any = entries.stream().anyMatch(e -> overlaps(e.latinTokens(), latin));
            return new Outcome(any ? Status.AMBIGUOUS : Status.UNMATCHED, null);
        }
        return new Outcome(Status.UNMATCHED, null);
    }

    /** Concatenate all Han (CJK) characters of a name — the Chinese-name anchor (order-independent). */
    static String chinesePart(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) sb.append(c);
        }
        return sb.toString();
    }

    /** Lowercased Latin word tokens (English/Indonesian name parts), for disambiguation. */
    static Set<String> latinTokens(String name) {
        Set<String> tokens = new HashSet<>();
        if (name == null) return tokens;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= name.length(); i++) {
            char c = i < name.length() ? name.charAt(i) : ' ';
            boolean latinLetter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (latinLetter) {
                cur.append(Character.toLowerCase(c));
            } else if (cur.length() > 0) {
                if (cur.length() >= 2) tokens.add(cur.toString());   // skip 1-letter initials
                cur.setLength(0);
            }
        }
        return tokens;
    }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return !Collections.disjoint(a, b);
    }

    /**
     * Among candidates, pick the one whose Latin tokens overlap the query the most — so a distinguishing
     * token (e.g. "kevin") wins over a shared surname (e.g. "zhang"). Returns null on a tie or no overlap.
     */
    private static Entry bestByLatinOverlap(List<Entry> candidates, Set<String> latin) {
        if (latin.isEmpty()) return null;
        int bestCount = 0;
        Entry best = null;
        boolean tie = false;
        for (Entry e : candidates) {
            int c = intersectionCount(e.latinTokens(), latin);
            if (c > bestCount) {
                bestCount = c;
                best = e;
                tie = false;
            } else if (c == bestCount && c > 0) {
                tie = true;
            }
        }
        return (best != null && !tie) ? best : null;
    }

    private static int intersectionCount(Set<String> a, Set<String> b) {
        int n = 0;
        for (String s : a) if (b.contains(s)) n++;
        return n;
    }

    /** Helper retained for potential locale-specific tweaks (unused suppression). */
    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
