package com.tianmu.agent.evaluation;

import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.video.VideoModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repair-01: the two configuration keys this slice introduced must be verified by their ACTUAL
 * Spring binding — the catalog documents the effective keys (`agent.models.protocol`,
 * `agent.video.vision-transport`), never the conceptual `vision.transport` spelling.
 */
class EvaluationProviderConfigBindingTest {

    @Configuration @EnableConfigurationProperties({DeepSeekProperties.class, VideoModelProperties.class})
    static class PropsConfig { }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class);

    @Test void agentModelsProtocolIsTheEffectiveBindingKey() {
        runner.withPropertyValues("agent.models.protocol=siliconflow", "agent.models.api-key=test-secret")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("siliconflow", context.getBean(DeepSeekProperties.class).resolvedProtocol(),
                            "an explicit protocol must win even on a host auto cannot classify");
                });
        runner.withPropertyValues("agent.models.api-key=test-secret")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    // the default test base URL (api.deepseek.com) classifies to the deepseek family
                    assertEquals("deepseek", context.getBean(DeepSeekProperties.class).resolvedProtocol());
                });
    }

    @Test void agentModelsProtocolRefusesUnknownValues() {
        runner.withPropertyValues("agent.models.protocol=openai", "agent.models.api-key=test-secret")
                .run(context -> assertNotNull(context.getStartupFailure(),
                        "a protocol outside the documented set must refuse startup"));
    }

    @Test void agentVideoVisionTransportIsTheEffectiveBindingKey() {
        runner.withPropertyValues("agent.video.vision-transport=openai_chat_vision",
                        "agent.video.provider=minimax", "agent.models.api-key=test-secret")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("openai_chat_vision", context.getBean(VideoModelProperties.class).resolvedTransport(),
                            "the explicit generic image transport must win over the auto mapping");
                });
        runner.withPropertyValues("agent.video.provider=minimax", "agent.models.api-key=test-secret")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("vlm_dedicated", context.getBean(VideoModelProperties.class).resolvedTransport(),
                            "auto keeps the historical dedicated mapping for minimax");
                });
    }

    @Test void agentVideoVisionTransportMustMatchTheProviderIdentity() {
        runner.withPropertyValues("agent.video.vision-transport=vlm_dedicated",
                        "agent.video.provider=compatible", "agent.models.api-key=test-secret")
                .run(context -> assertNotNull(context.getStartupFailure(),
                        "a transport inconsistent with the provider identity must refuse startup"));
        runner.withPropertyValues("agent.video.vision-transport=openai_chat_vision",
                        "agent.video.provider=compatible", "agent.models.api-key=test-secret")
                .run(context -> assertNotNull(context.getStartupFailure(),
                        "the generic image contract is never a provider disguise"));
    }
}
