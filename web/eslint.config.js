/**
 * ESLint 扁平配置（评审 P1-15：前端此前**没有 lint**）。
 * 规则取向：只保留"真能挡住 bug"的项，不做风格洁癖（格式交给编辑器/Prettier 各自的习惯）。
 * 其中 no-explicit-any / no-console 是 AGENT.md 已经写死的前端红线，这里落成机器可校验的规则。
 */
import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  // 构建产物与依赖不参与检查
  { ignores: ['dist', 'node_modules', 'e2e'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // 组件文件同时导出"配套常量/纯函数"是本项目的既定写法（如 MeetingStatusTag 的元数据即唯一映射处，
      // 见 docs/review-M2.md R15）。把它们拆走反而多一层文件跳转，这里显式放行这几个名字。
      'react-refresh/only-export-components': [
        'warn',
        {
          allowConstantExport: true,
          allowExportNames: [
            'router',
            'MEETING_STATUS_META',
            'statusBlockClass',
            'meetingStatusText',
            'meetingEventText',
          ],
        },
      ],
      // AGENT.md 红线：前端全库零 any（评审确认过，靠的此前只是自觉——现在由 lint 兜住）
      '@typescript-eslint/no-explicit-any': 'error',
      // 调试残留一律不允许进仓库（排障请用 window.__xxx 或临时改 log 级别）
      'no-console': 'error',
      // 未使用变量：允许 `_` 前缀显式表示"占位不打算用"
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // 中文 UI 里"全角空格"是刻意的排版手段（如时间线里 `标题　14:00-15:00　张三`）。
      // 默认只跳过普通字符串字面量，JSX 文本与模板字面量都会被判成"不规则空白"，
      // 故把这两处也放开（其余位置仍会报，能挡住真正的误输入）
      'no-irregular-whitespace': ['error', { skipJSXText: true, skipTemplates: true }],
    },
  },
)
