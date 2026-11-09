<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { accountSections, accountSectionTitles } from '../model/accountSections'
import ReceivedLikeTotal from '@/modules/community/ui/ReceivedLikeTotal.vue'
import { useOwnGrowth } from '../model/useOwnGrowth'
import AccountIcon from './AccountIcon.vue'
import FavoriteVisibilitySetting from './FavoriteVisibilitySetting.vue'
import FollowSummary from '@/modules/community/ui/FollowSummary.vue'
import FollowList from '@/modules/community/ui/FollowList.vue'
import FollowPrivacySetting from '@/modules/community/ui/FollowPrivacySetting.vue'
import RestaurantFavorites from '@/modules/restaurant/ui/RestaurantFavorites.vue'
import { authToken } from '@/shared/api/authSession'
import { MyReviews } from '@/modules/review'
import { MyContributions } from '@/modules/restaurant'
import { useOwnProfile } from '../model/useOwnProfile'
import { bioValidationError, normalizeBio } from '../model/profileValidation'

/** 个人中心只接入已存在的身份与成长接口；未开放能力不保存本地假资料或虚构统计。 */
const props = defineProps<{ section?: string; publicUserId?: string }>()
const auth = useLoginStore()
const route = useRoute()
const bioDialog = ref<globalThis.HTMLDialogElement>()
const bioDraft = ref('')
const bioNotice = ref('')
const profileTab = ref<'reviews' | 'favorites'>('reviews')
// 独立内容页复用主页列表的紧凑展示；数量与刷新状态仍由实际列表读取结果提供。
const listTotal = ref<number | null>(null)
const ownReviews = ref<InstanceType<typeof MyReviews>>()
const ownFavorites = ref<InstanceType<typeof RestaurantFavorites>>()
const activeAccountList = computed(() =>
  props.section === 'reviews' ? ownReviews.value : ownFavorites.value,
)
// 草稿只留在当前弹窗；退出、切页或切换查看对象均清除，不写入浏览器存储。
watch(
  () => [route.path, authToken.value],
  () => {
    bioDialog.value?.close()
    bioDraft.value = ''
    bioNotice.value = ''
    profileTab.value = 'reviews'
    listTotal.value = null
  },
)
function openBio() {
  if (!ownDetails.value || profileLoading.value || bioSaving.value) return
  bioDraft.value = ownDetails.value.bio
  bioNotice.value = ''
  profileState.clearSaveError()
  bioDialog.value?.showModal()
}
function cancelBio() {
  if (!bioSaving.value) bioDialog.value?.close()
}
async function saveBio() {
  if (await profileState.save(bioDraft.value)) {
    bioNotice.value = '个人简介已更新'
    bioDialog.value?.close()
  }
}
const publicProfile = computed(() => !!props.publicUserId)
const ownProfile = computed(
  () => !publicProfile.value || props.publicUserId === String(auth.user?.id),
)
const home = computed(() => !props.section)
// 首字头像只取公开用户名，不提供上传或额外保存用户资料。
const avatarInitial = computed(() =>
  Array.from(auth.user?.username.trim() || '?')[0]!.toUpperCase(),
)
const profileState = useOwnProfile(
  () => ownProfile.value && (home.value || props.section === 'settings'),
)
const {
  profile: ownDetails,
  loading: profileLoading,
  saving: bioSaving,
  error: profileError,
  saveError: bioSaveError,
} = profileState
const bioError = computed(() => bioValidationError(bioDraft.value))
const bioLength = computed(() => Array.from(normalizeBio(bioDraft.value)).length)
watch(bioDraft, profileState.clearSaveError)
const title = computed(() =>
  publicProfile.value ? '个人主页' : props.section ? accountSectionTitles[props.section] : '我的',
)
const loginTarget = computed(() => ({ path: '/login', query: { redirect: route.path } }))
const growth = useOwnGrowth(() => ownProfile.value && (home.value || props.section === 'growth'))
const { summary, loading, error, progress } = growth
const nextExperience = computed(() =>
  summary.value?.nextLevelThreshold == null
    ? null
    : Math.max(0, summary.value.nextLevelThreshold - summary.value.experience),
)
// 仅展示当前已开放的奖励规则；等级门槛和进度继续读取服务端，不在前端计算等级。
const growthRewards = [
  {
    title: '首次点评一家店',
    description: '每人每家餐厅或窗口仅奖励一次',
    icon: 'review',
    experience: 5,
  },
  { title: '新店投稿审核通过', icon: 'store', experience: 20 },
  { title: '纠错审核通过', icon: 'edit', experience: 10 },
  { title: '点评收到点赞', description: '每日最多获得 5 经验', icon: 'heart', experience: 1 },
]
const pendingSections: Record<string, readonly string[]> = {
  reviews: [
    'review',
    '我的点评即将开放',
    '这里会按店铺展示你的最新点评，并可展开历史记录。你仍可在店铺详情查看和删除自己的点评。',
  ],
  favorites: [
    'star',
    '收藏店铺即将开放',
    '在这里收好喜欢的餐厅和食堂。收藏默认仅自己可见，不会产生站内消息。',
  ],
  following: [
    'people',
    '关注列表即将开放',
    '关注名单默认仅自己可见，开放后可在账号设置中调整公开范围。',
  ],
  followers: [
    'people',
    '粉丝列表即将开放',
    '粉丝名单默认仅自己可见，开放后可在账号设置中调整公开范围。',
  ],
}
const pending = computed(() => pendingSections[props.section ?? ''])
</script>

<template>
  <section
    class="account-center"
    :class="{
      'account-home': home && !publicProfile,
      'account-growth': section === 'growth' && !publicProfile,
      'account-settings': section === 'settings' && !publicProfile,
      'account-list-page': (section === 'reviews' || section === 'favorites') && !publicProfile,
    }"
    aria-labelledby="account-title"
  >
    <header class="account-topline">
      <RouterLink v-if="section || publicProfile" class="account-back" to="/account"
        >‹ <span>我的</span></RouterLink
      >
      <h1 id="account-title">{{ title }}</h1>
    </header>

    <div v-if="!auth.authenticated" class="account-guest">
      <h2>{{ publicProfile ? '登录后查看个人主页' : '记录你的校园好味道' }}</h2>
      <p>
        {{
          publicProfile
            ? '个人主页仅向登录用户开放。'
            : '把用餐体验、喜欢的店铺和每一次贡献留在这里。'
        }}
      </p>
      <RouterLink class="account-primary" :to="loginTarget"
        >登录账号 <span aria-hidden="true">→</span></RouterLink
      >
    </div>

    <template v-if="auth.authenticated && home">
      <div v-if="ownProfile" class="account-profile">
        <div class="profile-identity">
          <span class="profile-avatar" aria-hidden="true">{{ avatarInitial }}</span>
          <div class="profile-info">
            <div class="profile-heading">
              <h2>{{ auth.user?.username }}</h2>
              <span v-if="summary" class="level-badge">Lv.{{ summary.level }}</span>
            </div>
            <p v-if="ownDetails" class="profile-bio">
              {{ ownDetails.bio || '还没有填写个人简介' }}
            </p>
            <p v-else-if="profileLoading" class="profile-bio" role="status">正在加载简介…</p>
            <div v-else-if="profileError" class="profile-read-error" role="alert">
              <p>个人资料加载失败：{{ profileError }}</p>
              <button class="inline-link" @click="profileState.refresh">重新加载资料</button>
            </div>
          </div>
          <RouterLink v-if="!publicProfile" class="public-link" :to="`/users/${auth.user?.id}`"
            >个人主页 <span aria-hidden="true">›</span></RouterLink
          >
        </div>
        <div class="profile-statistics" aria-label="互动统计">
          <FollowSummary v-if="auth.user" :user-id="String(auth.user.id)" />
          <ReceivedLikeTotal />
        </div>
        <!-- 首页成长资料收进个人卡片；失败重试及独立成长规则页继续使用原逻辑。 -->
        <div v-if="!publicProfile" class="growth-card growth-compact" :aria-busy="loading">
          <div class="growth-compact-line">
            <strong class="growth-compact-title"
              >成长等级 <span v-if="summary">Lv.{{ summary.level }}</span></strong
            >
            <RouterLink to="/account/growth" class="growth-entry" aria-label="查看成长规则">
              <span v-if="summary"
                >{{ summary.experience
                }}<template v-if="summary.nextLevelThreshold !== null">
                  / {{ summary.nextLevelThreshold }}</template
                >
                经验</span
              >
              <span v-else>成长规则</span>
              <span aria-hidden="true">›</span>
            </RouterLink>
          </div>
          <progress v-if="summary" :value="progress" max="100" aria-label="当前等级升级进度" />
          <p v-if="summary && nextExperience === null">已达到当前最高等级</p>
          <p v-else-if="loading" role="status">正在加载成长资料…</p>
          <div v-else-if="error" role="alert">
            <p>成长资料加载失败：{{ error }}</p>
            <button class="inline-link" @click="growth.refresh">重新加载</button>
          </div>
        </div>
      </div>
      <div v-else class="account-pending public-pending">
        <h2>个人资料即将开放</h2>
        <p>公开简介、点评和互动资料暂未开放。</p>
        <button class="account-secondary" disabled title="关注功能即将开放">关注 · 即将开放</button>
      </div>
    </template>

    <nav v-if="home && !publicProfile" class="account-tools" aria-label="我的功能">
      <RouterLink
        v-for="item in accountSections"
        :key="item.id"
        :to="
          auth.authenticated
            ? `/account/${item.id}`
            : { path: '/login', query: { redirect: `/account/${item.id}` } }
        "
      >
        <span class="tool-icon"><AccountIcon :name="item.icon" /></span
        ><strong>{{ item.title }}</strong
        ><span class="tool-detail">{{ item.detail }}</span
        ><span class="tool-arrow" aria-hidden="true">›</span>
      </RouterLink>
    </nav>
    <template v-if="auth.authenticated && publicProfile">
      <nav class="profile-tabs" aria-label="个人主页内容">
        <button
          :aria-pressed="profileTab === 'reviews'"
          aria-controls="profile-content"
          @click="profileTab = 'reviews'"
        >
          点评
        </button>
        <button
          :aria-pressed="profileTab === 'favorites'"
          aria-controls="profile-content"
          @click="profileTab = 'favorites'"
        >
          收藏店铺
        </button>
      </nav>
      <div id="profile-content" class="account-pending">
        <AccountIcon :name="profileTab === 'reviews' ? 'review' : 'star'" />
        <h2>{{ profileTab === 'reviews' ? '公开点评即将开放' : '收藏店铺即将开放' }}</h2>
        <p>
          {{
            profileTab === 'reviews'
              ? '这里会按店铺展示最新点评，并可展开历史记录。投稿和纠错记录不会出现在公开主页。'
              : '收藏默认仅自己可见。只有本人选择公开后，其他登录用户才可查看。'
          }}
        </p>
      </div>
    </template>

    <template v-if="auth.authenticated && section">
      <template v-if="section === 'growth'">
        <div class="growth-card growth-level-card" :aria-busy="loading">
          <div class="growth-level-heading">
            <span>当前等级</span>
            <button
              v-if="summary"
              class="growth-refresh"
              :disabled="loading"
              @click="growth.refresh"
            >
              <span aria-hidden="true">⟳</span> 刷新
            </button>
          </div>
          <template v-if="summary"
            ><div class="growth-numbers">
              <strong>Lv.{{ summary.level }}</strong
              ><span class="growth-experience"
                ><b>{{ summary.experience }}</b
                ><template v-if="summary.nextLevelThreshold !== null">
                  / {{ summary.nextLevelThreshold }}</template
                >
                经验</span
              >
            </div>
            <progress :value="progress" max="100" aria-label="当前等级升级进度" />
            <p>
              {{
                nextExperience === null
                  ? '已达到当前最高等级'
                  : `距离 Lv.${summary.level + 1} 还差 ${nextExperience} 经验`
              }}
            </p></template
          >
          <p v-else-if="loading" role="status">正在加载成长资料…</p>
          <div v-else-if="error" role="alert">
            <p>成长资料加载失败：{{ error }}</p>
            <button class="inline-link" @click="growth.refresh">重新加载</button>
          </div>
        </div>
        <section class="growth-rewards" aria-labelledby="growth-rewards-title">
          <h2 id="growth-rewards-title">如何获得经验</h2>
          <div v-for="reward in growthRewards" :key="reward.title" class="growth-reward-row">
            <span class="growth-reward-icon"><AccountIcon :name="reward.icon" /></span>
            <div class="growth-reward-info">
              <strong>{{ reward.title }}</strong>
              <p v-if="reward.description">{{ reward.description }}</p>
            </div>
            <span class="growth-reward-amount"
              ><b>+{{ reward.experience }}</b> 经验</span
            >
          </div>
        </section>
        <section class="growth-rules" aria-labelledby="growth-rules-title">
          <h2 id="growth-rules-title">规则说明</h2>
          <ul>
            <li>升级不消耗经验。</li>
            <li>同一点赞者每天最多贡献 1 经验，回复获赞不计。</li>
            <li>普通删评、取消点赞不扣经验，重复发布不重复奖励。</li>
            <li>违规或刷分可能撤销经验，等级随之下降。</li>
          </ul>
        </section>
      </template>

      <template v-else-if="section === 'settings'">
        <section class="account-block">
          <h2>个人资料</h2>
          <div class="setting-row username-setting">
            <span>用户名</span><strong>{{ auth.user?.username }}</strong>
          </div>
          <p class="fine-print">用于登录和公开展示，暂不支持修改。</p>
          <button
            class="setting-row bio-entry"
            :disabled="!ownDetails || profileLoading || bioSaving"
            @click="openBio"
          >
            <span>个人简介</span
            ><span class="pending-tag">{{
              profileLoading ? '加载中…' : ownDetails ? '修改 ›' : '暂时无法读取'
            }}</span>
          </button>
          <p v-if="ownDetails" class="bio-preview">{{ ownDetails.bio || '还没有填写个人简介' }}</p>
          <div v-if="profileError" class="profile-read-error" role="alert">
            <p>个人资料加载失败：{{ profileError }}</p>
            <button class="inline-link" @click="profileState.refresh">重新加载资料</button>
          </div>
          <p v-if="bioNotice" class="profile-save-notice" role="status">{{ bioNotice }}</p>
        </section>
        <section class="account-block">
          <h2>隐私设置</h2>
          <FavoriteVisibilitySetting />
          <FollowPrivacySetting />
          <p class="settings-privacy-note">关闭后仅自己可见，关注和粉丝数仍公开。</p>
        </section>
        <section class="account-block">
          <h2>账号安全</h2>
          <div class="setting-row">
            <span>绑定邮箱</span
            ><span class="masked-email">{{
              ownDetails?.maskedEmail || (profileLoading ? '正在加载…' : '暂时无法读取')
            }}</span>
          </div>
          <RouterLink class="setting-row setting-link" to="/password-reset"
            ><span>找回 / 重置密码</span><span aria-hidden="true">›</span></RouterLink
          >
        </section>
        <button class="sign-out" :disabled="auth.loggingOut" @click="auth.signOut">
          {{ auth.loggingOut ? '正在退出…' : '退出登录' }}
        </button>
        <dialog
          ref="bioDialog"
          class="bio-dialog"
          aria-labelledby="bio-title"
          @close="bioDraft = ''"
          @cancel.prevent="cancelBio"
        >
          <h2 id="bio-title">个人简介</h2>
          <label class="bio-label" for="account-bio"
            >介绍一下自己
            <span :class="{ 'bio-invalid': !!bioError }">{{ bioLength }} / 100</span></label
          >
          <textarea
            id="account-bio"
            v-model="bioDraft"
            rows="3"
            maxlength="200"
            :disabled="bioSaving"
            :aria-invalid="!!bioError || !!bioSaveError"
            aria-describedby="bio-rule"
          />
          <p id="bio-rule" class="fine-print">
            最多100字，不允许填写微信、QQ、手机号或网址。保存后直接生效，留空可清除简介。
          </p>
          <p v-if="bioError || bioSaveError" class="bio-validation-error" role="alert">
            {{ bioError || bioSaveError }}
          </p>
          <footer>
            <button class="account-secondary" :disabled="bioSaving" @click="cancelBio">取消</button
            ><button
              class="account-primary"
              :disabled="
                bioSaving || !!bioError || !ownDetails || normalizeBio(bioDraft) === ownDetails.bio
              "
              @click="saveBio"
            >
              {{ bioSaving ? '正在保存…' : '保存' }}
            </button>
          </footer>
        </dialog>
      </template>

      <section
        v-else-if="section === 'reviews' || section === 'favorites'"
        class="account-list-card"
        :aria-label="title"
      >
        <header class="account-list-toolbar">
          <span v-if="listTotal !== null" class="account-list-total"
            >共 {{ listTotal }} {{ section === 'reviews' ? '条点评' : '家店铺' }}</span
          >
          <div class="account-list-actions">
            <button
              type="button"
              :disabled="!activeAccountList || activeAccountList.refreshing"
              @click="activeAccountList?.refresh()"
            >
              <span aria-hidden="true">⟳</span> 刷新
            </button>
            <RouterLink v-if="section === 'reviews'" to="/?writeReview=1"
              >写点评 <span aria-hidden="true">›</span></RouterLink
            >
          </div>
        </header>
        <MyReviews
          v-if="section === 'reviews'"
          ref="ownReviews"
          profile-layout
          @total="listTotal = $event"
        />
        <RestaurantFavorites v-else ref="ownFavorites" profile-layout @total="listTotal = $event" />
      </section>
      <FollowList
        v-else-if="section === 'following' || section === 'followers'"
        :kind="section === 'following' ? 'FOLLOWING' : 'FOLLOWERS'"
      />
      <MyContributions v-else-if="section === 'contributions'" />
      <template v-else-if="pending">
        <div class="account-pending">
          <AccountIcon :name="pending[0] ?? 'review'" />
          <h2>{{ pending[1] }}</h2>
          <p>{{ pending[2] }}</p>
          <RouterLink
            v-if="section === 'reviews' || section === 'favorites'"
            class="account-secondary"
            to="/"
            >去发现美食 →</RouterLink
          >
        </div>
        <p v-if="section === 'favorites'" class="fine-print section-note">
          仅收藏餐厅和食堂，不收藏点评。店铺停业仍可浏览；删除后的收藏将在本人列表标记失效，并允许取消收藏。
        </p>
      </template>
    </template>
  </section>
</template>

<style scoped src="./account-center.css"></style>
<style scoped src="./growth-page.css"></style>
<style scoped src="./account-list-pages.css"></style>
<style scoped src="./account-settings.css"></style>
