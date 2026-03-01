package com.example.framework.extra.file.enums

/**
 * 文件存储策略
 *
 * @author gcc
 */
enum class FileStorageType {
    /** 本地 */
    LOCAL,

    /** minio */
    MINIO,

    /** 阿里云 */
    ALI_OSS,

    /** 七牛云 */
    QINIU
}
