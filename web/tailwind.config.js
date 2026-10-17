/**
 * Tailwind 配置（v3）
 * 核心功能：声明扫描范围；与 antd 5 共存的关键取舍写在 index.css 顶部注释里
 * 边界（02 文档 3.3 #7）：Tailwind 只管"排版/间距/响应式"，组件一律用 antd
 */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  // T6.3：class 策略；由 themeStore/AppThemeProvider 在 <html> 上切 dark
  darkMode: 'class',
  theme: {
    extend: {},
  },
  plugins: [],
}
