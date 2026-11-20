import type { Plugin } from 'vite'

export function assertLegacyMediaQueries(css: string, filename: string): void {
  // 检查最终产物，避免升级构建器后重新引入旧手机内核不识别的范围语法。
  const rangeQuery = css.match(/@media\b[^{};]*[<>=][^{};]*\{/i)
  if (rangeQuery) {
    throw new Error(`${filename}: unsupported media range syntax: ${rangeQuery[0]}`)
  }
}

export function cssCompatibility(): Plugin {
  return {
    name: 'foodmap-css-compatibility',
    apply: 'build',
    writeBundle(_options, bundle) {
      for (const asset of Object.values(bundle)) {
        if (asset.type === 'asset' && asset.fileName.endsWith('.css')) {
          const css =
            typeof asset.source === 'string' ? asset.source : new TextDecoder().decode(asset.source)
          assertLegacyMediaQueries(css, asset.fileName)
        }
      }
    },
  }
}
