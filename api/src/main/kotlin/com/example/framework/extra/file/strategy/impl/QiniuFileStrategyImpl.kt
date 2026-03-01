package com.example.framework.extra.file.strategy.impl

import cn.hutool.core.lang.Assert
import com.qiniu.common.QiniuException
import com.qiniu.http.Response
import com.qiniu.storage.BucketManager
import com.qiniu.storage.Configuration
import com.qiniu.storage.Region
import com.qiniu.storage.UploadManager
import com.qiniu.storage.model.DefaultPutRet
import com.qiniu.util.Auth
import com.example.framework.core.utils.JSONUtil
import com.example.framework.extra.file.enums.FileStorageType
import com.example.framework.extra.file.model.FileDeleteParam
import com.example.framework.extra.file.model.FileInfo
import com.example.framework.extra.file.properties.FileProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

/**
 * 七牛云存储 文件管理策略实现
 *
 * 参考文档：[七牛云对象存储](https://developer.qiniu.com/kodo/sdk/java)
 * @author gcc
 */
@Component("QINIU")
class QiniuFileStrategyImpl(
    private val fileProperties: FileProperties
) : AbstractFileStrategy() {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 策略类实现该方法进行文件上传
     *
     * @param file     文件对象
     * @param fileInfo 文件详情
     */
    override fun onUpload(file: MultipartFile, fileInfo: FileInfo) {
        val qiniu = fileProperties.qiniu
        Assert.notBlank(qiniu.bucket, "请配置七牛云存储桶bucket")
        Assert.notBlank(qiniu.endpoint, "请配置七牛云访问域名endpoint")
        Assert.notBlank(qiniu.accessKeyId, "请配置七牛云accessKeyId")
        Assert.notBlank(qiniu.accessKeySecret, "请配置七牛云accessKeySecret")

        // 创建认证对象
        val auth = Auth.create(qiniu.accessKeyId, qiniu.accessKeySecret)
        
        // 生成上传凭证
        val upToken = auth.uploadToken(qiniu.bucket)
        
        // 配置七牛云区域（根据endpoint自动选择）
        val cfg = Configuration(getRegionByEndpoint(qiniu.endpoint))
        
        // 创建上传管理器
        val uploadManager = UploadManager(cfg)
        
        try {
            // 执行文件上传
            val response: Response = uploadManager.put(file.inputStream, fileInfo.path, upToken, null, null)
            val putRet = JSONUtil.parseObject(response.bodyString(), DefaultPutRet::class.java)
            
            logger.info("七牛云文件上传结果：${JSONUtil.toJsonStr(putRet)}")
            
            // 设置文件详情
            fileInfo.bucket = qiniu.bucket
            fileInfo.url = "${qiniu.buildUrlPrefix()}${fileInfo.path}"
            fileInfo.storageType = FileStorageType.QINIU.name
            
        } catch (ex: QiniuException) {
            logger.error("七牛云文件上传异常：", ex)
            throw RuntimeException("七牛云文件上传失败", ex)
        }
    }


    /**
     * 获取文件输入流
     *
     * @param path 文件路径
     */
    override fun getObject(path: String): InputStream? {
        val qiniu = fileProperties.qiniu
        
        // 创建认证对象
        val auth = Auth.create(qiniu.accessKeyId, qiniu.accessKeySecret)
        
        // 生成私有空间的下载链接（默认1小时有效）
        val privateUrl = auth.privateDownloadUrl("${qiniu.buildUrlPrefix()}${path}", 3600)
        
        return try {
            // 这里可以使用HTTP客户端下载文件流
            // 但由于需要额外的HTTP客户端依赖，这里简化处理
            logger.warn("七牛云文件下载需要额外实现，返回null")
            null
        } catch (e: Exception) {
            logger.error("七牛云获取文件异常：", e)
            null
        }
    }

    /**
     * 删除文件
     *
     * @param param 文件删除参数
     */
    override fun delete(param: FileDeleteParam): Boolean {
        val qiniu = fileProperties.qiniu
        return try {
            // 创建认证对象
            val auth = Auth.create(qiniu.accessKeyId, qiniu.accessKeySecret)
            
            // 配置七牛云区域
            val cfg = Configuration(getRegionByEndpoint(qiniu.endpoint))
            
            // 创建空间管理器
            val bucketManager = BucketManager(auth, cfg)
            
            // 删除文件
            val response = bucketManager.delete(qiniu.bucket, param.path)
            
            if (response.isOK) {
                logger.info("七牛云文件删除成功: ${param.path}")
                true
            } else {
                logger.error("七牛云文件删除失败: ${response.error}")
                false
            }
        } catch (e: Exception) {
            logger.error("七牛云删除文件异常：", e)
            false
        }
    }
    
    /**
     * 根据endpoint获取七牛云区域配置
     */
    private fun getRegionByEndpoint(endpoint: String?): Region {
        return when {
            endpoint?.contains("cn-east-1") == true -> Region.region0() // 华东
            endpoint?.contains("cn-north-1") == true -> Region.region1() // 华北
            endpoint?.contains("cn-south-1") == true -> Region.region2() // 华南
            endpoint?.contains("us-north-1") == true -> Region.regionNa0() // 北美
            endpoint?.contains("ap-southeast-1") == true -> Region.regionAs0() // 东南亚
            else -> Region.autoRegion() // 自动选择
        }
    }
}
