import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 全项目统一用 @ 指向 src，避免出现 ../../../ 这种难以维护的相对路径
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      // 开发期把 /admin 转发到后端，浏览器侧同源，因此 axios 的 baseURL 可以留空。
      // 这样生产环境用 Nginx 反代时前端代码一行都不用改。
      '/admin': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      /*
       * ⚠️ /files 必须也代理，否则后台里的图片全部显示成「加载失败」。
       *
       * 后端把上传的图片存成**相对路径** `/files/product/1.png`（这是对的：
       * 写死域名后换机器、换端口、上云托管，数据库里的图片就全挂了）。
       * 但浏览器拿到 `/files/product/1.png` 会向**当前源**请求，也就是
       *   http://127.0.0.1:5173/files/product/1.png
       * 而 5173 是 vite dev server，它只认识源码与打包产物，不认识 /files，
       * 于是返回 404 —— 图片位置显示「加载失败」。
       *
       * 生产环境用 Nginx 反代时同样要把 /files 指向后端（或对象存储），
       * 所以开发期在这里配代理，是为了让两边的路径语义保持一致。
       */
      '/files': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 把体积大且不常变动的第三方库单独拆包，利用浏览器缓存：
        // 业务代码改动时不必让用户重新下载整个 element-plus / echarts
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          'element-plus': ['element-plus', '@element-plus/icons-vue'],
          echarts: ['echarts']
        }
      }
    }
  }
})
