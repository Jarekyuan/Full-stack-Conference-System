/**
 * PostCSS 配置：Tailwind v3 必需的构建管线（tailwind 展开工具类 + autoprefixer 补浏览器前缀）
 * 由 Vite 自动读取，无需在 vite.config.ts 里额外声明
 */
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
}
