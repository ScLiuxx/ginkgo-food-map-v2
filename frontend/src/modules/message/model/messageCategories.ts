/** 消息分类只描述前端入口；未接入真实收件箱前，不生成消息、时间或未读计数。 */
export const messageCategories = [
  {
    id: 'likes',
    title: '收到的赞',
    icon: 'like',
    tone: 'amber',
    detail: '其他用户对你的点评和回复的首次点赞提醒，将逐条展示在这里。',
  },
  {
    id: 'comments',
    title: '评论',
    icon: 'comment',
    tone: 'rose',
    detail: '其他用户回复你的点评时，将在这里收到提醒。',
  },
  {
    id: 'followers',
    title: '新增关注',
    icon: 'follow',
    tone: 'blue',
    detail: '关注你的新食友，将在这里与你见面。',
  },
  {
    id: 'system',
    title: '系统通知',
    icon: 'announcement',
    tone: 'blue',
    detail:
      '社区公告、新店投稿与纠错的审核结果和驳回原因，以及举报、处罚和申诉结果，将统一展示在这里。',
  },
] as const

export type MessageIconName = (typeof messageCategories)[number]['icon'] | 'inbox'

/** 系统会话的展示快照；后续由页面传入真实摘要，当前不接入个人聊天。 */
export interface MessageSession {
  readonly id: string
  readonly title: string
  readonly preview: string
  readonly timeLabel?: string
  readonly unreadCount?: number
}

const systemCategory = messageCategories[3]
// 系统通知只显示图标和标题；没有真实消息时不填用途说明或伪造摘要、接收时间。
export const initialMessageSessions: readonly MessageSession[] = [
  {
    id: systemCategory.id,
    title: systemCategory.title,
    preview: '',
  },
]
