/**
 * HTTP 客户端
 * 所有请求通过此文件发出，禁止在组件中直接使用 axios
 */
import axios from 'axios'
import { API_DEFAULTS } from './constants'

const client = axios.create({
  baseURL: API_DEFAULTS.BASE_URL,
  timeout: API_DEFAULTS.TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
})

export default client
