## interview-guide-kt
当前项目（api）是一个使用 kotlin 实现的一个完全仿照当前根目录下的 interview-guide的一个技术性重写项目，只是语言从java切换至kotlin其他的什么都不变！！！！

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
这种方式注释，原有代码中没有注释的也要补充完整的注释
4.逻辑过多时可以定义service，并写在service中
6.代码编写优雅，逻辑清晰，命名规范，注释充分，复用性强，高内聚、低耦合，高可用！
9.用到的表的所有sql按模块分文件名定义在目录`api/docs/sql`下
12.尽量不要整一个假的数据做兜底
13.ai生成的内容结果都需要调用fun String.stripCodeFences(): String 以去除代码块标记
