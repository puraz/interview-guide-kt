## Project Structure & Modules

- `api/`: Kotlin Spring Boot service (Kotlin 1.9, Boot 2.7). ORM use Jimmer framework. Key paths: `api/src/main/kotlin`,
  `api/src/test/kotlin`,
  `api/src/main/resources`.
- `client/`: UniApp + Vue3 front-end (pnpm) + UnoCSS. See `client/package.json` scripts.
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
3.接口参数少时使用@RequestParam接收，多时定义一个实体，实体的名称要以Param结尾，使用@RequestBody接收，不要使用@PathVariable
4.接口路径的必须要有版本v2标识，逻辑过多时可以定义service，并写在service中
5.不要执行gradlew命令，不要做任何测试的形为
6.代码编写优雅，逻辑清晰，命名规范，注释充分
6.1 获取当前登录用户ID请直接使用`SuperBaseController.currentUserId(notLoginMessage)`（统一登录校验与提示文案），不要在各个Controller里重复定义
`currentUserId()`方法
7.解析json使用com.xwkjframework.core.utils.JSONUtil
8.repository不要定义spring data jpa方法，请使用类似这种写查询：
9.v2版本的sql定义在目录`api/docs/sqlv2`下

```kotlin
return testRecordRepository.sql.createQuery(TestRecord::class) {
    where(table.userId eq userId)
    where(table.testType eq testType)
    where(table.status eq TestRecordStatus.COMPLETED)
    orderBy(table.completedAt.desc())
    select(table)
}.fetchFirstOrNull()
```

9.Jimmer实体中如需存储JSON数组（如标签列表），优先使用`@Serialized val tags: List<String>?`等结构，不要手动以字符串解析，这也是
`UserProfile`/`ChatCompanion`的推荐写法。
10.Jimmer实体中的字段的空与非空要和数据库表字段一致，且字段加了@IdView注解后就不要再加@Column注解。创建实体的时候尽量不要使用
`new(xxx::class).by {}`这种形式，直接使用
`xxx { ... }`这种形式
11.entity目录下可以定义一个模块，包含多个entity，方便管理。
12.尽量不要整一个假的数据做兜底
13.ai生成的内容结果都需要调用fun String.stripCodeFences(): String 以去除代码块标记
14.目前UI组件库使用的是uv-ui，弹窗尽量使用uv-popup组件，不要使用其他弹窗组件，uv-开头的组件可以直接使用，不需要查询是否已安装

## client module

## Project Structure & Module Organization

The LifeSim client uses UniApp with Vue 3. `src/main.ts` bootstraps `App.vue` and registers global plugins. Page
implementations live in `src/pages` (primary flows) and `src/pages-sub` (nested flows). Shared UI and logic sit in
`src/components`, `src/composables`, and `src/utils`. API hooks and the HTTP layer are split between `src/api` and
`src/http`, while global state resides in Pinia stores under `src/store`. Styling relies on `src/style` tokens plus
UnoCSS presets in `uno.config.ts`. Platform manifests and static assets are kept in `manifest.json`, `pages.json`, and
`src/static`. Environment presets live in `env/.env.*`.

## Coding Style & Naming Conventions

This repository relies on `@uni-helper/eslint-config` plus UnoCSS linting. Use two-space indentation, `<script setup>`
syntax, and TypeScript in Vue SFCs. Name Vue components in PascalCase (`UserCard.vue`), composables with the `use`
prefix (`useInventory.ts`), Pinia stores with the `useXStore` pattern, and constants in SCREAMING_SNAKE_CASE. Re-export
shared types from `src/types` when possible. Prefer UnoCSS utilities from the shared presets over inline style blocks.

## Environment & Configuration

Copy the appropriate preset before running locally, for example:

```bash
cp env/.env.development env/.env.local
```

Keep secrets out of tracked `.env.*` files; store overrides in ignored files. When touching deployment settings in
`manifest.config.ts` or `pages.config.ts`, flag any follow-up platform registration steps in your PR.

## 语言

请使用中文回答!!!

## 代码编写要求(重要！！！)

后端的接口现在需要定义在`client/src/api/v2`目录下, ts类型和接口定义在一个根据当前业务命令的文件中;
前端页面现在已配置了自动导入，所以不需要手动import
1.js,ts中需要有注释，原有代码中没有注释的也要补充完整的注释
2.使用unocss编写页面!!!!，请使用`rpx`不要使用`px`
3.unocss的部分配置如下：

```text
 shortcuts: [
  // 布局
  ['flex-center', 'flex justify-center items-center'],
  ['flex-x-center', 'flex justify-center'],
  ['flex-y-center', 'flex items-center'],
  ['flex-between', 'flex justify-between items-center'],
  ['flex-around', 'flex justify-around items-center'],

  // 底部安全区域
  [/^(bottom|mb|pb)-safe(?:-(\d+))?$/, ([n]) => handleBottomSafeArea(n)],
],
  // 动态图标需要在这里配置，或者写在vue页面中注释掉
  safelist: ['i-carbon-code'],
  rules: [
  // 自定义图标，支持 i-xw:xxx 语法
  [/^i-xw:((?!svg).+)$/, ([, iconName]) => buildXwIconCss(iconName)],
  [
    'p-safe',
    {
      padding:
        'env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left)',
    },
  ],
  ['pt-safe', { 'padding-top': 'env(safe-area-inset-top)' }],
  ['pb-safe', { 'padding-bottom': 'env(safe-area-inset-bottom)' }],
],
```

4.我已在useNavigation.ts中定义好了uniapp跳转的一些方法，可以直接使用，如下：

```text
// 关闭所有页面，打开到应用内的某个页面
export function reLaunch(url: string) {
  return uni.reLaunch({url})
}

// 关闭当前页面，跳转到应用内的某个页面。
export function redirectTo(url: string) {
  return uni.redirectTo({url})
}

// 保留当前页面，跳转到应用内的某个页面
export function navigateTo(url: string) {
  return uni.navigateTo({url})
}

// 关闭当前页面，返回上一页面或多级页面
export function navigateBack(delta: number) {
  return uni.navigateBack({delta})
}
```

5.引入自定义图标请使用：<text class="i-xw:图标名" />的形式引入
6.引入图片请使用类似这种的方式：
7.代码编写优雅，逻辑清晰，命名规范，注释充分!!!

```html

<image
        class="mt-6 h-auto w-[160rpx]"
        mode="widthFix"
        :src="`${appStore.oss.images}/smile.png`"
/>
```

// 7.尽量不要使用rpx标注大小，使用类似mt-4这种的，要简洁一点
8.尽量不要写过多的阴影，会不太好看!!
9.图片和图标都是存储在云存储且都是存在的，不要在本地查找
10.字体如果是黑色都使用默认的#111111，不要设置为其他的，然后不要使用font-semibold
11.不要有过多的白色card，因为页面上有默认背景的，不要有过多的阴影
12.渐变色使用原生style方式设置，unocss class设置方式会有兼容性问题
13.不要用unocss过渡的封装样式，不要写很多的层级，追求简约实用
14.字体的颜色如果是“黑色”的话不用特别标注，系统会有默认的
15.写页面时不需要手动import了，我已配置了自动导入!!
16.接口返回的code码，成功是0，错误是其他，接口路径不要以/api开头，因为已经配置了通用前缀
17.给容器加边框的时候要加上类border-solid，不然边框会不显示没有效果
17.1 使用 w-full 且容器有左右 padding 时需要加 box-border（或避免 w-full），防止内容超出屏幕
18.页面中需要数字/字母角标（如 1/2/3/A/B/C）时，优先使用 `uv-badge` 组件，不要手写圆点或数字徽标
class 造成兼容性问题
19.因为使用了自动生成pages.json，不要手动修改pages.json文件
20.button按钮使用view模拟，view模拟的button组件要注意其中的文字要居中

## 前端左右滑动 Tab 规范

1. 左右滑动切换 Tab 统一使用 `client/src/composables/useTabSwiper.ts`
2. `swiper` 必须绑定 `@change/@transition/@animationfinish`，高度使用 `tabContentDisplayHeight`，避免滑动时内容被截断
3. 每个 `swiper-item` 的内容容器必须设置 id（形如 `${prefix}${tabId}`），并与 `panelIdPrefix` 保持一致
4. 异步数据、图片加载、autoHeight 输入等会改变高度时，调用 `notifyTabContentChanged(tabId)` 同步高度
5. 若底部边框仍被裁切，优先调整 `heightBufferPx`，不要用空白占位撑高
