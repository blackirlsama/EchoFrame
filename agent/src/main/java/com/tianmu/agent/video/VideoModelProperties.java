package com.tianmu.agent.video;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Video/vision provider settings. {@code provider} is the supplier identity (compatible | minimax);
 * {@code visionTransport} is the wire protocol and is deliberately separate from it: the MiniMax
 * official OpenAI-compatible image input must not be reached by pretending the provider is
 * "compatible". {@code auto} keeps every existing configuration on its previous transport
 * (minimax -> dedicated /v1/coding_plan/vlm, compatible -> generic chat vision), so the default
 * behaviour is byte-identical.
 */
@ConfigurationProperties("agent.video")
public record VideoModelProperties(@DefaultValue("false") boolean enabled, @DefaultValue("compatible") String provider, URI baseUrl,
        @DefaultValue("") String apiKey, @DefaultValue("") String model,
        @DefaultValue("") String transcriptionModel,
        @DefaultValue("auto") String visionTransport) {
    public VideoModelProperties(boolean enabled, String provider, URI baseUrl, String apiKey, String model,
            String transcriptionModel) {
        this(enabled, provider, baseUrl, apiKey, model, transcriptionModel, "auto");
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public VideoModelProperties {
        if (!java.util.Set.of("compatible", "minimax").contains(provider)) throw new IllegalArgumentException("Unsupported video provider");
        if (!java.util.Set.of("auto", "vlm_dedicated", "openai_chat_vision", "generic_chat_vision").contains(visionTransport))
            throw new IllegalArgumentException("Unsupported vision transport");
        if (!"auto".equals(visionTransport)) {
            boolean consistent = ("minimax".equals(provider)
                    && java.util.Set.of("vlm_dedicated", "openai_chat_vision").contains(visionTransport))
                    || ("compatible".equals(provider) && "generic_chat_vision".equals(visionTransport));
            if (!consistent) throw new IllegalArgumentException("Vision transport does not match the provider identity");
        }
        if (enabled && (baseUrl == null || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || apiKey.isBlank() || apiKey.contains("\n") || model.isBlank() || model.length() > 160))
            throw new IllegalArgumentException("Configure an HTTPS vision endpoint, model and private key");
        // Repair-01: a loopback http endpoint is allowed for the evaluation double, mirroring the
        // model client's rule; every non-local endpoint still requires https.
        if (enabled && baseUrl != null && "http".equals(baseUrl.getScheme())
                && !SetHolder.LOCAL.contains(baseUrl.getHost()))
            throw new IllegalArgumentException("Configure an HTTPS vision endpoint, model and private key");
    }
    /** Effective transport: an explicit choice wins; auto keeps the historical per-provider mapping. */
    public String resolvedTransport() {
        if (!"auto".equals(visionTransport)) return visionTransport;
        return "minimax".equals(provider) ? "vlm_dedicated" : "generic_chat_vision";
    }
    private static class SetHolder { static final java.util.Set<String> LOCAL = java.util.Set.of("localhost", "127.0.0.1", "[::1]"); }
    @Override public String toString() { return "VideoModelProperties[enabled=" + enabled + ", provider=" + provider
            + ", transport=" + resolvedTransport() + "]"; }
}
