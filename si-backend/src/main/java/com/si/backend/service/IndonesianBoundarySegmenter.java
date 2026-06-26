package com.si.backend.service;

import com.si.backend.config.OpenAiProperties;
import com.si.backend.integration.LlmIntegration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * 方案2:LLM 实时分句。让快 LLM 在【句子边界】异步切句,不阻塞 ASR。
 *
 * <p>用法:ASR 拿到不断增长的印尼语文本后,调用 {@link #findAsync},本类在后台线程上请求 LLM
 * "逐字照抄已说完的整句",再用前缀匹配把 LLM 返回的完整句映射回原文的切点(字符数),
 * 通过回调把切点交还给调用方;调用方据此切出完整印尼语句走现有翻译/TTS 通路。</p>
 *
 * <p>核心映射 {@link #completedPrefixEnd} 是纯函数,便于单测:LLM 必须逐字照抄,否则前缀不匹配,
 * 返回 0(本轮不切,等下一轮或 Azure 终稿兜底),保证安全自纠。</p>
 */
@Slf4j
@Component
public class IndonesianBoundarySegmenter {

    private final OpenAiProperties openAiProperties;
    private final LlmIntegration llmIntegration;
    private final ExecutorService executor;

    public IndonesianBoundarySegmenter(OpenAiProperties openAiProperties, LlmIntegration llmIntegration) {
        this.openAiProperties = openAiProperties;
        this.llmIntegration = llmIntegration;
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "id-seg-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public boolean isEnabled() {
        return openAiProperties.isIdZhLlmSegmentEnabled();
    }

    public int getMinChars() {
        return openAiProperties.getIdZhLlmSegmentMinChars();
    }

    public int getRefireChars() {
        return openAiProperties.getIdZhLlmSegmentRefireChars();
    }

    /**
     * 异步求"已说完整句"的切点(相对 working 的字符数);不阻塞调用线程。
     * 结果通过 onBoundary 回调返回(可能在另一线程);0 表示本轮无可切的完整句。
     */
    public void findAsync(String working, IntConsumer onBoundary) {
        executor.submit(() -> {
            int end = 0;
            try {
                String completed = llmIntegration.findIndonesianSentenceBoundaryPrefix(working);
                end = completedPrefixEnd(working, completed);
                log.debug("[IdSegmenter] findAsync workingLen={}, completedLen={}, cut={}",
                        working.length(), completed == null ? 0 : completed.length(), end);
            } catch (Exception e) {
                log.warn("[IdSegmenter] findAsync error: {}", e.toString());
            }
            try {
                onBoundary.accept(end);
            } catch (Exception e) {
                log.warn("[IdSegmenter] onBoundary callback error: {}", e.toString());
            }
        });
    }

    /**
     * 把 LLM 逐字照抄返回的"完整句前缀" completed 映射回 working 里的切点(字符数)。
     *
     * <p>忽略两边空白、大小写做前缀比对;若 completed 不是 working 的前缀(LLM 改写了/幻觉)则返回 0,
     * 表示本轮不切。匹配成功时返回值会把切点后紧跟的空白一并吃掉,使切点落在词边界后。</p>
     */
    static int completedPrefixEnd(String working, String completed) {
        if (working == null || working.isEmpty() || completed == null || completed.isBlank()) {
            return 0;
        }
        int wn = working.length();
        int cn = completed.length();
        int wi = 0;
        int ci = 0;
        int lastWi = 0;            // working 中最后一个匹配字符之后的位置
        while (ci < cn) {
            while (ci < cn && Character.isWhitespace(completed.charAt(ci))) {
                ci++;
            }
            if (ci >= cn) {
                break;
            }
            while (wi < wn && Character.isWhitespace(working.charAt(wi))) {
                wi++;
            }
            if (wi >= wn) {
                return 0; // working 比 completed 短 → 不是前缀
            }
            char cc = Character.toLowerCase(completed.charAt(ci));
            char wc = Character.toLowerCase(working.charAt(wi));
            if (cc != wc) {
                return 0; // 出现不一致 → 判为非干净前缀,放弃本轮切句
            }
            wi++;
            ci++;
            lastWi = wi;
        }
        if (lastWi <= 0) {
            return 0;
        }
        // 吃掉切点后紧跟的空白,让切点落在词边界之后
        int end = lastWi;
        while (end < wn && Character.isWhitespace(working.charAt(end))) {
            end++;
        }
        return end;
    }
}
