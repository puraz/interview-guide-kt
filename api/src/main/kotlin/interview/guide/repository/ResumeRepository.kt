package interview.guide.repository

import interview.guide.entity.ResumeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 简历Repository
 */
@Repository
interface ResumeRepository : JpaRepository<ResumeEntity, Long> {

    /**
     * 根据文件哈希查找简历（用于去重）
     */
    fun findByFileHash(fileHash: String): ResumeEntity?

    /**
     * 检查文件哈希是否存在
     */
    fun existsByFileHash(fileHash: String): Boolean
}
