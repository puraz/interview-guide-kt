package interview.guide.common.config

import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.knowledgebase.model.RagChatVo
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("接口 JSON 字段契约")
class JsonPropertyNamesTest {

    private val mapper = JacksonConfig().objectMapper()

    private fun propertyNames(type: Class<*>): List<String> {
        return mapper.serializationConfig
            .introspect(mapper.constructType(type))
            .findProperties()
            .map { it.name }
    }

    @Test
    @DisplayName("is 前缀布尔字段保持 Java 版线协议名称")
    fun booleanFieldsKeepJavaNames() {
        assertThat(propertyNames(RagChatVo.SessionListItemDTO::class.java)).contains("isPinned")
        assertThat(propertyNames(InterviewQuestionVo::class.java)).contains("isFollowUp")
        assertThat(propertyNames(WebSocketSubtitleMessage::class.java)).contains("isFinal")
    }
}
