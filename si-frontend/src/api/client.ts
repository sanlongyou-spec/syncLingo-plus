import axios from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { API_DEFAULTS } from './constants'
import { clearAccessToken, getAccessToken, setAccessToken } from './authToken'
import { STORAGE_KEYS } from '../constants'

interface ApiResult<T> {
  code: number
  message: string
  data: T
}

interface LoginResult {
  userId: number
  token: string
  role: string
}

type RetriableRequestConfig = InternalAxiosRequestConfig & {
  _refreshRetried?: boolean
}

const client = axios.create({
  baseURL: API_DEFAULTS.BASE_URL,
  timeout: API_DEFAULTS.TIMEOUT,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
})

const refreshClient = axios.create({
  baseURL: API_DEFAULTS.BASE_URL,
  timeout: API_DEFAULTS.TIMEOUT,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
})

let refreshPromise: Promise<string | null> | null = null

client.interceptors.request.use(config => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

client.interceptors.response.use(
  response => response,
  async error => {
    const status = error?.response?.status
    const config = error?.config as RetriableRequestConfig | undefined
    const url = String(config?.url || '')

    if (status !== 401 || !config || config._refreshRetried || url.includes('/api/auth/refresh')) {
      throw error
    }

    config._refreshRetried = true
    const token = await refreshAccessToken()
    if (!token) {
      clearStoredAuth()
      throw error
    }
    config.headers.Authorization = `Bearer ${token}`
    return client(config)
  },
)

const refreshAccessToken = async (): Promise<string | null> => {
  if (!refreshPromise) {
    refreshPromise = refreshClient
      .post<ApiResult<LoginResult>>('/api/auth/refresh', {})
      .then(response => {
        const result = response.data
        if (result.code === 200 && result.data?.token) {
          setAccessToken(result.data.token)
          localStorage.setItem(STORAGE_KEYS.USER_ID, String(result.data.userId))
          if (result.data.role) {
            localStorage.setItem(STORAGE_KEYS.ROLE, result.data.role)
          }
          localStorage.removeItem(STORAGE_KEYS.TOKEN)
          return result.data.token
        }
        return null
      })
      .catch(() => null)
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

const clearStoredAuth = () => {
  clearAccessToken()
  localStorage.removeItem(STORAGE_KEYS.TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_ID)
  localStorage.removeItem(STORAGE_KEYS.ROLE)
}

export default client
