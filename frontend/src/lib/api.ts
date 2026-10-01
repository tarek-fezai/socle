// SPDX-License-Identifier: AGPL-3.0-or-later
import axios from 'axios'
import { getAccessToken, login } from './auth'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080',
  timeout: 15_000,
})

api.interceptors.request.use(async (config) => {
  const token = await getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      await login()
    }
    return Promise.reject(error)
  },
)
