/**
 * 分享页音频通道端到端探针。
 *
 * 作为一个“假听众”连接 /ws/share-audio，统计真实下行的 Opus 包数量/字节/码率，
 * 用于客观验证：①该语言是否收到音频 ②实际带宽 ③语言隔离（多开几个不同 lang 对比）。
 *
 * 用法（Node >= 18；若 Node < 21 需先 `npm i ws`）：
 *   node tests/share-audio-probe.mjs --url ws://localhost:8080 --session <SESSION_ID> --lang zh --seconds 20
 *
 * 同时开 3 个终端分别 --lang zh / id / en，让宿主分别用三种语言讲话，
 * 观察“只有对应语言的探针在涨字节”，即证明按语言隔离与原声/译音路由正确。
 */

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1]])
    return acc
  }, [])
)

const url = args.url || 'ws://localhost:8080'
const session = args.session
const lang = args.lang || 'zh'
const seconds = Number(args.seconds || 20)

if (!session) {
  console.error('缺少 --session <SESSION_ID>（从分享页 URL /share/<id> 里拿）')
  process.exit(1)
}

const WS = globalThis.WebSocket || (await import('ws')).default
const wsUrl = `${url.replace(/\/$/, '')}/ws/share-audio?sessionId=${encodeURIComponent(session)}&lang=${lang}`

let packets = 0
let bytes = 0
let firstPacketAt = 0
const start = Date.now()

console.log(`[probe] 连接 ${wsUrl}`)
const ws = new WS(wsUrl)
ws.binaryType = 'arraybuffer'

ws.onopen = () => console.log(`[probe] 已连接, 监听 ${seconds}s, lang=${lang} ...`)
ws.onerror = e => console.error('[probe] 连接错误:', e.message || e)
ws.onclose = () => console.log('[probe] 连接关闭')
ws.onmessage = ev => {
  const len = ev.data.byteLength ?? ev.data.length ?? 0
  if (packets === 0) firstPacketAt = Date.now()
  packets += 1
  bytes += len
}

const timer = setInterval(() => {
  const elapsed = (Date.now() - start) / 1000
  const kbps = bytes * 8 / elapsed / 1000
  console.log(`[probe] t=${elapsed.toFixed(0)}s  包=${packets}  字节=${bytes}  实时码率=${kbps.toFixed(1)} kbps`)
}, 2000)

setTimeout(() => {
  clearInterval(timer)
  const elapsed = (Date.now() - start) / 1000
  const kbps = bytes * 8 / elapsed / 1000
  console.log('\n===== 探针汇总 =====')
  console.log(`语言: ${lang}`)
  console.log(`时长: ${elapsed.toFixed(1)}s`)
  console.log(`收到包: ${packets}  总字节: ${bytes}`)
  console.log(`首包延迟: ${firstPacketAt ? ((firstPacketAt - start) / 1000).toFixed(1) + 's' : '未收到任何音频'}`)
  console.log(`平均码率(含静音): ${kbps.toFixed(1)} kbps`)
  console.log(`若有 N 个该语言听众, 该语言出口 ≈ ${(kbps * 1).toFixed(1)} kbps × N`)
  ws.close()
  process.exit(0)
}, seconds * 1000)
