import axios from 'axios'
import {
  clearSourceApiToken,
  getSourceApiToken,
  requestSourceApiToken,
} from './sourceToken'

// 旧版曾用 localStorage('remoteUrl') 覆盖后端地址（对应 UI 已移除），
// 残留值会让书架永远连不上当前服务，这里做一次清理。
try {
  localStorage.removeItem('remoteUrl')
  localStorage.removeItem('remoteOrigin')
} catch {}
const SECOND = 1000
const protectedSourcePaths = new Set([
  'getBookSourcesForManagement',
  'getBookSourceCheckStates',
  'startBookSourceCheck',
  'stopBookSourceCheck',
  'saveJsSource',
  'saveBookSource',
  'saveBookSources',
  'deleteBookSources',
  'saveRssSource',
  'saveRssSources',
  'deleteRssSources',
  'saveReplaceRule',
  'deleteReplaceRule',
  'testReplaceRule',
  'openLegacyReview',
  'runLegacyReview',
])

const ajax = axios.create({
  baseURL: import.meta.env.VITE_API || location.origin,
  timeout: 120 * SECOND,
})

ajax.interceptors.request.use(async config => {
  const path = new URL(
    config.url || '',
    config.baseURL || location.origin,
  ).pathname
  const endpoint = path.split('/').filter(Boolean).pop() || ''
  if (protectedSourcePaths.has(endpoint)) {
    const token = getSourceApiToken() || (await requestSourceApiToken())
    if (token) config.headers.set('X-Legado-Token', token)
  }
  return config
})

ajax.interceptors.response.use(response => {
  const errorMsg = response.data?.errorMsg
  if (typeof errorMsg === 'string' && errorMsg.includes('访问令牌')) {
    clearSourceApiToken()
  }
  return response
})

export default ajax
