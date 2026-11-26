package com.qtools.tool.web.subtitle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视频双语字幕配置（前缀 {@code qtools.media.subtitle}，docs/视频字幕-实施交接.md §T8）
 *
 * <p>与 {@code qtools.media.llm.transcribe.*} 是两套东西：转写那套服务「内容梗概」的音频素材，
 * 只取开头 + 结尾两段、120 秒预算；字幕这套要<b>整片</b>转写，40 分钟预算、单独开线程池，
 * 所以配置独立，互不影响。</p>
 *
 * @author qtools
 */
@Component
@ConfigurationProperties(prefix = "qtools.media.subtitle")
public class SubtitleProperties
{
    /** 总开关：false 时 build 直接落 status=3 + err=字幕功能未开启 */
    private boolean enabled = true;

    /** 生成线程数：默认 1（whisper 是 CPU 推理，多开只会互相抢核） */
    private int workers = 1;

    /** whisper 模型文件名（文件名 → &lt;home&gt;/data/models/whisper/；也可以是绝对路径） */
    private String whisperModel = "ggml-small.bin";

    /** 源语言：字幕是日语语音配中文译文，默认 ja（auto 在长片上偶发判错，不值得赌） */
    private String whisperLang = "ja";

    /** whisper 线程数 */
    private int threads = 6;

    /** 单条字幕的总限时（分钟）：抽音频 + 转写 + 翻译共用 */
    private int timeoutMinutes = 40;

    /** 成品目录；留空 = {@code <qtools.home>/data/subtitles} */
    private String dir = "";

    /** 翻译端点 */
    private Translate translate = new Translate();

    /** 翻译端点（DeepSeek / 任意 OpenAI 兼容 chat/completions） */
    public static class Translate
    {
        /** 只做记录用：实现上固定按 OpenAI 兼容协议发请求 */
        private String provider = "openai";

        private String baseUrl = "https://api.deepseek.com/v1/chat/completions";

        private String model = "deepseek-chat";

        /** 只从这里读，不做任何环境变量回退：密钥进仓就是事故 */
        private String apiKey = "";

        /** 每批多少行：40 行 / 批实测条数与 i 集合都对得上，再多模型开始合并句子 */
        private int batch = 40;

        /** 单次请求读超时（毫秒） */
        private int timeoutMs = 60000;

        public String getProvider()
        {
            return provider;
        }

        public void setProvider(String provider)
        {
            this.provider = provider;
        }

        public String getBaseUrl()
        {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl)
        {
            this.baseUrl = baseUrl;
        }

        public String getModel()
        {
            return model;
        }

        public void setModel(String model)
        {
            this.model = model;
        }

        public String getApiKey()
        {
            return apiKey;
        }

        public void setApiKey(String apiKey)
        {
            this.apiKey = apiKey;
        }

        public int getBatch()
        {
            return batch <= 0 ? 40 : batch;
        }

        public void setBatch(int batch)
        {
            this.batch = batch;
        }

        public int getTimeoutMs()
        {
            return timeoutMs < 1000 ? 1000 : timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs)
        {
            this.timeoutMs = timeoutMs;
        }
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public int getWorkers()
    {
        return workers <= 0 ? 1 : workers;
    }

    public void setWorkers(int workers)
    {
        this.workers = workers;
    }

    public String getWhisperModel()
    {
        return whisperModel == null || whisperModel.trim().isEmpty() ? "ggml-small.bin" : whisperModel.trim();
    }

    public void setWhisperModel(String whisperModel)
    {
        this.whisperModel = whisperModel;
    }

    public String getWhisperLang()
    {
        return whisperLang == null || whisperLang.trim().isEmpty() ? "ja" : whisperLang.trim();
    }

    public void setWhisperLang(String whisperLang)
    {
        this.whisperLang = whisperLang;
    }

    public int getThreads()
    {
        return threads <= 0 ? 6 : threads;
    }

    public void setThreads(int threads)
    {
        this.threads = threads;
    }

    public int getTimeoutMinutes()
    {
        return timeoutMinutes <= 0 ? 40 : timeoutMinutes;
    }

    public void setTimeoutMinutes(int timeoutMinutes)
    {
        this.timeoutMinutes = timeoutMinutes;
    }

    /** 单条字幕的总限时（毫秒） */
    public long getEffectiveTimeoutMs()
    {
        return getTimeoutMinutes() * 60000L;
    }

    public String getDir()
    {
        return dir;
    }

    public void setDir(String dir)
    {
        this.dir = dir;
    }

    /** 成品目录：配置优先，否则跟着 {@code qtools.home}（换机器只改 home 一处） */
    public String getEffectiveDir()
    {
        String configured = dir == null ? "" : dir.trim();
        if (!configured.isEmpty())
        {
            return configured.replace('\\', '/');
        }
        String home = System.getProperty("qtools.home", "");
        if (home == null || home.trim().isEmpty())
        {
            return "data/subtitles";
        }
        return home.replace('\\', '/').trim() + "/data/subtitles";
    }

    public Translate getTranslate()
    {
        return translate;
    }

    public void setTranslate(Translate translate)
    {
        this.translate = translate;
    }
}
