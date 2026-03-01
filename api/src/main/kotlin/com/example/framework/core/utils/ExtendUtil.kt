package com.example.framework.core.utils

import cn.hutool.core.date.ChineseDate
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * 扩展函数工具单例对象
 */
object ExtendUtil {

    /**
     * 计算年龄
     */
    fun LocalDate.age(): Int = Period.between(this, LocalDate.now()).years

    /**
     * 根据生日计算星座
     * @param birthday 生日
     * @return 星座名称
     */
    fun calculateZodiacSign(birthday: LocalDate): String {
        val month = birthday.monthValue
        val day = birthday.dayOfMonth

        return when {
            (month == 1 && day >= 20) || (month == 2 && day <= 18) -> "水瓶座"
            (month == 2 && day >= 19) || (month == 3 && day <= 20) -> "双鱼座"
            (month == 3 && day >= 21) || (month == 4 && day <= 19) -> "白羊座"
            (month == 4 && day >= 20) || (month == 5 && day <= 20) -> "金牛座"
            (month == 5 && day >= 21) || (month == 6 && day <= 21) -> "双子座"
            (month == 6 && day >= 22) || (month == 7 && day <= 22) -> "巨蟹座"
            (month == 7 && day >= 23) || (month == 8 && day <= 22) -> "狮子座"
            (month == 8 && day >= 23) || (month == 9 && day <= 22) -> "处女座"
            (month == 9 && day >= 23) || (month == 10 && day <= 23) -> "天秤座"
            (month == 10 && day >= 24) || (month == 11 && day <= 22) -> "天蝎座"
            (month == 11 && day >= 23) || (month == 12 && day <= 21) -> "射手座"
            (month == 12 && day >= 22) || (month == 1 && day <= 19) -> "摩羯座"
            else -> "未知"
        }
    }

    /**
     * 根据生日计算生肖
     * @param birthday 生日
     * @return 生肖名称
     */
    fun calculateChineseZodiac(birthday: LocalDate): String {
        val year = birthday.year
        val zodiacAnimals = arrayOf(
            "猴", "鸡", "狗", "猪", "鼠", "牛",
            "虎", "兔", "龙", "蛇", "马", "羊"
        )
        return zodiacAnimals[year % 12]
    }

    /**
     * 将“用户填写的生日”换算为用于计算星座/年龄的公历日期。//
     *
     * 说明：//
     * - 在业务侧我们允许用户选择“公历/农历”录入生日，但星座属于西方星座，必须使用公历日期来计算//
     * - 当用户选择农历时，前端仍会把“年月日”按 yyyy-MM-dd 传给后端，这里会把该年月日解释为农历年月日并换算成公历日期//
     * - 当前前端暂未采集“闰月”信息，因此统一按非闰月处理（isLeapMonth=false）//
     *
     * @param birthday LocalDate 用户录入的生日（若 isLunar=true，则该 LocalDate 的 year/month/day 表示农历年月日）//
     * @param isLunar Boolean 是否农历输入：true-农历，false-公历//
     * @param isLeapMonth Boolean 是否闰月（默认 false）// 前端未采集时保持默认即可
     * @return LocalDate? 换算后的公历日期；若农历日期不合法则返回 null
     */
    fun resolveGregorianBirthday(
        birthday: LocalDate, // 用户录入的生日（按 isLunar 解释）//
        isLunar: Boolean, // 是否农历：true-农历，false-公历//
        isLeapMonth: Boolean = false, // 是否闰月：默认 false //
    ): LocalDate? {
        if (!isLunar) {
            return birthday
        }

        return runCatching {
            val chineseDate = ChineseDate(
                birthday.year, // 农历年
                birthday.monthValue, // 农历月（1-12）
                birthday.dayOfMonth, // 农历日（1-30）
                isLeapMonth, // 是否闰月
            )
            chineseDate.gregorianDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() // 转换为系统时区 LocalDate
        }.getOrNull()
    }

    /**
     * 计算生肖（按农历年）。//
     *
     * 说明：//
     * - 生肖的“年”应按农历年计算，直接用公历 year%12 在春节前后会出现偏差//
     * - 若用户选择农历录入（isLunarInput=true）：直接按用户输入的农历年月日计算生肖//
     * - 若用户选择公历录入（isLunarInput=false）：先将公历日期换算为对应农历年后再计算生肖//
     *
     * @param storedBirthday LocalDate 用户录入的生日（按用户选择历法原样存储）//
     * @param gregorianBirthday LocalDate 用于计算星座/年龄的公历生日（通常为 resolveGregorianBirthday 的结果）//
     * @param isLunarInput Boolean 是否农历输入：true-农历，false-公历//
     * @param isLeapMonth Boolean 是否闰月（默认 false）// 前端未采集时保持默认即可
     * @return String 生肖（如：鼠/牛/虎...）//
     */
    fun resolveChineseZodiac(
        storedBirthday: LocalDate, // 用户录入的生日（按用户选择历法原样存储）//
        gregorianBirthday: LocalDate, // 计算用公历生日（用于公历->农历换算）//
        isLunarInput: Boolean, // 是否农历输入：true-农历，false-公历//
        isLeapMonth: Boolean = false, // 是否闰月：默认 false //
    ): String {
        return if (isLunarInput) {
            ChineseDate(
                storedBirthday.year, // 农历年
                storedBirthday.monthValue, // 农历月
                storedBirthday.dayOfMonth, // 农历日
                isLeapMonth, // 是否闰月
            ).chineseZodiac
        } else {
            ChineseDate(gregorianBirthday).chineseZodiac
        }
    }

    /**
     * 检查字符串是否有意义的非空（非null且非空白）
     * @param value 字符串值
     * @return 是否有意义
     */
    fun isMeaningfulString(value: String?): Boolean {
        return !value.isNullOrBlank()
    }

    /**
     * 检查JSON字符串是否有意义的非空（非null、非空白且不是空数组）
     * @param jsonValue JSON字符串值
     * @return 是否有意义
     */
    fun isMeaningfulJsonString(jsonValue: String?): Boolean {
        if (jsonValue.isNullOrBlank()) return false
        val trimmed = jsonValue.trim()
        return trimmed != "[]" && trimmed != "{}"
    }

}
