package com.si.backend.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 把长文本切成若干块,供"会前材料 → LLM"的多块抽取(热词/术语/知识包)覆盖全文,
 * 不再因单次截断丢掉后半部分。尽量在空白处切,避免切断词;块数有上限以控成本。
 */
public final class TextChunks {

    private TextChunks() {
    }

    /**
     * @param text      原文
     * @param chunkSize 每块目标字符数
     * @param maxChunks 最多切几块(控制 LLM 调用次数/成本);超出部分丢弃
     * @return 文本块列表(空输入返回空列表)
     */
    public static List<String> split(String text, int chunkSize, int maxChunks) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String t = text.strip();
        if (t.isEmpty() || chunkSize <= 0 || maxChunks <= 0) {
            return chunks;
        }
        int n = t.length();
        int pos = 0;
        while (pos < n && chunks.size() < maxChunks) {
            int end = Math.min(pos + chunkSize, n);
            if (end < n) {
                // 尽量回退到最近的空白处切,避免切断词
                int back = end;
                int floor = pos + chunkSize / 2;   // 不要为找空白回退过多
                while (back > floor && !Character.isWhitespace(t.charAt(back - 1))) {
                    back--;
                }
                if (back > floor) {
                    end = back;
                }
            }
            String piece = t.substring(pos, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            pos = end;
        }
        return chunks;
    }
}
