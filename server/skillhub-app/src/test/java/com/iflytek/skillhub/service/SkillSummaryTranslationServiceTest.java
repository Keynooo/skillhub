package com.iflytek.skillhub.service;

import com.iflytek.skillhub.config.AnthropicProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillSummaryTranslationServiceTest {

    private AnthropicService anthropicService;
    private SkillSummaryTranslationService service;

    @BeforeEach
    void setUp() {
        anthropicService = mock(AnthropicService.class);
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey("sk-test");
        properties.setBaseUrl("https://api.deepseek.com/anthropic");
        properties.setModel("deepseek-v4-flash");
        service = new SkillSummaryTranslationService(anthropicService, properties, "default");
    }

    @Test
    void translateToChinese_shouldSkipWhenSummaryIsPredominantlyChinese() throws Exception {
        when(anthropicService.isAvailable()).thenReturn(true);

        String result = service.translateToChinese("这是一个纯中文的技能摘要，用于测试翻译服务的行为");

        assertThat(result).isNull();
        verify(anthropicService, never())
                .sendMessageWithRetry(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void translateToChinese_shouldTranslateBilingualSummaryInsteadOfSkipping() throws Exception {
        when(anthropicService.isAvailable()).thenReturn(true);
        when(anthropicService.sendMessageWithRetry(
                anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(new AnthropicService.AnthropicMessageResponse("会话结束后对项目文档和记忆进行审查与同步", 12, 0.1f));

        String text = "End-of-session knowledge cleanup with OCD-level rigor. 会话结束后对项目文档和记忆进行洁癖级审查与同步。MUST trigger when the user says sync up.";
        String result = service.translateToChinese(text);

        assertThat(result).isEqualTo("会话结束后对项目文档和记忆进行审查与同步");
        verify(anthropicService)
                .sendMessageWithRetry(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void translateToChinese_shouldTranslatePureEnglishSummary() throws Exception {
        when(anthropicService.isAvailable()).thenReturn(true);
        when(anthropicService.sendMessageWithRetry(
                anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(new AnthropicService.AnthropicMessageResponse("检索并总结当前天气和预报", 10, 0.1f));

        String result = service.translateToChinese("Retrieve and summarize current weather and forecasts");

        assertThat(result).isEqualTo("检索并总结当前天气和预报");
    }
}
