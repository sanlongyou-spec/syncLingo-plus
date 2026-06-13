import axios from 'axios'
import { API_DEFAULTS } from './constants'

const client = axios.create({
  baseURL: API_DEFAULTS.BASE_URL,
  timeout: API_DEFAULTS.TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
})

client.interceptors.request.use(config => {
  const token = localStorage.getItem('si_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export default client
