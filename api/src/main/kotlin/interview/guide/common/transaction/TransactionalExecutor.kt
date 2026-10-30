package interview.guide.common.transaction

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 小范围事务执行器，用于避免同类内部调用导致事务注解失效。
 */
@Service
class TransactionalExecutor {

    /**
     * 在事务中执行动作（无返回值）
     *
     * @param action 事务内动作 // 需要事务保护的操作
     */
    @Transactional(rollbackFor = [Exception::class])
    fun run(action: () -> Unit) {
        action()
    }

    /**
     * 在事务中执行动作并返回结果
     *
     * @param action 事务内动作 // 需要事务保护的操作
     * @return 执行结果 // 动作返回值
     */
    @Transactional(rollbackFor = [Exception::class])
    fun <T> call(action: () -> T): T {
        return action()
    }

    /**
     * 在独立新事务中执行动作（无返回值）
     *
     * @param action 事务内动作 // 需要独立事务保护的操作
     */
    @Transactional(rollbackFor = [Exception::class], propagation = Propagation.REQUIRES_NEW)
    fun runRequiresNew(action: () -> Unit) {
        action()
    }

    /**
     * 在独立新事务中执行动作并返回结果
     *
     * @param action 事务内动作 // 需要独立事务保护的操作
     * @return 执行结果 // 动作返回值
     */
    @Transactional(rollbackFor = [Exception::class], propagation = Propagation.REQUIRES_NEW)
    fun <T> callRequiresNew(action: () -> T): T {
        return action()
    }
}
