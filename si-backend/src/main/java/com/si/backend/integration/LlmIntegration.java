package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.config.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.si.backend.dto.PreMeetingChatRequest.ChatTurn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI LLM integration for real-time Indonesian compression and meeting summaries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmIntegration {

    private static final long TERMINOLOGY_EXTRACTION_MAX_OUTPUT_TOKENS = 2500L;
    private static final long MEETING_KNOWLEDGE_PACK_MAX_OUTPUT_TOKENS = 1500L;
    private static final long HOTWORD_EXTRACTION_MAX_OUTPUT_TOKENS = 1500L;
    private static final ChatRequestOptions DEFAULT_CHAT_OPTIONS = new ChatRequestOptions(false);
    private static final ChatRequestOptions NO_REASONING_CHAT_OPTIONS = new ChatRequestOptions(true);

    private static final String INDONESIAN_COMPRESSION_PROMPT_TEMPLATE =
            "You are a simultaneous interpretation compression model.\n\n"
            + "Task: Compress the already-translated Indonesian text into a concise real-time interpretation version.\n\n"
            + "[Input Rules]\n"
            + "- The input text is already in Indonesian. Do NOT translate again.\n"
            + "- Preserve all proper nouns unchanged.\n\n"
            + "[Strict Constraints]\n"
            + "1. Do NOT rephrase or rewrite the original meaning.\n"
            + "2. Do NOT add any information not present in the input.\n"
            + "3. Do NOT add explanations, summaries, or conclusions.\n"
            + "4. Do NOT add emotional or dramatic expressions.\n"
            + "5. Delete podcast promotion, sponsorship, subscription, rating, and call-to-action text.\n"
            + "6. Do not change tone or expression style.\n\n"
            + "[Compression Rules]\n"
            + "- Only delete fillers, repetition, weak modifiers, and non-essential promotional wording.\n"
            + "- Preserve all facts, actions, entities, numbers, and results.\n"
            + "- Keep the original order.\n"
            + "- Target length: keep about %s of the original length when possible.\n\n"
            + "Output only the compressed text, no explanation.";

    private static final String ENGLISH_COMPRESSION_PROMPT_TEMPLATE =
            "You are a simultaneous interpretation compression model.\n\n"
            + "Task: Compress the already-translated English text into a concise real-time interpretation version.\n\n"
            + "[Input Rules]\n"
            + "- The input text is already in English. Do NOT translate again.\n"
            + "- Preserve all proper nouns unchanged.\n\n"
            + "[Strict Constraints]\n"
            + "1. Do NOT rephrase or rewrite the original meaning.\n"
            + "2. Do NOT add any information not present in the input.\n"
            + "3. Do NOT add explanations, summaries, or conclusions.\n"
            + "4. Keep factual details, entities, numbers, and results intact.\n"
            + "5. Do not switch languages.\n\n"
            + "[Compression Rules]\n"
            + "- Only delete fillers, repetition, weak modifiers, and redundant wording.\n"
            + "- Preserve sentence order and the speaker's intent.\n"
            + "- Target length: keep about %s of the original length when possible.\n\n"
            + "Output only the compressed English text, no explanation.";

    /**
     * 印尼语→中文 ASR 后处理 + 翻译。先按棕榈种植园施肥/缺素/叶片分析语境纠正 ASR 错词与断句，
     * 再翻成自然中文。只输出当前句的中文，供 TTS 直接朗读，不带任何标注。
     */
    private static final String ID_ZH_CORRECT_TRANSLATE_SYSTEM_PROMPT =
            "你是印尼语到中文的农业会议同声传译助手。输入来自语音识别(ASR)，可能有识别错误、"
            + "断句错误、口语重复和专业术语误识别。不要逐词硬翻：先结合上下文和下面的业务背景把印尼语纠正成"
            + "合理文本，再翻成自然、通顺的中文。\n\n"
            + "[业务背景] 印尼棕榈种植园的叶片分析、施肥建议、缺素分析会议。\n"
            + "[常见术语]\n"
            + "- defisiensi/defisitensi = 缺素/养分缺乏\n"
            + "- K/Kalium = 钾；P/Fosfor = 磷；N = 氮；Zn/unsur Zn = 锌元素；Cu = 铜；Ca = 钙；Mg = 镁；B/boron = 硼\n"
            + "- pupuk = 肥料/施肥；rekomendasi pupuk = 施肥建议；pemupukan = 施肥；dosis = 用量；MOP = 氯化钾肥\n"
            + "- analisa daun = 叶片分析；LSU = 叶片分析；blok = 区块/地块；kebun = 园区/种植园；pokok = 株/棵\n"
            + "- kg per pokok = 每株公斤数；curah hujan = 降雨量；lahan gambut = 泥炭地；agronomi = 农艺\n"
            + "- kondisi lapangan = 现场/田间情况；waterlogging = 渍水；topografi = 地形\n\n"
            + "[专有名词(固定译法)]\n"
            + "- Starship/starship = Starship；Starlink(常被识别为 starling) = 星链；satelit = 卫星\n"
            + "- Julong(常被识别为 julong/culong) = 聚龙(公司名,绝不音译成“朱龙”)\n"
            + "- pacar / pacarmen / pacar men / pak carmen / pak jaren / pak cermin = 董事长"
            + "(这些都是 ASR 误识,实为 “Pak Chairman/董事长”,统一按“董事长”翻,如“董事长强调……”;绝不要音译成人名)\n\n"
            + "[纠错规则]\n"
            + "1. ASR 把 K 听成 kang、pupuk 听成 kupu/pukul、blok 听成 blog/peluk、boron 听成 buron、"
            + "pH 听成 PHK、efisiensi 听成 episensi、konteks 听成 kontes、tonase 听成 tonas、LSU 听成 RSU、"
            + "unsur Zn 听成 unsumsi zin 等，请按上下文纠正回正确术语。\n"
            + "2. dari = “从…来看/的”，绝不是“发件人”。\n"
            + "3. 文本不完整时按上下文译成最可能的意思，但绝不添加原文没有的信息。\n"
            + "   【数字铁律·最高优先】数字只照搬原文中明确、完整出现的数字，逐位原样保留："
            + "绝不补全、绝不推算量级(如把 2670 改成 26670)、绝不改动小数位(如把 11,3 写成 11.03)、"
            + "绝不把被空格拆断的数字(如“2 5 2 6”)拼成你猜的数值；唯有明显的年份(2 6 2 7→2026/2027)可还原。"
            + "原文数字残缺/被截断/听不清时，用模糊词(“约”“数千吨”)或直接省略该数字，绝不编造一个具体数。"
            + "单位、元素符号按原文保留。\n"
            + "4. 遇到人名/地名/听不懂的词：直接音译或原样保留该词，不要解释、不要拒绝；"
            + "同一专有名词全场保持同一个中文译名，不要时而音译时而改写。\n"
            + "   即使整句是乱码、断词、英文残片或疑似人名(如“gpt lee wensong”)、看不出含义，"
            + "也只把它音译或原样保留地写出来，绝不分析它是什么、绝不援引会议背景去“脑补”它的意思、"
            + "绝不输出任何推测或说明，宁可原样照搬也不要解释。\n"
            + "5. 只翻译【当前句】，上文仅用于消歧，不要翻译或复述上文。\n\n"
            + "[输出译文前自检·同一次完成] 给出译文前，在内部对照【当前句原文】与【上文】快速复核一遍，发现不符就改正后再输出：①数字逐位照搬原文，未补全、未改量级、未改小数位；②强制术语、专有名词、人名/称谓采用对照表的固定译法，且与上文已出现的译法保持一致；③没有添加原文没有的信息、没有漏译事实；④中文通顺、指代清楚、符合上文语境。复核只在内部进行：绝不输出检查过程、对比、理由、是否正确之类的说明，只输出改正后的最终译文。\n\n"
            + "[输出铁律] 无论输入多碎、多难，只输出当前句的中文译文本身。"
            + "严禁输出任何解释、说明、备注、括号注释、铺垫、反问或对话"
            + "(例如“无法判断”“无法确定”“疑似识别错误”“按您的要求”“建议补充”“我注意到”“说明：”“根据本场会议背景”“根据上下文”“这句可能是指”等)，"
            + "严禁提及上文、语境、音频或翻译过程，严禁对输入做任何分析或推测说明。"
            + "只给译文，不要任何前后缀；输入再碎再难也只输出译文本身。";

    /** 可选的“实时口译精简”附加条款：忠实前提下轻度精简，绝不丢信息。 */
    private static final String ID_ZH_CONCISE_CLAUSE =
            "\n\n[实时口译精简] 在忠实翻译的同时做轻度口译式精简：删除口头语、语气词、明显重复与啰嗦；"
            + "但必须保留全部事实、数字、专有名词、结论与因果关系，绝不遗漏信息或改变原意。"
            + "把内容整理成自然、断句清晰、可直接朗读的中文。";

    /**
     * 会后"文档×ASR 对比挖错词"提示词：以上传文件为标准答案，找出 ASR 转写里被听错、
     * 而文件中有正确写法的"术语/人名/公司名/数字单位"。只输出 JSON 数组，供自动入错词库。
     */
    private static final String ASR_CORRECTION_MINING_SYSTEM_PROMPT =
            "你是 ASR 纠错挖掘助手。给你两份文本：①会议转写(ASR 识别，可能有听错)；②会议参考文件(标准答案)。\n"
            + "任务：找出【转写里被识别错、而文件中有对应正确写法】的词(主要是专有名词、人名、公司名、园区/项目名、"
            + "专业术语、数字单位)，输出从错误写法到正确写法的映射。\n"
            + "规则：\n"
            + "1. 只输出有文件依据的纠正；文件里找不到对应正确词的，不要输出(绝不编造)。\n"
            + "2. variant 用转写中实际出现的错误写法；canonical 用文件中的正确写法。\n"
            + "3. 普通虚词、常见词、正确的词不要输出。\n"
            + "4. confidence 取 0~1，依据是错误词与正确词的相似度及上下文吻合度。\n"
            + "只输出 JSON 数组，每个元素恰好三个字段：variant(string)、canonical(string)、confidence(number)。"
            + "不要输出任何解释或 markdown 代码围栏。";

    private static final String MEETING_SUMMARY_SYSTEM_PROMPT =
            "你是会议总结助手。请根据用户要求和会议记录生成会议总结。\n"
            + "如果用户没有提供额外要求，输出简洁、准确的中文总结。\n"
            + "保留专有名词、数字和关键事实，不要编造，不要强制固定章节结构。";

    private static final String HOTWORD_EXTRACTION_SYSTEM_PROMPT =
            "You are an NLP assistant. Extract named entities and domain-specific terms from the meeting transcript that would benefit ASR speech recognition accuracy.\n"
            + "Return a JSON array where each element has exactly three string fields:\n"
            + "  phrase: the exact term as it appears in the text\n"
            + "  category: one of 人名 / 地名 / 组织名 / 专业术语\n"
            + "  language: one of zh-CN / id-ID / en-US (the language the term naturally belongs to)\n"
            + "Rules: maximum 100 items; skip common words and stop words; proper nouns and domain terms only.\n"
            + "Output ONLY a valid JSON array with no explanation or markdown fences.";

    /** 从中↔印(或含英)双语会议材料里抽取对齐的专业术语/专有名词对,供自动入术语表(强制级)。 */
    private static final String TERMINOLOGY_PAIR_EXTRACTION_SYSTEM_PROMPT =
            "你是术语抽取助手。输入是一份会议材料,中文与印尼语(可能含英文)穿插出现。"
            + "请只抽取其中【在文中确实互为译文】的术语对——人名/公司/机构/园区/项目/部门、专业术语与缩写。\n"
            + "返回 JSON 数组,每个元素恰好这些字段(字符串):\n"
            + "  zh: 中文规范写法\n"
            + "  id: 对应的印尼语写法\n"
            + "  en: 对应的英文写法(没有就空字符串)\n"
            + "  category: 人名 / 地名 / 组织名 / 专业术语 之一\n"
            + "【宁缺毋滥】质量远比数量重要,拿不准就不要收。严格规则:\n"
            + "1. 只在【zh 与 id 两侧都在文中明确作为彼此译文出现】时才输出一条;两者必须都非空。\n"
            + "   只在一侧出现的词【不要】臆造另一侧——尤其人名:看不到对应中文名就【不要】编一个音近的中文名"
            + "(如把 Joshua 写成“乔布斯”、把 Rudi 写成别的名字),这类一律丢弃。\n"
            + "2. 行业借词/专名【保持原文,绝不按字面直译】:如 Plasma(指合作种植/小农户,不是“等离子体”)、"
            + "Estate、Region、Kebun、Blok 等;拿不准词义就不要收,绝不照字面翻。\n"
            + "3. 缩写只在文中能明确对应到全称/含义时才收;对应不确定(如 LSU/SSU 等)就【不要】猜一个意思。\n"
            + "4. 不要收:通用量纲/单位/符号/数字/货币(%、ppm、Ha、kg、ton、Rp 等)、常见词、停用词、整句。\n"
            + "5. 最多 80 条。只输出 JSON 数组,不要解释、不要 markdown 围栏。";

    /** 抽取双语材料中的术语对(中=印[=英]),返回 JSON 数组字符串。失败返回 "[]"。 */
    public String extractTerminologyPairsJson(String text) throws IOException {
        if (text == null || text.isBlank()) {
            return "[]";
        }
        log.info("[LlmIntegration] extractTerminologyPairs start, model={}, textLen={}",
                openAiProperties.effectiveExtractionModel(), text.length());
        String result = createTextResponse(
                openAiProperties.effectiveExtractionModel(),
                TERMINOLOGY_PAIR_EXTRACTION_SYSTEM_PROMPT,
                text,
                TERMINOLOGY_EXTRACTION_MAX_OUTPUT_TOKENS,
                extractionChatOptions()
        );
        log.info("[LlmIntegration] extractTerminologyPairs end, resultLen={}", result.length());
        return result;
    }

    /** 术语对【校验】系统提示词:逐条判断是否为该材料语境下的正确互译,只保留确认正确的,错的剔除。 */
    private static final String TERMINOLOGY_PAIR_VERIFY_SYSTEM_PROMPT =
            "你是术语校对员。下面给你【原材料片段】和一组【候选术语对】(从该材料抽出的 zh↔id 互译)。"
            + "请逐条核对每一对是否为【该材料语境下确实正确的互译】,只保留你确认正确的,其余一律剔除。\n"
            + "必须剔除的情形:\n"
            + "1. zh 与 id 含义不一致 / 不是互译(如 等离子体↔Plasma:这里 Plasma 指合作种植/小农户,非物理等离子体)。\n"
            + "2. 把行业借词/专名按字面错译(Estate、Region、Kebun、Plasma 等应保留原文含义)。\n"
            + "3. 给只在一种语言出现的人名臆造的另一种语言名(如 Joshua↔乔布斯)。\n"
            + "4. 缩写对应的含义是猜的、材料里无依据(如 LSU↔土壤样品 实为叶片取样)。\n"
            + "5. 通用单位/符号/数字/常见词。\n"
            + "判断不确定的也剔除(宁缺毋滥)。\n"
            + "只输出保留下来的术语对 JSON 数组,字段与输入相同(zh,id,en,category),"
            + "不要修改保留项的写法,不要解释,不要 markdown 围栏。";

    /**
     * 让 LLM 对已抽取的候选术语对逐条【校验】,只返回确认为正确互译的那些(JSON 数组)。
     * 抽取是"生成"任务易错,校验是"判断"任务更准——用它拦掉正则拦不掉的语义错。失败时返回原候选(不误删)。
     */
    public String verifyTerminologyPairsJson(String candidatesJson, String sourceText) throws IOException {
        if (candidatesJson == null || candidatesJson.isBlank() || "[]".equals(candidatesJson.trim())) {
            return "[]";
        }
        String user = "【原材料片段】\n"
                + (sourceText == null ? "" : sourceText.trim())
                + "\n\n【候选术语对】\n" + candidatesJson.trim();
        log.info("[LlmIntegration] verifyTerminologyPairs start, model={}, candLen={}, srcLen={}",
                openAiProperties.effectiveExtractionModel(), candidatesJson.length(),
                sourceText != null ? sourceText.length() : 0);
        String result = createTextResponse(
                openAiProperties.effectiveExtractionModel(),
                TERMINOLOGY_PAIR_VERIFY_SYSTEM_PROMPT,
                user,
                TERMINOLOGY_EXTRACTION_MAX_OUTPUT_TOKENS,
                extractionChatOptions()
        );
        log.info("[LlmIntegration] verifyTerminologyPairs end, resultLen={}", result.length());
        return result;
    }

    private static final String DOCUMENT_SUMMARY_SYSTEM_PROMPT =
            "你是文件总结助手。请根据用户要求和文件内容生成总结。\n"
            + "如果用户没有提供额外要求，输出简洁、准确的中文总结。\n"
            + "保留专有名词、数字和关键事实，不要编造，不要强制固定章节结构。";

    private static final String CHAT_SYSTEM_PROMPT =
            "你是专业的会议智能助手，基于提供的参考资料（会议文件 / 同传记录 / 摘要 / 行动项等）回答问题。\n"
            + "要求：\n"
            + "1. 只用参考资料作答；资料里没有就明确说【资料中未提及】，绝不编造或用常识填充。\n"
            + "2. 不要只复述片段：在资料范围内做归纳、对比、按时间或主题整理，给出有条理、直接回答问题的内容。\n"
            + "3. 信息有多条时分点作答；给出数字/结论时标注来源（会议名·日期·发言人，如有）。\n"
            + "4. 用与用户提问相同的语言回答（中文问中文答、印尼语问印尼语答、英文问英文答）。\n"
            + "5. 专有名词、数字与数据按原文保留。";

    private static final String CROSS_MEETING_SYSTEM_PROMPT =
            "你是会议记录智能检索助手。根据提供的资料（同传记录、会议总结、发言摘要、文件摘要、行动项、成本数据等）回答问题。\n"
            + "规则：\n"
            + "1. 只引用资料中明确记载的内容，不要推断或编造；资料确无相关信息就说【资料中未记录】，不要用自己的知识填充。\n"
            + "2. 不要只罗列片段：先归纳综合，再分点、有条理地直接回答问题。\n"
            + "3. 引用内容注明来源（来源：会议名称·日期·发言人，如有）。\n"
            + "4. 多场会议都相关时，按会议逐一列出并对比；涉及趋势/变化时按时间线整理。\n"
            + "5. 需要资料外的背景/术语补充时，在该部分前加【AI补充】与资料内容区分。\n"
            + "6. 保留专有名词、数字与数据；用与用户提问相同的语言回答。";

    private static final String MATERIAL_SUMMARY_SYSTEM_PROMPT =
            "你是会议总结助手。请结合会议安排、报告、高管名单和实时会议记录生成会议总结。\n"
            + "如果用户没有提供额外要求，输出简洁、准确的中文总结。\n"
            + "保留姓名、数字、项目名、地区和双语术语；不要编造材料或记录中没有的事实；不要强制固定章节结构。";

    private static final HttpClient STREAM_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 通用 LLM 文本请求的默认超时（聊天、文档总结等）。 */
    private static final Duration DEFAULT_LLM_TIMEOUT = Duration.ofSeconds(120);
    /**
     * 发言摘要请求的超时上限。发言摘要在后台异步线程池执行，DeepSeek 延迟不稳定（实测 13~66s）。
     * 设为 90s：高于实测最大值留足余量，又能在请求真正卡死时及时释放执行线程、走重试，
     * 避免单个卡死请求长期占用 SUMMARY_EXECUTOR 线程导致后续摘要排队。
     */
    private static final Duration SPEAKER_SUMMARY_TIMEOUT = Duration.ofSeconds(90);

    private final OpenAiProperties openAiProperties;

    /**
     * Compresses Indonesian translated text through OpenAI chat/completions API.
     *
     * @param text Indonesian text to compress
     * @return compressed Indonesian text
     * @throws IOException when OpenAI does not return usable text
     */
    public String compressIndonesian(String text) throws IOException {
        return compress(text, "zh->id", buildPrompt(
                INDONESIAN_COMPRESSION_PROMPT_TEMPLATE,
                openAiProperties.getCompressionZhToIdTargetRatio()
        ));
    }

    public String compressEnglish(String text) throws IOException {
        return compress(text, "zh->en", buildPrompt(
                ENGLISH_COMPRESSION_PROMPT_TEMPLATE,
                openAiProperties.getCompressionZhToEnTargetRatio()
        ));
    }

    private String compress(String text, String direction, String prompt) throws IOException {
        log.info("[LlmIntegration] compress start, direction={}, model={}, textLen={}",
                direction, openAiProperties.getCompressionModel(), text != null ? text.length() : 0);
        String result = createTextResponse(
                openAiProperties.getCompressionModel(),
                prompt,
                text,
                openAiProperties.getCompressionMaxOutputTokens()
        );
        log.info("[LlmIntegration] compress end, direction={}, originalLen={}, compressedLen={}",
                direction, text != null ? text.length() : 0, result.length());
        return result;
    }

    private String buildPrompt(String template, double targetRatio) {
        return String.format(template, String.format("%.0f%%", targetRatio * 100));
    }

    /**
     * 印尼语→中文 ASR 纠错翻译。结合滑动上下文与（可选）本句命中的术语表，先纠错再翻成中文。
     *
     * @param currentText    当前句印尼语 ASR 原文（待翻译）
     * @param recentContext  最近若干句印尼语原文（仅用于消歧，不翻译），可为空
     * @param dynamicGlossary 本句命中的术语对照（每行“印尼语 = 中文”），可为空
     * @return 当前句的中文译文
     * @throws IOException LLM 不可用 / 超时
     */
    public String correctAndTranslateIndonesianToChinese(
            String currentText,
            String recentContext,
            String dynamicGlossary,
            String knowledgePack
    ) throws IOException {
        if (currentText == null || currentText.isBlank()) {
            return "";
        }
        String model = openAiProperties.getIdZhLlmTranslateModel();
        StringBuilder userMessage = new StringBuilder();
        if (knowledgePack != null && !knowledgePack.isBlank()) {
            userMessage.append("[本场会议背景知识(来自会议文件;仅用于纠正听错的人名/术语/数字,不得据此增添内容)]\n")
                    .append(knowledgePack.trim()).append("\n\n");
        }
        if (recentContext != null && !recentContext.isBlank()) {
            userMessage.append("[上文(仅供消歧,不要翻译)]\n").append(recentContext.trim()).append("\n\n");
        }
        if (dynamicGlossary != null && !dynamicGlossary.isBlank()) {
            // 术语段(含"必须遵守"精确命中 + "参考"模糊命中)由调用方格式化好,这里原样插入
            userMessage.append(dynamicGlossary.trim()).append("\n\n");
        }
        userMessage.append("[当前句(请纠错后翻成中文)]\n").append(currentText.trim());

        boolean concise = openAiProperties.isIdZhLlmTranslateConcise();
        String systemPrompt = concise
                ? ID_ZH_CORRECT_TRANSLATE_SYSTEM_PROMPT + ID_ZH_CONCISE_CLAUSE
                : ID_ZH_CORRECT_TRANSLATE_SYSTEM_PROMPT;
        long start = System.currentTimeMillis();
        log.info("[LlmIntegration] idZhCorrectTranslate start, model={}, curLen={}, ctxLen={}, glossaryLines={}, packLen={}, concise={}",
                model, currentText.length(), recentContext != null ? recentContext.length() : 0,
                dynamicGlossary != null && !dynamicGlossary.isBlank() ? dynamicGlossary.split("\n").length : 0,
                knowledgePack != null ? knowledgePack.length() : 0, concise);
        String result = createTextResponse(
                model,
                systemPrompt,
                userMessage.toString(),
                openAiProperties.getIdZhLlmTranslateMaxOutputTokens(),
                Duration.ofMillis(openAiProperties.getIdZhLlmTranslateTimeoutMs()),
                realtimeChatOptions()
        );
        String cleaned = sanitizeIdZhTranslation(result);
        if (looksLikeMetaCommentary(cleaned)) {
            // LLM 吐出了解释/拒绝而非译文,判为无效 → 返回空,由上层回退普通翻译,绝不让元话语进入译文/TTS
            log.warn("[LlmIntegration] idZhCorrectTranslate meta-commentary detected, discard & fallback, costMs={}, raw='{}'",
                    System.currentTimeMillis() - start,
                    result.length() <= 120 ? result : result.substring(0, 117) + "...");
            return "";
        }
        log.info("[LlmIntegration] idZhCorrectTranslate end, costMs={}, outputLen={}",
                System.currentTimeMillis() - start, cleaned.length());
        // 调试用:把"源句 → 中文译文"成对打到日志,便于离线分析分段与翻译质量(截断防日志膨胀)
        log.info("[LlmIntegration] idZhCorrectTranslate io, src='{}' -> out='{}'",
                preview(currentText, 240), preview(cleaned, 240));
        return cleaned;
    }

    /** 日志预览:截断到 max 字符,去掉换行,避免刷屏。 */
    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /** 方案2:实时分句的系统提示。让 LLM 只判断"已说完的整句"边界,逐字照抄,绝不翻译/改写。 */
    private static final String ID_ZH_SENTENCE_BOUNDARY_SYSTEM_PROMPT =
            "你是印尼语口语转写的实时断句器。输入是一段还在持续增长的 ASR 文本(可能在中途被截断)。"
            + "你的唯一任务:找出其中【已经说完的完整句子】,把它们【逐字原样照抄】输出(保留原标点与空格)。\n"
            + "严格规则:\n"
            + "1. 只输出已说完的整句;最后一个还没说完的小句(被截断的尾巴)【一个字都不要输出】。\n"
            + "2. 【逐字照抄】输入原文,绝对不要翻译、不要改写、不要纠错、不要补全、不要加任何解释。\n"
            + "3. 如果连一个完整句子都还没说完,输出【空】(什么都不输出)。\n"
            + "4. 只输出照抄的印尼语原文本身,不要 markdown、不要引号、不要说明。";

    /**
     * 方案2:让快 LLM 在【句子边界】切句。返回输入文本里「已说完的完整句子」前缀(逐字照抄原文),
     * 尾部没说完的部分不返回;一句都没说完返回空串。调用方据此用前缀匹配算出切点。
     * 失败/超时返回空串(上层即本轮不切,等下一轮或 Azure 终稿兜底),绝不抛断同传。
     */
    public String findIndonesianSentenceBoundaryPrefix(String growingText) {
        if (growingText == null || growingText.isBlank()) {
            return "";
        }
        try {
            String result = createTextResponse(
                    openAiProperties.getIdZhLlmSegmentModel(),
                    ID_ZH_SENTENCE_BOUNDARY_SYSTEM_PROMPT,
                    growingText.trim(),
                    300L,
                    Duration.ofMillis(openAiProperties.getIdZhLlmSegmentTimeoutMs())
            );
            return result == null ? "" : result.trim();
        } catch (Exception e) {
            log.warn("[LlmIntegration] findIndonesianSentenceBoundaryPrefix failed: {}", e.toString());
            return "";
        }
    }

    /** 元话语标记:LLM 偶尔输出"解释/拒绝/说明"而非译文,命中即判无效(回退普通翻译)。 */
    private static final String[] ID_ZH_META_MARKERS = {
            "无法判断", "无法确定", "疑似识别错误", "按您的要求", "建议补充", "重新听取",
            "我注意到", "逻辑不完整", "原文结构", "说明：", "说明:", "【说明】", "**说明**",
            "这句话在输入中", "根据上文", "根据上下文", "根据本场会议背景", "可能的原句",
            "可能是指", "无法对应", "咨询词汇", "该词无法确定含义", "翻译过程", "音频"
    };

    /** 去掉译文里残留的(疑似…)括号注释与首尾空白。 */
    static String sanitizeIdZhTranslation(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[（(][^（()）]*疑似[^（()）]*[)）]", "").trim();
    }

    /** 判断输出是否是"解释/拒绝"等元话语,而非干净译文。 */
    static boolean looksLikeMetaCommentary(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        for (String marker : ID_ZH_META_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generates Chinese meeting summary through OpenAI Responses API.
     *
     * @param text meeting transcript text
     * @return meeting summary
     * @throws IOException when OpenAI does not return usable text
     */
    /**
     * 会后用强模型对比"会议转写 × 参考文件"，挖掘 ASR 错词，返回 JSON 数组字符串
     * (每元素 {variant, canonical, confidence})。供错词库自动入库。
     */
    public String mineAsrCorrectionsJson(String transcript, String documentText) throws IOException {
        if (transcript == null || transcript.isBlank() || documentText == null || documentText.isBlank()) {
            return "[]";
        }
        String t = transcript.length() > 12000 ? transcript.substring(0, 12000) : transcript;
        String d = documentText.length() > 12000 ? documentText.substring(0, 12000) : documentText;
        String input = "①会议转写(ASR):\n" + t + "\n\n②会议参考文件(标准答案):\n" + d;
        log.info("[LlmIntegration] mineAsrCorrections start, model={}, transcriptLen={}, docLen={}",
                openAiProperties.getSummaryModel(), t.length(), d.length());
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                ASR_CORRECTION_MINING_SYSTEM_PROMPT,
                input,
                1200L
        );
        log.info("[LlmIntegration] mineAsrCorrections end, resultLen={}", result.length());
        return result;
    }

    private static final String MEETING_KNOWLEDGE_PACK_SYSTEM_PROMPT =
            "你是会议同传的资料整理助手。从会议文件中提取用于「实时纠错翻译」的紧凑背景知识,帮助把"
            + "ASR 听错的专有名词纠正回来。只提取、不展开,输出紧凑文本(尽量 ≤20 行):\n"
            + "1. 人名/公司/园区/项目/部门:中文规范写法(后附印尼或英文原名,如 聚龙(Julong)、董事长(Pak Chairman))\n"
            + "2. 关键专业术语与缩写:原文 = 中文(如 LSU = 叶片分析、Starlink = 星链)\n"
            + "3. 重要数字/单位/目标(如 70000 公顷、8 年战略)\n"
            + "4. 末尾一行用「主题:」给出本次会议一句话主题。\n"
            + "只输出上述内容,不要解释、不要 markdown 代码围栏。";

    /**
     * 把会议文件文本蒸馏成紧凑"知识包"(人名/术语/数字/主题),供实时 id→zh 纠错翻译作接地上下文。
     * 用强模型,会前上传时调用一次(非实时)。
     */
    public String extractMeetingKnowledgePack(String fileText) throws IOException {
        if (fileText == null || fileText.isBlank()) {
            return "";
        }
        // 覆盖全文:分块蒸馏(每块 ≤12000 字,最多 6 块),逐块合并、按行去重,避免单次截断丢掉后半部分。
        List<String> chunks = com.si.backend.util.TextChunks.split(fileText, 12000, 6);
        log.info("[LlmIntegration] extractMeetingKnowledgePack start, model={}, fileLen={}, chunks={}",
                openAiProperties.effectiveExtractionModel(), fileText.length(), chunks.size());
        java.util.LinkedHashSet<String> mergedLines = new java.util.LinkedHashSet<>();
        int failedChunks = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String part;
            try {
                part = extractMeetingKnowledgeChunk(chunks.get(i));
            } catch (Exception e) {
                failedChunks++;
                log.warn("[LlmIntegration] extractMeetingKnowledgePack chunk failed, index={}, chunks={}, reason={}",
                        i + 1, chunks.size(), e.getMessage());
                continue;
            }
            for (String line : part.split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    mergedLines.add(trimmed);
                }
            }
        }
        String result = String.join("\n", mergedLines);
        log.info("[LlmIntegration] extractMeetingKnowledgePack end, chunks={}, failedChunks={}, mergedLines={}, resultLen={}",
                chunks.size(), failedChunks, mergedLines.size(), result.length());
        return result;
    }

    String extractMeetingKnowledgeChunk(String chunk) throws IOException {
        return createTextResponse(
                openAiProperties.effectiveExtractionModel(),
                MEETING_KNOWLEDGE_PACK_SYSTEM_PROMPT,
                chunk,
                MEETING_KNOWLEDGE_PACK_MAX_OUTPUT_TOKENS,
                extractionChatOptions()
        );
    }

    public String extractHotwordsJson(String text) throws IOException {
        log.info("[LlmIntegration] extractHotwordsJson start, model={}, textLen={}",
                openAiProperties.effectiveExtractionModel(), text != null ? text.length() : 0);
        String result = createTextResponse(
                openAiProperties.effectiveExtractionModel(),
                HOTWORD_EXTRACTION_SYSTEM_PROMPT,
                text,
                HOTWORD_EXTRACTION_MAX_OUTPUT_TOKENS,
                extractionChatOptions()
        );
        log.info("[LlmIntegration] extractHotwordsJson end, resultLen={}", result.length());
        return result;
    }

    public String summarizeDocument(String text, String requirements) throws IOException {
        log.info("[LlmIntegration] summarizeDocument start, model={}, textLen={}, hasRequirements={}",
                openAiProperties.getDocumentSummaryModel(), text != null ? text.length() : 0,
                requirements != null && !requirements.isBlank());
        String systemPrompt = (requirements != null && !requirements.isBlank())
                ? DOCUMENT_SUMMARY_SYSTEM_PROMPT + "\n\n[额外要求]\n" + requirements.trim()
                : DOCUMENT_SUMMARY_SYSTEM_PROMPT;
        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                systemPrompt,
                text,
                openAiProperties.getDocumentSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeDocument end, textLen={}, resultLen={}",
                text != null ? text.length() : 0, result.length());
        return result;
    }

    public String summarizeMeeting(String text) throws IOException {
        return summarizeMeeting(text, null);
    }

    public String summarizeMeeting(String text, String customRequirements) throws IOException {
        log.info("[LlmIntegration] summarizeMeeting start, model={}, textLen={}, hasCustomRequirements={}",
                openAiProperties.getSummaryModel(), text != null ? text.length() : 0,
                customRequirements != null && !customRequirements.isBlank());
        String systemPrompt = (customRequirements != null && !customRequirements.isBlank())
                ? MEETING_SUMMARY_SYSTEM_PROMPT + "\n\n[用户额外要求]\n" + customRequirements.trim()
                : MEETING_SUMMARY_SYSTEM_PROMPT;
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                systemPrompt,
                text,
                openAiProperties.getSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeMeeting end, textLen={}, resultLen={}",
                text != null ? text.length() : 0, result.length());
        return result;
    }

    public String summarizeMeetingRetry(String text) throws IOException {
        String systemPrompt = "你是会议总结助手。请根据会议记录生成中文总结。\n"
                + "保留专有名词、数字和关键事实，不要编造，不要强制固定章节结构。";
        String userMessage = "【商务会议同声传译记录】\n\n" + text;
        log.info("[LlmIntegration] summarizeMeetingRetry start, textLen={}", text != null ? text.length() : 0);
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                systemPrompt,
                userMessage,
                openAiProperties.getSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeMeetingRetry end, resultLen={}", result.length());
        return result;
    }

    public String summarizeMeetingWithMaterials(
            String transcript,
            String agendaText,
            String reportText,
            String executiveNames
    ) throws IOException {
        String input = "[会议安排]\n" + nullToBlank(agendaText)
                + "\n\n[会议报告]\n" + nullToBlank(reportText)
                + "\n\n[高管/重点发言人名单]\n" + nullToBlank(executiveNames)
                + "\n\n[会议实时文本记录]\n" + nullToBlank(transcript);
        log.info("[LlmIntegration] summarizeMeetingWithMaterials start, model={}, inputLen={}",
                openAiProperties.getSummaryModel(), input.length());
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                MATERIAL_SUMMARY_SYSTEM_PROMPT,
                input,
                openAiProperties.getSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeMeetingWithMaterials end, inputLen={}, resultLen={}",
                input.length(), result.length());
        return result;
    }

    public String summarizeSpeakerSegment(String speakerName, String text) throws IOException {
        return summarizeSpeakerSegment(speakerName, text, null);
    }

    public String summarizeSpeakerSegment(String speakerName, String text, String requirements) throws IOException {
        String systemPrompt = "你是发言摘要助手。请根据用户要求为这段发言生成摘要。\n"
                + "如果用户没有提供额外要求，输出简洁、准确的中文摘要。\n"
                + "保留专有名词、数字、决议和行动项，不要编造，不要强制固定标题或章节结构。"
                + (requirements != null && !requirements.isBlank() ? "\n额外要求：" + requirements : "");
        String userMessage = "发言人：" + speakerName + "\n\n内容：\n" + text;
        log.info("[LlmIntegration] summarizeSpeakerSegment start, speaker={}, textLen={}, hasReq={}", speakerName, text.length(), requirements != null && !requirements.isBlank());
        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                systemPrompt,
                userMessage,
                1200L,
                SPEAKER_SUMMARY_TIMEOUT
        );
        log.info("[LlmIntegration] summarizeSpeakerSegment end, resultLen={}", result.length());
        return result;
    }

    public String summarizeSpeakerSegmentRetry(String speakerName, String text) throws IOException {
        String systemPrompt = "你是发言摘要助手。请根据发言记录生成中文摘要。\n"
                + "保留专有名词、数字、决议和行动项，不要编造，不要强制固定标题或章节结构。";
        String userMessage = "【会议发言人】" + speakerName + "\n\n【发言记录】\n" + text;
        log.info("[LlmIntegration] summarizeSpeakerSegmentRetry start, speaker={}, textLen={}", speakerName, text.length());
        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                systemPrompt,
                userMessage,
                1200L,
                SPEAKER_SUMMARY_TIMEOUT
        );
        log.info("[LlmIntegration] summarizeSpeakerSegmentRetry end, resultLen={}", result.length());
        return result;
    }

    public String chat(String context, String question, List<ChatTurn> history) throws IOException {
        log.info("[LlmIntegration] chat start, model={}, contextLen={}, historySize={}",
                openAiProperties.getDocumentSummaryModel(),
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        StringBuilder input = new StringBuilder();
        if (context != null && !context.isBlank()) {
            input.append("[参考资料]\n").append(context).append("\n\n");
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                if ("user".equals(turn.getRole())) {
                    input.append("用户：").append(turn.getContent()).append("\n");
                } else {
                    input.append("助手：").append(turn.getContent()).append("\n");
                }
            }
        }
        input.append("用户：").append(question);

        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                CHAT_SYSTEM_PROMPT,
                input.toString(),
                openAiProperties.getDocumentSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] chat end, answerLen={}", result.length());
        return result;
    }

    public String chatCrossMeeting(String context, String question, List<ChatTurn> history) throws IOException {
        log.info("[LlmIntegration] chatCrossMeeting start, model={}, contextLen={}, historySize={}",
                openAiProperties.getDocumentSummaryModel(),
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        StringBuilder input = new StringBuilder();
        if (context != null && !context.isBlank()) {
            input.append("[历史会议记录]\n").append(context).append("\n\n");
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                if ("user".equals(turn.getRole())) {
                    input.append("用户：").append(turn.getContent()).append("\n");
                } else {
                    input.append("助手：").append(turn.getContent()).append("\n");
                }
            }
        }
        input.append("用户：").append(question);

        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                CROSS_MEETING_SYSTEM_PROMPT,
                input.toString(),
                openAiProperties.getDocumentSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] chatCrossMeeting end, answerLen={}", result.length());
        return result;
    }

    public void streamChatUnified(
            String context,
            String question,
            List<ChatTurn> history,
            Consumer<String> chunkConsumer) throws IOException {
        log.info("[LlmIntegration] streamChatUnified start, model={}, contextLen={}, historySize={}",
                openAiProperties.getDocumentSummaryModel(),
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        StringBuilder inputBuilder = new StringBuilder();
        if (context != null && !context.isBlank()) {
            inputBuilder.append("[历史会议记录]\n").append(context).append("\n\n");
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                inputBuilder.append("user".equals(turn.getRole()) ? "用户：" : "助手：")
                        .append(turn.getContent()).append("\n");
            }
        }
        inputBuilder.append("用户：").append(question);

        String requestBodyJson = buildStreamRequestJson(
                openAiProperties.getDocumentSummaryModel(),
                CROSS_MEETING_SYSTEM_PROMPT,
                inputBuilder.toString(),
                openAiProperties.getDocumentSummaryMaxOutputTokens());

        String chatUrl = openAiProperties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiProperties.getApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8));

        if (openAiProperties.getReferer() != null && !openAiProperties.getReferer().isBlank()) {
            requestBuilder.header("HTTP-Referer", openAiProperties.getReferer());
        }
        if (openAiProperties.getTitle() != null && !openAiProperties.getTitle().isBlank()) {
            requestBuilder.header("X-Title", openAiProperties.getTitle());
        }

        long start = System.currentTimeMillis();
        try {
            HttpResponse<java.io.InputStream> response = STREAM_HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("[LlmIntegration] streamChatUnified failed, status={}, body={}",
                        response.statusCode(), body);
                throw new IOException("OpenAI streaming request failed: HTTP " + response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        String chunk = parseDeltaContent(data);
                        if (chunk != null && !chunk.isEmpty()) {
                            chunkConsumer.accept(chunk);
                        }
                    }
                }
            }
            log.info("[LlmIntegration] streamChatUnified complete, costMs={}", System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Streaming interrupted", e);
        }
    }

    private String buildStreamRequestJson(String model, String systemPrompt, String userMessage, long maxTokens) throws IOException {
        return buildChatRequestJson(model, systemPrompt, userMessage, maxTokens, true, DEFAULT_CHAT_OPTIONS);
    }

    String buildTextRequestJson(
            String model,
            String systemPrompt,
            String userMessage,
            long maxTokens,
            ChatRequestOptions options
    ) throws IOException {
        return buildChatRequestJson(model, systemPrompt, userMessage, maxTokens, false, options);
    }

    private String buildChatRequestJson(
            String model,
            String systemPrompt,
            String userMessage,
            long maxTokens,
            boolean stream,
            ChatRequestOptions options
    ) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", model);
        req.put("stream", stream);
        req.put("max_tokens", maxTokens);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));
        req.put("messages", messages);
        if (options != null && options.disableReasoning()) {
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("effort", "none");
            reasoning.put("exclude", true);
            req.put("reasoning", reasoning);
            req.put("include_reasoning", false);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(req);
        } catch (Exception e) {
            throw new IOException("Failed to build stream request JSON", e);
        }
    }

    private String parseDeltaContent(String jsonData) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonData);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode content = choices.get(0).path("delta").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    return content.asText();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private ChatRequestOptions extractionChatOptions() {
        return openAiProperties.isExtractionDisableReasoning() && isOpenRouterBaseUrl()
                ? NO_REASONING_CHAT_OPTIONS
                : DEFAULT_CHAT_OPTIONS;
    }

    private ChatRequestOptions realtimeChatOptions() {
        return isOpenRouterBaseUrl() ? NO_REASONING_CHAT_OPTIONS : DEFAULT_CHAT_OPTIONS;
    }

    private boolean isOpenRouterBaseUrl() {
        String baseUrl = openAiProperties.getBaseUrl();
        return baseUrl != null && baseUrl.toLowerCase().contains("openrouter.ai");
    }

    /**
     * Generic non-streaming chat completion, for RAG helpers (query expansion, reranking, etc.).
     */
    public String complete(String model, String systemPrompt, String userMessage, long maxOutputTokens) throws IOException {
        return createTextResponse(model, systemPrompt, userMessage, maxOutputTokens);
    }

    public String complete(
            String model,
            String systemPrompt,
            String userMessage,
            long maxOutputTokens,
            Duration requestTimeout) throws IOException {
        return createTextResponse(model, systemPrompt, userMessage, maxOutputTokens, requestTimeout);
    }

    private String createTextResponse(
            String model,
            String systemPrompt,
            String userMessage,
            long maxOutputTokens
    ) throws IOException {
        return createTextResponse(model, systemPrompt, userMessage, maxOutputTokens, DEFAULT_LLM_TIMEOUT, DEFAULT_CHAT_OPTIONS);
    }

    private String createTextResponse(
            String model,
            String systemPrompt,
            String userMessage,
            long maxOutputTokens,
            ChatRequestOptions options
    ) throws IOException {
        return createTextResponse(model, systemPrompt, userMessage, maxOutputTokens, DEFAULT_LLM_TIMEOUT, options);
    }

    private String createTextResponse(
            String model,
            String systemPrompt,
            String userMessage,
            long maxOutputTokens,
            Duration requestTimeout
    ) throws IOException {
        return createTextResponse(model, systemPrompt, userMessage, maxOutputTokens, requestTimeout, DEFAULT_CHAT_OPTIONS);
    }

    private String createTextResponse(
            String model,
            String systemPrompt,
            String userMessage,
            long maxOutputTokens,
            Duration requestTimeout,
            ChatRequestOptions options
    ) throws IOException {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        if (openAiProperties.getApiKey() == null || openAiProperties.getApiKey().isBlank()) {
            throw new IOException("OPENAI_API_KEY is blank");
        }
        long start = System.currentTimeMillis();
        String requestBodyJson = buildTextRequestJson(model, systemPrompt, userMessage, maxOutputTokens, options);

        String chatUrl = openAiProperties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiProperties.getApiKey())
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8));
        if (openAiProperties.getReferer() != null && !openAiProperties.getReferer().isBlank()) {
            reqBuilder.header("HTTP-Referer", openAiProperties.getReferer());
        }
        if (openAiProperties.getTitle() != null && !openAiProperties.getTitle().isBlank()) {
            reqBuilder.header("X-Title", openAiProperties.getTitle());
        }
        try {
            HttpResponse<String> response = STREAM_HTTP_CLIENT.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("[LlmIntegration] chat/completions failed, status={}, body={}",
                        response.statusCode(), response.body());
                throw new IOException(buildLlmFailureMessage(response.statusCode(), response.body()));
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode choices = root.path("choices");
            JsonNode choice = choices.isArray() && !choices.isEmpty() ? choices.get(0) : OBJECT_MAPPER.createObjectNode();
            String text = choice.path("message").path("content").asText("").trim();
            if (text.isBlank()) {
                log.warn("[LlmIntegration] chat/completions empty content, model={}, finishReason={}, bodySnippet={}",
                        model, choice.path("finish_reason").asText(""),
                        abbreviateForLog(response.body(), 1000));
                throw new IOException("LLM response content is empty");
            }
            log.info("[LlmIntegration] chat/completions done, model={}, costMs={}, outputLen={}",
                    model, System.currentTimeMillis() - start, text.length());
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM request interrupted", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LlmIntegration] chat/completions error, model={}", model, e);
            throw new IOException("LLM response unavailable: " + sanitizeErrorMessage(e.getMessage()), e);
        }
    }

    private String buildLlmFailureMessage(int statusCode, String responseBody) {
        String providerMessage = extractProviderErrorMessage(responseBody);
        if (providerMessage.isBlank()) {
            return "LLM request failed: HTTP " + statusCode;
        }
        return "LLM request failed: HTTP " + statusCode + " - " + providerMessage;
    }

    private String extractProviderErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String message = root.path("error").path("message").asText("");
            return sanitizeErrorMessage(message);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final String ACTION_ITEM_SYSTEM_PROMPT =
            "你是会议行动项提取助手。从会议记录中提取所有明确的行动项（待办事项、跟进事项、决议等）。\n"
            + "每条行动项单独一行，格式：【负责人（如有）】行动内容（截止时间（如有））\n"
            + "如果没有明确的负责人或截止时间，省略对应部分。\n"
            + "只输出行动项列表，每行一条，不要编号，不要解释，不要重复原文。\n"
            + "如果没有找到行动项，只输出：无";

    /**
     * Extracts action items from meeting transcript text.
     * Returns a newline-separated list of action items, or "无" if none found.
     */
    public String extractActionItems(String transcriptText) throws IOException {
        if (transcriptText == null || transcriptText.isBlank()) return "无";
        String input = transcriptText.length() > 12000
                ? transcriptText.substring(0, 12000) : transcriptText;
        log.info("[LlmIntegration] extractActionItems start, textLen={}", input.length());
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                ACTION_ITEM_SYSTEM_PROMPT,
                input,
                800L
        );
        log.info("[LlmIntegration] extractActionItems end, resultLen={}", result.length());
        return result;
    }

    /**
     * Calls the OpenAI Embeddings API and returns the float vector for {@code text}.
     * Returns an empty array when the API key is missing or the text is blank.
     */
    public float[] embed(String text) throws IOException {
        if (text == null || text.isBlank()) return new float[0];
        String embKey = openAiProperties.effectiveEmbeddingApiKey();
        if (embKey == null || embKey.isBlank()) {
            throw new IOException("Embedding API key is blank (openai.embedding-api-key / OPENAI_API_KEY)");
        }
        String truncated = text.length() > 8000 ? text.substring(0, 8000) : text;
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", openAiProperties.getEmbeddingModel());
        req.put("input", truncated);
        String bodyJson = OBJECT_MAPPER.writeValueAsString(req);

        String embUrl = openAiProperties.effectiveEmbeddingBaseUrl().replaceAll("/+$", "") + "/embeddings";
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(embUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + embKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        if (openAiProperties.getReferer() != null && !openAiProperties.getReferer().isBlank()) {
            rb.header("HTTP-Referer", openAiProperties.getReferer());
        }
        try {
            HttpResponse<String> resp = STREAM_HTTP_CLIENT.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IOException("Embeddings API failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            JsonNode dataArr = OBJECT_MAPPER.readTree(resp.body()).path("data").get(0).path("embedding");
            float[] vec = new float[dataArr.size()];
            for (int i = 0; i < vec.length; i++) vec[i] = (float) dataArr.get(i).asDouble();
            log.debug("[LlmIntegration] embed done, model={}, dims={}", openAiProperties.getEmbeddingModel(), vec.length);
            return vec;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding request interrupted", e);
        }
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private String abbreviateForLog(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, maxChars) + "...";
    }

    record ChatRequestOptions(boolean disableReasoning) {
    }
}
