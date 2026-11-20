import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'
import { amapProxy } from './amapProxy'
import { cssCompatibility } from './cssCompatibility'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue(), amapProxy(env), cssCompatibility()],
    build: {
      // 部分手机浏览器仍使用旧内核，不能将手机断点压缩成 width <= 700px。
      cssTarget: ['chrome95', 'safari15'],
    },
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('../src', import.meta.url)),
      },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
        // 认证接口按后端既有约定使用 /auth 前缀，不在开发代理中改写路径。
        '/auth': {
          target: env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      // 本地同时运行后端、IDE及浏览器，限制并发以降低测试的CPU与内存峰值。
      maxWorkers: 1,
      fileParallelism: false,
      // worker单独设堆上限，不继承构建时较大的Node堆；测试仍逐文件隔离。
      execArgv: ['--max-old-space-size=512'],
      // Vue外置scoped样式依赖本轮组件描述缓存，不能复用磁盘中的局部转换结果。
      fsModuleCache: false,
      // 保留外置scoped样式的转换链，避免多段style的测试加载丢失组件描述。
      css: { include: [/.+/] },
    },
  }
})
