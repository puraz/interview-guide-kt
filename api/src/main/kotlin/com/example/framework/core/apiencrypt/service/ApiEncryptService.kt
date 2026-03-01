package com.example.framework.core.apiencrypt.service

interface ApiEncryptService {

    /**
     * 解密
     */
    fun decrypt(data: String): String

    /**
     * 加密
     */
    fun encrypt(data: String): String
}