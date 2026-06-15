// 术语批量导入：登录 admin -> 读 secretariat-terminology.json -> 分批 POST /api/terminology/batch
import fs from 'node:fs'

const BASE = 'https://julongtongchuan.icu'
const USER = 'admin', PASS = 'admin123'
const FILE = 'outputs/secretariat-terminology.json'
const CHUNK = 300

const login = async () => {
  const r = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const j = await r.json()
  if (j.code !== 200) throw new Error('登录失败: ' + JSON.stringify(j))
  return j.data // { userId, token, ... }
}

const main = async () => {
  const { userId, token } = await login()
  console.log('登录成功, userId =', userId)
  const terms = JSON.parse(fs.readFileSync(FILE, 'utf-8'))
  console.log('待导入术语:', terms.length)
  let ok = 0, fail = 0
  for (let i = 0; i < terms.length; i += CHUNK) {
    const batch = terms.slice(i, i + CHUNK)
    const r = await fetch(`${BASE}/api/terminology/batch?userId=${userId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: token || '' },
      body: JSON.stringify(batch),
    })
    const j = await r.json().catch(() => ({ code: r.status }))
    if (j.code === 200) { ok += batch.length; process.stdout.write(`  已导入 ${ok}/${terms.length}\r`) }
    else { fail += batch.length; console.log(`\n  批次 ${i} 失败:`, JSON.stringify(j).slice(0, 200)) }
  }
  console.log(`\n完成: 成功 ${ok}, 失败 ${fail}`)
}
main().catch(e => { console.error('出错:', e.message); process.exit(1) })
