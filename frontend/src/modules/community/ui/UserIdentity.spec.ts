import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
import { createApp, h, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it } from 'vitest'
import UserIdentity from './UserIdentity.vue'
import { userProfilePath } from '@/shared/lib/userProfileNavigation'

let app: App | undefined
let host: HTMLDivElement | undefined
afterEach(() => {
  app?.unmount()
  host?.remove()
})

/** 署名入口只按已认证用户ID区分本人，他人及游客的原有主页行为继续保留。 */
it('routes self to account, other users to public profiles and guests through login', async () => {
  const viewer = ref('42'),
    authenticated = ref(true)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
  })
  await router.push('/restaurants/1')
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({
    render: () =>
      h(UserIdentity, {
        userId: '42',
        username: '同学',
        viewerId: viewer.value,
        authenticated: authenticated.value,
      }),
  }).use(router)
  app.mount(host)
  expect(await captureLinkTarget(app, host.querySelector('a'))).toBe('/account')
  viewer.value = '88'
  await nextTick()
  expect(await captureLinkTarget(app, host.querySelector('a'))).toBe('/users/42')
  authenticated.value = false
  viewer.value = '42'
  await nextTick()
  expect(await captureLinkTarget(app, host.querySelector('a'))).toBe('/login?redirect=/users/42')
})

it('uses a stable authenticated ID instead of inferring self from a username', () => {
  expect(userProfilePath('42', 42)).toBe('/account')
  expect(userProfilePath('42', '88')).toBe('/users/42')
  expect(userProfilePath('42')).toBe('/users/42')
})
