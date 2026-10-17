/**
 * 主题解析的单测（T6.3）：三态模式在系统偏好下的实际结果。
 * 这是“跟随系统”能否正确覆盖/被覆盖的核心规则，抽成纯函数后不需要 DOM。
 */
import { describe, expect, it } from 'vitest'
import { resolveTheme } from './themeStore'

describe('resolveTheme', () => {
  it('手动亮色：无论系统偏好都返回 light', () => {
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('light', false)).toBe('light')
  })

  it('手动暗色：无论系统偏好都返回 dark', () => {
    expect(resolveTheme('dark', true)).toBe('dark')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('跟随系统：系统暗 → dark，系统亮 → light', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
  })
})
