## interview-guide-kt
当前项目是一个使用 kotlin + springboot 3 + jimmer 实现的一个仿照当前根目录下的 interview-guide的一个技术性重写项目。

## Project Structure & Modules

- `api/`: Kotlin Spring Boot service (Kotlin 1.9, Boot 2.7). ORM use Jimmer framework. Key paths: `api/src/main/kotlin`,
  `api/src/test/kotlin`,
  `api/src/main/resources`.
- `api/docs/sql/**/*`: SQL 结构(`docs/sql/**/*`).
- Root Gradle wrapper (`gradlew`) manages backend builds.

## 语言
请用中文回答

## api模块（重要）

1.接口返回实体，返回实体的名称要以Vo结尾，直接定义到对应的controller或service中（有service的话优先定义在service中），不要定义在dto包中
2.接口、方法、方法中的关键代码、参数、返回字段的用途和返回字段这些必须要有详细的注释说明，可以直接写在字段的后面使用
`// xxx注释内容`，注意不要是这种形式的注释：
`// xxx 注释内容 //`，这种后面多了个` //`
这种方式注释
xxx注释，原有代码中没有注释的也要补充完整的注释
3.接口参数少时使用@RequestParam接收，多时定义一个实体，实体的名称要以Param结尾，使用@RequestBody接收；默认不使用@PathVariable，但为对齐 interview 模块的原始RESTful路径，允许在面试模块中使用@PathVariable
4.逻辑过多时可以定义service，并写在service中
5.不要执行gradlew命令，不要做任何测试的形为
6.代码编写优雅，逻辑清晰，命名规范，注释充分，复用性强，高内聚、低耦合，高可用！
6.1 获取当前登录用户ID请直接使用`SuperBaseController.currentUserId(notLoginMessage)`（统一登录校验与提示文案），不要在各个Controller里重复定义
`currentUserId()`方法
7.解析json使用com.xwkjframework.core.utils.JSONUtil
8.repository不要定义spring data jpa方法，请使用类似这种写查询：
```kotlin
return testRecordRepository.sql.createQuery(TestRecord::class) {
    where(table.userId eq userId)
    where(table.testType eq testType)
    where(table.status eq TestRecordStatus.COMPLETED)
    orderBy(table.completedAt.desc())
    select(table)
}.fetchFirstOrNull()
```
9.用到的表的所有sql按模块分文件名定义在目录`api/docs/sql`下
10.Jimmer实体中如需存储JSON数组（如标签列表），优先使用`@Serialized val tags: List<String>?`等结构，不要手动以字符串解析
11.Jimmer实体中的字段的空与非空要和数据库表字段一致，且字段加了@IdView注解后就不要再加@Column注解。创建实体的时候尽量不要使用
`new(xxx::class).by {}`这种形式，直接使用
`xxx { ... }`这种形式，实体的类型那些都需要在enums目录下定义枚举。
12.entity目录下可以按业务定义一个模块（目录），包含多个entity，方便管理。
12.尽量不要整一个假的数据做兜底
13.ai生成的内容结果都需要调用fun String.stripCodeFences(): String 以去除代码块标记
