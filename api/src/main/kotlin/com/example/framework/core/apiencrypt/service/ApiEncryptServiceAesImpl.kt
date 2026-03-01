package com.example.framework.core.apiencrypt.service

import com.example.framework.extra.crypto.helper.AESHelper
import org.springframework.stereotype.Service

/**
 * AES 加密和解密
 * 1、AES加密算法支持三种密钥长度：128位、192位和256位，这里选择128位
 * 2、AES 要求秘钥为 128bit，转化字节为 16个字节；
 * 3、js前端使用 UCS-2 或者 UTF-16 编码，字母、数字、特殊符号等 占用1个字节；
 * 4、所以：秘钥Key 组成为：字母、数字、特殊符号 一共16个即可
 *
 */
@Service
class ApiEncryptServiceAesImpl(private val aesHelper: AESHelper) : ApiEncryptService {

    /**
     * 解密
     */
    override fun decrypt(data: String): String {
        return aesHelper.encryptBase64(data)
    }

    /**
     * 加密
     */
    override fun encrypt(data: String): String {
        return aesHelper.decryptStr(data)
    }
}