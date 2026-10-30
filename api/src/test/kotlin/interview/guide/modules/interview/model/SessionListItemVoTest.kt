package interview.guide.modules.interview.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("面试会话列表项映射")
class SessionListItemVoTest {

    @Test
    @DisplayName("from 映射知识库面试方向字段")
    fun shouldMapInterviewCategory() {
        val entity = InterviewSessionEntity()
        entity.sessionId = "s1"
        entity.sourceType = "KNOWLEDGE_BASE"
        entity.knowledgeBaseId = 1L
        entity.interviewCategory = "MySQL"
        entity.totalQuestions = 5

        val vo = SessionListItemVo.from(entity)

        assertThat(vo.interviewCategory).isEqualTo("MySQL")
        assertThat(vo.sourceType).isEqualTo("KNOWLEDGE_BASE")
        assertThat(vo.knowledgeBaseId).isEqualTo(1L)
    }

    @Test
    @DisplayName("历史普通面试记录的 interviewCategory 为 null 时正常映射")
    fun shouldMapNullInterviewCategory() {
        val entity = InterviewSessionEntity()
        entity.sessionId = "s2"
        entity.totalQuestions = null

        val vo = SessionListItemVo.from(entity)

        assertThat(vo.interviewCategory).isNull()
        assertThat(vo.totalQuestions).isZero()
    }
}
