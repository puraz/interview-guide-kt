package com.example.business.service

import com.qiniu.common.QiniuException
import com.qiniu.storage.Configuration
import com.qiniu.storage.Region
import com.qiniu.storage.UploadManager
import com.qiniu.util.Auth
import com.example.framework.extra.file.properties.FileProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

/**
 * 七牛云服务类
 * 
 * @author gcc
 */
@Service
class QiniuService(
    private val fileProperties: FileProperties
) {
    private val logger = LoggerFactory.getLogger(QiniuService::class.java)
    
    /**
     * 获取七牛云上传token
     * 
     * @return 上传token
     */
    fun getUploadToken(): String {
        val qiniu = fileProperties.qiniu
        
        // 创建认证对象
        val auth = Auth.create(qiniu.accessKeyId, qiniu.accessKeySecret)
        
        // 生成上传凭证，有效时间为1小时
        return auth.uploadToken(qiniu.bucket)
    }
    
    /**
     * 获取七牛云上传token（带过期时间）
     * 
     * @param expireSeconds 过期时间（秒）
     * @return 上传token
     */
    fun getUploadToken(expireSeconds: Long = 3600): String {
        val qiniu = fileProperties.qiniu
        
        // 创建认证对象
        val auth = Auth.create(qiniu.accessKeyId, qiniu.accessKeySecret)
        
        // 生成上传凭证，可指定过期时间
        return auth.uploadToken(qiniu.bucket, null, expireSeconds, null)
    }

    /**
     * 以字节流方式上传文件到七牛云
     *
     * @param bytes    文件字节内容
     * @param key      文件存储路径（为空时自动生成）
     * @param mimeType 文件类型，默认 image/png
     * @return 文件的公网访问地址
     */
    fun uploadBytes(bytes: ByteArray, key: String? = null, mimeType: String = "image/png"): String {
        val qiniu = fileProperties.qiniu
        val bucket = qiniu.bucket ?: throw IllegalStateException("请配置七牛云存储桶bucket")
        val accessKeyId = qiniu.accessKeyId ?: throw IllegalStateException("请配置七牛云accessKeyId")
        val accessKeySecret = qiniu.accessKeySecret ?: throw IllegalStateException("请配置七牛云accessKeySecret")

        val finalKey = (key?.takeIf { it.isNotBlank() } ?: run {
            val uuid = UUID.randomUUID().toString().replace("-", "")
            "miniapp/${uuid}.png"
        })

        val auth = Auth.create(accessKeyId, accessKeySecret)
        val cfg = Configuration(getRegionByEndpoint(qiniu.endpoint))
        val uploadManager = UploadManager(cfg)

        val upToken = auth.uploadToken(bucket)

        try {
            val response = uploadManager.put(bytes, finalKey, upToken, null, mimeType, true)
            if (!response.isOK) {
                logger.error("七牛云上传失败，code={}, error={}", response.statusCode, response.error)
                throw IllegalStateException("七牛云上传失败: ${response.error}")
            }
        } catch (ex: QiniuException) {
            logger.error("七牛云上传异常", ex)
            throw IllegalStateException("七牛云上传异常: ${ex.message}", ex)
        }

        return "${qiniu.buildUrlPrefix()}$finalKey"
    }

    private fun getRegionByEndpoint(endpoint: String?): Region {
        return when {
            endpoint?.contains("cn-east-1") == true -> Region.region0()
            endpoint?.contains("cn-north-1") == true -> Region.region1()
            endpoint?.contains("cn-south-1") == true -> Region.region2()
            endpoint?.contains("us-north-1") == true -> Region.regionNa0()
            endpoint?.contains("ap-southeast-1") == true -> Region.regionAs0()
            else -> Region.autoRegion()
        }
    }
}
