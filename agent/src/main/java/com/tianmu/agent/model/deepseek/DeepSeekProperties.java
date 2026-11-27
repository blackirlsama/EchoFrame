package com.tianmu.agent.model.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

/**
 * Controller client settings. `protocol` selects the thinking-field contract explicitly:
 * `auto` derives it from the base host (deepseek.com -> deepseek, siliconflow.cn -> siliconflow,
 * anything else -> none), which keeps every historical configuration byte-identical. An explicit
 * value exists for evaluation wiring against a loopback double, where the host cannot carry the
 * provider identity; it is never a way to send one provider's fields to another.
 */
@ConfigurationProperties("agent.models")
public record DeepSeekProperties(@DefaultValue("https://api.deepseek.com") URI baseUrl,
        String apiKey, @DefaultValue("deepseek-flash") String preparationModel,
        @DefaultValue("deepseek-flash") String harnessModel,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("384") int preparationMaxTokens, @DefaultValue("3072") int harnessMaxTokens,
        @DefaultValue("deepseek-flash") String analysisModel,@DefaultValue("true") boolean thinkingEnabled,
        @DefaultValue("high") String thinkingEffort,@DefaultValue("low") String analysisThinkingEffort,
        @DefaultValue("16384") int analysisMaxTokens,
        @DefaultValue("auto") String protocol) {
    public DeepSeekProperties(URI baseUrl,String apiKey,String preparationModel,String harnessModel,Duration connectTimeout,int preparationMaxTokens,int harnessMaxTokens,String analysisModel,boolean thinkingEnabled){this(baseUrl,apiKey,preparationModel,harnessModel,connectTimeout,preparationMaxTokens,harnessMaxTokens,analysisModel,thinkingEnabled,"high","low",16384,"auto");}
    public DeepSeekProperties(URI baseUrl,String apiKey,String preparationModel,String harnessModel,Duration connectTimeout,int preparationMaxTokens,int harnessMaxTokens,String analysisModel){this(baseUrl,apiKey,preparationModel,harnessModel,connectTimeout,preparationMaxTokens,harnessMaxTokens,analysisModel,true);}
    public DeepSeekProperties(URI baseUrl, String apiKey, String preparationModel, String harnessModel,
            Duration connectTimeout, int preparationMaxTokens, int harnessMaxTokens) {
        this(baseUrl, apiKey, preparationModel, harnessModel, connectTimeout, preparationMaxTokens, harnessMaxTokens, "deepseek-flash");
    }
    public DeepSeekProperties(URI baseUrl,String apiKey,String preparationModel,String harnessModel,Duration connectTimeout,int preparationMaxTokens,int harnessMaxTokens,String analysisModel,boolean thinkingEnabled,String thinkingEffort,String analysisThinkingEffort,int analysisMaxTokens){
        this(baseUrl,apiKey,preparationModel,harnessModel,connectTimeout,preparationMaxTokens,harnessMaxTokens,analysisModel,thinkingEnabled,thinkingEffort,analysisThinkingEffort,analysisMaxTokens,"auto");
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DeepSeekProperties {
        if(thinkingEffort==null || analysisThinkingEffort==null || !java.util.Set.of("low","high","max").contains(thinkingEffort) || !java.util.Set.of("low","high","max").contains(analysisThinkingEffort)
                || analysisMaxTokens<8192 || analysisMaxTokens>32768)throw new IllegalArgumentException("Invalid thinking policy");
        if (protocol == null || !java.util.Set.of("auto","deepseek","siliconflow","none").contains(protocol))
            throw new IllegalArgumentException("Unsupported chat protocol selection");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("DEEPSEEK_API_KEY is required");
        boolean local = SetHolder.LOCAL.contains(baseUrl.getHost());
        if ((!"https".equals(baseUrl.getScheme()) && !(local && "http".equals(baseUrl.getScheme())))
                || baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getFragment() != null)
            throw new IllegalArgumentException("A secure model base URL is required");
        for (String model : new String[]{preparationModel, harnessModel, analysisModel})
            if (model == null || !model.matches("[a-zA-Z0-9_.:/-]{1,100}")) throw new IllegalArgumentException("Invalid model ID");
        if (connectTimeout == null || connectTimeout.toMillis() < 1 || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || preparationMaxTokens < 64 || preparationMaxTokens > 2048 || harnessMaxTokens < 128 || harnessMaxTokens > 8192)
            throw new IllegalArgumentException("Invalid model call limits");
    }
    /** Effective thinking-field contract; explicit selection wins, auto follows the documented hosts.
     *
     *  auto never guesses: only the two public hosts are classified by suffix, and anything else
     *  (a loopback double, an internal gateway, a mirror) sends neither family's field until the
     *  operator declares it with protocol=siliconflow|deepseek. A wrong-family field on a live call
     *  is a 400; guessing from a stub's host would also make a rehearsal silently differ from the
     *  wire format it is supposed to rehearse. */
    public String resolvedProtocol() {
        if (protocol != null && !protocol.isBlank() && !"auto".equals(protocol)) return protocol;
        String host = baseUrl == null ? "" : baseUrl.getHost();
        if (host == null) return "none";
        if (host.endsWith("siliconflow.cn")) return "siliconflow";
        if (host.endsWith("deepseek.com")) return "deepseek";
        return "none";
    }
    private static class SetHolder { static final java.util.Set<String> LOCAL = java.util.Set.of("localhost", "127.0.0.1", "[::1]"); }
    public String analysisProtocolRevision(){return "thinking-v2\n"+baseUrl+"\n"+analysisModel+"\n"+thinkingEnabled+"\n"+analysisThinkingEffort+"\n"+analysisMaxTokens+"\n"+resolvedProtocol();}
    @Override public String toString() { return "DeepSeekProperties[preparationModel=" + preparationModel + ", harnessModel=" + harnessModel + ", protocol=" + resolvedProtocol() + ", apiKey=REDACTED]"; }
}
