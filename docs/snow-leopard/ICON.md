# 雪豹桌面图标

- 使用内置 imagegen 生成，再按用户反馈编辑为更扁平、可爱的风格。
- 最新提示词：透明背景上的简洁可爱雪豹头；圆耳、象牙白脸、少量炭黑斑点、柔和小眼睛、淡粉小鼻子与闭合微笑；无拉链、毛发纹理、渐变、边框或扬声器标记。主体居中并留出透明空间。
- 最终资源：`app/src/main/res/drawable-nodpi/ic_snow_leopard_flat.webp`，432 × 432，仅为应用资源做尺寸缩放和无损 WebP 编码。
- 实际资源保留 alpha 透明度，不把背景烘焙进图片；自适应图标使用独立纯色背景、四边 16% inset 的透明前景和单独的雪豹单色层。主体缩小并保留裁切空间，所有兼容 alias 一致。
- Compose 设置/关于/侧栏直接读取 WebP，不使用其不支持的 bitmap/inset/layer-list/adaptive XML；背景由界面 Modifier 绘制。桌面和应用内使用不同类型的资源入口，防止再次触发 painterResource 异常。
