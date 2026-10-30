package interview.guide.common.evaluation

/**
 * 通用面试问答记录（文字面试和语音面试共用）
 * questionIndex 与 parentQuestionIndex 均为 0-based，展示时再加 1
 */
data class QaRecord(
    val questionIndex: Int = 0, // 题目索引（0-based）
    val question: String = "", // 题面
    val category: String? = null, // 题目分类
    val userAnswer: String? = null, // 用户回答，null 表示未回答
    val followUp: Boolean = false, // 是否为追问
    val parentQuestionIndex: Int? = null // 追问所属的主问题索引
) {

    /**
     * 简化构造：非追问记录
     */
    constructor(
        questionIndex: Int,
        question: String,
        category: String?,
        userAnswer: String?
    ) : this(questionIndex, question, category, userAnswer, false, null)

    /**
     * 追问所属的主问题索引；非追问返回自身索引
     */
    fun groupRootIndex(): Int {
        return if (followUp) parentQuestionIndex ?: questionIndex else questionIndex
    }
}
