package interview.guide.modules.voiceinterview.model

/**
 * Voice Interview Session Status
 * 语音面试会话状态
 */
enum class VoiceInterviewSessionStatus {
    /**
     * In progress - WebSocket connected, interview ongoing
     * 面试进行中：WebSocket 已连接，面试正在继续
     */
    IN_PROGRESS,

    /**
     * Paused - User paused or timeout, state saved to DB
     * 已暂停：用户主动暂停或超时，状态已保存到数据库
     */
    PAUSED,

    /**
     * Completed - Interview finished
     * 已完成：面试结束
     */
    COMPLETED,

    /**
     * Failed - Error occurred
     * 已失败：发生错误
     */
    FAILED
}
