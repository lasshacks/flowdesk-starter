import path from 'node:path'
import { pathToFileURL } from 'node:url'

const frontend = process.argv[2]
const { createServer } = await import(pathToFileURL(path.join(frontend, 'node_modules/vite/dist/node/index.js')))
const server = await createServer({
  configFile: path.join(frontend, 'vite.config.ts'),
  logLevel: 'error',
  server: { host: '127.0.0.1', port: 0, proxy: { '/api': { target: process.argv[3], changeOrigin: false } } },
})
await server.listen()
console.log('VITE_PORT=' + server.httpServer.address().port)
process.stdin.on('data', async () => { await server.close(); process.exit(0) })
